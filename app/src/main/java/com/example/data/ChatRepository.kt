package com.example.data

import android.content.Context
import com.example.data.model.BLOCKED_MESSAGE
import com.example.data.model.ChatMessage
import com.example.data.model.ChatUser
import com.example.data.model.FeedPayload
import com.example.data.model.NetworkResult
import com.example.data.model.UserSession
import com.example.data.network.BackendJsonParser
import com.example.data.network.HelloSirApi
import com.example.data.network.RetrofitClient
import com.example.notifications.ChatNotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException

class ChatRepository(
    context: Context,
    private val api: HelloSirApi = RetrofitClient.api,
    private val sessionStore: SessionStore = SessionStore(context.applicationContext),
    private val notificationHelper: ChatNotificationHelper = ChatNotificationHelper(context.applicationContext)
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    private var baselineLoaded = false
    private val seenMessageKeys = mutableSetOf<String>()

    private val _session = MutableStateFlow<UserSession?>(null)
    val session: StateFlow<UserSession?> = _session.asStateFlow()

    private val _users = MutableStateFlow<List<ChatUser>>(emptyList())
    val users: StateFlow<List<ChatUser>> = _users.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _lastSyncMs = MutableStateFlow<Long?>(null)
    val lastSyncMs: StateFlow<Long?> = _lastSyncMs.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    private val _blockedEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val blockedEvents: SharedFlow<String> = _blockedEvents.asSharedFlow()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    init {
        scope.launch {
            seenMessageKeys.addAll(sessionStore.getNotifiedMessageIds())
            sessionStore.sessionFlow.collect { saved ->
                _session.value = saved
                if (saved != null) {
                    startPolling()
                } else {
                    stopPolling()
                }
            }
        }
    }

    suspend fun register(userId: String, name: String): NetworkResult<UserSession> {
        val cleanId = userId.trim()
        val cleanName = name.trim()
        val json = JSONObject().apply {
            put("user_id", cleanId)
            put("name", cleanName)
        }.toString()

        return try {
            val response = api.register(json.toRequestBody(jsonMediaType))
            if (response.code() == 403) {
                _blockedEvents.tryEmit(BLOCKED_MESSAGE)
                return NetworkResult.Blocked(BLOCKED_MESSAGE)
            }
            if (!response.isSuccessful) {
                val errBody = runCatching { response.errorBody()?.string() }.getOrNull()
                return NetworkResult.Error(extractErrorMessage(errBody, "Registration failed (${response.code()})"))
            }
            val newSession = UserSession(userId = cleanId, name = cleanName)
            sessionStore.saveSession(cleanId, cleanName)
            _session.value = newSession
            baselineLoaded = false
            refreshFeed()
            startPolling()
            NetworkResult.Success(newSession)
        } catch (e: SocketTimeoutException) {
            NetworkResult.Error("Server is waking up or timed out. Please tap Connect again.")
        } catch (e: IOException) {
            NetworkResult.Error("Network connection error. Check your internet and try again.")
        } catch (e: Exception) {
            NetworkResult.Error(e.localizedMessage ?: "Unexpected error during registration.")
        }
    }

    suspend fun refreshFeed(): NetworkResult<FeedPayload> {
        return try {
            val response = api.getFeed()
            if (response.code() == 403) {
                _blockedEvents.tryEmit(BLOCKED_MESSAGE)
                return NetworkResult.Blocked(BLOCKED_MESSAGE)
            }
            if (!response.isSuccessful) {
                val err = "Sync error (${response.code()})"
                _syncError.value = err
                return NetworkResult.Error(err)
            }

            val rawBody = response.body()?.string().orEmpty()
            val payload = BackendJsonParser.parseFeed(rawBody)

            val now = System.currentTimeMillis()
            val sortedUsers = payload.users.sortedWith(
                compareByDescending<ChatUser> { it.isOnline(now) }
                    .thenBy { it.name.lowercase() }
            )

            _users.value = sortedUsers
            _messages.value = payload.messages
            _lastSyncMs.value = now
            _syncError.value = null

            processIncomingNotifications(sortedUsers, payload.messages)
            NetworkResult.Success(payload)
        } catch (e: SocketTimeoutException) {
            val msg = "Connection timed out while syncing."
            _syncError.value = msg
            NetworkResult.Error(msg)
        } catch (e: IOException) {
            val msg = "Unable to reach server. Retrying…"
            _syncError.value = msg
            NetworkResult.Error(msg)
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: "Failed to parse server response."
            _syncError.value = msg
            NetworkResult.Error(msg)
        }
    }

    suspend fun sendMessage(receiver: String, text: String): NetworkResult<Unit> {
        val currentUser = _session.value
            ?: return NetworkResult.Error("Not logged in.")
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            return NetworkResult.Error("Message cannot be empty.")
        }

        val json = JSONObject().apply {
            put("sender", currentUser.userId)
            put("receiver", receiver.trim())
            put("text", cleanText)
        }.toString()

        return try {
            val response = api.sendMessage(json.toRequestBody(jsonMediaType))
            if (response.code() == 403) {
                _blockedEvents.tryEmit(BLOCKED_MESSAGE)
                return NetworkResult.Blocked(BLOCKED_MESSAGE)
            }
            if (!response.isSuccessful) {
                val errBody = runCatching { response.errorBody()?.string() }.getOrNull()
                return NetworkResult.Error(extractErrorMessage(errBody, "Failed to send message (${response.code()})"))
            }

            // Append optimistic local message immediately, then reconcile via GET /get
            val optimisticMsg = ChatMessage(
                id = "local_${System.currentTimeMillis()}_${cleanText.hashCode()}",
                sender = currentUser.userId,
                senderName = currentUser.name,
                receiver = receiver.trim(),
                text = cleanText,
                timestampMs = System.currentTimeMillis()
            )
            seenMessageKeys.add(optimisticMsg.dedupeKey)
            _messages.value = (_messages.value + optimisticMsg).distinctBy { it.dedupeKey }

            refreshFeed()
            NetworkResult.Success(Unit)
        } catch (e: SocketTimeoutException) {
            NetworkResult.Error("Send timed out. Please try again.")
        } catch (e: IOException) {
            NetworkResult.Error("Network unavailable. Message not sent.")
        } catch (e: Exception) {
            NetworkResult.Error(e.localizedMessage ?: "Failed to send message.")
        }
    }

    suspend fun signOut() {
        stopPolling()
        baselineLoaded = false
        _users.value = emptyList()
        _messages.value = emptyList()
        _syncError.value = null
        sessionStore.clearSession()
        _session.value = null
    }

    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            while (isActive && _session.value != null) {
                val result = refreshFeed()
                if (result is NetworkResult.Blocked) {
                    delay(15_000L)
                } else {
                    delay(4_000L)
                }
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun processIncomingNotifications(
        users: List<ChatUser>,
        messages: List<ChatMessage>
    ) {
        val currentUser = _session.value ?: return
        if (!baselineLoaded) {
            // Seed existing messages on initial load so user isn't flooded with old history notifications
            val existingKeys = messages.map { it.dedupeKey }.toSet()
            seenMessageKeys.addAll(existingKeys)
            sessionStore.addNotifiedMessageIds(existingKeys)
            baselineLoaded = true
            return
        }

        val userLookup = users.associateBy { it.userId }
        val newlyNotified = mutableSetOf<String>()

        for (msg in messages) {
            val key = msg.dedupeKey
            if (seenMessageKeys.contains(key)) continue
            seenMessageKeys.add(key)
            newlyNotified.add(key)

            val isFromSomeoneElse = !msg.sender.equals(currentUser.userId, ignoreCase = true)
            val isAddressedToMe = msg.isBroadcast || msg.receiver.equals(currentUser.userId, ignoreCase = true)
            if (isFromSomeoneElse && isAddressedToMe) {
                val senderDisplay = msg.senderName
                    ?: userLookup[msg.sender]?.name
                    ?: msg.sender
                notificationHelper.showIncomingMessageNotification(msg, senderDisplay)
            }
        }

        if (newlyNotified.isNotEmpty()) {
            sessionStore.addNotifiedMessageIds(newlyNotified)
        }
    }

    private fun extractErrorMessage(raw: String?, fallback: String): String {
        if (raw.isNullOrBlank()) return fallback
        return runCatching {
            val obj = JSONObject(raw)
            obj.optString("error").takeIf { it.isNotBlank() }
                ?: obj.optString("message").takeIf { it.isNotBlank() }
                ?: obj.optString("detail").takeIf { it.isNotBlank() }
                ?: fallback
        }.getOrDefault(fallback)
    }

    companion object {
        @Volatile
        private var instance: ChatRepository? = null

        fun getInstance(context: Context): ChatRepository {
            return instance ?: synchronized(this) {
                instance ?: ChatRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
