package com.example.data

import android.content.Context
import android.net.Uri
import com.example.data.local.ActivityNotificationEntity
import com.example.data.local.AppliedSocialActionEntity
import com.example.data.local.ChatDatabase
import com.example.data.local.MessageEntity
import com.example.data.local.ProfileExtraEntity
import com.example.data.local.SocialCommentEntity
import com.example.data.local.SocialPostEntity
import com.example.data.local.ThreadReadEntity
import com.example.data.local.UserEntity
import com.example.data.model.ActivityNotification
import com.example.data.model.ActivityType
import com.example.data.model.BLOCKED_MESSAGE
import com.example.data.model.BROADCAST_TARGET_ID
import com.example.data.model.ChatMessage
import com.example.data.model.ChatUser
import com.example.data.model.FeedPayload
import com.example.data.model.LocationPayload
import com.example.data.model.MediaPayload
import com.example.data.model.MessageDeliveryStatus
import com.example.data.model.MessageKind
import com.example.data.model.NetworkResult
import com.example.data.model.SignalingEnvelope
import com.example.data.model.SocialActionEnvelope
import com.example.data.model.SocialComment
import com.example.data.model.SocialMediaType
import com.example.data.model.SocialPost
import com.example.data.model.UserProfileExtras
import com.example.data.model.UserSession
import com.example.data.network.ApiService
import com.example.data.network.BackendConfig
import com.example.data.network.BackendJsonParser
import com.example.data.network.MediaUploadService
import com.example.data.network.RetrofitClient
import com.example.data.network.SupabaseRestClient
import com.example.location.LocationTracker
import com.example.media.MediaHelper
import com.example.notifications.ChatNotificationHelper
import com.example.webrtc.CallUiState
import com.example.webrtc.SignalingClient
import com.example.webrtc.SignalingTransportState
import com.example.webrtc.WebRTCClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.Locale
import java.util.UUID

class ChatRepository(
    context: Context,
    private val api: ApiService = RetrofitClient.apiService,
    private val sessionManager: SessionManager = SessionManager(context.applicationContext),
    private val database: ChatDatabase = ChatDatabase.getInstance(context.applicationContext),
    private val notificationHelper: ChatNotificationHelper = ChatNotificationHelper(context.applicationContext),
    private val mediaUploadService: MediaUploadService = MediaUploadService(context.applicationContext, api),
    private val supabaseClient: SupabaseRestClient = SupabaseRestClient()
) {
    private val appContext = context.applicationContext
    private val dao = database.chatDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    val signalingClient = SignalingClient(RetrofitClient.okHttpClient, api)
    val webRTCClient = WebRTCClient(appContext, signalingClient)
    val locationTracker = LocationTracker(appContext)

    private var pollingJob: Job? = null
    private var liveLocationJob: Job? = null
    private var baselineLoaded = false
    @Volatile
    private var activePeerTarget: String? = null
    private val seenMessageKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private val processedSocialActionIds = Collections.synchronizedSet(mutableSetOf<String>())

    private val _session = MutableStateFlow<UserSession?>(null)
    val session: StateFlow<UserSession?> = _session.asStateFlow()

    val users: Flow<List<ChatUser>> = dao.observeUsers().map { entities ->
        entities.map { it.toDomain() }
    }

    val messages: Flow<List<ChatMessage>> = dao.observeAllMessages().map { entities ->
        entities.map { it.toDomain() }
    }

    val threadReads: Flow<Map<String, Long>> = dao.observeThreadReads().map { list ->
        list.associate { it.threadId to it.lastReadMs }
    }

    val socialPosts: Flow<List<SocialPost>> = dao.observeSocialPosts().map { entities ->
        entities.map { it.toDomain() }
    }

    val socialComments: Flow<Map<String, List<SocialComment>>> = dao.observeAllComments().map { list ->
        list.map { it.toDomain() }.groupBy { it.postId }
    }

    val profileExtras: Flow<Map<String, UserProfileExtras>> = dao.observeProfileExtras().map { list ->
        list.map { it.toDomain() }.associateBy { it.userId }
    }

    val activityNotifications: Flow<List<ActivityNotification>> =
        dao.observeActivityNotifications().map { list ->
            list.map { it.toDomain() }
        }

    val callState: StateFlow<CallUiState> = webRTCClient.callState
    val signalingTransportState: StateFlow<SignalingTransportState> = signalingClient.transportState

    private val _isLiveLocationSharing = MutableStateFlow(false)
    val isLiveLocationSharing: StateFlow<Boolean> = _isLiveLocationSharing.asStateFlow()

    private val _lastSyncMs = MutableStateFlow<Long?>(null)
    val lastSyncMs: StateFlow<Long?> = _lastSyncMs.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    private val _blockedEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val blockedEvents: SharedFlow<String> = _blockedEvents.asSharedFlow()

    init {
        RetrofitClient.activeUserIdProvider = { _session.value?.userId }

        scope.launch {
            seenMessageKeys.addAll(sessionManager.getNotifiedMessageIds())
            processedSocialActionIds.addAll(dao.getAppliedSocialActionIds())

            launch {
                signalingClient.incomingSignals.collect { envelope ->
                    handleIncomingSignal(envelope)
                }
            }
            launch {
                signalingClient.blockedSignals.collect { msg ->
                    _blockedEvents.tryEmit(msg)
                }
            }

            sessionManager.sessionFlow.collect { saved ->
                _session.value = saved
                if (saved != null) {
                    webRTCClient.setCurrentUser(saved.userId, saved.name)
                    signalingClient.connect(saved.userId)
                    ensureMyProfileExtra(saved.userId)
                    startPolling()
                } else {
                    signalingClient.disconnect()
                    webRTCClient.cleanupCallResources()
                    stopLiveLocationSharing()
                    stopPolling()
                }
            }
        }
    }

    fun setActiveChatTarget(targetId: String?) {
        activePeerTarget = targetId
        if (!targetId.isNullOrBlank()) {
            scope.launch { refreshFeed() }
        }
    }

    private suspend fun ensureMyProfileExtra(userId: String) {
        if (dao.getProfileExtra(userId) == null) {
            dao.upsertProfileExtra(
                ProfileExtraEntity(
                    userId = userId,
                    bio = "Connecting live on Hello Sir",
                    followersCount = 0,
                    followingCount = 0,
                    isFollowedByMe = false
                )
            )
        }
    }

    suspend fun register(userId: String, name: String, phone: String = ""): NetworkResult<UserSession> {
        if (sessionManager.isDeviceLocked()) {
            _session.value?.let { return NetworkResult.Success(it) }
        }
        val cleanId = userId.trim()
        val cleanName = name.trim()
        val cleanPhone = phone.trim()
        val deviceLockId = sessionManager.getOrCreateDeviceLockId()

        val authPayload = JSONObject().apply {
            put("user_id", cleanId)
            put("userId", cleanId)
            put("username", cleanId)
            put("name", cleanName)
            if (cleanPhone.isNotBlank()) put("phone", cleanPhone)
            put("device_id", deviceLockId)
            put("deviceId", deviceLockId)
        }.toString()

        return try {
            // 1. Direct login via /api/auth/login
            var authResponse = api.login(authPayload.toRequestBody(jsonMediaType))
            if (authResponse.code() == 403) {
                _blockedEvents.tryEmit(BLOCKED_MESSAGE)
                return NetworkResult.Blocked(BLOCKED_MESSAGE)
            }

            // 2. If user does not exist yet or login returned 404/401/400, register via /api/users/register
            if (!authResponse.isSuccessful) {
                authResponse = api.register(authPayload.toRequestBody(jsonMediaType))
                if (authResponse.code() == 403) {
                    _blockedEvents.tryEmit(BLOCKED_MESSAGE)
                    return NetworkResult.Blocked(BLOCKED_MESSAGE)
                }
            }

            if (!authResponse.isSuccessful) {
                val errBody = runCatching { authResponse.errorBody()?.string() }.getOrNull()
                return NetworkResult.Error(extractErrorMessage(errBody, "Registration failed (${authResponse.code()})"))
            }

            val respBody = runCatching { authResponse.body()?.string().orEmpty() }.getOrDefault("")
            val parsedUser = BackendJsonParser.parseUsers(respBody).firstOrNull()
            val resolvedId = parsedUser?.userId?.takeIf { it.isNotBlank() } ?: cleanId
            val resolvedName = parsedUser?.name?.takeIf { it.isNotBlank() } ?: cleanName
            val resolvedPhone = parsedUser?.phone?.takeIf { it.isNotBlank() } ?: cleanPhone

            // 3. Ensure profile is saved via /api/users/profile
            runCatching {
                val profileJson = JSONObject().apply {
                    put("user_id", resolvedId)
                    put("userId", resolvedId)
                    put("username", resolvedId)
                    put("name", resolvedName)
                    if (resolvedPhone.isNotBlank()) put("phone", resolvedPhone)
                    put("device_id", deviceLockId)
                }.toString()
                api.createOrUpdateProfile(profileJson.toRequestBody(jsonMediaType))
            }

            // 4. Mirror to Supabase REST cleanly if configured
            scope.launch {
                supabaseClient.upsertUserProfile(
                    userId = resolvedId,
                    name = resolvedName,
                    phone = resolvedPhone,
                    deviceId = deviceLockId
                )
            }

            val lockedSession = sessionManager.lockSession(resolvedId, resolvedName, resolvedPhone)
            _session.value = lockedSession
            webRTCClient.setCurrentUser(lockedSession.userId, lockedSession.name)
            signalingClient.connect(lockedSession.userId)
            ensureMyProfileExtra(lockedSession.userId)
            baselineLoaded = false
            refreshFeed()
            startPolling()
            NetworkResult.Success(lockedSession)
        } catch (e: SocketTimeoutException) {
            NetworkResult.Error("Server is waking up on Render (cold start). Please tap Connect again.")
        } catch (e: IOException) {
            NetworkResult.Error("Network connection error. Check your internet and try again.")
        } catch (e: Exception) {
            NetworkResult.Error(e.localizedMessage ?: "Unexpected error during registration.")
        }
    }

    suspend fun searchRemoteUsers(query: String): List<ChatUser> {
        val clean = query.trim().removePrefix("@")
        if (clean.isEmpty()) return emptyList()

        val found = mutableListOf<ChatUser>()
        runCatching {
            val searchResp = api.searchUsers(clean)
            if (searchResp.code() == 403) {
                _blockedEvents.tryEmit(BLOCKED_MESSAGE)
                return emptyList()
            }
            if (searchResp.isSuccessful) {
                found += BackendJsonParser.parseUsers(searchResp.body()?.string().orEmpty())
            }
        }
        runCatching {
            val findResp = api.findUser(
                userId = clean,
                userIdSnake = clean,
                username = clean,
                phone = if (clean.all { it.isDigit() || it == '+' }) clean else null
            )
            if (findResp.isSuccessful) {
                found += BackendJsonParser.parseUsers(findResp.body()?.string().orEmpty())
            }
        }
        if (supabaseClient.isConfigured()) {
            found += supabaseClient.searchUsers(clean)
        }

        val existingMap = dao.getAllUsers().associateBy { it.userId.lowercase(Locale.US) }
        val merged = mergeUserLists(found, existingMap)
        if (merged.isNotEmpty()) {
            dao.upsertUsers(merged.map { UserEntity.fromDomain(it) })
            syncUserBiosToExtras(merged)
        }
        return merged
    }

    suspend fun fetchAndCacheUser(userId: String) {
        val clean = userId.trim().removePrefix("@")
        if (clean.isEmpty()) return
        val found = mutableListOf<ChatUser>()
        runCatching {
            val resp = api.findUser(userId = clean, userIdSnake = clean, username = clean)
            if (resp.isSuccessful) {
                found += BackendJsonParser.parseUsers(resp.body()?.string().orEmpty())
            }
        }
        if (found.isNotEmpty()) {
            val existingMap = dao.getAllUsers().associateBy { it.userId.lowercase(Locale.US) }
            val merged = mergeUserLists(found, existingMap)
            dao.upsertUsers(merged.map { UserEntity.fromDomain(it) })
            syncUserBiosToExtras(merged)
        }
    }

    suspend fun refreshFeed(): NetworkResult<FeedPayload> {
        val currentUser = _session.value ?: return NetworkResult.Error("Not logged in.")
        return try {
            // 1. Fetch active users list from /api/users
            val usersResp = api.getUsers()
            if (usersResp.code() == 403) {
                _blockedEvents.tryEmit(BLOCKED_MESSAGE)
                return NetworkResult.Blocked(BLOCKED_MESSAGE)
            }
            if (!usersResp.isSuccessful) {
                val err = "Sync error (${usersResp.code()})"
                _syncError.value = err
                return NetworkResult.Error(err)
            }

            val rawUsers = usersResp.body()?.string().orEmpty()
            val parsedUsersFromUsersApi = BackendJsonParser.parseUsers(rawUsers)
            val parsedUsersFeed = BackendJsonParser.parseFeed(rawUsers)

            val allUsers = mutableListOf<ChatUser>()
            allUsers += parsedUsersFromUsersApi
            if (parsedUsersFeed.users.isNotEmpty()) {
                allUsers += parsedUsersFeed.users
            }

            // Optional Supabase user enrichment if configured
            if (supabaseClient.isConfigured()) {
                allUsers += supabaseClient.fetchUsers()
            }

            val existingMap = dao.getAllUsers().associateBy { it.userId.lowercase(Locale.US) }
            val dedupedUsers = mergeUserLists(allUsers, existingMap)

            if (dedupedUsers.isNotEmpty()) {
                dao.upsertUsers(dedupedUsers.map { UserEntity.fromDomain(it) })
                syncUserBiosToExtras(dedupedUsers)
            }

            // 2. Poll global broadcast ("all"), active chat, and ALL peer threads in bounded parallel batches
            val peerIdsToPoll = linkedSetOf(BROADCAST_TARGET_ID)
            activePeerTarget?.takeIf { it.isNotBlank() }?.let { peerIdsToPoll.add(it) }
            val existingUsers = dedupedUsers.ifEmpty { dao.getAllUsers().map { it.toDomain() } }
            existingUsers
                .filterNot { it.userId.equals(currentUser.userId, ignoreCase = true) }
                .sortedByDescending { it.lastSeenEpochMs ?: 0L }
                .forEach { peerIdsToPoll.add(it.userId) }

            val collectedMessages = Collections.synchronizedList(mutableListOf<ChatMessage>())
            val collectedSignals = Collections.synchronizedList(mutableListOf<SignalingEnvelope>())
            val collectedPosts = Collections.synchronizedList(mutableListOf<SocialPost>())
            val collectedActions = Collections.synchronizedList(mutableListOf<SocialActionEnvelope>())

            collectedMessages += parsedUsersFeed.messages
            collectedSignals += parsedUsersFeed.signals
            collectedPosts += parsedUsersFeed.posts
            collectedActions += parsedUsersFeed.socialActions

            if (supabaseClient.isConfigured()) {
                collectedPosts += supabaseClient.fetchSocialPosts()
            }

            var blockedByPeerPoll = false
            val semaphore = Semaphore(4)
            coroutineScope {
                peerIdsToPoll.map { peerId ->
                    async {
                        semaphore.withPermit {
                            val msgResp = runCatching {
                                api.getMessagesWithPeer(peerId = peerId, userId = currentUser.userId)
                            }.getOrNull() ?: return@withPermit

                            if (msgResp.code() == 403) {
                                blockedByPeerPoll = true
                                return@withPermit
                            }
                            if (msgResp.isSuccessful) {
                                val bodyStr = msgResp.body()?.string().orEmpty()
                                val feed = BackendJsonParser.parseFeed(bodyStr)
                                collectedMessages += feed.messages
                                collectedSignals += feed.signals
                                collectedPosts += feed.posts
                                collectedActions += feed.socialActions
                            }
                        }
                    }
                }.awaitAll()
            }

            if (blockedByPeerPoll) {
                _blockedEvents.tryEmit(BLOCKED_MESSAGE)
                return NetworkResult.Blocked(BLOCKED_MESSAGE)
            }

            if (collectedSignals.isNotEmpty()) {
                signalingClient.onRestSignalsReceived(collectedSignals)
            }
            val finalMessages = collectedMessages.distinctBy { it.dedupeKey }.sortedBy { it.timestampMs }
            if (finalMessages.isNotEmpty()) {
                dao.upsertMessages(finalMessages.map { MessageEntity.fromDomain(it) })
            }
            val finalPosts = collectedPosts.distinctBy { it.id }
            if (finalPosts.isNotEmpty()) {
                dao.insertSocialPostsIfAbsent(finalPosts.map { SocialPostEntity.fromDomain(it) })
            }
            if (collectedActions.isNotEmpty()) {
                applyIncomingSocialActions(
                    collectedActions.distinctBy { it.actionId }.sortedBy { it.timestampMs }
                )
            }

            _lastSyncMs.value = System.currentTimeMillis()
            _syncError.value = null

            processIncomingNotifications(dedupedUsers, finalMessages)
            NetworkResult.Success(
                FeedPayload(
                    users = dedupedUsers,
                    messages = finalMessages,
                    signals = collectedSignals.toList(),
                    posts = finalPosts,
                    socialActions = collectedActions.toList()
                )
            )
        } catch (e: SocketTimeoutException) {
            val msg = "Server is waking up / timed out while syncing."
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

    private fun mergeUserLists(
        incoming: List<ChatUser>,
        existingMap: Map<String, UserEntity>
    ): List<ChatUser> {
        val mergedById = linkedMapOf<String, ChatUser>()
        for (user in incoming) {
            if (user.userId.isBlank()) continue
            val key = user.userId.lowercase(Locale.US)
            val prev = mergedById[key] ?: existingMap[key]?.toDomain()
            mergedById[key] = if (prev == null) {
                user
            } else {
                ChatUser(
                    userId = user.userId,
                    name = user.name.ifBlank { prev.name },
                    lastSeenEpochMs = maxOfNullable(user.lastSeenEpochMs, prev.lastSeenEpochMs),
                    explicitOnline = user.explicitOnline ?: prev.explicitOnline,
                    statusText = user.statusText ?: prev.statusText,
                    avatarUrl = user.avatarUrl?.takeIf { it.isNotBlank() } ?: prev.avatarUrl,
                    bio = user.bio?.takeIf { it.isNotBlank() } ?: prev.bio,
                    phone = user.phone?.takeIf { it.isNotBlank() } ?: prev.phone
                )
            }
        }
        return mergedById.values.toList()
    }

    private fun maxOfNullable(a: Long?, b: Long?): Long? = when {
        a != null && b != null -> maxOf(a, b)
        a != null -> a
        else -> b
    }

    private suspend fun syncUserBiosToExtras(users: List<ChatUser>) {
        for (u in users) {
            val incomingBio = u.bio?.takeIf { it.isNotBlank() } ?: continue
            val existing = dao.getProfileExtra(u.userId)?.toDomain() ?: UserProfileExtras(userId = u.userId)
            if (existing.bio != incomingBio) {
                dao.upsertProfileExtra(ProfileExtraEntity.fromDomain(existing.copy(bio = incomingBio)))
            }
        }
    }

    // ==================== Chat Messaging ====================

    suspend fun sendTextMessage(receiver: String, text: String): NetworkResult<Unit> {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return NetworkResult.Error("Message cannot be empty.")
        return sendOutgoingMessage(
            receiver = receiver,
            wireText = cleanText,
            displayText = cleanText,
            kind = MessageKind.TEXT
        )
    }

    suspend fun sendLocationMessage(receiver: String, location: LocationPayload): NetworkResult<Unit> {
        val wireText = BackendJsonParser.encodeLocation(location)
        val displayText = if (location.isLive) "Live GPS Location" else "Shared Location"
        return sendOutgoingMessage(
            receiver = receiver,
            wireText = wireText,
            displayText = displayText,
            kind = MessageKind.LOCATION,
            location = location
        )
    }

    suspend fun sendMediaMessage(receiver: String, uri: Uri, forcedKind: MessageKind?): NetworkResult<Unit> {
        val currentUser = _session.value ?: return NetworkResult.Error("Not logged in.")
        val inspected = MediaHelper.inspectPickedUri(appContext, uri, forcedKind)
        val uploaded = mediaUploadService.uploadUri(
            uri = uri,
            fileName = inspected.displayName,
            mimeType = inspected.mimeType,
            userId = currentUser.userId,
            mediaTypeTag = inspected.kind.name.lowercase(Locale.US)
        )
        val finalPayload = if (uploaded != null) {
            inspected.copy(localUri = uploaded.remoteUrl)
        } else {
            inspected
        }
        val wireText = BackendJsonParser.encodeMedia(finalPayload)
        return sendOutgoingMessage(
            receiver = receiver,
            wireText = wireText,
            displayText = finalPayload.displayName,
            kind = finalPayload.kind,
            media = finalPayload
        )
    }

    suspend fun deleteChatMessage(message: ChatMessage): NetworkResult<Unit> {
        val currentUser = _session.value ?: return NetworkResult.Error("Not logged in.")
        dao.deleteMessage(message.dedupeKey, message.id)
        if (message.id.startsWith("local_")) {
            return NetworkResult.Success(Unit)
        }
        return try {
            val response = api.deleteMessage(message.id, currentUser.userId)
            if (response.code() == 403) {
                _blockedEvents.tryEmit(BLOCKED_MESSAGE)
                return NetworkResult.Blocked(BLOCKED_MESSAGE)
            }
            NetworkResult.Success(Unit)
        } catch (e: IOException) {
            NetworkResult.Error("Deleted locally; server unreachable.")
        }
    }

    private suspend fun sendOutgoingMessage(
        receiver: String,
        wireText: String,
        displayText: String,
        kind: MessageKind,
        location: LocationPayload? = null,
        media: MediaPayload? = null
    ): NetworkResult<Unit> {
        val currentUser = _session.value ?: return NetworkResult.Error("Not logged in.")
        val target = receiver.trim()
        val now = System.currentTimeMillis()
        val localId = "local_${now}_${UUID.randomUUID().toString().take(6)}"

        val optimisticMsg = ChatMessage(
            id = localId,
            sender = currentUser.userId,
            senderName = currentUser.name,
            receiver = target,
            text = displayText,
            timestampMs = now,
            kind = kind,
            deliveryStatus = MessageDeliveryStatus.PENDING,
            location = location,
            media = media
        )
        seenMessageKeys.add(optimisticMsg.dedupeKey)
        dao.insertMessage(MessageEntity.fromDomain(optimisticMsg))
        markThreadAsRead(target)

        val mediaUrl = media?.localUri?.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }
        val msgTypeStr = when (kind) {
            MessageKind.TEXT -> "text"
            MessageKind.IMAGE -> "image"
            MessageKind.VIDEO -> "video"
            MessageKind.AUDIO -> "audio"
            MessageKind.DOCUMENT -> "document"
            MessageKind.LOCATION -> "location"
        }

        val json = JSONObject().apply {
            put("sender_id", currentUser.userId)
            put("senderId", currentUser.userId)
            put("sender", currentUser.userId)
            put("receiver_id", target)
            put("receiverId", target)
            put("receiver", target)
            put("message", wireText)
            put("text", wireText)
            put("content", wireText)
            if (mediaUrl != null) {
                put("media_url", mediaUrl)
                put("mediaUrl", mediaUrl)
            }
            put("msg_type", msgTypeStr)
            put("type", msgTypeStr)
            if (location != null) {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("location_label", location.label)
                put("locationLabel", location.label)
            }
            put("timestamp", now)
            put("is_read", false)
            put("isRead", false)
        }.toString()

        return try {
            val response = api.sendMessage(json.toRequestBody(jsonMediaType))
            if (response.code() == 403) {
                dao.deleteMessage(optimisticMsg.dedupeKey)
                _blockedEvents.tryEmit(BLOCKED_MESSAGE)
                return NetworkResult.Blocked(BLOCKED_MESSAGE)
            }
            if (!response.isSuccessful) {
                val errBody = runCatching { response.errorBody()?.string() }.getOrNull()
                return NetworkResult.Error(extractErrorMessage(errBody, "Failed to send (${response.code()})"))
            }

            dao.insertMessage(
                MessageEntity.fromDomain(
                    optimisticMsg.copy(deliveryStatus = MessageDeliveryStatus.SENT)
                )
            )
            refreshFeed()
            NetworkResult.Success(Unit)
        } catch (e: SocketTimeoutException) {
            NetworkResult.Error("Server timed out (cold start). Message kept locally; retry shortly.")
        } catch (e: IOException) {
            NetworkResult.Error("Network unavailable. Message queued locally.")
        } catch (e: Exception) {
            NetworkResult.Error(e.localizedMessage ?: "Failed to send message.")
        }
    }

    suspend fun markThreadAsRead(threadId: String) {
        val currentUser = _session.value ?: return
        val now = System.currentTimeMillis()
        dao.markThreadRead(ThreadReadEntity(threadId = threadId, lastReadMs = now))

        if (threadId != BROADCAST_TARGET_ID && !threadId.equals(currentUser.userId, ignoreCase = true)) {
            scope.launch {
                runCatching {
                    val body = JSONObject().apply {
                        put("userId", currentUser.userId)
                        put("user_id", currentUser.userId)
                        put("is_read", true)
                        put("isRead", true)
                    }.toString()
                    api.markMessagesRead(threadId, body.toRequestBody(jsonMediaType))
                }
                signalingClient.sendSignal(
                    SignalingEnvelope(
                        signalId = "read_${currentUser.userId}_${threadId}_${now / 3000}",
                        type = "read_receipt",
                        sender = currentUser.userId,
                        senderName = currentUser.name,
                        receiver = threadId,
                        timestampMs = now
                    )
                )
            }
        }
    }

    // ==================== Social Media Actions ====================

    private suspend fun recordActionId(actionId: String) {
        processedSocialActionIds.add(actionId)
        dao.markSocialActionApplied(AppliedSocialActionEntity(actionId))
    }

    suspend fun createSocialPost(
        mediaType: SocialMediaType,
        mediaUri: Uri?,
        caption: String,
        shayariTag: String = ""
    ): NetworkResult<SocialPost> {
        val me = _session.value ?: return NetworkResult.Error("Not logged in.")
        var resolvedRemoteUrl = ""
        var compactThumbnailBase64 = ""

        if (mediaUri != null) {
            val forced = when (mediaType) {
                SocialMediaType.PHOTO -> MessageKind.IMAGE
                SocialMediaType.REEL -> MessageKind.VIDEO
                SocialMediaType.QUOTE -> null
            }
            val inspected = MediaHelper.inspectPickedUri(appContext, mediaUri, forced)
            val uploaded = mediaUploadService.uploadUri(
                uri = mediaUri,
                fileName = inspected.displayName,
                mimeType = inspected.mimeType,
                userId = me.userId,
                mediaTypeTag = mediaType.name.lowercase(Locale.US)
            )

            if (mediaType == SocialMediaType.REEL) {
                if (uploaded == null || uploaded.remoteUrl.isBlank()) {
                    return NetworkResult.Error("Reel video upload failed. Check internet connection and try again.")
                }
                resolvedRemoteUrl = uploaded.remoteUrl
                compactThumbnailBase64 = inspected.inlineDataBase64
            } else if (mediaType == SocialMediaType.PHOTO) {
                if (uploaded != null && uploaded.remoteUrl.isNotBlank()) {
                    resolvedRemoteUrl = uploaded.remoteUrl
                    compactThumbnailBase64 = ""
                } else if (inspected.inlineDataBase64.isNotBlank()) {
                    compactThumbnailBase64 = inspected.inlineDataBase64
                } else {
                    return NetworkResult.Error("Photo upload failed. Please try another photo.")
                }
            }
        }

        val post = SocialPost(
            id = "post_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}",
            authorId = me.userId,
            authorName = me.name,
            mediaType = mediaType,
            mediaUri = resolvedRemoteUrl,
            inlineImageBase64 = compactThumbnailBase64,
            caption = caption.trim(),
            shayariTag = shayariTag.trim(),
            timestampMs = System.currentTimeMillis(),
            reachCount = 1
        )
        dao.upsertSocialPost(SocialPostEntity.fromDomain(post))

        val wire = BackendJsonParser.encodePost(post)
        val sendResult = sendSocialWireEnvelope(
            sender = me.userId,
            receiver = BROADCAST_TARGET_ID,
            wireText = wire,
            mediaUrl = resolvedRemoteUrl.takeIf { it.isNotBlank() }
        )
        if (supabaseClient.isConfigured()) {
            scope.launch { supabaseClient.upsertSocialPost(post) }
        }
        return when (sendResult) {
            is NetworkResult.Blocked -> sendResult
            is NetworkResult.Error -> {
                // Keep locally visible while informing caller that server sync failed
                NetworkResult.Error(sendResult.message)
            }
            is NetworkResult.Success -> NetworkResult.Success(post)
        }
    }

    suspend fun toggleLikePost(postId: String) {
        val me = _session.value ?: return
        val existing = dao.getPostById(postId)?.toDomain() ?: return
        val nowLiked = !existing.isLikedByMe
        val updated = existing.copy(
            isLikedByMe = nowLiked,
            likeCount = (existing.likeCount + if (nowLiked) 1 else -1).coerceAtLeast(0),
            reachCount = existing.reachCount + if (nowLiked) 1 else 0
        )
        dao.upsertSocialPost(SocialPostEntity.fromDomain(updated))

        val action = SocialActionEnvelope(
            actionId = "act_${UUID.randomUUID()}",
            actionType = if (nowLiked) "like" else "unlike",
            actorId = me.userId,
            actorName = me.name,
            targetUserId = existing.authorId,
            postId = postId
        )
        recordActionId(action.actionId)
        sendSocialWireEnvelope(me.userId, BROADCAST_TARGET_ID, BackendJsonParser.encodeSocialAction(action))
    }

    suspend fun toggleBookmarkPost(postId: String) {
        val existing = dao.getPostById(postId)?.toDomain() ?: return
        val updated = existing.copy(isBookmarkedByMe = !existing.isBookmarkedByMe)
        dao.upsertSocialPost(SocialPostEntity.fromDomain(updated))
    }

    suspend fun addComment(postId: String, text: String) {
        val me = _session.value ?: return
        val clean = text.trim()
        if (clean.isEmpty()) return
        val now = System.currentTimeMillis()
        val commentId = "cmt_${now}_${UUID.randomUUID().toString().take(6)}"
        val comment = SocialComment(
            id = commentId,
            postId = postId,
            authorId = me.userId,
            authorName = me.name,
            text = clean,
            timestampMs = now
        )
        dao.insertComment(SocialCommentEntity.fromDomain(comment))

        val existing = dao.getPostById(postId)?.toDomain()
        if (existing != null) {
            dao.upsertSocialPost(
                SocialPostEntity.fromDomain(
                    existing.copy(
                        commentCount = existing.commentCount + 1,
                        reachCount = existing.reachCount + 2
                    )
                )
            )
        }

        val action = SocialActionEnvelope(
            actionId = commentId,
            actionType = "comment",
            actorId = me.userId,
            actorName = me.name,
            targetUserId = existing?.authorId.orEmpty(),
            postId = postId,
            payloadText = clean,
            timestampMs = now
        )
        recordActionId(action.actionId)
        sendSocialWireEnvelope(me.userId, BROADCAST_TARGET_ID, BackendJsonParser.encodeSocialAction(action))
    }

    suspend fun repostPost(postId: String) {
        val me = _session.value ?: return
        val original = dao.getPostById(postId)?.toDomain() ?: return
        val now = System.currentTimeMillis()
        dao.upsertSocialPost(
            SocialPostEntity.fromDomain(
                original.copy(
                    shareCount = original.shareCount + 1,
                    reachCount = original.reachCount + 3
                )
            )
        )
        val repost = original.copy(
            id = "repost_${now}_${UUID.randomUUID().toString().take(6)}",
            authorId = me.userId,
            authorName = me.name,
            timestampMs = now,
            likeCount = 0,
            commentCount = 0,
            shareCount = 0,
            reachCount = 1,
            isLikedByMe = false,
            isBookmarkedByMe = false,
            isRepost = true,
            repostedFromName = original.authorName
        )
        dao.upsertSocialPost(SocialPostEntity.fromDomain(repost))
        sendSocialWireEnvelope(
            me.userId,
            BROADCAST_TARGET_ID,
            BackendJsonParser.encodePost(repost),
            repost.mediaUri.takeIf { it.startsWith("http", true) }
        )
        val repostAction = SocialActionEnvelope(
            actionId = "rep_${repost.id}",
            actionType = "repost",
            actorId = me.userId,
            actorName = me.name,
            targetUserId = original.authorId,
            postId = original.id,
            payloadText = original.caption.take(48),
            timestampMs = now
        )
        recordActionId(repostAction.actionId)
        sendSocialWireEnvelope(me.userId, BROADCAST_TARGET_ID, BackendJsonParser.encodeSocialAction(repostAction))
    }

    suspend fun toggleFollowUser(targetUserId: String) {
        val me = _session.value ?: return
        if (targetUserId.equals(me.userId, ignoreCase = true)) return

        val targetExtra = dao.getProfileExtra(targetUserId)?.toDomain()
            ?: UserProfileExtras(userId = targetUserId)
        val myExtra = dao.getProfileExtra(me.userId)?.toDomain()
            ?: UserProfileExtras(userId = me.userId)

        val nowFollowing = !targetExtra.isFollowedByMe
        val delta = if (nowFollowing) 1 else -1

        dao.upsertProfileExtra(
            ProfileExtraEntity.fromDomain(
                targetExtra.copy(
                    isFollowedByMe = nowFollowing,
                    followersCount = (targetExtra.followersCount + delta).coerceAtLeast(0)
                )
            )
        )
        dao.upsertProfileExtra(
            ProfileExtraEntity.fromDomain(
                myExtra.copy(
                    followingCount = (myExtra.followingCount + delta).coerceAtLeast(0)
                )
            )
        )

        val action = SocialActionEnvelope(
            actionId = "act_${UUID.randomUUID()}",
            actionType = if (nowFollowing) "follow" else "unfollow",
            actorId = me.userId,
            actorName = me.name,
            targetUserId = targetUserId
        )
        recordActionId(action.actionId)
        sendSocialWireEnvelope(me.userId, BROADCAST_TARGET_ID, BackendJsonParser.encodeSocialAction(action))
    }

    suspend fun updateMyBio(newBio: String): NetworkResult<Unit> {
        return updateMyProfile(newBio = newBio, avatarUri = null)
    }

    suspend fun updateMyAvatar(avatarUri: Uri): NetworkResult<Unit> {
        return updateMyProfile(newBio = null, avatarUri = avatarUri)
    }

    suspend fun updateMyProfile(newBio: String?, avatarUri: Uri?): NetworkResult<Unit> {
        val me = _session.value ?: return NetworkResult.Error("Not logged in.")
        val cleanBio = newBio?.trim()

        var uploadedAvatarUrl: String? = null
        if (avatarUri != null) {
            val inspected = MediaHelper.inspectPickedUri(appContext, avatarUri, MessageKind.IMAGE)
            val uploaded = mediaUploadService.uploadUri(
                uri = avatarUri,
                fileName = inspected.displayName.ifBlank { "avatar_${me.userId}.jpg" },
                mimeType = inspected.mimeType,
                userId = me.userId,
                mediaTypeTag = "avatar"
            )
            if (uploaded == null || uploaded.remoteUrl.isBlank()) {
                return NetworkResult.Error("Profile photo upload failed. Check internet and try again.")
            }
            uploadedAvatarUrl = uploaded.remoteUrl
        }

        val existingExtra = dao.getProfileExtra(me.userId)?.toDomain()
            ?: UserProfileExtras(userId = me.userId)
        val effectiveBio = cleanBio ?: existingExtra.bio

        if (cleanBio != null) {
            dao.upsertProfileExtra(
                ProfileExtraEntity.fromDomain(existingExtra.copy(bio = cleanBio))
            )
        }

        val existingUser = dao.getUserById(me.userId)?.toDomain()
            ?: ChatUser(userId = me.userId, name = me.name, phone = me.phone.takeIf { it.isNotBlank() })
        val effectiveAvatar = uploadedAvatarUrl ?: existingUser.avatarUrl.orEmpty()
        dao.upsertUser(
            UserEntity.fromDomain(
                existingUser.copy(
                    avatarUrl = effectiveAvatar.takeIf { it.isNotBlank() },
                    bio = effectiveBio
                )
            )
        )

        val profileJson = JSONObject().apply {
            put("user_id", me.userId)
            put("userId", me.userId)
            put("username", me.userId)
            put("name", me.name)
            put("bio", effectiveBio)
            if (effectiveAvatar.isNotBlank()) {
                put("avatar_url", effectiveAvatar)
                put("avatarUrl", effectiveAvatar)
            }
            if (me.phone.isNotBlank()) put("phone", me.phone)
        }.toString()

        runCatching {
            val putResp = api.updateProfile(profileJson.toRequestBody(jsonMediaType))
            if (!putResp.isSuccessful) {
                api.createOrUpdateProfile(profileJson.toRequestBody(jsonMediaType))
            }
        }
        scope.launch {
            supabaseClient.upsertUserProfile(
                userId = me.userId,
                name = me.name,
                phone = me.phone,
                deviceId = me.deviceLockId,
                bio = effectiveBio,
                avatarUrl = effectiveAvatar
            )
        }

        if (cleanBio != null) {
            val bioAction = SocialActionEnvelope(
                actionId = "bio_${UUID.randomUUID()}",
                actionType = "profile_bio",
                actorId = me.userId,
                actorName = me.name,
                targetUserId = me.userId,
                payloadText = cleanBio
            )
            recordActionId(bioAction.actionId)
            sendSocialWireEnvelope(me.userId, BROADCAST_TARGET_ID, BackendJsonParser.encodeSocialAction(bioAction))
        }
        if (uploadedAvatarUrl != null) {
            val avatarAction = SocialActionEnvelope(
                actionId = "avt_${UUID.randomUUID()}",
                actionType = "profile_avatar",
                actorId = me.userId,
                actorName = me.name,
                targetUserId = me.userId,
                payloadText = uploadedAvatarUrl
            )
            recordActionId(avatarAction.actionId)
            sendSocialWireEnvelope(me.userId, BROADCAST_TARGET_ID, BackendJsonParser.encodeSocialAction(avatarAction))
        }
        return NetworkResult.Success(Unit)
    }

    suspend fun markAllActivityRead() {
        dao.markAllActivityRead()
    }

    private suspend fun sendSocialWireEnvelope(
        sender: String,
        receiver: String,
        wireText: String,
        mediaUrl: String? = null
    ): NetworkResult<Unit> {
        val json = JSONObject().apply {
            put("sender_id", sender)
            put("senderId", sender)
            put("sender", sender)
            put("receiver_id", receiver)
            put("receiverId", receiver)
            put("receiver", receiver)
            put("message", wireText)
            put("text", wireText)
            put("content", wireText)
            if (!mediaUrl.isNullOrBlank() && mediaUrl.startsWith("http", ignoreCase = true)) {
                put("media_url", mediaUrl)
                put("mediaUrl", mediaUrl)
            }
            put("msg_type", "text")
            put("type", "text")
            put("timestamp", System.currentTimeMillis())
        }.toString()
        return try {
            val resp = api.sendMessage(json.toRequestBody(jsonMediaType))
            when {
                resp.code() == 403 -> {
                    _blockedEvents.tryEmit(BLOCKED_MESSAGE)
                    NetworkResult.Blocked(BLOCKED_MESSAGE)
                }
                resp.isSuccessful -> NetworkResult.Success(Unit)
                else -> NetworkResult.Error("Server sync failed (${resp.code()}).")
            }
        } catch (e: IOException) {
            NetworkResult.Error("Saved locally; server unreachable right now.")
        } catch (e: Exception) {
            NetworkResult.Error(e.localizedMessage ?: "Failed to sync with server.")
        }
    }

    private suspend fun applyIncomingSocialActions(actions: List<SocialActionEnvelope>) {
        val me = _session.value ?: return
        for (action in actions) {
            if (!processedSocialActionIds.add(action.actionId)) continue
            val inserted = dao.markSocialActionApplied(AppliedSocialActionEntity(action.actionId))
            if (inserted == -1L) continue

            val isMine = action.actorId.equals(me.userId, ignoreCase = true)

            when (action.actionType) {
                "like", "unlike" -> {
                    val post = dao.getPostById(action.postId)?.toDomain() ?: continue
                    val isLike = action.actionType == "like"
                    val delta = if (isLike) 1 else -1
                    dao.upsertSocialPost(
                        SocialPostEntity.fromDomain(
                            post.copy(
                                likeCount = (post.likeCount + delta).coerceAtLeast(0),
                                reachCount = post.reachCount + if (delta > 0) 1 else 0,
                                isLikedByMe = if (isMine) isLike else post.isLikedByMe
                            )
                        )
                    )
                    if (isLike && !isMine && post.authorId.equals(me.userId, ignoreCase = true)) {
                        dao.insertActivityNotification(
                            ActivityNotificationEntity.fromDomain(
                                ActivityNotification(
                                    id = action.actionId,
                                    type = ActivityType.LIKE,
                                    actorId = action.actorId,
                                    actorName = action.actorName,
                                    postId = post.id,
                                    postSnippet = post.caption.take(48),
                                    timestampMs = action.timestampMs
                                )
                            )
                        )
                    }
                }

                "comment" -> {
                    val existingComment = dao.getCommentById(action.actionId)
                    dao.insertComment(
                        SocialCommentEntity(
                            id = action.actionId,
                            postId = action.postId,
                            authorId = action.actorId,
                            authorName = action.actorName,
                            text = action.payloadText,
                            timestampMs = action.timestampMs
                        )
                    )
                    val post = dao.getPostById(action.postId)?.toDomain()
                    if (post != null && existingComment == null) {
                        dao.upsertSocialPost(
                            SocialPostEntity.fromDomain(
                                post.copy(
                                    commentCount = post.commentCount + 1,
                                    reachCount = post.reachCount + 2
                                )
                            )
                        )
                        if (!isMine && post.authorId.equals(me.userId, ignoreCase = true)) {
                            dao.insertActivityNotification(
                                ActivityNotificationEntity.fromDomain(
                                    ActivityNotification(
                                        id = action.actionId,
                                        type = ActivityType.COMMENT,
                                        actorId = action.actorId,
                                        actorName = action.actorName,
                                        postId = post.id,
                                        postSnippet = action.payloadText.take(60),
                                        timestampMs = action.timestampMs
                                    )
                                )
                            )
                        }
                    }
                }

                "repost" -> {
                    val post = dao.getPostById(action.postId)?.toDomain() ?: continue
                    dao.upsertSocialPost(
                        SocialPostEntity.fromDomain(
                            post.copy(
                                shareCount = post.shareCount + 1,
                                reachCount = post.reachCount + 3
                            )
                        )
                    )
                    if (!isMine && post.authorId.equals(me.userId, ignoreCase = true)) {
                        dao.insertActivityNotification(
                            ActivityNotificationEntity.fromDomain(
                                ActivityNotification(
                                    id = action.actionId,
                                    type = ActivityType.REPOST,
                                    actorId = action.actorId,
                                    actorName = action.actorName,
                                    postId = post.id,
                                    postSnippet = action.payloadText.take(48),
                                    timestampMs = action.timestampMs
                                )
                            )
                        )
                    }
                }

                "follow", "unfollow" -> {
                    val isFollow = action.actionType == "follow"
                    val delta = if (isFollow) 1 else -1
                    if (action.targetUserId.isNotBlank()) {
                        val targetExtra = dao.getProfileExtra(action.targetUserId)?.toDomain()
                            ?: UserProfileExtras(userId = action.targetUserId)
                        dao.upsertProfileExtra(
                            ProfileExtraEntity.fromDomain(
                                targetExtra.copy(
                                    followersCount = (targetExtra.followersCount + delta).coerceAtLeast(0),
                                    isFollowedByMe = if (isMine) isFollow else targetExtra.isFollowedByMe
                                )
                            )
                        )
                    }
                    if (action.actorId.isNotBlank()) {
                        val actorExtra = dao.getProfileExtra(action.actorId)?.toDomain()
                            ?: UserProfileExtras(userId = action.actorId)
                        dao.upsertProfileExtra(
                            ProfileExtraEntity.fromDomain(
                                actorExtra.copy(
                                    followingCount = (actorExtra.followingCount + delta).coerceAtLeast(0)
                                )
                            )
                        )
                    }
                    if (isFollow && !isMine &&
                        action.targetUserId.equals(me.userId, ignoreCase = true)
                    ) {
                        dao.insertActivityNotification(
                            ActivityNotificationEntity.fromDomain(
                                ActivityNotification(
                                    id = action.actionId,
                                    type = ActivityType.FOLLOW,
                                    actorId = action.actorId,
                                    actorName = action.actorName,
                                    postSnippet = "Started following you",
                                    timestampMs = action.timestampMs
                                )
                            )
                        )
                    }
                }

                "profile_bio" -> {
                    val extra = dao.getProfileExtra(action.actorId)?.toDomain()
                        ?: UserProfileExtras(userId = action.actorId)
                    dao.upsertProfileExtra(
                        ProfileExtraEntity.fromDomain(extra.copy(bio = action.payloadText))
                    )
                    dao.getUserById(action.actorId)?.let { u ->
                        dao.upsertUser(u.copy(bio = action.payloadText))
                    }
                }

                "profile_avatar" -> {
                    val resolvedUrl = BackendConfig.resolveUploadUrl(action.payloadText)
                    if (resolvedUrl.isNotBlank()) {
                        val existingUser = dao.getUserById(action.actorId)?.toDomain()
                            ?: ChatUser(userId = action.actorId, name = action.actorName)
                        dao.upsertUser(UserEntity.fromDomain(existingUser.copy(avatarUrl = resolvedUrl)))
                    }
                }
            }
        }
    }

    // ==================== Live GPS & Lifecycle ====================

    fun startLiveLocationSharing(receiver: String, durationMinutes: Int = 15) {
        liveLocationJob?.cancel()
        _isLiveLocationSharing.value = true
        val durationMs = durationMinutes * 60_000L
        liveLocationJob = scope.launch {
            try {
                locationTracker.liveLocationStream(durationMs).collect { locPayload ->
                    sendLocationMessage(receiver, locPayload)
                }
            } finally {
                _isLiveLocationSharing.value = false
            }
        }
    }

    fun stopLiveLocationSharing() {
        liveLocationJob?.cancel()
        liveLocationJob = null
        _isLiveLocationSharing.value = false
    }

    suspend fun logoutAndResetDeviceLock() {
        stopPolling()
        stopLiveLocationSharing()
        signalingClient.disconnect()
        webRTCClient.cleanupCallResources()
        baselineLoaded = false
        activePeerTarget = null
        seenMessageKeys.clear()
        processedSocialActionIds.clear()
        _syncError.value = null
        dao.clearUsers()
        dao.clearMessages()
        dao.clearThreadReads()
        dao.clearSocialPosts()
        dao.clearSocialComments()
        dao.clearProfileExtras()
        dao.clearActivityNotifications()
        dao.clearAppliedSocialActions()
        sessionManager.resetSessionLock()
        _session.value = null
    }

    private suspend fun handleIncomingSignal(envelope: SignalingEnvelope) {
        val currentUser = _session.value ?: return
        if (envelope.type == "read_receipt") {
            dao.updateOutgoingStatus(
                senderId = currentUser.userId,
                receiverId = envelope.sender,
                status = MessageDeliveryStatus.READ.name
            )
            return
        }
        webRTCClient.onIncomingSignal(envelope)
    }

    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            while (isActive && _session.value != null) {
                val result = refreshFeed()
                if (result is NetworkResult.Blocked) {
                    delay(15_000L)
                } else {
                    delay(3_000L)
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
            val existingKeys = messages.map { it.dedupeKey }.toSet()
            seenMessageKeys.addAll(existingKeys)
            sessionManager.addNotifiedMessageIds(existingKeys)
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
            sessionManager.addNotifiedMessageIds(newlyNotified)
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
