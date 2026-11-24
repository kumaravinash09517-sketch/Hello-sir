package com.example.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.SocialMediaType
import com.example.data.model.SocialPost
import com.example.ui.ChatUiState
import com.example.ui.components.CommentsBottomSheet
import com.example.ui.components.CreatePostBottomSheet
import com.example.ui.components.SharePostBottomSheet
import com.example.ui.components.SocialVideoPlayer
import com.example.ui.components.UserAvatar

@Composable
fun ReelsScreen(
    uiState: ChatUiState,
    onOpenUserProfile: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    onToggleFollow: (String) -> Unit,
    onToggleLike: (String) -> Unit,
    onToggleBookmark: (String) -> Unit,
    onAddComment: (postId: String, text: String) -> Unit,
    onRepost: (String) -> Unit,
    onShareToChat: (SocialPost, String) -> Unit,
    onPublishReel: (Uri?, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentUserId = uiState.session?.userId.orEmpty()
    val reels = uiState.reelsPosts

    var isMuted by remember { mutableStateOf(false) }
    var activeCommentsPostId by remember { mutableStateOf<String?>(null) }
    var activeSharePost by remember { mutableStateOf<SocialPost?>(null) }
    var showCreateReelSheet by remember { mutableStateOf(false) }

    if (activeCommentsPostId != null) {
        val post = reels.firstOrNull { it.id == activeCommentsPostId }
        if (post != null) {
            CommentsBottomSheet(
                post = post,
                comments = uiState.socialComments[post.id].orEmpty(),
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

    if (showCreateReelSheet) {
        CreatePostBottomSheet(
            initialType = SocialMediaType.REEL,
            onDismiss = { showCreateReelSheet = false },
            onPublish = { _, uri, caption ->
                onPublishReel(uri, caption)
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (reels.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.VideoLibrary,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "No Reels Yet",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Upload a short video Reel to watch it full-screen with audio and seek controls.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.78f)
                )
                Spacer(modifier = Modifier.height(18.dp))
                Button(
                    onClick = { showCreateReelSheet = true },
                    modifier = Modifier.testTag("create_reel_empty_button")
                ) {
                    Icon(Icons.Rounded.AddCircleOutline, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Upload Reel")
                }
            }
        } else {
            val pagerState = rememberPagerState(pageCount = { reels.size })

            VerticalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("reels_pager")
            ) { page ->
                val reel = reels[page]
                val isFollowed = uiState.profileExtras[reel.authorId]?.isFollowedByMe == true

                Box(modifier = Modifier.fillMaxSize()) {
                    SocialVideoPlayer(
                        videoUri = reel.mediaUri,
                        isVisible = pagerState.currentPage == page,
                        isMuted = isMuted,
                        onToggleMute = { isMuted = !isMuted },
                        showBottomSeekBar = true,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Bottom gradient scrim for readable metadata
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(210.dp)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))
                                )
                            )
                    )

                    // Right action column
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp, bottom = 58.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ReelActionButton(
                            icon = if (reel.isLikedByMe) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            tint = if (reel.isLikedByMe) Color(0xFFEF4444) else Color.White,
                            count = reel.likeCount.toString(),
                            contentDescription = "Like reel",
                            onClick = { onToggleLike(reel.id) }
                        )
                        ReelActionButton(
                            icon = Icons.Rounded.ChatBubbleOutline,
                            tint = Color.White,
                            count = reel.commentCount.toString(),
                            contentDescription = "Comment on reel",
                            onClick = { activeCommentsPostId = reel.id }
                        )
                        ReelActionButton(
                            icon = Icons.Rounded.Share,
                            tint = Color.White,
                            count = reel.shareCount.toString(),
                            contentDescription = "Share reel",
                            onClick = { activeSharePost = reel }
                        )
                        ReelActionButton(
                            icon = if (reel.isBookmarkedByMe) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                            tint = if (reel.isBookmarkedByMe) Color(0xFF4ADE80) else Color.White,
                            count = if (reel.isBookmarkedByMe) "Saved" else "Save",
                            contentDescription = "Bookmark reel",
                            onClick = { onToggleBookmark(reel.id) }
                        )
                    }

                    // Bottom-left author & caption overlay
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 16.dp, end = 84.dp, bottom = 58.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            UserAvatar(
                                initials = reel.authorName.take(2).uppercase(),
                                isOnline = true,
                                seedKey = reel.authorId,
                                size = 38.dp,
                                showPresenceDot = false,
                                modifier = Modifier.clickable { onOpenUserProfile(reel.authorId) }
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .clickable { onOpenUserProfile(reel.authorId) }
                            ) {
                                Text(
                                    text = reel.authorName,
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "@${reel.authorId}",
                                    color = Color.White.copy(alpha = 0.78f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            if (!reel.authorId.equals(currentUserId, ignoreCase = true)) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = Color.White.copy(alpha = 0.2f),
                                    modifier = Modifier.clickable { onToggleFollow(reel.authorId) }
                                ) {
                                    Text(
                                        text = if (isFollowed) "Following" else "Follow",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                    )
                                }
                                FilledIconButton(
                                    onClick = { onOpenChat(reel.authorId) },
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = Color.White.copy(alpha = 0.22f),
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.Chat,
                                        contentDescription = "Chat with author",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        if (reel.caption.isNotBlank()) {
                            Text(
                                text = reel.caption,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Top overlay header
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Reels",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            TextButton(
                onClick = { showCreateReelSheet = true },
                modifier = Modifier.testTag("create_reel_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.AddCircleOutline,
                    contentDescription = null,
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("New Reel", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ReelActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    count: String,
    contentDescription: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.minimumInteractiveComponentSize()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(28.dp)
            )
        }
        Text(
            text = count,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}
