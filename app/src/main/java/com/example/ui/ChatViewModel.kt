package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ChatRepository
import com.example.data.model.ActivityNotification
import com.example.data.model.BROADCAST_TARGET_ID
import com.example.data.model.BlockedUser
import com.example.data.model.ChatMessage
import com.example.data.model.ChatUser
import com.example.data.model.MessageKind
import com.example.data.model.NetworkResult
import com.example.data.model.SocialComment
import com.example.data.model.SocialMediaType
import com.example.data.model.SocialPost
import com.example.data.model.SocialRelation
import com.example.data.model.UploadProgressState
import com.example.data.model.UserProfileExtras
import com.example.data.model.UserSession
import com.example.media.MediaHelper
import com.example.webrtc.CallStatus
import com.example.webrtc.CallUiState
import com.example.webrtc.SignalingTransportState
import com.example.webrtc.WebRTCClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

enum class AppTab {
    FEED,
    REELS,
    EXPLORE,
    CHATS,
    PROFILE
}

data class ChatUiState(
    val session: UserSession? = null,
    val activeTab: AppTab = AppTab.FEED,
    val users: List<ChatUser> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val threadReads: Map<String, Long> = emptyMap(),
    val socialPosts: List<SocialPost> = emptyList(),
    val socialComments: Map<String, List<SocialComment>> = emptyMap(),
    val profileExtras: Map<String, UserProfileExtras> = emptyMap(),
    val activityNotifications: List<ActivityNotification> = emptyList(),
    val searchQuery: String = "",
    val exploreQuery: String = "",
    val selectedTargetId: String? = null,
    val viewedProfileUserId: String? = null,
    val showActivityFeed: Boolean = false,
    val showProfileSettings: Boolean = false,
    val isCallScreenMinimized: Boolean = false,
    val isRegistering: Boolean = false,
    val isRefreshing: Boolean = false,
    val isSending: Boolean = false,
    val isFetchingLocation: Boolean = false,
    val isLiveLocationSharing: Boolean = false,
    val isSearchingRemote: Boolean = false,
    val isPublishingPost: Boolean = false,
    val isUpdatingProfile: Boolean = false,
    val uploadProgress: UploadProgressState? = null,
    val socialRelations: List<SocialRelation> = emptyList(),
    val blockedUsers: List<BlockedUser> = emptyList(),
    val registerError: String? = null,
    val sendError: String? = null,
    val syncError: String? = null,
    val lastSyncMs: Long? = null,
    val blockedAlertMessage: String? = null,
    val callState: CallUiState = CallUiState(),
    val signalingTransportState: SignalingTransportState = SignalingTransportState.DISCONNECTED
) {
    val blockedUserIds: Set<String>
        get() = blockedUsers.map { it.userId.lowercase() }.toSet()

    fun isUserBlocked(userId: String): Boolean =
        userId.isNotBlank() && blockedUserIds.contains(userId.lowercase())

    fun followersOf(userId: String): List<ChatUser> {
        val followerIds = socialRelations
            .filter { it.followingId.equals(userId, ignoreCase = true) }
            .map { it.followerId }
            .distinctBy { it.lowercase() }
        val userMap = users.associateBy { it.userId.lowercase() }
        return followerIds.map { fid ->
            userMap[fid.lowercase()] ?: ChatUser(userId = fid, name = fid)
        }
    }

    fun followingOf(userId: String): List<ChatUser> {
        val followingIds = socialRelations
            .filter { it.followerId.equals(userId, ignoreCase = true) }
            .map { it.followingId }
            .distinctBy { it.lowercase() }
        val userMap = users.associateBy { it.userId.lowercase() }
        return followingIds.map { fid ->
            userMap[fid.lowercase()] ?: ChatUser(userId = fid, name = fid)
        }
    }

    /**
     * Sorts chat users so active conversations with the newest messages bubble to the top,
     * preserving online priority and alphabetical fallback. Excludes locally blocked users.
     */
    val sortedChatUsers: List<ChatUser>
        get() {
            val now = System.currentTimeMillis()
            val blocked = blockedUserIds
            val unblocked = users.filterNot { blocked.contains(it.userId.lowercase()) }
            val q = searchQuery.trim().removePrefix("@")
            val filtered = if (q.isEmpty()) {
                unblocked
            } else {
                unblocked.filter {
                    it.name.contains(q, ignoreCase = true) ||
                        it.userId.contains(q, ignoreCase = true) ||
                        (it.phone?.contains(q, ignoreCase = true) == true)
                }
            }
            return filtered.sortedWith(
                compareByDescending<ChatUser> { latestPreviewForTarget(it.userId)?.timestampMs ?: 0L }
                    .thenByDescending { it.isOnline(now) }
                    .thenBy { it.name.lowercase() }
            )
        }

    /**
     * Supports Explore search by Name, Username, @UserID, or Phone (case-insensitive, @-prefix supported).
     */
    val exploredUsers: List<ChatUser>
        get() {
            val blocked = blockedUserIds
            val unblocked = users.filterNot { blocked.contains(it.userId.lowercase()) }
            val q = exploreQuery.trim().removePrefix("@")
            if (q.isEmpty()) return unblocked
            return unblocked.filter {
                it.name.contains(q, ignoreCase = true) ||
                    it.userId.contains(q, ignoreCase = true) ||
                    (it.phone?.contains(q, ignoreCase = true) == true)
            }
        }

    val exploredPosts: List<SocialPost>
        get() {
            val q = exploreQuery.trim().removePrefix("@")
            if (q.isEmpty()) return socialPosts
            return socialPosts.filter {
                it.caption.contains(q, ignoreCase = true) ||
                    it.authorName.contains(q, ignoreCase = true) ||
                    it.authorId.contains(q, ignoreCase = true) ||
                    it.shayariTag.contains(q, ignoreCase = true)
            }
        }

    val reelsPosts: List<SocialPost>
        get() = socialPosts.filter { it.mediaType == SocialMediaType.REEL }

    val totalUnreadMessages: Int
        get() {
            val blocked = blockedUserIds
            val broadcastUnread = unreadCountForTarget(BROADCAST_TARGET_ID)
            val userUnread = users
                .filterNot { blocked.contains(it.userId.lowercase()) }
                .sumOf { unreadCountForTarget(it.userId) }
            return broadcastUnread + userUnread
        }

    val unreadActivityCount: Int
        get() = activityNotifications.count { !it.isRead }

    val onlineCount: Int
        get() {
            val blocked = blockedUserIds
            return users.count { !blocked.contains(it.userId.lowercase()) && it.isOnline() }
        }

    fun unreadCountForTarget(targetId: String): Int {
        val me = session?.userId ?: return 0
        if (activeTab == AppTab.CHATS && selectedTargetId.equals(targetId, ignoreCase = true)) return 0
        val lastRead = threadReads[targetId] ?: 0L
        return messagesForTarget(targetId).count { msg ->
            !msg.sender.equals(me, ignoreCase = true) && msg.timestampMs > lastRead
        }
    }

    fun messagesForTarget(targetId: String): List<ChatMessage> {
        val me = session?.userId ?: return emptyList()
        val blocked = blockedUserIds
        if (targetId == BROADCAST_TARGET_ID) {
            return messages.filter { it.isBroadcast && !blocked.contains(it.sender.lowercase()) }
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
    val activeTab: AppTab = AppTab.FEED,
    val searchQuery: String = "",
    val exploreQuery: String = "",
    val selectedTargetId: String? = null,
    val viewedProfileUserId: String? = null,
    val showActivityFeed: Boolean = false,
    val showProfileSettings: Boolean = false,
    val isCallScreenMinimized: Boolean = false,
    val isRegistering: Boolean = false,
    val isRefreshing: Boolean = false,
    val isSending: Boolean = false,
    val isFetchingLocation: Boolean = false,
    val isSearchingRemote: Boolean = false,
    val isPublishingPost: Boolean = false,
    val isUpdatingProfile: Boolean = false,
    val registerError: String? = null,
    val sendError: String? = null,
    val blockedAlertMessage: String? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ChatRepository.getInstance(application)
    val webRTCClient: WebRTCClient = repository.webRTCClient

    private val localFlags = MutableStateFlow(LocalUiFlags())
    private var remoteSearchJob: Job? = null

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 6)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<ChatUiState> = combine(
        repository.session,
        repository.users,
        repository.messages,
        repository.threadReads,
        repository.socialPosts,
        repository.socialComments,
        repository.profileExtras,
        repository.activityNotifications,
        repository.syncError,
        repository.lastSyncMs,
        repository.callState,
        repository.signalingTransportState,
        repository.isLiveLocationSharing,
        repository.uploadProgress,
        repository.socialRelations,
        repository.blockedUsers,
        localFlags
    ) { values ->
        val session = values[0] as UserSession?
        val users = values[1] as List<ChatUser>
        val messages = values[2] as List<ChatMessage>
        val threadReads = values[3] as Map<String, Long>
        val rawPosts = values[4] as List<SocialPost>
        val comments = values[5] as Map<String, List<SocialComment>>
        val extras = values[6] as Map<String, UserProfileExtras>
        val rawActivities = values[7] as List<ActivityNotification>
        val syncError = values[8] as String?
        val lastSyncMs = values[9] as Long?
        val callState = values[10] as CallUiState
        val transport = values[11] as SignalingTransportState
        val isLiveLoc = values[12] as Boolean
        val uploadProgress = values[13] as UploadProgressState?
        val socialRelations = values[14] as List<SocialRelation>
        val blockedUsers = values[15] as List<BlockedUser>
        val flags = values[16] as LocalUiFlags

        val blockedIds = blockedUsers.map { it.userId.lowercase() }.toSet()
        val visiblePosts = rawPosts.filterNot { blockedIds.contains(it.authorId.lowercase()) }
        val visibleActivities = rawActivities.filterNot { blockedIds.contains(it.actorId.lowercase()) }

        ChatUiState(
            session = session,
            activeTab = flags.activeTab,
            users = users,
            messages = messages,
            threadReads = threadReads,
            socialPosts = visiblePosts,
            socialComments = comments,
            profileExtras = extras,
            activityNotifications = visibleActivities,
            searchQuery = flags.searchQuery,
            exploreQuery = flags.exploreQuery,
            selectedTargetId = flags.selectedTargetId,
            viewedProfileUserId = flags.viewedProfileUserId,
            showActivityFeed = flags.showActivityFeed,
            showProfileSettings = flags.showProfileSettings,
            isCallScreenMinimized = if (callState.isActive) flags.isCallScreenMinimized else false,
            isRegistering = flags.isRegistering,
            isRefreshing = flags.isRefreshing,
            isSending = flags.isSending,
            isFetchingLocation = flags.isFetchingLocation,
            isLiveLocationSharing = isLiveLoc,
            isSearchingRemote = flags.isSearchingRemote,
            isPublishingPost = flags.isPublishingPost,
            isUpdatingProfile = flags.isUpdatingProfile,
            uploadProgress = uploadProgress,
            socialRelations = socialRelations,
            blockedUsers = blockedUsers,
            registerError = flags.registerError,
            sendError = flags.sendError,
            syncError = syncError,
            lastSyncMs = lastSyncMs,
            blockedAlertMessage = flags.blockedAlertMessage,
            callState = callState,
            signalingTransportState = transport
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
        viewModelScope.launch {
            repository.callState.collect { call ->
                if (call.status == CallStatus.INCOMING_RINGING || call.status == CallStatus.OUTGOING_RINGING) {
                    localFlags.update { it.copy(isCallScreenMinimized = false) }
                }
            }
        }
    }

    fun selectTab(tab: AppTab) {
        localFlags.update {
            it.copy(
                activeTab = tab,
                showActivityFeed = false,
                showProfileSettings = false,
                viewedProfileUserId = if (tab == AppTab.PROFILE) it.viewedProfileUserId else null
            )
        }
    }

    fun openUserProfile(userId: String) {
        localFlags.update {
            it.copy(
                activeTab = AppTab.PROFILE,
                viewedProfileUserId = userId,
                showActivityFeed = false,
                showProfileSettings = false
            )
        }
        viewModelScope.launch {
            repository.fetchAndCacheUser(userId)
            repository.refreshFeed()
        }
    }

    fun openActivityFeed() {
        localFlags.update { it.copy(showActivityFeed = true, showProfileSettings = false) }
        viewModelScope.launch { repository.markAllActivityRead() }
    }

    fun closeActivityFeed() {
        localFlags.update { it.copy(showActivityFeed = false) }
    }

    fun minimizeCallScreen() {
        localFlags.update { it.copy(isCallScreenMinimized = true) }
    }

    fun reopenCallScreen() {
        localFlags.update { it.copy(isCallScreenMinimized = false) }
    }

    fun register(userId: String, name: String, phone: String = "") {
        val cleanName = name.trim()
        val cleanId = userId.trim()
        val cleanPhone = phone.trim()
        if (cleanName.isEmpty()) {
            localFlags.update { it.copy(registerError = "Please enter your name.") }
            return
        }
        if (cleanId.isEmpty() || cleanId.contains(" ")) {
            localFlags.update {
                it.copy(registerError = "Please enter a valid User ID / Username without spaces.")
            }
            return
        }

        viewModelScope.launch {
            localFlags.update { it.copy(isRegistering = true, registerError = null) }
            when (val result = repository.register(cleanId, cleanName, cleanPhone)) {
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
            when (val result = repository.sendTextMessage(receiver, cleanText)) {
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

    fun sendCurrentLocation(receiver: String, liveStream: Boolean = false) {
        viewModelScope.launch {
            localFlags.update { it.copy(isFetchingLocation = true) }
            if (liveStream) {
                val initial = repository.locationTracker.getCurrentLocation(isLive = true, durationMs = 15 * 60_000L)
                localFlags.update { it.copy(isFetchingLocation = false) }
                if (initial == null) {
                    _toastEvents.tryEmit("Unable to acquire GPS fix. Enable location services.")
                    return@launch
                }
                repository.startLiveLocationSharing(receiver, durationMinutes = 15)
                _toastEvents.tryEmit("Live GPS sharing started for 15 minutes.")
            } else {
                val loc = repository.locationTracker.getCurrentLocation(isLive = false)
                localFlags.update { it.copy(isFetchingLocation = false) }
                if (loc == null) {
                    _toastEvents.tryEmit("Unable to acquire GPS fix. Enable location services.")
                    return@launch
                }
                val res = repository.sendLocationMessage(receiver, loc)
                if (res is NetworkResult.Error) _toastEvents.tryEmit(res.message)
            }
        }
    }

    fun stopLiveLocationSharing() {
        repository.stopLiveLocationSharing()
        _toastEvents.tryEmit("Live location sharing stopped.")
    }

    fun sendMedia(receiver: String, uri: Uri, kind: MessageKind?) {
        viewModelScope.launch {
            localFlags.update { it.copy(isSending = true) }
            when (val res = repository.sendMediaMessage(receiver, uri, kind)) {
                is NetworkResult.Success -> localFlags.update { it.copy(isSending = false) }
                is NetworkResult.Blocked -> {
                    localFlags.update { it.copy(isSending = false, blockedAlertMessage = res.message) }
                    _toastEvents.tryEmit(res.message)
                }
                is NetworkResult.Error -> {
                    localFlags.update { it.copy(isSending = false) }
                    _toastEvents.tryEmit(res.message)
                }
            }
        }
    }

    fun deleteMessage(message: ChatMessage) {
        viewModelScope.launch {
            when (val res = repository.deleteChatMessage(message)) {
                is NetworkResult.Success -> _toastEvents.tryEmit("Message deleted.")
                is NetworkResult.Blocked -> _toastEvents.tryEmit(res.message)
                is NetworkResult.Error -> _toastEvents.tryEmit(res.message)
            }
        }
    }

    // ==================== Social Actions ====================

    fun createSocialPost(
        mediaType: SocialMediaType,
        mediaUri: Uri?,
        caption: String,
        shayariTag: String = ""
    ) {
        if (caption.isBlank() && mediaUri == null) {
            _toastEvents.tryEmit("Add a photo, video, or caption to publish.")
            return
        }
        if ((mediaType == SocialMediaType.REEL || mediaType == SocialMediaType.PHOTO) && mediaUri == null) {
            _toastEvents.tryEmit(
                if (mediaType == SocialMediaType.REEL) "Select a video to publish a Reel."
                else "Select a photo or switch to Quote/Shayari."
            )
            return
        }
        viewModelScope.launch {
            localFlags.update { it.copy(isPublishingPost = true) }
            val result = repository.createSocialPost(mediaType, mediaUri, caption, shayariTag)
            localFlags.update { it.copy(isPublishingPost = false) }
            when (result) {
                is NetworkResult.Success -> {
                    _toastEvents.tryEmit(
                        if (mediaType == SocialMediaType.REEL) "Reel published!" else "Post shared to feed!"
                    )
                }
                is NetworkResult.Blocked -> {
                    localFlags.update { it.copy(blockedAlertMessage = result.message) }
                    _toastEvents.tryEmit(result.message)
                }
                is NetworkResult.Error -> {
                    _toastEvents.tryEmit(result.message)
                }
            }
        }
    }

    fun toggleLikePost(postId: String) {
        viewModelScope.launch { repository.toggleLikePost(postId) }
    }

    fun toggleBookmarkPost(postId: String) {
        viewModelScope.launch { repository.toggleBookmarkPost(postId) }
    }

    fun addComment(postId: String, text: String) {
        viewModelScope.launch { repository.addComment(postId, text) }
    }

    fun repostPost(postId: String) {
        viewModelScope.launch {
            repository.repostPost(postId)
            _toastEvents.tryEmit("Reposted to your feed!")
        }
    }

    fun sharePostToChat(post: SocialPost, targetUserId: String) {
        viewModelScope.launch {
            localFlags.update { it.copy(isSending = true) }
            when (val res = repository.sendSharedPostToChat(targetUserId, post)) {
                is NetworkResult.Success -> {
                    localFlags.update { it.copy(isSending = false) }
                    _toastEvents.tryEmit("Shared to chat!")
                }
                is NetworkResult.Blocked -> {
                    localFlags.update { it.copy(isSending = false, blockedAlertMessage = res.message) }
                    _toastEvents.tryEmit(res.message)
                }
                is NetworkResult.Error -> {
                    localFlags.update { it.copy(isSending = false) }
                    _toastEvents.tryEmit(res.message)
                }
            }
        }
    }

    fun downloadVideo(urlOrUri: String, title: String) {
        if (urlOrUri.isBlank()) {
            _toastEvents.tryEmit("Video URL is not ready yet.")
            return
        }
        viewModelScope.launch {
            _toastEvents.tryEmit("Downloading video to Gallery…")
            val result = MediaHelper.downloadVideoToGallery(getApplication(), urlOrUri, title)
            result.onSuccess { pathInfo ->
                _toastEvents.tryEmit("Saved to Gallery ($pathInfo)")
            }.onFailure { err ->
                _toastEvents.tryEmit(err.localizedMessage ?: "Failed to download video.")
            }
        }
    }

    fun toggleFollowUser(targetUserId: String) {
        viewModelScope.launch { repository.toggleFollowUser(targetUserId) }
    }

    fun removeFollower(followerUserId: String) {
        viewModelScope.launch {
            repository.removeFollower(followerUserId)
            _toastEvents.tryEmit("Follower removed.")
        }
    }

    fun blockUser(targetUserId: String, targetName: String) {
        viewModelScope.launch {
            repository.blockUser(targetUserId, targetName)
            _toastEvents.tryEmit("$targetName has been blocked.")
        }
    }

    fun unblockUser(targetUserId: String) {
        viewModelScope.launch {
            repository.unblockUser(targetUserId)
            _toastEvents.tryEmit("User unblocked.")
        }
    }

    fun updateMyBio(bio: String) {
        updateMyProfile(bio = bio, avatarUri = null)
    }

    fun updateMyAvatar(avatarUri: Uri) {
        updateMyProfile(bio = null, avatarUri = avatarUri)
    }

    fun updateMyProfile(bio: String?, avatarUri: Uri?) {
        viewModelScope.launch {
            localFlags.update { it.copy(isUpdatingProfile = true) }
            val result = repository.updateMyProfile(bio, avatarUri)
            localFlags.update { it.copy(isUpdatingProfile = false) }
            when (result) {
                is NetworkResult.Success -> _toastEvents.tryEmit("Profile updated & synced!")
                is NetworkResult.Blocked -> _toastEvents.tryEmit(result.message)
                is NetworkResult.Error -> _toastEvents.tryEmit(result.message)
            }
        }
    }

    // ==================== WebRTC Calling ====================

    fun startCall(targetUserId: String, targetUserName: String, isVideo: Boolean) {
        if (targetUserId == BROADCAST_TARGET_ID) {
            _toastEvents.tryEmit("Select an individual user to start a WebRTC call.")
            return
        }
        localFlags.update { it.copy(isCallScreenMinimized = false) }
        webRTCClient.startOutgoingCall(targetUserId, targetUserName, isVideo)
    }

    fun acceptCall(withVideo: Boolean) {
        localFlags.update { it.copy(isCallScreenMinimized = false) }
        webRTCClient.acceptIncomingCall(withVideo)
    }

    fun declineOrEndCall() {
        localFlags.update { it.copy(isCallScreenMinimized = false) }
        webRTCClient.declineOrEndCall()
    }

    fun selectTarget(targetId: String?) {
        localFlags.update {
            it.copy(
                activeTab = if (targetId != null) AppTab.CHATS else it.activeTab,
                selectedTargetId = targetId,
                sendError = null,
                showProfileSettings = false,
                showActivityFeed = false
            )
        }
        repository.setActiveChatTarget(targetId)
        if (!targetId.isNullOrBlank()) {
            viewModelScope.launch {
                repository.markThreadAsRead(targetId)
            }
        }
    }

    fun openProfileSettings() {
        localFlags.update { it.copy(showProfileSettings = true) }
    }

    fun closeProfileSettings() {
        localFlags.update { it.copy(showProfileSettings = false) }
    }

    fun updateSearchQuery(query: String) {
        localFlags.update { it.copy(searchQuery = query) }
        triggerRemoteUserSearch(query)
    }

    fun updateExploreQuery(query: String) {
        localFlags.update { it.copy(exploreQuery = query) }
        triggerRemoteUserSearch(query)
    }

    fun submitExploreSearch() {
        val clean = localFlags.value.exploreQuery.trim().removePrefix("@")
        if (clean.isEmpty()) return
        remoteSearchJob?.cancel()
        remoteSearchJob = viewModelScope.launch {
            localFlags.update { it.copy(isSearchingRemote = true) }
            repository.searchRemoteUsers(clean)
            localFlags.update { it.copy(isSearchingRemote = false) }
        }
    }

    private fun triggerRemoteUserSearch(query: String) {
        val clean = query.trim().removePrefix("@")
        if (clean.isEmpty()) {
            remoteSearchJob?.cancel()
            localFlags.update { it.copy(isSearchingRemote = false) }
            return
        }
        remoteSearchJob?.cancel()
        remoteSearchJob = viewModelScope.launch {
            delay(280L)
            localFlags.update { it.copy(isSearchingRemote = true) }
            repository.searchRemoteUsers(clean)
            localFlags.update { it.copy(isSearchingRemote = false) }
        }
    }

    fun clearRegisterError() {
        localFlags.update { it.copy(registerError = null) }
    }

    fun dismissBlockedAlert() {
        localFlags.update { it.copy(blockedAlertMessage = null) }
    }

    fun logoutAndResetDeviceLock() {
        viewModelScope.launch {
            localFlags.value = LocalUiFlags()
            repository.logoutAndResetDeviceLock()
            _toastEvents.tryEmit("Device session lock reset.")
        }
    }
}
