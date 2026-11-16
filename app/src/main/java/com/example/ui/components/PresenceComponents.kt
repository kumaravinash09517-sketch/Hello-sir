package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.OfflineGray
import com.example.ui.theme.OnlineGreen
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
    showPresenceDot: Boolean = true
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
