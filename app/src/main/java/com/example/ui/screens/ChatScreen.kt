package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.BROADCAST_TARGET_ID
import com.example.data.model.ChatMessage
import com.example.data.model.ChatUser
import com.example.data.model.MessageDeliveryStatus
import com.example.data.model.MessageKind
import com.example.data.network.BackendConfig
import com.example.media.MediaHelper
import com.example.ui.components.PresenceBadge
import com.example.ui.components.SocialVideoPlayer
import com.example.ui.components.UserAvatar
import com.example.ui.theme.OnlineGreen
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    currentUserId: String,
    targetId: String,
    users: List<ChatUser>,
    messages: List<ChatMessage>,
    isSending: Boolean,
    isFetchingLocation: Boolean,
    isLiveLocationSharing: Boolean,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onSelectTarget: (String) -> Unit,
    onSendMessage: (receiver: String, text: String, onSent: () -> Unit) -> Unit,
    onSendLocation: (receiver: String, live: Boolean) -> Unit,
    onStopLiveLocation: () -> Unit,
    onSendMedia: (receiver: String, uri: Uri, kind: MessageKind?) -> Unit,
    onStartCall: (targetUserId: String, targetUserName: String, isVideo: Boolean) -> Unit,
    onDeleteMessage: (ChatMessage) -> Unit = {},
    onDownloadVideo: (url: String, title: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    if (showBackButton) {
        BackHandler(onBack = onBack)
    }

    val context = LocalContext.current
    val isBroadcast = targetId == BROADCAST_TARGET_ID
    val targetUser = remember(users, targetId) {
        users.firstOrNull { it.userId.equals(targetId, ignoreCase = true) }
    }
    val userMap = remember(users) { users.associateBy { it.userId } }
    val targetDisplayName = targetUser?.name ?: targetId

    var draft by rememberSaveable(targetId) { mutableStateOf("") }
    var showTargetMenu by remember { mutableStateOf(false) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var pendingLiveLocation by remember { mutableStateOf(false) }
    var pendingVideoCall by remember { mutableStateOf<Boolean?>(null) }
    var pendingDeleteMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var activeVideoPreview by remember { mutableStateOf<Pair<String, String>?>(null) }
    var videoPlayerMuted by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Photo Picker (Play-Policy compliant zero-permission)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { onSendMedia(targetId, it, MessageKind.IMAGE) }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { onSendMedia(targetId, it, MessageKind.VIDEO) }
    }

    // SAF OpenDocument for Audio & Documents
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { onSendMedia(targetId, it, MessageKind.AUDIO) }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { onSendMedia(targetId, it, MessageKind.DOCUMENT) }
    }

    // Location runtime permission launcher
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            onSendLocation(targetId, pendingLiveLocation)
        }
    }

    // WebRTC Call runtime permission launcher
    val callPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val audioOk = grants[Manifest.permission.RECORD_AUDIO] == true
        val videoWanted = pendingVideoCall ?: false
        val cameraOk = !videoWanted || grants[Manifest.permission.CAMERA] == true
        if (audioOk && cameraOk) {
            onStartCall(targetId, targetDisplayName, videoWanted)
        }
        pendingVideoCall = null
    }

    fun launchLocationShare(live: Boolean) {
        pendingLiveLocation = live
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            onSendLocation(targetId, live)
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    fun launchWebRTCCall(isVideo: Boolean) {
        val perms = if (isVideo) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }
        val allGranted = perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            onStartCall(targetId, targetDisplayName, isVideo)
        } else {
            pendingVideoCall = isVideo
            callPermissionLauncher.launch(perms)
        }
    }

    LaunchedEffect(messages.size, targetId) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    pendingDeleteMessage?.let { targetMsg ->
        AlertDialog(
            onDismissRequest = { pendingDeleteMessage = null },
            title = { Text("Delete message?") },
            text = {
                Text(
                    text = "Remove \"${targetMsg.previewSummary().take(80)}\" from this conversation?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteMessage(targetMsg)
                        pendingDeleteMessage = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteMessage = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    activeVideoPreview?.let { (videoUrl, videoTitle) ->
        Dialog(
            onDismissRequest = { activeVideoPreview = null },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            BackHandler(onBack = { activeVideoPreview = null })
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .testTag("chat_video_player_dialog")
            ) {
                SocialVideoPlayer(
                    videoUri = videoUrl,
                    isVisible = true,
                    isMuted = videoPlayerMuted,
                    onToggleMute = { videoPlayerMuted = !videoPlayerMuted },
                    showBottomSeekBar = true,
                    fitInside = true,
                    controlsTopPadding = 68.dp,
                    modifier = Modifier.fillMaxSize()
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        FilledIconButton(
                            onClick = { activeVideoPreview = null },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Color.Black.copy(alpha = 0.55f),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Close video"
                            )
                        }
                        Text(
                            text = videoTitle,
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    FilledIconButton(
                        onClick = { onDownloadVideo(videoUrl, videoTitle) },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.55f),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = "Download video"
                        )
                    }
                }
            }
        }
    }

    if (showAttachmentSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachmentSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Share Media & Live Location",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    AttachmentOptionTile(
                        icon = Icons.Rounded.PhotoLibrary,
                        label = "Photo",
                        tint = Color(0xFF0D6E5E),
                        onClick = {
                            showAttachmentSheet = false
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                    AttachmentOptionTile(
                        icon = Icons.Rounded.VideoLibrary,
                        label = "Video",
                        tint = Color(0xFFD96133),
                        onClick = {
                            showAttachmentSheet = false
                            videoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        }
                    )
                    AttachmentOptionTile(
                        icon = Icons.Rounded.AudioFile,
                        label = "Audio",
                        tint = Color(0xFF6B4E71),
                        onClick = {
                            showAttachmentSheet = false
                            audioPickerLauncher.launch(arrayOf("audio/*"))
                        }
                    )
                    AttachmentOptionTile(
                        icon = Icons.Rounded.Description,
                        label = "Document",
                        tint = Color(0xFF2D5A7B),
                        onClick = {
                            showAttachmentSheet = false
                            documentPickerLauncher.launch(arrayOf("*/*"))
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            showAttachmentSheet = false
                            launchLocationShare(live = false)
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pin GPS")
                    }
                    OutlinedButton(
                        onClick = {
                            showAttachmentSheet = false
                            launchLocationShare(live = true)
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MyLocation,
                            contentDescription = null,
                            tint = OnlineGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Live (15m)")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("chat_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back)
                            )
                        }
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isBroadcast) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Campaign,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = stringResource(R.string.title_global_broadcast),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${users.count { it.isOnline() }} online • Everyone",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            val isOnline = targetUser?.isOnline() ?: false
                            UserAvatar(
                                initials = targetUser?.initials ?: targetId.take(2).uppercase(),
                                isOnline = isOnline,
                                seedKey = targetId,
                                size = 38.dp,
                                avatarUrl = targetUser?.avatarUrl
                            )
                            Column {
                                Text(
                                    text = targetDisplayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                PresenceBadge(
                                    isOnline = isOnline,
                                    label = targetUser?.presenceLabel() ?: "Offline"
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (!isBroadcast && !targetId.equals(currentUserId, ignoreCase = true)) {
                        IconButton(
                            onClick = { launchWebRTCCall(isVideo = false) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("audio_call_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Call,
                                contentDescription = stringResource(R.string.cd_audio_call),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(
                            onClick = { launchWebRTCCall(isVideo = true) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("video_call_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Videocam,
                                contentDescription = stringResource(R.string.cd_video_call),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Box {
                        IconButton(
                            onClick = { showTargetMenu = true },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("switch_recipient_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SwapHoriz,
                                contentDescription = "Switch recipient"
                            )
                        }
                        DropdownMenu(
                            expanded = showTargetMenu,
                            onDismissRequest = { showTargetMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.title_global_broadcast),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Campaign,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                },
                                onClick = {
                                    showTargetMenu = false
                                    onSelectTarget(BROADCAST_TARGET_ID)
                                }
                            )
                            users.filterNot { it.userId.equals(currentUserId, ignoreCase = true) }
                                .forEach { user ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            if (user.isOnline()) OnlineGreen
                                                            else MaterialTheme.colorScheme.outlineVariant
                                                        )
                                                )
                                                Text("${user.name} (@${user.userId})")
                                            }
                                        },
                                        onClick = {
                                            showTargetMenu = false
                                            onSelectTarget(user.userId)
                                        }
                                    )
                                }
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    AnimatedVisibility(visible = isLiveLocationSharing || isFetchingLocation) {
                        Surface(
                            color = OnlineGreen.copy(alpha = 0.14f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MyLocation,
                                        contentDescription = null,
                                        tint = OnlineGreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = if (isFetchingLocation) {
                                            "Acquiring GPS coordinates…"
                                        } else {
                                            "Live GPS location sharing active"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = OnlineGreen,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                if (isLiveLocationSharing) {
                                    TextButton(onClick = onStopLiveLocation) {
                                        Text("Stop", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = { showAttachmentSheet = true },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("attach_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AttachFile,
                                contentDescription = stringResource(R.string.cd_attach),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            placeholder = {
                                Text(
                                    text = if (isBroadcast) {
                                        "Broadcast to everyone…"
                                    } else {
                                        "Message $targetDisplayName…"
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            maxLines = 4,
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface
                            ),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Send
                            ),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (draft.isNotBlank() && !isSending) {
                                        onSendMessage(targetId, draft) { draft = "" }
                                    }
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("message_input")
                        )

                        FilledIconButton(
                            onClick = {
                                if (draft.isNotBlank() && !isSending) {
                                    onSendMessage(targetId, draft) { draft = "" }
                                }
                            },
                            enabled = draft.isNotBlank() && !isSending,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier
                                .size(50.dp)
                                .testTag("send_button")
                        ) {
                            if (isSending) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(R.string.cd_send)
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isBroadcast) Icons.Rounded.Campaign else Icons.Rounded.Forum,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                    Text(
                        text = if (isBroadcast) {
                            "No global broadcasts yet"
                        } else {
                            "Start a conversation with $targetDisplayName"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Send messages, photos, files, GPS location, or place a WebRTC call.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .testTag("messages_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    items = messages,
                    key = { it.dedupeKey }
                ) { msg ->
                    val isMine = msg.sender.equals(currentUserId, ignoreCase = true)
                    val senderDisplay = msg.senderName
                        ?: userMap[msg.sender]?.name
                        ?: msg.sender

                    MessageBubble(
                        message = msg,
                        senderDisplayName = senderDisplay,
                        isMine = isMine,
                        showSenderName = isBroadcast || !isMine,
                        onLongClick = { if (isMine) pendingDeleteMessage = msg },
                        onOpenVideo = { url, title -> activeVideoPreview = url to title },
                        onDownloadVideo = onDownloadVideo
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentOptionTile(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = tint.copy(alpha = 0.14f),
            modifier = Modifier.size(52.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = tint,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    senderDisplayName: String,
    isMine: Boolean,
    showSenderName: Boolean,
    onLongClick: () -> Unit = {},
    onOpenVideo: (url: String, title: String) -> Unit = { _, _ -> },
    onDownloadVideo: (url: String, title: String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val bubbleShape = if (isMine) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
    }

    val containerColor = if (isMine) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = if (isMine) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val metaColor = if (isMine) {
        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = containerColor,
            shape = bubbleShape,
            tonalElevation = if (isMine) 0.dp else 1.dp,
            modifier = Modifier
                .widthIn(max = 310.dp)
                .clip(bubbleShape)
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongClick
                )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (showSenderName && !isMine) {
                    Text(
                        text = senderDisplayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }

                when (message.kind) {
                    MessageKind.LOCATION -> {
                        val loc = message.location
                        if (loc != null) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isMine) Color.Black.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        MediaHelper.openLocationInMaps(
                                            context,
                                            loc.latitude,
                                            loc.longitude,
                                            loc.label
                                        )
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = if (loc.isLive) Icons.Rounded.MyLocation else Icons.Rounded.LocationOn,
                                        contentDescription = null,
                                        tint = if (loc.isLive) Color(0xFF4ADE80) else textColor,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (loc.isLive) "Live GPS Location" else "Location Pin",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = textColor,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = String.format(
                                                Locale.US,
                                                "%.5f, %.5f",
                                                loc.latitude,
                                                loc.longitude
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = metaColor
                                        )
                                        Text(
                                            text = "Tap to open in Maps",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = metaColor
                                        )
                                    }
                                }
                            }
                        }
                    }

                    MessageKind.IMAGE -> {
                        val media = message.media
                        val imageModel: Any? = remember(media?.localUri, media?.inlineDataBase64) {
                            when {
                                !media?.localUri.isNullOrBlank() -> Uri.parse(media!!.localUri)
                                !media?.inlineDataBase64.isNullOrBlank() ->
                                    runCatching { Base64.decode(media!!.inlineDataBase64, Base64.NO_WRAP) }.getOrNull()
                                else -> null
                            }
                        }
                        if (imageModel != null) {
                            AsyncImage(
                                model = imageModel,
                                contentDescription = media?.displayName ?: "Shared image",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 120.dp, max = 220.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (!media?.localUri.isNullOrBlank()) {
                                            MediaHelper.openMediaUri(
                                                context,
                                                media!!.localUri,
                                                media.mimeType
                                            )
                                        }
                                    }
                            )
                        }
                        if (!media?.displayName.isNullOrBlank()) {
                            Text(
                                text = media!!.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = metaColor
                            )
                        }
                    }

                    MessageKind.VIDEO -> {
                        val media = message.media
                        val videoUrl = media?.localUri.orEmpty()
                        val videoTitle = media?.displayName?.ifBlank { null } ?: message.text.ifBlank { "Reel Video" }
                        val thumbModel: Any? = remember(media?.inlineDataBase64, videoUrl) {
                            when {
                                !media?.inlineDataBase64.isNullOrBlank() ->
                                    runCatching { Base64.decode(media!!.inlineDataBase64, Base64.NO_WRAP) }.getOrNull()
                                videoUrl.isNotBlank() ->
                                    BackendConfig.deriveVideoThumbnailUrl(videoUrl).takeIf { it.isNotBlank() }
                                else -> null
                            }
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(190.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(Color(0xFF0F2E2A), Color(0xFF1F3A52))
                                        )
                                    )
                                    .clickable {
                                        if (videoUrl.isNotBlank()) {
                                            onOpenVideo(videoUrl, videoTitle)
                                        }
                                    }
                                    .testTag("chat_video_card_${message.id}"),
                                contentAlignment = Alignment.Center
                            ) {
                                if (thumbModel != null) {
                                    AsyncImage(
                                        model = thumbModel,
                                        contentDescription = videoTitle,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.28f))
                                    )
                                }

                                Surface(
                                    color = Color.Black.copy(alpha = 0.65f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = "REEL",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }

                                if (videoUrl.isNotBlank()) {
                                    FilledIconButton(
                                        onClick = { onDownloadVideo(videoUrl, videoTitle) },
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = Color.Black.copy(alpha = 0.58f),
                                            contentColor = Color.White
                                        ),
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp)
                                            .size(34.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Download,
                                            contentDescription = "Download video",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                Icon(
                                    imageVector = Icons.Rounded.PlayCircle,
                                    contentDescription = "Play video",
                                    tint = Color.White,
                                    modifier = Modifier.size(54.dp)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = videoTitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textColor,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                media?.formattedSize()?.takeIf { it.isNotEmpty() }?.let { sz ->
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = sz,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = metaColor
                                    )
                                }
                            }
                        }
                    }

                    MessageKind.AUDIO,
                    MessageKind.DOCUMENT -> {
                        val media = message.media
                        val docIcon = when (message.kind) {
                            MessageKind.AUDIO -> Icons.Rounded.AudioFile
                            else -> Icons.Rounded.Description
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isMine) Color.Black.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (!media?.localUri.isNullOrBlank()) {
                                        MediaHelper.openMediaUri(context, media!!.localUri, media.mimeType)
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = docIcon,
                                    contentDescription = null,
                                    tint = textColor,
                                    modifier = Modifier.size(26.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = media?.displayName ?: message.text,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = textColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val sizeStr = media?.formattedSize().orEmpty()
                                    if (sizeStr.isNotEmpty()) {
                                        Text(
                                            text = sizeStr,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = metaColor
                                        )
                                    }
                                }
                            }
                        }
                    }

                    MessageKind.TEXT -> {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = textColor
                        )
                    }
                }

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val timeStr = message.formattedTime()
                    if (timeStr.isNotEmpty()) {
                        Text(
                            text = timeStr,
                            style = MaterialTheme.typography.labelSmall,
                            color = metaColor
                        )
                    }
                    if (isMine) {
                        val receiptIcon = when (message.deliveryStatus) {
                            MessageDeliveryStatus.PENDING -> Icons.Rounded.Schedule
                            MessageDeliveryStatus.SENT -> Icons.Rounded.Check
                            MessageDeliveryStatus.DELIVERED,
                            MessageDeliveryStatus.READ -> Icons.Rounded.DoneAll
                        }
                        val receiptTint = when (message.deliveryStatus) {
                            MessageDeliveryStatus.READ -> Color(0xFF0284C7) // 2 blue ticks
                            MessageDeliveryStatus.DELIVERED,
                            MessageDeliveryStatus.SENT,
                            MessageDeliveryStatus.PENDING -> Color(0xFF6B7280) // 1 grey / 2 grey ticks
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.White.copy(alpha = 0.90f),
                            modifier = Modifier.testTag("receipt_${message.deliveryStatus.name.lowercase()}")
                        ) {
                            Icon(
                                imageVector = receiptIcon,
                                contentDescription = message.deliveryStatus.name,
                                tint = receiptTint,
                                modifier = Modifier
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                                    .size(13.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
