package com.example.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val BROADCAST_TARGET_ID = "all"
const val BLOCKED_MESSAGE = "You are blocked by the administrator."
private const val ONLINE_WINDOW_MS = 5 * 60 * 1000L

data class UserSession(
    val userId: String,
    val name: String
)

data class ChatUser(
    val userId: String,
    val name: String,
    val lastSeenEpochMs: Long? = null,
    val explicitOnline: Boolean? = null,
    val statusText: String? = null
) {
    fun isOnline(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (explicitOnline != null) return explicitOnline
        if (!statusText.isNullOrBlank()) {
            val normalized = statusText.trim().lowercase(Locale.US)
            if (normalized == "online" || normalized == "active") return true
            if (normalized == "offline" || normalized == "inactive") return false
        }
        if (lastSeenEpochMs != null) {
            return (nowMs - lastSeenEpochMs) <= ONLINE_WINDOW_MS
        }
        return true
    }

    fun presenceLabel(nowMs: Long = System.currentTimeMillis()): String {
        if (isOnline(nowMs)) return "Online"
        val ts = lastSeenEpochMs ?: return "Offline"
        val diffMin = ((nowMs - ts) / 60_000L).coerceAtLeast(1L)
        return when {
            diffMin < 60L -> "${diffMin}m ago"
            diffMin < 24L * 60L -> "${diffMin / 60L}h ago"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ts))
        }
    }

    val initials: String
        get() {
            val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return when {
                parts.size >= 2 -> "${parts[0].first().uppercaseChar()}${parts[1].first().uppercaseChar()}"
                parts.size == 1 -> parts[0].take(2).uppercase(Locale.getDefault())
                userId.isNotBlank() -> userId.take(2).uppercase(Locale.getDefault())
                else -> "?"
            }
        }
}

data class ChatMessage(
    val id: String,
    val sender: String,
    val senderName: String? = null,
    val receiver: String,
    val text: String,
    val timestampMs: Long
) {
    val isBroadcast: Boolean
        get() = receiver.isBlank() ||
            receiver.equals("all", ignoreCase = true) ||
            receiver.equals("broadcast", ignoreCase = true) ||
            receiver.equals("global", ignoreCase = true) ||
            receiver.equals("everyone", ignoreCase = true)

    fun formattedTime(): String {
        if (timestampMs <= 0L) return ""
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestampMs))
    }

    val dedupeKey: String
        get() = if (id.isNotBlank()) id else "${sender}|${receiver}|${text}|${timestampMs}"
}

data class FeedPayload(
    val users: List<ChatUser>,
    val messages: List<ChatMessage>
)

sealed interface NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>
    data class Blocked(val message: String = BLOCKED_MESSAGE) : NetworkResult<Nothing>
    data class Error(val message: String) : NetworkResult<Nothing>
}
