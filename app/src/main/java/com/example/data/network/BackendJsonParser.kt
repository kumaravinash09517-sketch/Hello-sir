package com.example.data.network

import com.example.data.model.BROADCAST_TARGET_ID
import com.example.data.model.ChatMessage
import com.example.data.model.ChatUser
import com.example.data.model.FeedPayload
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object BackendJsonParser {

    fun parseFeed(rawBody: String): FeedPayload {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return FeedPayload(emptyList(), emptyList())

        val parsed = runCatching { JSONTokener(trimmed).nextValue() }.getOrNull()
            ?: return FeedPayload(emptyList(), emptyList())

        val users = mutableListOf<ChatUser>()
        val messages = mutableListOf<ChatMessage>()

        when (parsed) {
            is JSONObject -> {
                val root = if (parsed.has("data") && parsed.opt("data") is JSONObject &&
                    !parsed.has("users") && !parsed.has("messages")
                ) {
                    parsed.getJSONObject("data")
                } else {
                    parsed
                }

                extractUsersFromContainer(root, users)
                extractMessagesFromContainer(root, messages)

                // Fallback: some backends return a flat object map of userId -> userObject
                if (users.isEmpty() && messages.isEmpty()) {
                    extractUsersFromFlatMap(root, users)
                }
            }

            is JSONArray -> {
                for (i in 0 until parsed.length()) {
                    val item = parsed.optJSONObject(i) ?: continue
                    if (looksLikeMessage(item)) {
                        parseMessageObject(item, index = i)?.let { messages.add(it) }
                    } else if (looksLikeUser(item)) {
                        parseUserObject(item, fallbackId = null)?.let { users.add(it) }
                    }
                }
            }
        }

        val dedupedUsers = users
            .filter { it.userId.isNotBlank() }
            .associateBy { it.userId }
            .values
            .toList()

        val dedupedMessages = messages
            .filter { it.text.isNotBlank() || it.sender.isNotBlank() }
            .distinctBy { it.dedupeKey }
            .sortedBy { it.timestampMs }

        return FeedPayload(users = dedupedUsers, messages = dedupedMessages)
    }

    private fun extractUsersFromContainer(root: JSONObject, out: MutableList<ChatUser>) {
        val userKeys = listOf("users", "user_list", "active_users", "registered_users", "members")
        for (key in userKeys) {
            if (!root.has(key) || root.isNull(key)) continue
            when (val value = root.opt(key)) {
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        val obj = value.optJSONObject(i) ?: continue
                        parseUserObject(obj, fallbackId = null)?.let { out.add(it) }
                    }
                    return
                }
                is JSONObject -> {
                    extractUsersFromFlatMap(value, out)
                    return
                }
            }
        }
    }

    private fun extractUsersFromFlatMap(mapObj: JSONObject, out: MutableList<ChatUser>) {
        val keys = mapObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in IGNORED_TOP_LEVEL_KEYS) continue
            when (val entry = mapObj.opt(key)) {
                is JSONObject -> {
                    if (looksLikeMessage(entry)) continue
                    parseUserObject(entry, fallbackId = key)?.let { out.add(it) }
                }
                is String -> {
                    if (key.isNotBlank() && entry.isNotBlank()) {
                        out.add(ChatUser(userId = key, name = entry))
                    }
                }
            }
        }
    }

    private fun extractMessagesFromContainer(root: JSONObject, out: MutableList<ChatMessage>) {
        val msgKeys = listOf("messages", "chats", "inbox", "msg_list", "history")
        for (key in msgKeys) {
            if (!root.has(key) || root.isNull(key)) continue
            when (val value = root.opt(key)) {
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        val obj = value.optJSONObject(i) ?: continue
                        parseMessageObject(obj, index = i)?.let { out.add(it) }
                    }
                    return
                }
                is JSONObject -> {
                    val keys = value.keys()
                    var idx = 0
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val obj = value.optJSONObject(k)
                        if (obj != null) {
                            parseMessageObject(obj, index = idx++, fallbackId = k)?.let { out.add(it) }
                        }
                    }
                    return
                }
            }
        }
    }

    private fun looksLikeMessage(obj: JSONObject): Boolean {
        return obj.has("text") || obj.has("message") || obj.has("content") ||
            obj.has("sender") || obj.has("receiver")
    }

    private fun looksLikeUser(obj: JSONObject): Boolean {
        return obj.has("user_id") || obj.has("userId") || obj.has("name") || obj.has("username")
    }

    private fun parseUserObject(obj: JSONObject, fallbackId: String?): ChatUser? {
        val userId = optStringFirst(obj, "user_id", "userId", "id", "uid")
            ?: fallbackId
            ?: return null
        val name = optStringFirst(obj, "name", "username", "display_name", "displayName")
            ?: userId

        val explicitOnline = when {
            obj.has("online") && !obj.isNull("online") -> optBooleanOrNull(obj, "online")
            obj.has("is_online") && !obj.isNull("is_online") -> optBooleanOrNull(obj, "is_online")
            obj.has("isOnline") && !obj.isNull("isOnline") -> optBooleanOrNull(obj, "isOnline")
            obj.has("active") && !obj.isNull("active") -> optBooleanOrNull(obj, "active")
            else -> null
        }

        val statusText = optStringFirst(obj, "status", "presence", "state")
        val lastSeenMs = parseTimestampFirst(
            obj,
            "last_seen", "lastSeen", "last_active", "lastActive", "updated_at", "timestamp", "time"
        )

        return ChatUser(
            userId = userId.trim(),
            name = name.trim(),
            lastSeenEpochMs = lastSeenMs,
            explicitOnline = explicitOnline,
            statusText = statusText
        )
    }

    private fun parseMessageObject(
        obj: JSONObject,
        index: Int,
        fallbackId: String? = null
    ): ChatMessage? {
        val text = optStringFirst(obj, "text", "message", "content", "body") ?: return null
        val sender = optStringFirst(obj, "sender", "from", "sender_id", "senderId", "user_id") ?: "unknown"
        val senderName = optStringFirst(obj, "sender_name", "senderName", "from_name")
        val receiver = optStringFirst(obj, "receiver", "to", "receiver_id", "receiverId", "recipient")
            ?: BROADCAST_TARGET_ID
        val timestampMs = parseTimestampFirst(
            obj,
            "timestamp", "created_at", "createdAt", "sent_at", "time", "date"
        ) ?: 0L
        val id = optStringFirst(obj, "id", "msg_id", "message_id", "_id")
            ?: fallbackId
            ?: "${sender}_${receiver}_${timestampMs}_${index}_${text.hashCode()}"

        return ChatMessage(
            id = id,
            sender = sender.trim(),
            senderName = senderName?.trim(),
            receiver = receiver.trim(),
            text = text,
            timestampMs = timestampMs
        )
    }

    private fun optStringFirst(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            if (obj.has(key) && !obj.isNull(key)) {
                val value = obj.optString(key, "").trim()
                if (value.isNotEmpty() && !value.equals("null", ignoreCase = true)) {
                    return value
                }
            }
        }
        return null
    }

    private fun optBooleanOrNull(obj: JSONObject, key: String): Boolean? {
        val raw = obj.opt(key) ?: return null
        return when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> when (raw.trim().lowercase(Locale.US)) {
                "true", "1", "yes", "online" -> true
                "false", "0", "no", "offline" -> false
                else -> null
            }
            else -> null
        }
    }

    private fun parseTimestampFirst(obj: JSONObject, vararg keys: String): Long? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val raw = obj.opt(key) ?: continue
            parseAnyTimestamp(raw)?.let { return it }
        }
        return null
    }

    fun parseAnyTimestamp(raw: Any): Long? {
        when (raw) {
            is Number -> {
                val num = raw.toDouble()
                if (num <= 0.0) return null
                return if (num < 10_000_000_000.0) (num * 1000.0).toLong() else num.toLong()
            }
            is String -> {
                val s = raw.trim()
                if (s.isEmpty()) return null
                s.toDoubleOrNull()?.let { num ->
                    if (num > 0.0) {
                        return if (num < 10_000_000_000.0) (num * 1000.0).toLong() else num.toLong()
                    }
                }
                for (pattern in DATE_PATTERNS) {
                    runCatching {
                        val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                            timeZone = TimeZone.getTimeZone("UTC")
                            isLenient = true
                        }
                        sdf.parse(s)?.time
                    }.getOrNull()?.let { return it }
                }
                // Try local timezone fallback for naive date strings
                for (pattern in LOCAL_DATE_PATTERNS) {
                    runCatching {
                        SimpleDateFormat(pattern, Locale.US).parse(s)?.time
                    }.getOrNull()?.let { return it }
                }
            }
        }
        return null
    }

    private val IGNORED_TOP_LEVEL_KEYS = setOf(
        "status", "ok", "success", "message", "error", "count", "total", "timestamp"
    )

    private val DATE_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd HH:mm:ss"
    )

    private val LOCAL_DATE_PATTERNS = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss"
    )
}
