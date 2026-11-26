package com.example.ui.screens

import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.model.SocialMediaType
import com.example.data.model.SocialPost
import com.example.data.network.BackendConfig
import com.example.ui.ChatUiState
import com.example.ui.components.PresenceBadge
import com.example.ui.components.SocialPostViewerDialog
import com.example.ui.components.UserAvatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    uiState: ChatUiState,
    onExploreQueryChange: (String) -> Unit,
    onOpenUserProfile: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    onToggleFollow: (String) -> Unit,
    onStartCall: (userId: String, userName: String, isVideo: Boolean) -> Unit,
    onAddComment: (postId: String, text: String) -> Unit,
    onToggleLike: (String) -> Unit = {},
    onToggleBookmark: (String) -> Unit = {},
    onRepost: (String) -> Unit = {},
    onShareToChat: (SocialPost, String) -> Unit = { _, _ -> },
    onSubmitSearch: () -> Unit = {},
    onDownloadVideo: (SocialPost) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val currentUserId = uiState.session?.userId.orEmpty()
    val postRows = remember(uiState.exploredPosts) { uiState.exploredPosts.chunked(3) }
    var selectedPostId by remember { mutableStateOf<String?>(null) }

    if (selectedPostId != null) {
        val viewerPosts = if (uiState.exploredPosts.any { it.id == selectedPostId }) {
            uiState.exploredPosts
        } else {
            uiState.socialPosts
        }
        SocialPostViewerDialog(
            posts = viewerPosts,
            initialPostId = selectedPostId!!,
            currentUserId = currentUserId,
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                title = {
                    Text(
                        text = "Explore & Search",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState.isSearchingRemote) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            OutlinedTextField(
                value = uiState.exploreQuery,
                onValueChange = onExploreQueryChange,
                placeholder = { Text("Search people, @userId, phone, or posts…") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.exploreQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { onExploreQueryChange("") },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmitSearch() }),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("explore_search_input")
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("explore_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "people_header") {
                    Text(
                        text = "People (${uiState.exploredUsers.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (uiState.exploredUsers.isEmpty()) {
                    item(key = "no_people") {
                        Text(
                            text = if (uiState.isSearchingRemote) {
                                "Searching server for \"${uiState.exploreQuery}\"…"
                            } else {
                                "No matching users for \"${uiState.exploreQuery}\""
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(uiState.exploredUsers, key = { "exp_${it.userId}" }) { user ->
                        val isMe = user.userId.equals(currentUserId, ignoreCase = true)
                        val isFollowed = uiState.profileExtras[user.userId]?.isFollowedByMe == true
                        Card(
                            onClick = { onOpenUserProfile(user.userId) },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("explore_user_${user.userId}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                UserAvatar(
                                    initials = user.initials,
                                    isOnline = user.isOnline(),
                                    seedKey = user.userId,
                                    size = 46.dp,
                                    avatarUrl = user.avatarUrl
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isMe) "${user.name} (You)" else user.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "@${user.userId}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        PresenceBadge(
                                            isOnline = user.isOnline(),
                                            label = if (user.isOnline()) "Online" else "Offline"
                                        )
                                    }
                                }

                                if (!isMe) {
                                    IconButton(
                                        onClick = { onOpenChat(user.userId) },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.Chat,
                                            contentDescription = "Chat"
                                        )
                                    }
                                    IconButton(
                                        onClick = { onStartCall(user.userId, user.name, false) },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Call,
                                            contentDescription = "Audio call",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(
                                        onClick = { onStartCall(user.userId, user.name, true) },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Videocam,
                                            contentDescription = "Video call",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    if (isFollowed) {
                                        OutlinedButton(
                                            onClick = { onToggleFollow(user.userId) },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                        ) {
                                            Text("Following", style = MaterialTheme.typography.labelMedium)
                                        }
                                    } else {
                                        FilledTonalButton(
                                            onClick = { onToggleFollow(user.userId) },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                        ) {
                                            Text("Follow", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "posts_header") {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Discover Posts & Reels (${uiState.exploredPosts.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                items(postRows, key = { row -> row.joinToString("_") { it.id } }) { row ->
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
fun ExplorePostTile(
    post: SocialPost,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .testTag("explore_post_tile_${post.id}")
    ) {
        when (post.mediaType) {
            SocialMediaType.PHOTO -> {
                val model: Any? = remember(post.mediaUri, post.inlineImageBase64) {
                    when {
                        post.mediaUri.isNotBlank() -> Uri.parse(post.mediaUri)
                        post.inlineImageBase64.isNotBlank() ->
                            runCatching { Base64.decode(post.inlineImageBase64, Base64.NO_WRAP) }.getOrNull()
                        else -> null
                    }
                }
                if (model != null) {
                    AsyncImage(
                        model = model,
                        contentDescription = post.caption.ifBlank { "Photo" },
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            SocialMediaType.REEL -> {
                val thumbModel: Any? = remember(post.inlineImageBase64, post.mediaUri) {
                    if (post.inlineImageBase64.isNotBlank()) {
                        runCatching { Base64.decode(post.inlineImageBase64, Base64.NO_WRAP) }.getOrNull()
                    } else {
                        BackendConfig.deriveVideoThumbnailUrl(post.mediaUri).takeIf { it.isNotBlank() }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF0F2E2A), Color(0xFF1F3A52))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumbModel != null) {
                        AsyncImage(
                            model = thumbModel,
                            contentDescription = "Reel thumbnail",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.25f))
                        )
                    } else if (post.caption.isNotBlank()) {
                        Text(
                            text = post.caption,
                            color = Color.White.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                        )
                    }
                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                    ) {
                        Text(
                            text = "REEL",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.PlayCircle,
                        contentDescription = "Play Reel",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            SocialMediaType.QUOTE -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF0D6E5E), Color(0xFF2D5A7B))
                            )
                        )
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.FormatQuote,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = post.caption,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(5.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Favorite,
                    contentDescription = null,
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(11.dp)
                )
                Text(
                    text = "${post.likeCount}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
