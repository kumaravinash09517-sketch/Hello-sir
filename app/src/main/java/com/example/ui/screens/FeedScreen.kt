package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.DynamicFeed
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.DailyShayariProvider
import com.example.data.model.SocialMediaType
import com.example.data.model.SocialPost
import com.example.ui.ChatUiState
import com.example.ui.components.CommentsBottomSheet
import com.example.ui.components.CreatePostBottomSheet
import com.example.ui.components.DailyShayariCard
import com.example.ui.components.HelloSirWordmark
import com.example.ui.components.SharePostBottomSheet
import com.example.ui.components.SocialPostCard
import com.example.ui.components.SocialPostViewerDialog
import com.example.ui.components.UserAvatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    uiState: ChatUiState,
    onRefresh: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenUserProfile: (String) -> Unit,
    onOpenChat: (String) -> Unit = {},
    onToggleFollow: (String) -> Unit,
    onToggleLike: (String) -> Unit,
    onToggleBookmark: (String) -> Unit,
    onAddComment: (postId: String, text: String) -> Unit,
    onRepost: (String) -> Unit,
    onShareToChat: (SocialPost, String) -> Unit,
    onPublishPost: (SocialMediaType, Uri?, String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentUserId = uiState.session?.userId.orEmpty()
    val listState = rememberLazyListState()
    val userMap = remember(uiState.users) { uiState.users.associateBy { it.userId.lowercase() } }

    var shayariOffset by remember { mutableIntStateOf(0) }
    val todayShayari = remember(shayariOffset) { DailyShayariProvider.shayariForToday(shayariOffset) }

    var isMuted by remember { mutableStateOf(true) }
    var activeCommentsPostId by remember { mutableStateOf<String?>(null) }
    var activeSharePost by remember { mutableStateOf<SocialPost?>(null) }
    var fullScreenPostId by remember { mutableStateOf<String?>(null) }
    var showCreateSheet by remember { mutableStateOf(false) }
    var createPrefillCaption by remember { mutableStateOf("") }
    var createInitialType by remember { mutableStateOf(SocialMediaType.PHOTO) }

    val activeVisiblePostKey by remember {
        derivedStateOf {
            val viewportCenter = listState.layoutInfo.viewportEndOffset / 2
            listState.layoutInfo.visibleItemsInfo
                .filter { it.key.toString().startsWith("post_") }
                .minByOrNull { kotlin.math.abs((it.offset + it.size / 2) - viewportCenter) }
                ?.key
                ?.toString()
        }
    }

    if (fullScreenPostId != null) {
        SocialPostViewerDialog(
            posts = uiState.socialPosts,
            initialPostId = fullScreenPostId!!,
            currentUserId = currentUserId,
            users = uiState.users,
            commentsByPost = uiState.socialComments,
            profileExtras = uiState.profileExtras,
            onDismiss = { fullScreenPostId = null },
            onOpenUserProfile = onOpenUserProfile,
            onOpenChat = onOpenChat,
            onToggleFollow = onToggleFollow,
            onToggleLike = onToggleLike,
            onToggleBookmark = onToggleBookmark,
            onAddComment = onAddComment,
            onRepost = onRepost,
            onShareToChat = onShareToChat
        )
    }

    if (activeCommentsPostId != null) {
        val post = uiState.socialPosts.firstOrNull { it.id == activeCommentsPostId }
        if (post != null) {
            CommentsBottomSheet(
                post = post,
                comments = uiState.socialComments[post.id].orEmpty(),
                users = uiState.users,
                onDismiss = { activeCommentsPostId = null },
                onAddComment = { text -> onAddComment(post.id, text) }
            )
        }
    }

    if (activeSharePost != null) {
        SharePostBottomSheet(
            post = activeSharePost!!,
            users = uiState.users,
            currentUserId = currentUserId,
            onDismiss = { activeSharePost = null },
            onRepost = { onRepost(activeSharePost!!.id) },
            onSendToChat = { targetId -> onShareToChat(activeSharePost!!, targetId) }
        )
    }

    if (showCreateSheet) {
        CreatePostBottomSheet(
            initialCaption = createPrefillCaption,
            initialType = createInitialType,
            onDismiss = { showCreateSheet = false },
            onPublish = { type, uri, caption ->
                val tag = if (createPrefillCaption.isNotBlank() && type == SocialMediaType.QUOTE) "Daily Shayari" else ""
                onPublishPost(type, uri, caption, tag)
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
                title = {
                    HelloSirWordmark()
                },
                actions = {
                    IconButton(
                        onClick = {
                            createPrefillCaption = ""
                            createInitialType = SocialMediaType.PHOTO
                            showCreateSheet = true
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("create_post_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AddCircleOutline,
                            contentDescription = "Create post"
                        )
                    }

                    IconButton(
                        onClick = onOpenActivity,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("activity_feed_button")
                    ) {
                        BadgedBox(
                            badge = {
                                if (uiState.unreadActivityCount > 0) {
                                    Badge(containerColor = Color(0xFFEF4444)) {
                                        Text(
                                            text = if (uiState.unreadActivityCount > 9) "9+" else "${uiState.unreadActivityCount}",
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FavoriteBorder,
                                contentDescription = "Activity and Notifications"
                            )
                        }
                    }

                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh feed"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState.isRefreshing || uiState.isPublishingPost) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 640.dp)
                        .testTag("social_feed_list"),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Stories / Active Circles strip
                    item(key = "stories_strip") {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(uiState.users, key = { "story_${it.userId}" }) { user ->
                                val isOnline = user.isOnline()
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .width(68.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable { onOpenUserProfile(user.userId) }
                                        .padding(vertical = 2.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(60.dp)
                                            .border(
                                                BorderStroke(
                                                    width = 2.5.dp,
                                                    brush = Brush.linearGradient(
                                                        if (isOnline) {
                                                            listOf(Color(0xFF0D6E5E), Color(0xFFD96133))
                                                        } else {
                                                            listOf(
                                                                MaterialTheme.colorScheme.outlineVariant,
                                                                MaterialTheme.colorScheme.outline
                                                            )
                                                        }
                                                    )
                                                ),
                                                CircleShape
                                            )
                                            .padding(4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        UserAvatar(
                                            initials = user.initials,
                                            isOnline = isOnline,
                                            seedKey = user.userId,
                                            size = 50.dp,
                                            avatarUrl = user.avatarUrl
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (user.userId.equals(currentUserId, ignoreCase = true)) "You" else user.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    // Daily Hindi Shayari card
                    item(key = "daily_shayari") {
                        DailyShayariCard(
                            shayari = todayShayari,
                            onNextShayari = { shayariOffset += 1 },
                            onCopyShayari = { sh ->
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("Shayari", "${sh.hindiText}\n— ${sh.poet}"))
                                Toast.makeText(context, "Shayari copied!", Toast.LENGTH_SHORT).show()
                            },
                            onShareToFeed = { sh ->
                                createPrefillCaption = "${sh.hindiText}\n\n— ${sh.poet}"
                                createInitialType = SocialMediaType.QUOTE
                                showCreateSheet = true
                            }
                        )
                    }

                    if (uiState.socialPosts.isEmpty()) {
                        item(key = "empty_feed") {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DynamicFeed,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Text(
                                        text = "Your Feed is Ready",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Share a photo, short Reel, or Shayari with everyone on Hello Sir.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Button(
                                        onClick = {
                                            createPrefillCaption = ""
                                            createInitialType = SocialMediaType.PHOTO
                                            showCreateSheet = true
                                        }
                                    ) {
                                        Text("Create First Post")
                                    }
                                }
                            }
                        }
                    } else {
                        itemsIndexed(
                            items = uiState.socialPosts,
                            key = { _, post -> "post_${post.id}" }
                        ) { _, post ->
                            val isFollowed = uiState.profileExtras[post.authorId]?.isFollowedByMe == true
                            val authorAvatar = userMap[post.authorId.lowercase()]?.avatarUrl
                            SocialPostCard(
                                post = post,
                                currentUserId = currentUserId,
                                isFollowedByMe = isFollowed,
                                isVideoVisible = activeVisiblePostKey == "post_${post.id}",
                                isMuted = isMuted,
                                onToggleMute = { isMuted = !isMuted },
                                onAuthorClick = onOpenUserProfile,
                                onToggleFollow = onToggleFollow,
                                onToggleLike = onToggleLike,
                                onOpenComments = { activeCommentsPostId = it.id },
                                onOpenShare = { activeSharePost = it },
                                onToggleBookmark = onToggleBookmark,
                                authorAvatarUrl = authorAvatar,
                                onOpenFullScreen = { fullScreenPostId = it.id }
                            )
                        }
                    }
                }
            }
        }
    }
}
