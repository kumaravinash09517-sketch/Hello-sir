package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ChatRepository
import com.example.data.model.BROADCAST_TARGET_ID
import com.example.data.model.ChatMessage
import com.example.data.model.ChatUser
import com.example.data.model.NetworkResult
import com.example.data.model.UserSession
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val session: UserSession? = null,
    val users: List<ChatUser> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val searchQuery: String = "",
    val selectedTargetId: String? = null,
    val isRegistering: Boolean = false,
    val isRefreshing: Boolean = false,
    val isSending: Boolean = false,
    val registerError: String? = null,
    val sendError: String? = null,
    val syncError: String? = null,
    val lastSyncMs: Long? = null,
    val blockedAlertMessage: String? = null
) {
    val filteredUsers: List<ChatUser>
        get() {
            val q = searchQuery.trim()
            if (q.isEmpty()) return users
            return users.filter {
                it.name.contains(q, ignoreCase = true) ||
                    it.userId.contains(q, ignoreCase = true)
            }
        }

    val onlineCount: Int
        get() = users.count { it.isOnline() }

    fun messagesForTarget(targetId: String): List<ChatMessage> {
        val me = session?.userId ?: return emptyList()
        if (targetId == BROADCAST_TARGET_ID) {
            return messages.filter { it.isBroadcast }
        }
        return messages.filter { msg ->
            !msg.isBroadcast && (
                (msg.sender.equals(me, ignoreCase = true) && msg.receiver.equals(targetId, ignoreCase = true)) ||
                    (msg.sender.equals(targetId, ignoreCase = true) && msg.receiver.equals(me, ignoreCase = true))
                )
        }
    }

    fun latestPreviewForTarget(targetId: String): ChatMessage? {
        return messagesForTarget(targetId).lastOrNull()
    }
}

private data class LocalUiFlags(
    val searchQuery: String = "",
    val selectedTargetId: String? = null,
    val isRegistering: Boolean = false,
    val isRefreshing: Boolean = false,
    val isSending: Boolean = false,
    val registerError: String? = null,
    val sendError: String? = null,
    val blockedAlertMessage: String? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ChatRepository.getInstance(application)
    private val localFlags = MutableStateFlow(LocalUiFlags())

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    val uiState: StateFlow<ChatUiState> = combine(
        repository.session,
        repository.users,
        repository.messages,
        repository.syncError,
        repository.lastSyncMs,
        localFlags
    ) { values ->
        val session = values[0] as UserSession?
        @Suppress("UNCHECKED_CAST")
        val users = values[1] as List<ChatUser>
        @Suppress("UNCHECKED_CAST")
        val messages = values[2] as List<ChatMessage>
        val syncError = values[3] as String?
        val lastSyncMs = values[4] as Long?
        val flags = values[5] as LocalUiFlags

        ChatUiState(
            session = session,
            users = users,
            messages = messages,
            searchQuery = flags.searchQuery,
            selectedTargetId = flags.selectedTargetId,
            isRegistering = flags.isRegistering,
            isRefreshing = flags.isRefreshing,
            isSending = flags.isSending,
            registerError = flags.registerError,
            sendError = flags.sendError,
            syncError = syncError,
            lastSyncMs = lastSyncMs,
            blockedAlertMessage = flags.blockedAlertMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ChatUiState()
    )

    init {
        viewModelScope.launch {
            repository.blockedEvents.collect { msg ->
                localFlags.update { it.copy(blockedAlertMessage = msg) }
                _toastEvents.tryEmit(msg)
            }
        }
    }

    fun register(userId: String, name: String) {
        val cleanName = name.trim()
        val cleanId = userId.trim()
        if (cleanName.isEmpty()) {
            localFlags.update { it.copy(registerError = "Please enter your name.") }
            return
        }
        if (cleanId.isEmpty() || cleanId.contains(" ")) {
            localFlags.update {
                it.copy(registerError = "Please enter a valid User ID without spaces.")
            }
            return
        }

        viewModelScope.launch {
            localFlags.update { it.copy(isRegistering = true, registerError = null) }
            when (val result = repository.register(cleanId, cleanName)) {
                is NetworkResult.Success -> {
                    localFlags.update { it.copy(isRegistering = false, registerError = null) }
                }
                is NetworkResult.Blocked -> {
                    localFlags.update {
                        it.copy(
                            isRegistering = false,
                            registerError = result.message,
                            blockedAlertMessage = result.message
                        )
                    }
                    _toastEvents.tryEmit(result.message)
                }
                is NetworkResult.Error -> {
                    localFlags.update {
                        it.copy(isRegistering = false, registerError = result.message)
                    }
                }
            }
        }
    }

    fun refreshFeed() {
        viewModelScope.launch {
            localFlags.update { it.copy(isRefreshing = true) }
            val result = repository.refreshFeed()
            localFlags.update { it.copy(isRefreshing = false) }
            if (result is NetworkResult.Blocked) {
                localFlags.update { it.copy(blockedAlertMessage = result.message) }
                _toastEvents.tryEmit(result.message)
            }
        }
    }

    fun sendMessage(receiver: String, text: String, onSuccess: () -> Unit = {}) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return

        viewModelScope.launch {
            localFlags.update { it.copy(isSending = true, sendError = null) }
            when (val result = repository.sendMessage(receiver, cleanText)) {
                is NetworkResult.Success -> {
                    localFlags.update { it.copy(isSending = false, sendError = null) }
                    onSuccess()
                }
                is NetworkResult.Blocked -> {
                    localFlags.update {
                        it.copy(
                            isSending = false,
                            sendError = result.message,
                            blockedAlertMessage = result.message
                        )
                    }
                    _toastEvents.tryEmit(result.message)
                }
                is NetworkResult.Error -> {
                    localFlags.update { it.copy(isSending = false, sendError = result.message) }
                    _toastEvents.tryEmit(result.message)
                }
            }
        }
    }

    fun selectTarget(targetId: String?) {
        localFlags.update { it.copy(selectedTargetId = targetId, sendError = null) }
    }

    fun updateSearchQuery(query: String) {
        localFlags.update { it.copy(searchQuery = query) }
    }

    fun clearRegisterError() {
        localFlags.update { it.copy(registerError = null) }
    }

    fun dismissBlockedAlert() {
        localFlags.update { it.copy(blockedAlertMessage = null) }
    }

    fun signOut() {
        viewModelScope.launch {
            localFlags.value = LocalUiFlags()
            repository.signOut()
        }
    }
}
