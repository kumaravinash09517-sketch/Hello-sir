package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.SocialMediaType
import com.example.data.model.UserProfileExtras
import com.example.ui.ChatUiState
import com.example.ui.components.CommentsBottomSheet
import com.example.ui.components.CreatePostBottomSheet
import com.example.ui.components.PresenceBadge
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
    onAddComment: (postId: String, text: String) -> Unit,
    onPublishPost: (SocialMediaType, Uri?, String, String) -> Unit,
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

    if (showEditBioDialog) {
        AlertDialog(
            onDismissRequest = { showEditBioDialog = false },
            title = { Text("Edit Profile Bio") },
            text = {
                OutlinedTextField(
                    value = bioDraft,
                    onValueChange = { bioDraft = it },
                    label = { Text("Bio") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
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
        val post = uiState.socialPosts.firstOrNull { it.id == selectedPostId }
        if (post != null) {
            CommentsBottomSheet(
                post = post,
                comments = uiState.socialComments[post.id].orEmpty(),
                onDismiss = { selectedPostId = null },
                onAddComment = { text -> onAddComment(post.id, text) }
            )
        }
    }

    if (showCreateSheet) {
        CreatePostBottomSheet(
            onDismiss = { showCreateSheet = false },
            onPublish = { type, uri, caption ->
                onPublishPost(type, uri, caption, "")
            }
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
                    }
                }
            )
        }
    ) { innerPadding ->
        val displayedList = when (selectedTab) {
            1 -> userReels
            2 -> if (isMe) savedPosts else userPosts
            else -> userPosts
        }
        val gridRows = remember(displayedList) { displayedList.chunked(3) }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
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
                        UserAvatar(
                            initials = initials,
                            isOnline = isMe || (matchedUser?.isOnline() == true),
                            seedKey = targetUserId,
                            size = 78.dp
                        )
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            ProfileStat("${userPosts.size}", "Posts")
                            ProfileStat("${extras.followersCount}", "Followers")
                            ProfileStat("${extras.followingCount}", "Following")
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
                                    .heightIn(min = 46.dp)
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
                                    .heightIn(min = 46.dp)
                            ) {
                                Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Device & Lock")
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onToggleFollow(targetUserId) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1.2f)
                                    .heightIn(min = 46.dp)
                                    .testTag("profile_follow_button")
                            ) {
                                Text(if (extras.isFollowedByMe) "Following" else "Follow")
                            }
                            FilledTonalButton(
                                onClick = { onOpenChat(targetUserId) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 46.dp)
                                    .testTag("profile_chat_button")
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Chat")
                            }
                            IconButton(
                                onClick = { onStartCall(targetUserId, displayName, false) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("profile_audio_call_button")
                            ) {
                                Icon(Icons.Rounded.Call, contentDescription = "Audio call", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(
                                onClick = { onStartCall(targetUserId, displayName, true) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("profile_video_call_button")
                            ) {
                                Icon(Icons.Rounded.Videocam, contentDescription = "Video call", tint = MaterialTheme.colorScheme.primary)
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

@Composable
private fun ProfileStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
