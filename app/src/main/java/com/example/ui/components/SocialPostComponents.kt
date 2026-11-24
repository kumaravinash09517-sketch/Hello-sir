package com.example.ui.components

import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.BROADCAST_TARGET_ID
import com.example.data.model.ChatUser
import com.example.data.model.SocialComment
import com.example.data.model.SocialMediaType
import com.example.data.model.SocialPost
import kotlinx.coroutines.delay

@Composable
fun SocialPostCard(
    post: SocialPost,
    currentUserId: String,
    isFollowedByMe: Boolean,
    isVideoVisible: Boolean,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    onAuthorClick: (String) -> Unit,
    onToggleFollow: (String) -> Unit,
    onToggleLike: (String) -> Unit,
    onOpenComments: (SocialPost) -> Unit,
    onOpenShare: (SocialPost) -> Unit,
    onToggleBookmark: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDoubleTapHeart by remember { mutableStateOf(false) }

    LaunchedEffect(showDoubleTapHeart) {
        if (showDoubleTapHeart) {
            delay(650L)
            showDoubleTapHeart = false
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("social_post_${post.id}")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (post.isRepost && !post.repostedFromName.isNullOrBlank()) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Repeat,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Reposted from ${post.repostedFromName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val initials = post.authorName.trim().take(2).uppercase()
                    .ifBlank { post.authorId.take(2).uppercase() }

                UserAvatar(
                    initials = initials,
                    isOnline = true,
                    seedKey = post.authorId,
                    size = 40.dp,
                    showPresenceDot = false,
                    modifier = Modifier.clickable { onAuthorClick(post.authorId) }
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onAuthorClick(post.authorId) }
                ) {
                    Text(
                        text = post.authorName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "@${post.authorId} • ${post.formattedRelativeTime()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (!post.authorId.equals(currentUserId, ignoreCase = true)) {
                    TextButton(
                        onClick = { onToggleFollow(post.authorId) },
                        modifier = Modifier.testTag("follow_button_${post.authorId}")
                    ) {
                        Text(
                            text = if (isFollowedByMe) "Following" else "Follow",
                            fontWeight = FontWeight.Bold,
                            color = if (isFollowedByMe) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                }
            }

            // Media Body with double-tap to like
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(post.id) {
                        detectTapGestures(
                            onDoubleTap = {
                                showDoubleTapHeart = true
                                if (!post.isLikedByMe) {
                                    onToggleLike(post.id)
                                }
                            }
                        )
                    }
            ) {
                when (post.mediaType) {
                    SocialMediaType.PHOTO -> {
                        val imageModel: Any? = remember(post.mediaUri, post.inlineImageBase64) {
                            when {
                                post.mediaUri.isNotBlank() -> Uri.parse(post.mediaUri)
                                post.inlineImageBase64.isNotBlank() ->
                                    runCatching { Base64.decode(post.inlineImageBase64, Base64.NO_WRAP) }.getOrNull()
                                else -> null
                            }
                        }
                        if (imageModel != null) {
                            AsyncImage(
                                model = imageModel,
                                contentDescription = post.caption.ifBlank { "Photo post" },
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 240.dp, max = 380.dp)
                            )
                        }
                    }

                    SocialMediaType.REEL -> {
                        if (post.mediaUri.isNotBlank()) {
                            SocialVideoPlayer(
                                videoUri = post.mediaUri,
                                isVisible = isVideoVisible,
                                isMuted = isMuted,
                                onToggleMute = onToggleMute,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(340.dp)
                            )
                        }
                    }

                    SocialMediaType.QUOTE -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            Color(0xFF0D6E5E),
                                            Color(0xFF1E3A5F)
                                        )
                                    )
                                )
                                .padding(horizontal = 24.dp, vertical = 34.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = post.caption,
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                lineHeight = 28.sp
                            )
                        }
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = showDoubleTapHeart,
                    enter = scaleIn(spring(stiffness = Spring.StiffnessMediumLow)),
                    exit = scaleOut(),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(84.dp)
                    )
                }
            }

            // Action Row (Like, Comment, Share/Repost, Bookmark)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { onToggleLike(post.id) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("like_button_${post.id}")
                ) {
                    Icon(
                        imageVector = if (post.isLikedByMe) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = "Like",
                        tint = if (post.isLikedByMe) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "${post.likeCount}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(
                    onClick = { onOpenComments(post) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("comment_button_${post.id}")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ChatBubbleOutline,
                        contentDescription = "Comments"
                    )
                }
                Text(
                    text = "${post.commentCount}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(
                    onClick = { onOpenShare(post) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("share_button_${post.id}")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = "Share or Repost"
                    )
                }
                Text(
                    text = "${post.shareCount}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.weight(1f))

                IconButton(
                    onClick = { onToggleBookmark(post.id) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("bookmark_button_${post.id}")
                ) {
                    Icon(
                        imageVector = if (post.isBookmarkedByMe) {
                            Icons.Rounded.Bookmark
                        } else {
                            Icons.Rounded.BookmarkBorder
                        },
                        contentDescription = "Save post",
                        tint = if (post.isBookmarkedByMe) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }

            // Caption & Reach footer
            Column(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (post.mediaType != SocialMediaType.QUOTE && post.caption.isNotBlank()) {
                    Text(
                        text = "${post.authorName}  ${post.caption}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = "${post.reachCount} reach • View ${post.commentCount} comments",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onOpenComments(post) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsBottomSheet(
    post: SocialPost,
    comments: List<SocialComment>,
    onDismiss: () -> Unit,
    onAddComment: (String) -> Unit
) {
    var draft by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Comments (${comments.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            HorizontalDivider()

            if (comments.isEmpty()) {
                Text(
                    text = "No comments yet. Be the first to reply!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 20.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(comments, key = { it.id }) { comment ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            UserAvatar(
                                initials = comment.authorName.take(2).uppercase(),
                                isOnline = true,
                                seedKey = comment.authorId,
                                size = 32.dp,
                                showPresenceDot = false
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${comment.authorName} (@${comment.authorId})",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = comment.text,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Add a comment…") },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("comment_input")
                )
                FilledIconButton(
                    onClick = {
                        if (draft.isNotBlank()) {
                            onAddComment(draft)
                            draft = ""
                        }
                    },
                    enabled = draft.isNotBlank(),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("post_comment_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Post comment"
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharePostBottomSheet(
    post: SocialPost,
    users: List<ChatUser>,
    currentUserId: String,
    onDismiss: () -> Unit,
    onRepost: () -> Unit,
    onSendToChat: (String) -> Unit
) {
    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Share & Repost",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        onRepost()
                        onDismiss()
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("repost_button")
                ) {
                    Icon(Icons.Rounded.Repeat, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Repost to Feed")
                }

                OutlinedButton(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Hello Sir • ${post.authorName}: ${post.caption}"
                            )
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(shareIntent, "Share via"))
                        }
                        onDismiss()
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("External")
                }
            }

            Text(
                text = "Send directly in Chat",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(
                onClick = {
                    onSendToChat(BROADCAST_TARGET_ID)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Rounded.Chat, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Global Broadcast")
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(
                    users.filterNot { it.userId.equals(currentUserId, ignoreCase = true) },
                    key = { it.userId }
                ) { user ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onSendToChat(user.userId)
                                onDismiss()
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        UserAvatar(
                            initials = user.initials,
                            isOnline = user.isOnline(),
                            seedKey = user.userId,
                            size = 34.dp
                        )
                        Text("${user.name} (@${user.userId})", modifier = Modifier.weight(1f))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePostBottomSheet(
    initialCaption: String = "",
    initialType: SocialMediaType = SocialMediaType.PHOTO,
    onDismiss: () -> Unit,
    onPublish: (type: SocialMediaType, mediaUri: Uri?, caption: String) -> Unit
) {
    var selectedType by remember { mutableStateOf(initialType) }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var caption by remember { mutableStateOf(initialCaption) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            pickedUri = uri
            selectedType = SocialMediaType.PHOTO
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            pickedUri = uri
            selectedType = SocialMediaType.REEL
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Create Post or Reel",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedType == SocialMediaType.PHOTO,
                    onClick = { selectedType = SocialMediaType.PHOTO },
                    label = { Text("Photo") },
                    leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, contentDescription = null) }
                )
                FilterChip(
                    selected = selectedType == SocialMediaType.REEL,
                    onClick = { selectedType = SocialMediaType.REEL },
                    label = { Text("Reel") },
                    leadingIcon = { Icon(Icons.Rounded.VideoLibrary, contentDescription = null) }
                )
                FilterChip(
                    selected = selectedType == SocialMediaType.QUOTE,
                    onClick = {
                        selectedType = SocialMediaType.QUOTE
                        pickedUri = null
                    },
                    label = { Text("Quote/Shayari") },
                    leadingIcon = { Icon(Icons.Rounded.FormatQuote, contentDescription = null) }
                )
            }

            if (selectedType != SocialMediaType.QUOTE) {
                OutlinedButton(
                    onClick = {
                        if (selectedType == SocialMediaType.REEL) {
                            videoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        } else {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Icon(
                        imageVector = if (selectedType == SocialMediaType.REEL) {
                            Icons.Rounded.VideoLibrary
                        } else {
                            Icons.Rounded.PhotoLibrary
                        },
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (pickedUri != null) {
                            "Media selected • Tap to change"
                        } else if (selectedType == SocialMediaType.REEL) {
                            "Pick Video for Reel"
                        } else {
                            "Pick Photo"
                        }
                    )
                }
            }

            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it },
                label = { Text(if (selectedType == SocialMediaType.QUOTE) "Write your Shayari or thought" else "Write a caption…") },
                minLines = 2,
                maxLines = 5,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("create_post_caption")
            )

            Button(
                onClick = {
                    onPublish(selectedType, pickedUri, caption)
                    onDismiss()
                },
                enabled = caption.isNotBlank() || pickedUri != null,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .testTag("publish_post_button")
            ) {
                Text("Publish", fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
