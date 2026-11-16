package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.BROADCAST_TARGET_ID
import com.example.data.model.ChatMessage
import com.example.data.model.ChatUser
import com.example.ui.components.PresenceBadge
import com.example.ui.components.UserAvatar
import com.example.ui.theme.OnlineGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    currentUserId: String,
    targetId: String,
    users: List<ChatUser>,
    messages: List<ChatMessage>,
    isSending: Boolean,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onSelectTarget: (String) -> Unit,
    onSendMessage: (receiver: String, text: String, onSent: () -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    if (showBackButton) {
        BackHandler(onBack = onBack)
    }

    val isBroadcast = targetId == BROADCAST_TARGET_ID
    val targetUser = remember(users, targetId) {
        users.firstOrNull { it.userId.equals(targetId, ignoreCase = true) }
    }
    val userMap = remember(users) { users.associateBy { it.userId } }

    var draft by rememberSaveable(targetId) { mutableStateOf("") }
    var showTargetMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, targetId) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
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
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (isBroadcast) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Campaign,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(22.dp)
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
                            val displayName = targetUser?.name ?: targetId
                            val isOnline = targetUser?.isOnline() ?: false
                            UserAvatar(
                                initials = targetUser?.initials ?: targetId.take(2).uppercase(),
                                isOnline = isOnline,
                                seedKey = targetId,
                                size = 40.dp
                            )
                            Column {
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "@$targetId",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    }
                },
                actions = {
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = {
                            Text(
                                text = if (isBroadcast) {
                                    "Broadcast to everyone…"
                                } else {
                                    "Message ${targetUser?.name ?: targetId}…"
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
                            .size(52.dp)
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
                            "Start a conversation with ${targetUser?.name ?: targetId}"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (isBroadcast) {
                            "Send a message below to notify everyone online."
                        } else {
                            "Say \"Hello Sir\" to kick off the chat."
                        },
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
                        showSenderName = isBroadcast || !isMine
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    senderDisplayName: String,
    isMine: Boolean,
    showSenderName: Boolean
) {
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
    val timeColor = if (isMine) {
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
            modifier = Modifier.widthIn(max = 310.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (showSenderName && !isMine) {
                    Text(
                        text = senderDisplayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor
                )
                val timeStr = message.formattedTime()
                if (timeStr.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = timeStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = timeColor,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    }
}
