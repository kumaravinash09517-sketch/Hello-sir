package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.BlockedUser
import com.example.data.model.ChatUser
import com.example.data.model.SocialMediaType
import com.example.data.model.SocialPost
import com.example.data.model.UserProfileExtras
import com.example.ui.ChatUiState
import com.example.ui.components.CreatePostBottomSheet
import com.example.ui.components.PresenceBadge
import com.example.ui.components.SocialPostViewerDialog
import com.example.ui.components.UserAvatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    uiState: ChatUiState,
    onBackFromViewedProfile: () -> Unit,
    onOpenProfileSettings: () -> Unit,
    onOpenChat: (String) -> Unit,
    onStartCall: (userId: String, userName: String, isVideo: Boolean) -> Unit,
    onToggleFollow: (String) -> Unit,
    onUpdateBio: (String) -> Unit,
    onUpdateAvatar: (Uri) -> Unit = {},
    onToggleLike: (String) -> Unit = {},
    onToggleBookmark: (String) -> Unit = {},
    onAddComment: (postId: String, text: String) -> Unit,
    onRepost: (String) -> Unit = {},
    onShareToChat: (SocialPost, String) -> Unit = { _, _ -> },
    onPublishPost: (SocialMediaType, Uri?, String, String) -> Unit,
    onOpenUserProfile: (String) -> Unit = {},
    onDownloadVideo: (SocialPost) -> Unit = {},
    onRemoveFollower: (String) -> Unit = {},
    onBlockUser: (userId: String, name: String) -> Unit = { _, _ -> },
    onUnblockUser: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val currentSession = uiState.session ?: return
    val targetUserId = uiState.viewedProfileUserId ?: currentSession.userId
    val isMe = targetUserId.equals(currentSession.userId, ignoreCase = true)

    if (!isMe && uiState.viewedProfileUserId != null) {
        BackHandler(onBack = onBackFromViewedProfile)
    }

    val matchedUser = remember(uiState.users, targetUserId) {
        uiState.users.firstOrNull { it.userId.equals(targetUserId, ignoreCase = true) }
    }
    val displayName = if (isMe) currentSession.name else matchedUser?.name ?: targetUserId
    val extras = uiState.profileExtras[targetUserId] ?: UserProfileExtras(userId = targetUserId)
    val avatarUrl = matchedUser?.avatarUrl
    val isTargetBlocked = !isMe && uiState.isUserBlocked(targetUserId)

    val followersList = remember(uiState.socialRelations, uiState.users, targetUserId) {
        uiState.followersOf(targetUserId)
    }
    val followingList = remember(uiState.socialRelations, uiState.users, targetUserId) {
        uiState.followingOf(targetUserId)
    }
    val displayedFollowersCount = maxOf(extras.followersCount, followersList.size)
    val displayedFollowingCount = maxOf(extras.followingCount, followingList.size)

    val userPosts = remember(uiState.socialPosts, targetUserId) {
        uiState.socialPosts.filter { it.authorId.equals(targetUserId, ignoreCase = true) }
    }
    val userReels = remember(userPosts) {
        userPosts.filter { it.mediaType == SocialMediaType.REEL }
    }
    val savedPosts = remember(uiState.socialPosts) {
        uiState.socialPosts.filter { it.isBookmarkedByMe }
    }

    var selectedTab by remember(targetUserId) { mutableIntStateOf(0) }
    var showEditBioDialog by remember { mutableStateOf(false) }
    var bioDraft by remember(extras.bio) { mutableStateOf(extras.bio) }
    var selectedPostId by remember { mutableStateOf<String?>(null) }
    var showCreateSheet by remember { mutableStateOf(false) }
    var followSheetInitialTab by remember { mutableStateOf<Int?>(null) }
    var showBlockConfirm by remember { mutableStateOf(false) }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onUpdateAvatar(uri)
        }
    }

    val displayedList = when (selectedTab) {
        1 -> userReels
        2 -> if (isMe) savedPosts else userPosts
        else -> userPosts
    }

    if (showEditBioDialog) {
        AlertDialog(
            onDismissRequest = { showEditBioDialog = false },
            title = { Text("Edit Profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            avatarPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("change_profile_photo_button")
                    ) {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Change Profile Photo")
                    }
                    OutlinedTextField(
                        value = bioDraft,
                        onValueChange = { bioDraft = it },
                        label = { Text("Bio") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUpdateBio(bioDraft)
                        showEditBioDialog = false
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showEditBioDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (selectedPostId != null) {
        val viewerPosts = if (displayedList.any { it.id == selectedPostId }) {
            displayedList
        } else {
            uiState.socialPosts
        }
        SocialPostViewerDialog(
            posts = viewerPosts,
            initialPostId = selectedPostId!!,
            currentUserId = currentSession.userId,
            users = uiState.users,
            commentsByPost = uiState.socialComments,
            profileExtras = uiState.profileExtras,
            onDismiss = { selectedPostId = null },
            onOpenUserProfile = onOpenUserProfile,
            onOpenChat = onOpenChat,
            onToggleFollow = onToggleFollow,
            onToggleLike = onToggleLike,
            onToggleBookmark = onToggleBookmark,
            onAddComment = onAddComment,
            onRepost = onRepost,
            onShareToChat = onShareToChat,
            onDownloadVideo = onDownloadVideo
        )
    }

    if (showCreateSheet) {
        CreatePostBottomSheet(
            onDismiss = { showCreateSheet = false },
            onPublish = { type, uri, caption ->
                onPublishPost(type, uri, caption, "")
            }
        )
    }

    if (showBlockConfirm) {
        AlertDialog(
            onDismissRequest = { showBlockConfirm = false },
            title = { Text(if (isTargetBlocked) "Unblock $displayName?" else "Block $displayName?") },
            text = {
                Text(
                    if (isTargetBlocked) {
                        "$displayName will be able to message, call, and show up in your feed again."
                    } else {
                        "Blocking $displayName hides their posts, comments, messages, and incoming calls on your device."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isTargetBlocked) {
                            onUnblockUser(targetUserId)
                        } else {
                            onBlockUser(targetUserId, displayName)
                        }
                        showBlockConfirm = false
                    },
                    colors = if (!isTargetBlocked) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    Text(if (isTargetBlocked) "Unblock" else "Block")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    followSheetInitialTab?.let { initialSheetTab ->
        FollowListBottomSheet(
            initialTab = initialSheetTab,
            isMe = isMe,
            currentUserId = currentSession.userId,
            followers = followersList,
            following = followingList,
            blockedUsers = uiState.blockedUsers,
            profileExtras = uiState.profileExtras,
            onDismiss = { followSheetInitialTab = null },
            onOpenUserProfile = { uid ->
                followSheetInitialTab = null
                onOpenUserProfile(uid)
            },
            onToggleFollow = onToggleFollow,
            onRemoveFollower = onRemoveFollower,
            onBlockUser = onBlockUser,
            onUnblockUser = onUnblockUser
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                navigationIcon = {
                    if (!isMe && uiState.viewedProfileUserId != null) {
                        IconButton(
                            onClick = onBackFromViewedProfile,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back)
                            )
                        }
                    }
                },
                title = {
                    Text(
                        text = "@$targetUserId",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    if (isMe) {
                        IconButton(
                            onClick = { showCreateSheet = true },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Rounded.AddCircleOutline, contentDescription = "Create post")
                        }
                        IconButton(
                            onClick = onOpenProfileSettings,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("profile_settings_trigger")
                        ) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Device Lock & Settings")
                        }
                    } else {
                        IconButton(
                            onClick = { showBlockConfirm = true },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("profile_block_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Block,
                                contentDescription = if (isTargetBlocked) "Unblock user" else "Block user",
                                tint = if (isTargetBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        val gridRows = remember(displayedList) { displayedList.chunked(3) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val uploadProg = uiState.uploadProgress
            if (uploadProg != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = uploadProg.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${uploadProg.percent}%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        LinearProgressIndicator(
                            progress = { (uploadProg.percent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            } else if (uiState.isPublishingPost || uiState.isUpdatingProfile || uiState.isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("profile_content"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item(key = "profile_header") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            val initials = displayName.trim().take(2).uppercase()
                                .ifBlank { targetUserId.take(2).uppercase() }
                            Box(
                                modifier = if (isMe) {
                                    Modifier.clickable {
                                        avatarPicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    }
                                } else {
                                    Modifier
                                }
                            ) {
                                UserAvatar(
                                    initials = initials,
                                    isOnline = isMe || (matchedUser?.isOnline() == true),
                                    seedKey = targetUserId,
                                    size = 78.dp,
                                    showPresenceDot = !isMe,
                                    avatarUrl = avatarUrl
                                )
                                if (isMe) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .size(26.dp)
                                            .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
                                            .testTag("profile_avatar_edit_badge")
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Rounded.CameraAlt,
                                                contentDescription = "Update profile photo",
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                ProfileStat("${userPosts.size}", "Posts")
                                ProfileStat(
                                    value = "$displayedFollowersCount",
                                    label = "Followers",
                                    onClick = { followSheetInitialTab = 0 },
                                    testTag = "followers_stat_button"
                                )
                                ProfileStat(
                                    value = "$displayedFollowingCount",
                                    label = "Following",
                                    onClick = { followSheetInitialTab = 1 },
                                    testTag = "following_stat_button"
                                )
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                PresenceBadge(
                                    isOnline = isMe || (matchedUser?.isOnline() == true),
                                    label = if (isMe || (matchedUser?.isOnline() == true)) "Online" else "Offline"
                                )
                            }
                            Text(
                                text = extras.bio,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (isMe) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        bioDraft = extras.bio
                                        showEditBioDialog = true
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("edit_profile_button")
                                ) {
                                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Edit Profile")
                                }
                                FilledTonalButton(
                                    onClick = onOpenProfileSettings,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                ) {
                                    Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Device & Lock")
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = { onToggleFollow(targetUserId) },
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp),
                                    modifier = Modifier
                                        .weight(1.1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("profile_follow_button")
                                ) {
                                    Text(
                                        text = if (extras.isFollowedByMe) "Following" else "Follow",
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                FilledTonalButton(
                                    onClick = { onOpenChat(targetUserId) },
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("profile_chat_button")
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.Chat,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Chat",
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                                IconButton(
                                    onClick = { onStartCall(targetUserId, displayName, false) },
                                    modifier = Modifier
                                        .size(44.dp)
                                        .testTag("profile_audio_call_button")
                                ) {
                                    Icon(Icons.Rounded.Call, contentDescription = "Audio call", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(
                                    onClick = { onStartCall(targetUserId, displayName, true) },
                                    modifier = Modifier
                                        .size(44.dp)
                                        .testTag("profile_video_call_button")
                                ) {
                                    Icon(Icons.Rounded.Videocam, contentDescription = "Video call", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            if (isTargetBlocked) {
                                OutlinedButton(
                                    onClick = { onUnblockUser(targetUserId) },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Unblock @$targetUserId")
                                }
                            }
                        }
                    }
                }

                item(key = "profile_tabs") {
                    val tabTitles = if (isMe) {
                        listOf(
                            "Posts" to Icons.Rounded.GridOn,
                            "Reels" to Icons.Rounded.VideoLibrary,
                            "Saved" to Icons.Rounded.Bookmark
                        )
                    } else {
                        listOf(
                            "Posts" to Icons.Rounded.GridOn,
                            "Reels" to Icons.Rounded.VideoLibrary
                        )
                    }
                    PrimaryTabRow(selectedTabIndex = selectedTab.coerceAtMost(tabTitles.lastIndex)) {
                        tabTitles.forEachIndexed { idx, (title, icon) ->
                            Tab(
                                selected = selectedTab == idx,
                                onClick = { selectedTab = idx },
                                text = { Text(title) },
                                icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            )
                        }
                    }
                }

                if (gridRows.isEmpty()) {
                    item(key = "empty_profile_grid") {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = when (selectedTab) {
                                        1 -> "No Reels shared yet"
                                        2 -> "No saved posts yet"
                                        else -> "No posts yet"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Tap the + button to share a photo, Reel, or Shayari.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(gridRows, key = { row -> "p_" + row.joinToString("_") { it.id } }) { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            row.forEach { post ->
                                ExplorePostTile(
                                    post = post,
                                    onClick = { selectedPostId = post.id },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(3 - row.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileStat(
    value: String,
    label: String,
    onClick: (() -> Unit)? = null,
    testTag: String? = null
) {
    val baseModifier = Modifier
        .clip(RoundedCornerShape(10.dp))
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .let { if (testTag != null) it.testTag(testTag) else it }
        .padding(horizontal = 8.dp, vertical = 4.dp)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = baseModifier
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FollowListBottomSheet(
    initialTab: Int,
    isMe: Boolean,
    currentUserId: String,
    followers: List<ChatUser>,
    following: List<ChatUser>,
    blockedUsers: List<BlockedUser>,
    profileExtras: Map<String, UserProfileExtras>,
    onDismiss: () -> Unit,
    onOpenUserProfile: (String) -> Unit,
    onToggleFollow: (String) -> Unit,
    onRemoveFollower: (String) -> Unit,
    onBlockUser: (userId: String, name: String) -> Unit,
    onUnblockUser: (String) -> Unit
) {
    val tabs = if (isMe) {
        listOf("Followers (${followers.size})", "Following (${following.size})", "Blocked (${blockedUsers.size})")
    } else {
        listOf("Followers (${followers.size})", "Following (${following.size})")
    }
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, tabs.lastIndex)) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { idx, title ->
                    Tab(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }

            if (selectedTab == 2 && isMe) {
                if (blockedUsers.isEmpty()) {
                    Text(
                        text = "You haven't blocked anyone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(blockedUsers, key = { it.userId }) { blocked ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                UserAvatar(
                                    initials = blocked.name.take(2).uppercase(),
                                    isOnline = false,
                                    seedKey = blocked.userId,
                                    size = 40.dp,
                                    showPresenceDot = false
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(blocked.name, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text(
                                        "@${blocked.userId}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                OutlinedButton(onClick = { onUnblockUser(blocked.userId) }) {
                                    Text("Unblock")
                                }
                            }
                        }
                    }
                }
            } else {
                val activeList = if (selectedTab == 0) followers else following
                if (activeList.isEmpty()) {
                    Text(
                        text = if (selectedTab == 0) "No followers recorded yet." else "Not following anyone yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 340.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(activeList, key = { it.userId }) { user ->
                            val isSelfRow = user.userId.equals(currentUserId, ignoreCase = true)
                            val isFollowed = profileExtras[user.userId]?.isFollowedByMe == true
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onOpenUserProfile(user.userId) }
                                    .padding(horizontal = 6.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                UserAvatar(
                                    initials = user.initials,
                                    isOnline = user.isOnline(),
                                    seedKey = user.userId,
                                    size = 40.dp,
                                    avatarUrl = user.avatarUrl
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = user.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "@${user.userId}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                                if (!isSelfRow) {
                                    if (selectedTab == 0 && isMe) {
                                        TextButton(onClick = { onRemoveFollower(user.userId) }) {
                                            Text("Remove", color = MaterialTheme.colorScheme.error)
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = { onToggleFollow(user.userId) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Text(if (isFollowed) "Unfollow" else "Follow")
                                        }
                                    }
                                    IconButton(
                                        onClick = { onBlockUser(user.userId, user.name) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Block,
                                            contentDescription = "Block user",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }
    }
}
