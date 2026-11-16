package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PeopleOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.BROADCAST_TARGET_ID
import com.example.data.model.ChatUser
import com.example.ui.ChatUiState
import com.example.ui.components.PresenceBadge
import com.example.ui.components.UserAvatar
import com.example.ui.theme.OnlineGreen

@Composable
fun HomeScreen(
    uiState: ChatUiState,
    notificationsEnabled: Boolean,
    onRequestNotifications: () -> Unit,
    onRefresh: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectTarget: (String?) -> Unit,
    onSendMessage: (receiver: String, text: String, onSent: () -> Unit) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentSession = uiState.session ?: return

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isTwoPane = maxWidth >= 720.dp

        if (isTwoPane) {
            Row(modifier = Modifier.fillMaxSize()) {
                UserDirectoryPane(
                    uiState = uiState,
                    currentUserId = currentSession.userId,
                    currentUserName = currentSession.name,
                    notificationsEnabled = notificationsEnabled,
                    onRequestNotifications = onRequestNotifications,
                    onRefresh = onRefresh,
                    onSearchQueryChange = onSearchQueryChange,
                    onSelectTarget = { onSelectTarget(it) },
                    onSignOut = onSignOut,
                    modifier = Modifier
                        .width(360.dp)
                        .fillMaxHeight()
                )
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                )
                val activeTarget = uiState.selectedTargetId ?: BROADCAST_TARGET_ID
                ChatScreen(
                    currentUserId = currentSession.userId,
                    targetId = activeTarget,
                    users = uiState.users,
                    messages = uiState.messagesForTarget(activeTarget),
                    isSending = uiState.isSending,
                    showBackButton = false,
                    onBack = { onSelectTarget(null) },
                    onSelectTarget = { onSelectTarget(it) },
                    onSendMessage = onSendMessage,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            val activeTarget = uiState.selectedTargetId
            if (activeTarget != null) {
                ChatScreen(
                    currentUserId = currentSession.userId,
                    targetId = activeTarget,
                    users = uiState.users,
                    messages = uiState.messagesForTarget(activeTarget),
                    isSending = uiState.isSending,
                    showBackButton = true,
                    onBack = { onSelectTarget(null) },
                    onSelectTarget = { onSelectTarget(it) },
                    onSendMessage = onSendMessage
                )
            } else {
                UserDirectoryPane(
                    uiState = uiState,
                    currentUserId = currentSession.userId,
                    currentUserName = currentSession.name,
                    notificationsEnabled = notificationsEnabled,
                    onRequestNotifications = onRequestNotifications,
                    onRefresh = onRefresh,
                    onSearchQueryChange = onSearchQueryChange,
                    onSelectTarget = { onSelectTarget(it) },
                    onSignOut = onSignOut,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserDirectoryPane(
    uiState: ChatUiState,
    currentUserId: String,
    currentUserName: String,
    notificationsEnabled: Boolean,
    onRequestNotifications: () -> Unit,
    onRefresh: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectTarget: (String) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val broadcastPreview = uiState.latestPreviewForTarget(BROADCAST_TARGET_ID)

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "$currentUserName (@$currentUserId)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onRequestNotifications,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("notifications_button")
                    ) {
                        Icon(
                            imageVector = if (notificationsEnabled) {
                                Icons.Rounded.NotificationsActive
                            } else {
                                Icons.Rounded.NotificationsOff
                            },
                            contentDescription = stringResource(R.string.cd_notifications),
                            tint = if (notificationsEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("refresh_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh)
                        )
                    }
                    IconButton(
                        onClick = onSignOut,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("sign_out_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Logout,
                            contentDescription = stringResource(R.string.cd_sign_out)
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
                .navigationBarsPadding()
        ) {
            if (uiState.isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            AnimatedVisibility(visible = !uiState.syncError.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.WifiOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = uiState.syncError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Presence summary & Search bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.title_active_users),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = OnlineGreen.copy(alpha = 0.12f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(OnlineGreen)
                            )
                            Text(
                                text = "${uiState.onlineCount} Online • ${uiState.users.size} Total",
                                style = MaterialTheme.typography.labelMedium,
                                color = OnlineGreen,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Search users by name or ID…") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("user_search_input")
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("users_list"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "global_broadcast") {
                    GlobalBroadcastCard(
                        selected = uiState.selectedTargetId == BROADCAST_TARGET_ID,
                        latestText = broadcastPreview?.let { "${it.sender}: ${it.text}" }
                            ?: stringResource(R.string.subtitle_global_broadcast),
                        onClick = { onSelectTarget(BROADCAST_TARGET_ID) }
                    )
                }

                if (uiState.filteredUsers.isEmpty()) {
                    item(key = "empty_users") {
                        EmptyUsersPlaceholder(
                            hasSearch = uiState.searchQuery.isNotBlank(),
                            onRefresh = onRefresh
                        )
                    }
                } else {
                    items(
                        items = uiState.filteredUsers,
                        key = { it.userId }
                    ) { user ->
                        val isMe = user.userId.equals(currentUserId, ignoreCase = true)
                        val latestMsg = uiState.latestPreviewForTarget(user.userId)
                        UserRowCard(
                            user = user,
                            isMe = isMe,
                            isSelected = uiState.selectedTargetId.equals(user.userId, ignoreCase = true),
                            previewText = latestMsg?.text,
                            onClick = { onSelectTarget(user.userId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GlobalBroadcastCard(
    selected: Boolean,
    latestText: String,
    onClick: () -> Unit
) {
    val gradientBrush = Brush.horizontalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.78f)
        )
    )

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("global_broadcast_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradientBrush)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Campaign,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.title_global_broadcast),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = latestText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun UserRowCard(
    user: ChatUser,
    isMe: Boolean,
    isSelected: Boolean,
    previewText: String?,
    onClick: () -> Unit
) {
    val isOnline = user.isOnline()
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("user_item_${user.userId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            UserAvatar(
                initials = user.initials,
                isOnline = isOnline,
                seedKey = user.userId,
                size = 48.dp
            )

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (isMe) "${user.name} (You)" else user.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    PresenceBadge(
                        isOnline = isOnline,
                        label = user.presenceLabel()
                    )
                }

                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text = previewText ?: "@${user.userId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyUsersPlaceholder(
    hasSearch: Boolean,
    onRefresh: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.PeopleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = if (hasSearch) "No matching users" else "No registered users loaded yet",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = if (hasSearch) {
                    "Try searching with a different name or ID."
                } else {
                    "Pull fresh status from the server or send a Global Broadcast above."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!hasSearch) {
                OutlinedButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.action_refresh))
                }
            }
        }
    }
}
