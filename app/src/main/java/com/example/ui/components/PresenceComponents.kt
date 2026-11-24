package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import coil.compose.AsyncImage
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.DailyShayari
import com.example.ui.theme.OfflineGray
import com.example.ui.theme.OnlineGreen
import com.example.ui.theme.SpaceGroteskFontFamily
import com.example.webrtc.CallStatus
import com.example.webrtc.CallUiState
import java.util.Locale
import kotlin.math.abs

private val AvatarPalette = listOf(
    Color(0xFF0D6E5E) to Color(0xFFFFFFFF),
    Color(0xFFD96133) to Color(0xFFFFFFFF),
    Color(0xFF2D5A7B) to Color(0xFFFFFFFF),
    Color(0xFF6B4E71) to Color(0xFFFFFFFF),
    Color(0xFF3E6B48) to Color(0xFFFFFFFF),
    Color(0xFF8C5327) to Color(0xFFFFFFFF)
)

@Composable
fun UserAvatar(
    initials: String,
    isOnline: Boolean,
    seedKey: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    showPresenceDot: Boolean = true,
    avatarUrl: String? = null
) {
    val pair = AvatarPalette[abs(seedKey.hashCode()) % AvatarPalette.size]

    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(pair.first),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initials,
                color = pair.second,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.36f).sp
            )
            if (!avatarUrl.isNullOrBlank()) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            }
        }

        if (showPresenceDot) {
            val dotSize = (size.value * 0.28f).dp.coerceAtLeast(11.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(if (isOnline) OnlineGreen else OfflineGray)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
            )
        }
    }
}

@Composable
fun PresenceBadge(
    isOnline: Boolean,
    label: String,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isOnline) {
        OnlineGreen.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isOnline) {
        OnlineGreen
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(contentColor)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun HelloSirWordmark(modifier: Modifier = Modifier) {
    val brandGradient = Brush.linearGradient(
        colors = listOf(
            Color(0xFF0D6E5E),
            Color(0xFF19947E),
            Color(0xFFD96133)
        )
    )
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(brandGradient),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "HS",
                color = Color.White,
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }
        Text(
            text = "Hello Sir",
            style = TextStyle(
                brush = brandGradient,
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                letterSpacing = (-0.5).sp
            )
        )
    }
}

@Composable
fun ActiveCallBanner(
    callState: CallUiState,
    onReopenCall: () -> Unit,
    onToggleMute: () -> Unit,
    onHangUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val durationText = when (callState.status) {
        CallStatus.CONNECTED -> {
            val mins = callState.durationSeconds / 60L
            val secs = callState.durationSeconds % 60L
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
        CallStatus.OUTGOING_RINGING -> "Calling…"
        CallStatus.INCOMING_RINGING -> "Incoming call…"
        CallStatus.CONNECTING -> "Connecting…"
        CallStatus.IDLE -> ""
    }

    Surface(
        color = Color(0xFF0D6E5E),
        contentColor = Color.White,
        shadowElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onReopenCall)
            .testTag("active_call_bar")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Call,
                contentDescription = null,
                tint = Color(0xFF86EFAC),
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = callState.remoteUserName.ifBlank { callState.remoteUserId },
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${if (callState.isVideoCall) "Video" else "Audio"} • $durationText • Tap to return",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
            FilledIconButton(
                onClick = onToggleMute,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (callState.isMuted) Color.White else Color.White.copy(alpha = 0.2f),
                    contentColor = if (callState.isMuted) Color(0xFF0D6E5E) else Color.White
                ),
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = if (callState.isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                    contentDescription = "Mute",
                    modifier = Modifier.size(18.dp)
                )
            }
            FilledIconButton(
                onClick = onHangUp,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color(0xFFDC2626),
                    contentColor = Color.White
                ),
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.CallEnd,
                    contentDescription = "Hang up",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun DailyShayariCard(
    shayari: DailyShayari,
    onNextShayari: () -> Unit,
    onCopyShayari: (DailyShayari) -> Unit,
    onShareToFeed: (DailyShayari) -> Unit,
    modifier: Modifier = Modifier
) {
    val gradient = Brush.linearGradient(
        colors = listOf(
            Color(0xFF0D6E5E),
            Color(0xFF134E4A),
            Color(0xFF7C2D12)
        )
    )
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradient)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = Color(0xFFFDE68A),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "आज की शायरी • ${shayari.mood}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFFDE68A),
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(
                    onClick = onNextShayari,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Next Shayari",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Text(
                text = shayari.hindiText,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                lineHeight = 24.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "— ${shayari.poet}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.78f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = { onCopyShayari(shayari) },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = "Copy Shayari",
                            tint = Color.White,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    IconButton(
                        onClick = { onShareToFeed(shayari) },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = "Post Shayari to Feed",
                            tint = Color.White,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BlockedAlertDialog(
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Block,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = stringResource(R.string.blocked_dialog_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier.testTag("blocked_dismiss_button")
            ) {
                Text(stringResource(R.string.blocked_dialog_action))
            }
        }
    )
}
