package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.components.UserAvatar
import com.example.webrtc.CallStatus
import com.example.webrtc.CallUiState
import com.example.webrtc.WebRTCClient
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.util.Locale

@Composable
fun CallScreen(
    callState: CallUiState,
    webRTCClient: WebRTCClient,
    onAcceptCall: (withVideo: Boolean) -> Unit,
    onDeclineOrEnd: () -> Unit,
    onMinimize: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        if (callState.status != CallStatus.INCOMING_RINGING) {
            onMinimize()
        }
    }

    var localViewRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var remoteViewRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    DisposableEffect(callState.isVideoCall, localViewRef, remoteViewRef) {
        if (callState.isVideoCall) {
            webRTCClient.attachRenderers(localViewRef, remoteViewRef)
        }
        onDispose {
            webRTCClient.detachRenderers()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0A1F1C), Color(0xFF091211))
                )
            )
    ) {
        if (callState.isVideoCall && callState.status != CallStatus.INCOMING_RINGING) {
            // Remote full-screen WebRTC Video Renderer
            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        init(webRTCClient.eglBase.eglBaseContext, null)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        setEnableHardwareScaler(true)
                        remoteViewRef = this
                    }
                },
                onRelease = { renderer ->
                    runCatching { renderer.release() }
                    remoteViewRef = null
                },
                modifier = Modifier.fillMaxSize()
            )

            // Local PiP Camera Renderer
            Surface(
                shape = RoundedCornerShape(18.dp),
                shadowElevation = 8.dp,
                color = Color.Black,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 16.dp, end = 16.dp)
                    .size(width = 112.dp, height = 160.dp)
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).apply {
                            init(webRTCClient.eglBase.eglBaseContext, null)
                            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                            setMirror(true)
                            setZOrderMediaOverlay(true)
                            setEnableHardwareScaler(true)
                            localViewRef = this
                        }
                    },
                    onRelease = { renderer ->
                        runCatching { renderer.release() }
                        localViewRef = null
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top caller identity & ICE status
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                if (callState.status != CallStatus.INCOMING_RINGING) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        FilledIconButton(
                            onClick = onMinimize,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Color.White.copy(alpha = 0.16f),
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("minimize_call_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = "Minimize call"
                            )
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.White.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = callState.iceStatusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF99F6E4),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }

                if (!callState.isVideoCall || callState.status != CallStatus.CONNECTED) {
                    Spacer(modifier = Modifier.height(20.dp))
                    val initials = callState.remoteUserName.trim().take(2).uppercase()
                        .ifBlank { callState.remoteUserId.take(2).uppercase() }
                    UserAvatar(
                        initials = initials,
                        isOnline = true,
                        seedKey = callState.remoteUserId,
                        size = 104.dp,
                        showPresenceDot = false
                    )
                }

                Text(
                    text = callState.remoteUserName.ifBlank { callState.remoteUserId },
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = when (callState.status) {
                        CallStatus.OUTGOING_RINGING -> "Ringing…"
                        CallStatus.INCOMING_RINGING ->
                            if (callState.isVideoCall) "Incoming WebRTC Video Call" else "Incoming WebRTC Audio Call"
                        CallStatus.CONNECTING -> "Establishing P2P connection…"
                        CallStatus.CONNECTED -> formatCallDuration(callState.durationSeconds)
                        CallStatus.IDLE -> ""
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.82f)
                )
            }

            // Bottom controls
            if (callState.status == CallStatus.INCOMING_RINGING) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = onDeclineOrEnd,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Color(0xFFDC2626),
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .size(68.dp)
                                .testTag("decline_call_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CallEnd,
                                contentDescription = "Decline call",
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Decline", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = { onAcceptCall(callState.isVideoCall) },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Color(0xFF16A34A),
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .size(68.dp)
                                .testTag("accept_call_button")
                        ) {
                            Icon(
                                imageVector = if (callState.isVideoCall) {
                                    Icons.Rounded.Videocam
                                } else {
                                    Icons.Rounded.Call
                                },
                                contentDescription = "Accept call",
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Accept", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(32.dp),
                    color = Color.Black.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CallControlIcon(
                            active = callState.isMuted,
                            icon = if (callState.isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                            label = if (callState.isMuted) "Unmute" else "Mute",
                            onClick = webRTCClient::toggleMute
                        )

                        CallControlIcon(
                            active = callState.isSpeakerOn,
                            icon = Icons.Rounded.VolumeUp,
                            label = "Speaker",
                            onClick = webRTCClient::toggleSpeaker
                        )

                        if (callState.isVideoCall) {
                            CallControlIcon(
                                active = !callState.isCameraEnabled,
                                icon = if (callState.isCameraEnabled) {
                                    Icons.Rounded.Videocam
                                } else {
                                    Icons.Rounded.VideocamOff
                                },
                                label = "Camera",
                                onClick = webRTCClient::toggleCamera
                            )

                            CallControlIcon(
                                active = false,
                                icon = Icons.Rounded.Cameraswitch,
                                label = "Flip",
                                onClick = webRTCClient::switchCamera
                            )
                        }

                        FilledIconButton(
                            onClick = onDeclineOrEnd,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Color(0xFFDC2626),
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .testTag("end_call_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CallEnd,
                                contentDescription = "End call"
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallControlIcon(
    active: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (active) Color.White else Color.White.copy(alpha = 0.16f),
            contentColor = if (active) Color(0xFF0A1F1C) else Color.White
        ),
        modifier = Modifier.size(52.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label)
    }
}

private fun formatCallDuration(seconds: Long): String {
    val mins = seconds / 60L
    val secs = seconds % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
