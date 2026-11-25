package com.example.ui.components

import android.net.Uri
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.data.model.ChatUser
import com.example.data.model.SocialComment
import com.example.data.model.SocialMediaType
import com.example.data.model.SocialPost
import com.example.data.model.UserProfileExtras
import kotlinx.coroutines.delay

/**
 * Full-screen Instagram / Reels post & video viewer.
 * Opens from Profile grids, Explore grids, or Feed cards so users can watch full-screen
 * Reels, view photos/quotes, Like, Comment, Share/Repost, Save, Follow, or start Chat.
 */
@Composable
fun SocialPostViewerDialog(
    posts: List<SocialPost>,
    initialPostId: String,
    currentUserId: String,
    users: List<ChatUser>,
    commentsByPost: Map<String, List<SocialComment>>,
    profileExtras: Map<String, UserProfileExtras>,
    onDismiss: () -> Unit,
    onOpenUserProfile: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    onToggleFollow: (String) -> Unit,
    onToggleLike: (String) -> Unit,
    onToggleBookmark: (String) -> Unit,
    onAddComment: (postId: String, text: String) -> Unit,
    onRepost: (String) -> Unit,
    onShareToChat: (SocialPost, String) -> Unit
) {
    if (posts.isEmpty()) return
    val initialIndex = remember(posts, initialPostId) {
        posts.indexOfFirst { it.id == initialPostId }.coerceAtLeast(0)
    }
    val userMap = remember(users) { users.associateBy { it.userId.lowercase() } }

    var isMuted by remember { mutableStateOf(false) }
    var activeCommentsPostId by remember { mutableStateOf<String?>(null) }
    var activeSharePost by remember { mutableStateOf<SocialPost?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler(onBack = onDismiss)

        val pagerState = rememberPagerState(
            initialPage = initialIndex,
            pageCount = { posts.size }
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("fullscreen_post_viewer")
        ) {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val post = posts[page]
                val authorUser = userMap[post.authorId.lowercase()]
                val isFollowed = profileExtras[post.authorId]?.isFollowedByMe == true
                var showDoubleTapHeart by remember(post.id) { mutableStateOf(false) }

                LaunchedEffect(showDoubleTapHeart) {
                    if (showDoubleTapHeart) {
                        delay(650L)
                        showDoubleTapHeart = false
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    when (post.mediaType) {
                        SocialMediaType.REEL -> {
                            if (post.mediaUri.isNotBlank()) {
                                SocialVideoPlayer(
                                    videoUri = post.mediaUri,
                                    isVisible = pagerState.currentPage == page,
                                    isMuted = isMuted,
                                    onToggleMute = { isMuted = !isMuted },
                                    showBottomSeekBar = true,
                                    fitInside = true,
                                    controlsTopPadding = 68.dp,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0xFF102A27)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Video unavailable",
                                        color = Color.White.copy(alpha = 0.75f)
                                    )
                                }
                            }
                        }

                        SocialMediaType.PHOTO -> {
                            val model: Any? = remember(post.mediaUri, post.inlineImageBase64) {
                                when {
                                    post.mediaUri.isNotBlank() -> Uri.parse(post.mediaUri)
                                    post.inlineImageBase64.isNotBlank() ->
                                        runCatching {
                                            Base64.decode(post.inlineImageBase64, Base64.NO_WRAP)
                                        }.getOrNull()
                                    else -> null
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(post.id) {
                                        detectTapGestures(
                                            onDoubleTap = {
                                                showDoubleTapHeart = true
                                                if (!post.isLikedByMe) onToggleLike(post.id)
                                            }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (model != null) {
                                    AsyncImage(
                                        model = model,
                                        contentDescription = post.caption.ifBlank { "Photo" },
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }

                        SocialMediaType.QUOTE -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.linearGradient(
                                            listOf(Color(0xFF0D6E5E), Color(0xFF1E3A5F))
                                        )
                                    )
                                    .pointerInput(post.id) {
                                        detectTapGestures(
                                            onDoubleTap = {
                                                showDoubleTapHeart = true
                                                if (!post.isLikedByMe) onToggleLike(post.id)
                                            }
                                        )
                                    }
                                    .padding(horizontal = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = post.caption,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = Color.White,
                                    lineHeight = 34.sp
                                )
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = showDoubleTapHeart,
                        enter = scaleIn(spring(stiffness = Spring.StiffnessMediumLow)),
                        exit = scaleOut(),
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Favorite,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(92.dp)
                        )
                    }

                    // Top gradient scrim + Back / Author / Follow bar
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)
                                )
                            )
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("viewer_close_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Close",
                                    tint = Color.White
                                )
                            }

                            UserAvatar(
                                initials = post.authorName.take(2).uppercase()
                                    .ifBlank { post.authorId.take(2).uppercase() },
                                isOnline = authorUser?.isOnline() ?: true,
                                seedKey = post.authorId,
                                size = 36.dp,
                                showPresenceDot = false,
                                avatarUrl = authorUser?.avatarUrl,
                                modifier = Modifier.clickable {
                                    onDismiss()
                                    onOpenUserProfile(post.authorId)
                                }
                            )

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        onDismiss()
                                        onOpenUserProfile(post.authorId)
                                    }
                            ) {
                                Text(
                                    text = post.authorName,
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "@${post.authorId} • ${post.formattedRelativeTime()}",
                                    color = Color.White.copy(alpha = 0.78f),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            if (!post.authorId.equals(currentUserId, ignoreCase = true)) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = Color.White.copy(alpha = 0.22f),
                                    modifier = Modifier.clickable { onToggleFollow(post.authorId) }
                                ) {
                                    Text(
                                        text = if (isFollowed) "Following" else "Follow",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                                FilledIconButton(
                                    onClick = {
                                        onDismiss()
                                        onOpenChat(post.authorId)
                                    },
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = Color.White.copy(alpha = 0.22f),
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.Chat,
                                        contentDescription = "Chat with author",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Bottom gradient scrim
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(210.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                                )
                            )
                    )

                    // Right action rail (Like, Comment, Share, Bookmark)
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(end = 12.dp, bottom = 58.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ViewerActionButton(
                            icon = if (post.isLikedByMe) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            tint = if (post.isLikedByMe) Color(0xFFEF4444) else Color.White,
                            label = post.likeCount.toString(),
                            contentDescription = "Like",
                            onClick = { onToggleLike(post.id) },
                            testTag = "viewer_like_button"
                        )
                        ViewerActionButton(
                            icon = Icons.Rounded.ChatBubbleOutline,
                            tint = Color.White,
                            label = post.commentCount.toString(),
                            contentDescription = "Comments",
                            onClick = { activeCommentsPostId = post.id },
                            testTag = "viewer_comment_button"
                        )
                        ViewerActionButton(
                            icon = Icons.Rounded.Share,
                            tint = Color.White,
                            label = post.shareCount.toString(),
                            contentDescription = "Share or Repost",
                            onClick = { activeSharePost = post },
                            testTag = "viewer_share_button"
                        )
                        ViewerActionButton(
                            icon = if (post.isBookmarkedByMe) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                            tint = if (post.isBookmarkedByMe) Color(0xFF4ADE80) else Color.White,
                            label = if (post.isBookmarkedByMe) "Saved" else "Save",
                            contentDescription = "Save post",
                            onClick = { onToggleBookmark(post.id) },
                            testTag = "viewer_bookmark_button"
                        )
                    }

                    // Bottom-left caption overlay
                    if (post.mediaType != SocialMediaType.QUOTE && post.caption.isNotBlank()) {
                        Text(
                            text = post.caption,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .navigationBarsPadding()
                                .padding(start = 16.dp, end = 86.dp, bottom = 64.dp)
                        )
                    }
                }
            }

            activeCommentsPostId?.let { postId ->
                val activePost = posts.firstOrNull { it.id == postId }
                if (activePost != null) {
                    CommentsBottomSheet(
                        post = activePost,
                        comments = commentsByPost[postId].orEmpty(),
                        users = users,
                        onDismiss = { activeCommentsPostId = null },
                        onAddComment = { text -> onAddComment(postId, text) }
                    )
                }
            }

            activeSharePost?.let { shareTarget ->
                SharePostBottomSheet(
                    post = shareTarget,
                    users = users,
                    currentUserId = currentUserId,
                    onDismiss = { activeSharePost = null },
                    onRepost = { onRepost(shareTarget.id) },
                    onSendToChat = { targetId -> onShareToChat(shareTarget, targetId) }
                )
            }
        }
    }
}

@Composable
private fun ViewerActionButton(
    icon: ImageVector,
    tint: Color,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    testTag: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag(testTag)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(28.dp)
            )
        }
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}
