package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.SocialMediaType
import com.example.notifications.ChatNotificationHelper
import com.example.ui.AppTab
import com.example.ui.ChatViewModel
import com.example.ui.components.ActiveCallBanner
import com.example.ui.components.BlockedAlertDialog
import com.example.ui.screens.ActivityScreen
import com.example.ui.screens.CallScreen
import com.example.ui.screens.ExploreScreen
import com.example.ui.screens.FeedScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LoginScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.ProfileSettingsScreen
import com.example.ui.screens.ReelsScreen
import com.example.ui.theme.HelloSirTheme

private data class PendingOutgoingCall(
    val userId: String,
    val userName: String,
    val isVideo: Boolean
)

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleNotificationIntent(intent)

        setContent {
            HelloSirTheme {
                val context = LocalContext.current
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                var hasNotificationPermission by remember {
                    mutableStateOf(checkNotificationPermission(context))
                }
                var pendingAcceptVideo by remember { mutableStateOf<Boolean?>(null) }
                var pendingOutgoingCall by remember { mutableStateOf<PendingOutgoingCall?>(null) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    hasNotificationPermission = granted
                    if (!granted) {
                        Toast.makeText(
                            context,
                            "Enable notifications in settings to receive live chat & call alerts.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                val incomingCallPermLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { grants ->
                    val audioOk = grants[Manifest.permission.RECORD_AUDIO] == true
                    val videoWanted = pendingAcceptVideo ?: false
                    val cameraOk = !videoWanted || grants[Manifest.permission.CAMERA] == true
                    if (audioOk && cameraOk) {
                        viewModel.acceptCall(videoWanted)
                    } else {
                        Toast.makeText(
                            context,
                            "Microphone/Camera permission is required for WebRTC calls.",
                            Toast.LENGTH_SHORT
                        ).show()
                        viewModel.declineOrEndCall()
                    }
                    pendingAcceptVideo = null
                }

                val outgoingCallPermLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { grants ->
                    val req = pendingOutgoingCall
                    if (req != null) {
                        val audioOk = grants[Manifest.permission.RECORD_AUDIO] == true
                        val cameraOk = !req.isVideo || grants[Manifest.permission.CAMERA] == true
                        if (audioOk && cameraOk) {
                            viewModel.startCall(req.userId, req.userName, req.isVideo)
                        } else {
                            Toast.makeText(
                                context,
                                "Microphone/Camera permission is required to place calls.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    pendingOutgoingCall = null
                }

                fun launchCallWithPermission(targetId: String, targetName: String, isVideo: Boolean) {
                    val perms = if (isVideo) {
                        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                    } else {
                        arrayOf(Manifest.permission.RECORD_AUDIO)
                    }
                    val allGranted = perms.all {
                        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                    }
                    if (allGranted) {
                        viewModel.startCall(targetId, targetName, isVideo)
                    } else {
                        pendingOutgoingCall = PendingOutgoingCall(targetId, targetName, isVideo)
                        outgoingCallPermLauncher.launch(perms)
                    }
                }

                LaunchedEffect(uiState.session) {
                    if (uiState.session != null &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !hasNotificationPermission
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                LaunchedEffect(Unit) {
                    viewModel.toastEvents.collect { msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }

                val blockedMessage = uiState.blockedAlertMessage
                if (blockedMessage != null) {
                    BlockedAlertDialog(
                        message = blockedMessage,
                        onDismiss = viewModel::dismissBlockedAlert
                    )
                }

                val session = uiState.session
                when {
                    session == null -> {
                        LoginScreen(
                            isLoading = uiState.isRegistering,
                            errorMessage = uiState.registerError,
                            onRegister = { uid, name, phone -> viewModel.register(uid, name, phone) },
                            onClearError = viewModel::clearRegisterError
                        )
                    }

                    uiState.callState.isActive && !uiState.isCallScreenMinimized -> {
                        CallScreen(
                            callState = uiState.callState,
                            webRTCClient = viewModel.webRTCClient,
                            onAcceptCall = { withVideo ->
                                val perms = if (withVideo) {
                                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                                } else {
                                    arrayOf(Manifest.permission.RECORD_AUDIO)
                                }
                                val allGranted = perms.all {
                                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                                }
                                if (allGranted) {
                                    viewModel.acceptCall(withVideo)
                                } else {
                                    pendingAcceptVideo = withVideo
                                    incomingCallPermLauncher.launch(perms)
                                }
                            },
                            onDeclineOrEnd = viewModel::declineOrEndCall,
                            onMinimize = viewModel::minimizeCallScreen
                        )
                    }

                    else -> {
                        val showBottomBar = !uiState.showProfileSettings &&
                            !uiState.showActivityFeed &&
                            uiState.selectedTargetId == null

                        Column(modifier = Modifier.fillMaxSize()) {
                            AnimatedVisibility(
                                visible = uiState.callState.isActive && uiState.isCallScreenMinimized
                            ) {
                                ActiveCallBanner(
                                    callState = uiState.callState,
                                    onReopenCall = viewModel::reopenCallScreen,
                                    onToggleMute = viewModel.webRTCClient::toggleMute,
                                    onHangUp = viewModel::declineOrEndCall
                                )
                            }

                            Scaffold(
                                modifier = Modifier.weight(1f),
                                bottomBar = {
                                    if (showBottomBar) {
                                        NavigationBar(
                                            modifier = Modifier.testTag("main_bottom_bar")
                                        ) {
                                            NavigationBarItem(
                                                selected = uiState.activeTab == AppTab.FEED,
                                                onClick = { viewModel.selectTab(AppTab.FEED) },
                                                icon = {
                                                    Icon(Icons.Rounded.Home, contentDescription = "Feed")
                                                },
                                                label = { Text("Feed") },
                                                modifier = Modifier.testTag("tab_feed")
                                            )
                                            NavigationBarItem(
                                                selected = uiState.activeTab == AppTab.REELS,
                                                onClick = { viewModel.selectTab(AppTab.REELS) },
                                                icon = {
                                                    Icon(Icons.Rounded.VideoLibrary, contentDescription = "Reels")
                                                },
                                                label = { Text("Reels") },
                                                modifier = Modifier.testTag("tab_reels")
                                            )
                                            NavigationBarItem(
                                                selected = uiState.activeTab == AppTab.EXPLORE,
                                                onClick = { viewModel.selectTab(AppTab.EXPLORE) },
                                                icon = {
                                                    Icon(Icons.Rounded.Search, contentDescription = "Explore")
                                                },
                                                label = { Text("Explore") },
                                                modifier = Modifier.testTag("tab_explore")
                                            )
                                            NavigationBarItem(
                                                selected = uiState.activeTab == AppTab.CHATS,
                                                onClick = { viewModel.selectTab(AppTab.CHATS) },
                                                icon = {
                                                    BadgedBox(
                                                        badge = {
                                                            if (uiState.totalUnreadMessages > 0) {
                                                                Badge(containerColor = Color(0xFFEF4444)) {
                                                                    Text(
                                                                        text = if (uiState.totalUnreadMessages > 99) "99+" else "${uiState.totalUnreadMessages}",
                                                                        color = Color.White
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    ) {
                                                        Icon(
                                                            Icons.AutoMirrored.Rounded.Chat,
                                                            contentDescription = "Chats"
                                                        )
                                                    }
                                                },
                                                label = { Text("Chats") },
                                                modifier = Modifier.testTag("tab_chats")
                                            )
                                            NavigationBarItem(
                                                selected = uiState.activeTab == AppTab.PROFILE,
                                                onClick = { viewModel.selectTab(AppTab.PROFILE) },
                                                icon = {
                                                    Icon(Icons.Rounded.Person, contentDescription = "Profile")
                                                },
                                                label = { Text("Profile") },
                                                modifier = Modifier.testTag("tab_profile")
                                            )
                                        }
                                    }
                                }
                            ) { innerPadding ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(bottom = innerPadding.calculateBottomPadding())
                                ) {
                                    when {
                                        uiState.showProfileSettings -> {
                                            ProfileSettingsScreen(
                                                session = session,
                                                transportState = uiState.signalingTransportState,
                                                notificationsEnabled = hasNotificationPermission,
                                                onRequestNotifications = {
                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                                        !hasNotificationPermission
                                                    ) {
                                                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                    } else {
                                                        Toast.makeText(
                                                            context,
                                                            "Message & call notifications are active.",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                },
                                                onBack = viewModel::closeProfileSettings,
                                                onLogoutAndReset = viewModel::logoutAndResetDeviceLock
                                            )
                                        }

                                        uiState.showActivityFeed -> {
                                            ActivityScreen(
                                                uiState = uiState,
                                                onBack = viewModel::closeActivityFeed,
                                                onOpenActorProfile = viewModel::openUserProfile
                                            )
                                        }

                                        uiState.activeTab == AppTab.FEED -> {
                                            FeedScreen(
                                                uiState = uiState,
                                                onRefresh = viewModel::refreshFeed,
                                                onOpenActivity = viewModel::openActivityFeed,
                                                onOpenUserProfile = viewModel::openUserProfile,
                                                onOpenChat = { target -> viewModel.selectTarget(target) },
                                                onToggleFollow = viewModel::toggleFollowUser,
                                                onToggleLike = viewModel::toggleLikePost,
                                                onToggleBookmark = viewModel::toggleBookmarkPost,
                                                onAddComment = viewModel::addComment,
                                                onRepost = viewModel::repostPost,
                                                onShareToChat = viewModel::sharePostToChat,
                                                onPublishPost = viewModel::createSocialPost
                                            )
                                        }

                                        uiState.activeTab == AppTab.REELS -> {
                                            ReelsScreen(
                                                uiState = uiState,
                                                onOpenUserProfile = viewModel::openUserProfile,
                                                onOpenChat = { target -> viewModel.selectTarget(target) },
                                                onToggleFollow = viewModel::toggleFollowUser,
                                                onToggleLike = viewModel::toggleLikePost,
                                                onToggleBookmark = viewModel::toggleBookmarkPost,
                                                onAddComment = viewModel::addComment,
                                                onRepost = viewModel::repostPost,
                                                onShareToChat = viewModel::sharePostToChat,
                                                onPublishReel = { uri, caption ->
                                                    viewModel.createSocialPost(
                                                        mediaType = SocialMediaType.REEL,
                                                        mediaUri = uri,
                                                        caption = caption
                                                    )
                                                }
                                            )
                                        }

                                        uiState.activeTab == AppTab.EXPLORE -> {
                                            ExploreScreen(
                                                uiState = uiState,
                                                onExploreQueryChange = viewModel::updateExploreQuery,
                                                onOpenUserProfile = viewModel::openUserProfile,
                                                onOpenChat = { target -> viewModel.selectTarget(target) },
                                                onToggleFollow = viewModel::toggleFollowUser,
                                                onStartCall = { id, name, video ->
                                                    launchCallWithPermission(id, name, video)
                                                },
                                                onAddComment = viewModel::addComment,
                                                onToggleLike = viewModel::toggleLikePost,
                                                onToggleBookmark = viewModel::toggleBookmarkPost,
                                                onRepost = viewModel::repostPost,
                                                onShareToChat = viewModel::sharePostToChat,
                                                onSubmitSearch = viewModel::submitExploreSearch
                                            )
                                        }

                                        uiState.activeTab == AppTab.CHATS -> {
                                            HomeScreen(
                                                uiState = uiState,
                                                notificationsEnabled = hasNotificationPermission,
                                                onRequestNotifications = {
                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                                        !hasNotificationPermission
                                                    ) {
                                                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                    } else {
                                                        Toast.makeText(
                                                            context,
                                                            "Message notifications are active.",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                },
                                                onOpenProfileSettings = viewModel::openProfileSettings,
                                                onRefresh = viewModel::refreshFeed,
                                                onSearchQueryChange = viewModel::updateSearchQuery,
                                                onSelectTarget = viewModel::selectTarget,
                                                onSendMessage = viewModel::sendMessage,
                                                onSendLocation = viewModel::sendCurrentLocation,
                                                onStopLiveLocation = viewModel::stopLiveLocationSharing,
                                                onSendMedia = viewModel::sendMedia,
                                                onStartCall = { id, name, video ->
                                                    launchCallWithPermission(id, name, video)
                                                },
                                                onDeleteMessage = viewModel::deleteMessage
                                            )
                                        }

                                        uiState.activeTab == AppTab.PROFILE -> {
                                            ProfileScreen(
                                                uiState = uiState,
                                                onBackFromViewedProfile = { viewModel.selectTab(AppTab.FEED) },
                                                onOpenProfileSettings = viewModel::openProfileSettings,
                                                onOpenChat = { target -> viewModel.selectTarget(target) },
                                                onStartCall = { id, name, video ->
                                                    launchCallWithPermission(id, name, video)
                                                },
                                                onToggleFollow = viewModel::toggleFollowUser,
                                                onUpdateBio = viewModel::updateMyBio,
                                                onUpdateAvatar = viewModel::updateMyAvatar,
                                                onToggleLike = viewModel::toggleLikePost,
                                                onToggleBookmark = viewModel::toggleBookmarkPost,
                                                onAddComment = viewModel::addComment,
                                                onRepost = viewModel::repostPost,
                                                onShareToChat = viewModel::sharePostToChat,
                                                onPublishPost = viewModel::createSocialPost,
                                                onOpenUserProfile = viewModel::openUserProfile
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(ChatNotificationHelper.EXTRA_OPEN_CALL, false) == true) {
            viewModel.reopenCallScreen()
            return
        }
        val target = intent?.getStringExtra(ChatNotificationHelper.EXTRA_CHAT_TARGET)
        if (!target.isNullOrBlank()) {
            viewModel.selectTarget(target)
        }
    }

    private fun checkNotificationPermission(context: android.content.Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
