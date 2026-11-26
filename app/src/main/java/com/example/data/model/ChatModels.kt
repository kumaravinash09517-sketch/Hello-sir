package com.example.data.model

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

const val BROADCAST_TARGET_ID = "all"
const val BLOCKED_MESSAGE = "You are blocked by the administrator."
private const val ONLINE_WINDOW_MS = 5 * 60 * 1000L

data class UserSession(
    val userId: String,
    val name: String,
    val phone: String = "",
    val deviceLockId: String = "",
    val lockedAtMs: Long = 0L
)

enum class MessageDeliveryStatus {
    PENDING,
    SENT,
    DELIVERED,
    READ
}

enum class MessageKind {
    TEXT,
    LOCATION,
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT
}

data class LocationPayload(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float = 0f,
    val isLive: Boolean = false,
    val expiresAtMs: Long = 0L,
    val label: String = "Shared Location"
)

data class MediaPayload(
    val kind: MessageKind,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val localUri: String = "",
    val inlineDataBase64: String = ""
) {
    fun formattedSize(): String {
        if (sizeBytes <= 0L) return ""
        return when {
            sizeBytes < 1024L -> "$sizeBytes B"
            sizeBytes < 1024L * 1024L -> "${sizeBytes / 1024L} KB"
            else -> String.format(Locale.US, "%.1f MB", sizeBytes / (1024.0 * 1024.0))
        }
    }
}

data class ChatUser(
    val userId: String,
    val name: String,
    val lastSeenEpochMs: Long? = null,
    val explicitOnline: Boolean? = null,
    val statusText: String? = null,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val phone: String? = null
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
    val timestampMs: Long,
    val kind: MessageKind = MessageKind.TEXT,
    val deliveryStatus: MessageDeliveryStatus = MessageDeliveryStatus.DELIVERED,
    val location: LocationPayload? = null,
    val media: MediaPayload? = null
) {
    val isBroadcast: Boolean
        get() = receiver.isBlank() ||
            receiver.equals("all", ignoreCase = true) ||
            receiver.equals("broadcast", ignoreCase = true) ||
            receiver.equals("global", ignoreCase = true) ||
            receiver.equals("everyone", ignoreCase = true)

    fun threadIdFor(currentUserId: String): String {
        return if (isBroadcast) {
            BROADCAST_TARGET_ID
        } else if (sender.equals(currentUserId, ignoreCase = true)) {
            receiver
        } else {
            sender
        }
    }

    fun previewSummary(): String {
        return when (kind) {
            MessageKind.TEXT -> text
            MessageKind.LOCATION -> if (location?.isLive == true) "📍 Live Location" else "📍 Location Pin"
            MessageKind.IMAGE -> "🖼️ Photo${media?.displayName?.let { " • $it" }.orEmpty()}"
            MessageKind.VIDEO -> "🎬 Video${media?.displayName?.let { " • $it" }.orEmpty()}"
            MessageKind.AUDIO -> "🎵 Audio${media?.displayName?.let { " • $it" }.orEmpty()}"
            MessageKind.DOCUMENT -> "📄 ${media?.displayName ?: "Document"}"
        }
    }

    fun formattedTime(): String {
        if (timestampMs <= 0L) return ""
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestampMs))
    }

    val dedupeKey: String
        get() = if (id.isNotBlank() && !id.startsWith("local_")) id else "${sender}|${receiver}|${text}|${timestampMs}"
}

data class SignalingEnvelope(
    val signalId: String,
    val type: String, // offer, answer, ice, call_end, call_reject, read_receipt
    val sender: String,
    val senderName: String? = null,
    val receiver: String,
    val sdp: String? = null,
    val isVideo: Boolean = false,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int = 0,
    val timestampMs: Long = System.currentTimeMillis()
)

// ==================== Instagram-Style Social Media Models ====================

enum class SocialMediaType {
    PHOTO,
    REEL,
    QUOTE
}

data class SocialPost(
    val id: String,
    val authorId: String,
    val authorName: String,
    val mediaType: SocialMediaType,
    val mediaUri: String = "",
    val inlineImageBase64: String = "",
    val caption: String,
    val shayariTag: String = "",
    val timestampMs: Long,
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val shareCount: Int = 0,
    val reachCount: Int = 1,
    val isLikedByMe: Boolean = false,
    val isBookmarkedByMe: Boolean = false,
    val isRepost: Boolean = false,
    val repostedFromName: String? = null
) {
    fun formattedRelativeTime(nowMs: Long = System.currentTimeMillis()): String {
        val diffSec = ((nowMs - timestampMs) / 1000L).coerceAtLeast(1L)
        return when {
            diffSec < 60L -> "Just now"
            diffSec < 3600L -> "${diffSec / 60L}m"
            diffSec < 86400L -> "${diffSec / 3600L}h"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestampMs))
        }
    }
}

data class SocialComment(
    val id: String,
    val postId: String,
    val authorId: String,
    val authorName: String,
    val text: String,
    val timestampMs: Long
)

data class UserProfileExtras(
    val userId: String,
    val bio: String = "Connecting live on Hello Sir",
    val followersCount: Int = 0,
    val followingCount: Int = 0,
    val isFollowedByMe: Boolean = false
)

enum class ActivityType {
    LIKE,
    COMMENT,
    FOLLOW,
    REPOST
}

data class ActivityNotification(
    val id: String,
    val type: ActivityType,
    val actorId: String,
    val actorName: String,
    val postId: String? = null,
    val postSnippet: String = "",
    val timestampMs: Long,
    val isRead: Boolean = false
)

data class SocialActionEnvelope(
    val actionId: String,
    val actionType: String, // like, unlike, comment, follow, unfollow, remove_follower, block, unblock, repost, profile_bio
    val actorId: String,
    val actorName: String,
    val targetUserId: String = "",
    val postId: String = "",
    val payloadText: String = "",
    val timestampMs: Long = System.currentTimeMillis()
)

data class UploadProgressState(
    val label: String,
    val percent: Int
)

data class SocialRelation(
    val followerId: String,
    val followingId: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class BlockedUser(
    val userId: String,
    val name: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class DailyShayari(
    val id: String,
    val hindiText: String,
    val poet: String,
    val mood: String
)

object DailyShayariProvider {
    private val collection = listOf(
        DailyShayari(
            id = "s1",
            hindiText = "खुद से जीतने की ज़िद है, मुझे खुद को ही हराना है;\nमैं भीड़ नहीं हूँ दुनिया की, मेरे अंदर एक ज़माना है।",
            poet = "Hello Sir Daily",
            mood = "प्रेरणा"
        ),
        DailyShayari(
            id = "s2",
            hindiText = "सफ़र में धूप तो होगी जो चल सको तो चलो,\nसभी हैं भीड़ में तुम भी निकल सको तो चलो।",
            poet = "निदा फ़ाज़ली",
            mood = "सफ़र"
        ),
        DailyShayari(
            id = "s3",
            hindiText = "मंज़िलें उन्हें नहीं मिलती जिनके ख़्वाब बड़े होते हैं,\nबल्कि उन्हें मिलती हैं जो ज़िद पर अड़े होते हैं।",
            poet = "Hello Sir Daily",
            mood = "हौसला"
        ),
        DailyShayari(
            id = "s4",
            hindiText = "वक़्त से लड़कर जो नसीब बदल दे,\nइंसान वही जो अपनी तक़दीर बदल दे।",
            poet = "Hello Sir Daily",
            mood = "जज़्बा"
        ),
        DailyShayari(
            id = "s5",
            hindiText = "मुस्कराने की वजह न ढूँढो वरना ज़िन्दगी यूँ ही कट जाएगी,\nकभी बेवजह भी मुस्करा कर देखो साथ में शाम भी खिल जाएगी।",
            poet = "Hello Sir Daily",
            mood = "सुकून"
        ),
        DailyShayari(
            id = "s6",
            hindiText = "हज़ारों ख़्वाहिशें ऐसी कि हर ख़्वाहिश पे दम निकले,\nबहुत निकले मेरे अरमान लेकिन फिर भी कम निकले।",
            poet = "मिर्ज़ा ग़ालिब",
            mood = "क्लासिक"
        ),
        DailyShayari(
            id = "s7",
            hindiText = "दिल से निकली दुआ है हमारी,\nज़िन्दगी में मिले खुशियाँ आपको सारी।",
            poet = "Hello Sir Daily",
            mood = "दोस्ती"
        )
    )

    fun shayariForToday(offset: Int = 0): DailyShayari {
        val dayOfYear = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val idx = ((dayOfYear + offset) % collection.size + collection.size) % collection.size
        return collection[idx]
    }
}

data class FeedPayload(
    val users: List<ChatUser>,
    val messages: List<ChatMessage>,
    val signals: List<SignalingEnvelope> = emptyList(),
    val posts: List<SocialPost> = emptyList(),
    val socialActions: List<SocialActionEnvelope> = emptyList()
)

sealed interface NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>
    data class Blocked(val message: String = BLOCKED_MESSAGE) : NetworkResult<Nothing>
    data class Error(val message: String) : NetworkResult<Nothing>
}
