package com.example.data.network

import com.example.data.model.BROADCAST_TARGET_ID
import com.example.data.model.ChatMessage
import com.example.data.model.ChatUser
import com.example.data.model.FeedPayload
import com.example.data.model.LocationPayload
import com.example.data.model.MediaPayload
import com.example.data.model.MessageDeliveryStatus
import com.example.data.model.MessageKind
import com.example.data.model.SignalingEnvelope
import com.example.data.model.SocialActionEnvelope
import com.example.data.model.SocialMediaType
import com.example.data.model.SocialPost
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.webrtc.PeerConnection
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object BackendJsonParser {

    const val PREFIX_SIGNAL = "[HS_SIG]"
    const val PREFIX_LOCATION = "[HS_LOC]"
    const val PREFIX_MEDIA = "[HS_MEDIA]"
    const val PREFIX_POST = "[HS_POST]"
    const val PREFIX_SOCIAL_ACTION = "[HS_SOC]"

    fun encodeSignal(envelope: SignalingEnvelope): String {
        val obj = JSONObject().apply {
            put("signal_id", envelope.signalId)
            put("type", envelope.type)
            put("sender", envelope.sender)
            put("sender_id", envelope.sender)
            envelope.senderName?.let { put("sender_name", it) }
            put("receiver", envelope.receiver)
            put("receiver_id", envelope.receiver)
            envelope.sdp?.let { put("sdp", it) }
            put("is_video", envelope.isVideo)
            envelope.candidate?.let { put("candidate", it) }
            envelope.sdpMid?.let { put("sdp_mid", it) }
            put("sdp_m_line_index", envelope.sdpMLineIndex)
            put("timestamp_ms", envelope.timestampMs)
        }
        return "$PREFIX_SIGNAL$obj"
    }

    fun encodeLocation(location: LocationPayload): String {
        val obj = JSONObject().apply {
            put("lat", location.latitude)
            put("lng", location.longitude)
            put("acc", location.accuracyMeters.toDouble())
            put("live", location.isLive)
            put("expires_at", location.expiresAtMs)
            put("label", location.label)
        }
        return "$PREFIX_LOCATION$obj"
    }

    fun encodeMedia(media: MediaPayload): String {
        val obj = JSONObject().apply {
            put("kind", media.kind.name)
            put("name", media.displayName)
            put("mime", media.mimeType)
            put("size", media.sizeBytes)
            if (media.localUri.isNotBlank()) put("uri", media.localUri)
            if (media.inlineDataBase64.isNotBlank()) put("b64", media.inlineDataBase64)
        }
        return "$PREFIX_MEDIA$obj"
    }

    fun encodePost(post: SocialPost): String {
        val obj = JSONObject().apply {
            put("id", post.id)
            put("author_id", post.authorId)
            put("author_name", post.authorName)
            put("media_type", post.mediaType.name)
            if (post.mediaUri.isNotBlank()) put("media_uri", post.mediaUri)
            if (post.inlineImageBase64.isNotBlank()) put("b64", post.inlineImageBase64)
            put("caption", post.caption)
            if (post.shayariTag.isNotBlank()) put("shayari_tag", post.shayariTag)
            put("timestamp_ms", post.timestampMs)
            put("is_repost", post.isRepost)
            post.repostedFromName?.let { put("repost_from", it) }
        }
        return "$PREFIX_POST$obj"
    }

    fun encodeSocialAction(action: SocialActionEnvelope): String {
        val obj = JSONObject().apply {
            put("action_id", action.actionId)
            put("action_type", action.actionType)
            put("actor_id", action.actorId)
            put("actor_name", action.actorName)
            put("target_user_id", action.targetUserId)
            put("post_id", action.postId)
            put("payload_text", action.payloadText)
            put("timestamp_ms", action.timestampMs)
        }
        return "$PREFIX_SOCIAL_ACTION$obj"
    }

    fun tryParseSignalJson(raw: String): SignalingEnvelope? {
        val jsonPart = when {
            raw.startsWith(PREFIX_SIGNAL) -> raw.removePrefix(PREFIX_SIGNAL).trim()
            raw.trim().startsWith("{") -> raw.trim()
            else -> return null
        }
        return runCatching {
            parseSignalObject(JSONObject(jsonPart))
        }.getOrNull()
    }

    private fun parseSignalObject(obj: JSONObject): SignalingEnvelope? {
        val rawType = optStringFirst(obj, "type", "signal_type", "event") ?: return null
        val normalizedType = rawType.lowercase(Locale.US)
        val sender = optStringFirst(obj, "sender", "sender_id", "senderId", "caller_id", "from")
            ?: return null
        val receiver = optStringFirst(obj, "receiver", "receiver_id", "receiverId", "callee_id", "to")
            ?: return null
        val timestampMs = parseTimestampFirst(
            obj,
            "timestamp_ms", "timestampMs", "timestamp", "created_at", "createdAt"
        ) ?: System.currentTimeMillis()
        val isVideo = when {
            obj.has("is_video") -> optBooleanOrNull(obj, "is_video") ?: false
            obj.has("isVideo") -> optBooleanOrNull(obj, "isVideo") ?: false
            else -> optStringFirst(obj, "call_type", "media_type")
                ?.equals("video", ignoreCase = true) ?: false
        }
        val signalId = optStringFirst(obj, "signal_id", "signalId", "id", "call_id")
            ?: "${normalizedType}_${sender}_${timestampMs}"

        return SignalingEnvelope(
            signalId = signalId,
            type = normalizedType,
            sender = sender,
            senderName = optStringFirst(obj, "sender_name", "senderName", "caller_name"),
            receiver = receiver,
            sdp = optStringFirst(obj, "sdp", "offer", "answer"),
            isVideo = isVideo,
            candidate = optStringFirst(obj, "candidate"),
            sdpMid = optStringFirst(obj, "sdp_mid", "sdpMid"),
            sdpMLineIndex = if (obj.has("sdp_m_line_index")) {
                obj.optInt("sdp_m_line_index", 0)
            } else {
                obj.optInt("sdpMLineIndex", 0)
            },
            timestampMs = timestampMs
        )
    }

    fun parseCallSignals(rawBody: String): List<SignalingEnvelope> {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return emptyList()
        val parsed = runCatching { JSONTokener(trimmed).nextValue() }.getOrNull() ?: return emptyList()
        val out = mutableListOf<SignalingEnvelope>()

        when (parsed) {
            is JSONArray -> {
                for (i in 0 until parsed.length()) {
                    parsed.optJSONObject(i)?.let { parseSignalObject(it) }?.let { out.add(it) }
                }
            }
            is JSONObject -> {
                val arrayKeys = listOf("signals", "calls", "active_calls", "activeCalls", "data", "items")
                var foundArray = false
                for (key in arrayKeys) {
                    val arr = parsed.optJSONArray(key) ?: continue
                    foundArray = true
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { parseSignalObject(it) }?.let { out.add(it) }
                    }
                }
                if (!foundArray) {
                    val nestedObj = parsed.optJSONObject("signal") ?: parsed.optJSONObject("call") ?: parsed
                    parseSignalObject(nestedObj)?.let { out.add(it) }
                }
            }
        }
        return out.distinctBy { it.signalId }
    }

    fun parseIceServers(rawBody: String): List<PeerConnection.IceServer> {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return emptyList()
        val parsed = runCatching { JSONTokener(trimmed).nextValue() }.getOrNull() ?: return emptyList()

        val array = when (parsed) {
            is JSONArray -> parsed
            is JSONObject -> parsed.optJSONArray("iceServers")
                ?: parsed.optJSONArray("ice_servers")
                ?: parsed.optJSONArray("servers")
                ?: parsed.optJSONObject("data")?.optJSONArray("iceServers")
                ?: parsed.optJSONObject("data")?.optJSONArray("ice_servers")
            else -> null
        } ?: return emptyList()

        val out = mutableListOf<PeerConnection.IceServer>()
        for (i in 0 until array.length()) {
            val item = array.opt(i)
            when (item) {
                is String -> {
                    if (item.isNotBlank()) {
                        out.add(PeerConnection.IceServer.builder(item.trim()).createIceServer())
                    }
                }
                is JSONObject -> {
                    val urlsList = mutableListOf<String>()
                    item.optJSONArray("urls")?.let { arr ->
                        for (j in 0 until arr.length()) {
                            arr.optString(j, "").trim().takeIf { it.isNotEmpty() }?.let { urlsList.add(it) }
                        }
                    }
                    if (urlsList.isEmpty()) {
                        optStringFirst(item, "urls", "url")?.let { urlsList.add(it) }
                    }
                    if (urlsList.isEmpty()) continue
                    val builder = PeerConnection.IceServer.builder(urlsList)
                    optStringFirst(item, "username")?.let { builder.setUsername(it) }
                    optStringFirst(item, "credential", "password")?.let { builder.setPassword(it) }
                    out.add(builder.createIceServer())
                }
            }
        }
        return out
    }

    fun parseUsers(rawBody: String): List<ChatUser> {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return emptyList()
        val parsed = runCatching { JSONTokener(trimmed).nextValue() }.getOrNull() ?: return emptyList()
        val users = mutableListOf<ChatUser>()

        when (parsed) {
            is JSONArray -> {
                for (i in 0 until parsed.length()) {
                    val obj = parsed.optJSONObject(i) ?: continue
                    parseUserObject(obj, fallbackId = null)?.let { users.add(it) }
                }
            }
            is JSONObject -> {
                val root = if (parsed.has("data") && parsed.opt("data") is JSONObject &&
                    !parsed.has("users") && !parsed.has("user")
                ) {
                    parsed.getJSONObject("data")
                } else {
                    parsed
                }
                extractUsersFromContainer(root, users)
                if (users.isEmpty()) {
                    root.optJSONObject("user")?.let { obj ->
                        parseUserObject(obj, fallbackId = null)?.let { users.add(it) }
                    }
                }
                if (users.isEmpty() && looksLikeUser(root)) {
                    parseUserObject(root, fallbackId = null)?.let { users.add(it) }
                }
                if (users.isEmpty()) {
                    extractUsersFromFlatMap(root, users)
                }
            }
        }
        return users
            .filter { it.userId.isNotBlank() }
            .associateBy { it.userId }
            .values
            .toList()
    }

    fun parseFeed(rawBody: String): FeedPayload {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return FeedPayload(emptyList(), emptyList())

        val parsed = runCatching { JSONTokener(trimmed).nextValue() }.getOrNull()
            ?: return FeedPayload(emptyList(), emptyList())

        val users = mutableListOf<ChatUser>()
        val messages = mutableListOf<ChatMessage>()
        val signals = mutableListOf<SignalingEnvelope>()
        val posts = mutableListOf<SocialPost>()
        val socialActions = mutableListOf<SocialActionEnvelope>()

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
                extractMessagesFromContainer(root, messages, signals, posts, socialActions)
                extractPostsFromContainer(root, posts)

                // Handle { "data": [ ... ] }
                if (messages.isEmpty() && users.isEmpty()) {
                    parsed.optJSONArray("data")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            if (looksLikeMessage(item)) {
                                parseMessageOrEnvelope(item, i, null, messages, signals, posts, socialActions)
                            } else if (looksLikeUser(item)) {
                                parseUserObject(item, fallbackId = null)?.let { users.add(it) }
                            }
                        }
                    }
                }

                if (users.isEmpty() && messages.isEmpty() && signals.isEmpty() && posts.isEmpty()) {
                    extractUsersFromFlatMap(root, users)
                }
            }

            is JSONArray -> {
                for (i in 0 until parsed.length()) {
                    val item = parsed.optJSONObject(i) ?: continue
                    if (looksLikeMessage(item)) {
                        parseMessageOrEnvelope(item, i, null, messages, signals, posts, socialActions)
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
            .filter { it.text.isNotBlank() || it.sender.isNotBlank() || it.media != null || it.location != null }
            .distinctBy { it.dedupeKey }
            .sortedBy { it.timestampMs }

        return FeedPayload(
            users = dedupedUsers,
            messages = dedupedMessages,
            signals = signals.distinctBy { it.signalId },
            posts = posts.distinctBy { it.id }.sortedByDescending { it.timestampMs },
            socialActions = socialActions.distinctBy { it.actionId }
        )
    }

    private fun extractUsersFromContainer(root: JSONObject, out: MutableList<ChatUser>) {
        val userKeys = listOf("users", "results", "user_list", "active_users", "registered_users", "members")
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

    private fun extractMessagesFromContainer(
        root: JSONObject,
        messages: MutableList<ChatMessage>,
        signals: MutableList<SignalingEnvelope>,
        posts: MutableList<SocialPost>,
        socialActions: MutableList<SocialActionEnvelope>
    ) {
        val msgKeys = listOf("messages", "chats", "inbox", "msg_list", "history")
        for (key in msgKeys) {
            if (!root.has(key) || root.isNull(key)) continue
            when (val value = root.opt(key)) {
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        val obj = value.optJSONObject(i) ?: continue
                        parseMessageOrEnvelope(obj, i, null, messages, signals, posts, socialActions)
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
                            parseMessageOrEnvelope(obj, idx++, k, messages, signals, posts, socialActions)
                        }
                    }
                    return
                }
            }
        }
    }

    private fun extractPostsFromContainer(root: JSONObject, posts: MutableList<SocialPost>) {
        val keys = listOf("posts", "feed", "reels")
        for (key in keys) {
            val arr = root.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                parseSocialPostObject(obj)?.let { posts.add(it) }
            }
        }
    }

    private fun parseSocialPostObject(obj: JSONObject): SocialPost? {
        val id = optStringFirst(obj, "id", "post_id") ?: return null
        val authorId = optStringFirst(obj, "author_id", "user_id", "sender_id", "sender") ?: return null
        val authorName = optStringFirst(obj, "author_name", "name") ?: authorId
        val mediaType = runCatching {
            SocialMediaType.valueOf(obj.optString("media_type", "PHOTO").uppercase(Locale.US))
        }.getOrDefault(SocialMediaType.PHOTO)
        val resolvedMediaUri = optStringFirst(obj, "media_uri", "media_url", "mediaUrl")
            ?.let { BackendConfig.resolveUploadUrl(it) }
            .orEmpty()
        return SocialPost(
            id = id,
            authorId = authorId,
            authorName = authorName,
            mediaType = mediaType,
            mediaUri = resolvedMediaUri,
            inlineImageBase64 = obj.optString("b64", ""),
            caption = obj.optString("caption", ""),
            shayariTag = obj.optString("shayari_tag", ""),
            timestampMs = obj.optLong("timestamp_ms", System.currentTimeMillis()),
            likeCount = obj.optInt("likes", 0),
            commentCount = obj.optInt("comments", 0),
            shareCount = obj.optInt("shares", 0),
            reachCount = obj.optInt("reach", 1),
            isRepost = obj.optBoolean("is_repost", false),
            repostedFromName = optStringFirst(obj, "repost_from")
        )
    }

    private fun looksLikeMessage(obj: JSONObject): Boolean {
        return obj.has("text") || obj.has("message") || obj.has("content") ||
            obj.has("sender") || obj.has("sender_id") || obj.has("senderId") ||
            obj.has("receiver") || obj.has("receiver_id") || obj.has("receiverId") ||
            obj.has("media_url") || obj.has("mediaUrl") || obj.has("latitude")
    }

    private fun looksLikeUser(obj: JSONObject): Boolean {
        return obj.has("user_id") || obj.has("userId") || obj.has("name") ||
            obj.has("username") || obj.has("phone")
    }

    private fun parseUserObject(obj: JSONObject, fallbackId: String?): ChatUser? {
        val userId = optStringFirst(obj, "user_id", "userId", "username", "id", "uid")
            ?: fallbackId
            ?: return null
        val name = optStringFirst(obj, "name", "display_name", "displayName", "username")
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
        val avatarUrl = optStringFirst(obj, "avatar_url", "avatarUrl", "avatar", "photo_url")
            ?.let { BackendConfig.resolveUploadUrl(it) }
        val bio = optStringFirst(obj, "bio", "about")
        val phone = optStringFirst(obj, "phone", "phone_number", "mobile")

        return ChatUser(
            userId = userId.trim(),
            name = name.trim(),
            lastSeenEpochMs = lastSeenMs,
            explicitOnline = explicitOnline,
            statusText = statusText,
            avatarUrl = avatarUrl,
            bio = bio,
            phone = phone
        )
    }

    private fun parseMessageOrEnvelope(
        obj: JSONObject,
        index: Int,
        fallbackId: String? = null,
        messages: MutableList<ChatMessage>,
        signals: MutableList<SignalingEnvelope>,
        posts: MutableList<SocialPost>,
        socialActions: MutableList<SocialActionEnvelope>
    ) {
        val rawText = optStringFirst(obj, "text", "message", "content", "body").orEmpty()
        val mediaUrlRaw = optStringFirst(obj, "media_url", "mediaUrl", "file_url", "fileUrl")
        val msgTypeRaw = optStringFirst(obj, "msg_type", "msgType", "type")
        val hasLatLng = obj.has("latitude") && !obj.isNull("latitude") &&
            obj.has("longitude") && !obj.isNull("longitude")

        if (rawText.isEmpty() && mediaUrlRaw == null && !hasLatLng) return

        val sender = optStringFirst(obj, "sender", "sender_id", "senderId", "from", "user_id") ?: "unknown"
        val senderName = optStringFirst(obj, "sender_name", "senderName", "from_name")
        val receiver = optStringFirst(obj, "receiver", "receiver_id", "receiverId", "to", "recipient")
            ?: BROADCAST_TARGET_ID
        val timestampMs = parseTimestampFirst(
            obj,
            "timestamp", "timestamp_ms", "created_at", "createdAt", "sent_at", "time", "date"
        ) ?: 0L
        val id = optStringFirst(obj, "id", "msg_id", "message_id", "_id")
            ?: fallbackId
            ?: "${sender}_${receiver}_${timestampMs}_${index}_${rawText.hashCode()}"

        if (rawText.startsWith(PREFIX_SIGNAL)) {
            tryParseSignalJson(rawText)?.let { sig ->
                signals.add(
                    sig.copy(
                        sender = sig.sender.ifBlank { sender.trim() },
                        receiver = sig.receiver.ifBlank { receiver.trim() },
                        senderName = sig.senderName ?: senderName?.trim()
                    )
                )
            }
            return
        }

        if (rawText.startsWith(PREFIX_POST)) {
            runCatching {
                parseSocialPostObject(JSONObject(rawText.removePrefix(PREFIX_POST).trim()))
            }.getOrNull()?.let { posts.add(it) }
            return
        }

        if (rawText.startsWith(PREFIX_SOCIAL_ACTION)) {
            runCatching {
                val aObj = JSONObject(rawText.removePrefix(PREFIX_SOCIAL_ACTION).trim())
                SocialActionEnvelope(
                    actionId = aObj.optString("action_id"),
                    actionType = aObj.optString("action_type"),
                    actorId = aObj.optString("actor_id", sender.trim()),
                    actorName = aObj.optString("actor_name", senderName ?: sender.trim()),
                    targetUserId = aObj.optString("target_user_id", ""),
                    postId = aObj.optString("post_id", ""),
                    payloadText = aObj.optString("payload_text", ""),
                    timestampMs = aObj.optLong("timestamp_ms", timestampMs)
                )
            }.getOrNull()?.takeIf { it.actionId.isNotBlank() }?.let { socialActions.add(it) }
            return
        }

        var kind = MessageKind.TEXT
        var displayText = rawText
        var locationPayload: LocationPayload? = null
        var mediaPayload: MediaPayload? = null

        if (rawText.startsWith(PREFIX_LOCATION)) {
            val parsedLoc = runCatching {
                val locJson = JSONObject(rawText.removePrefix(PREFIX_LOCATION).trim())
                LocationPayload(
                    latitude = locJson.getDouble("lat"),
                    longitude = locJson.getDouble("lng"),
                    accuracyMeters = locJson.optDouble("acc", 0.0).toFloat(),
                    isLive = locJson.optBoolean("live", false),
                    expiresAtMs = locJson.optLong("expires_at", 0L),
                    label = locJson.optString("label", "Shared Location")
                )
            }.getOrNull()
            if (parsedLoc != null) {
                kind = MessageKind.LOCATION
                locationPayload = parsedLoc
                displayText = if (parsedLoc.isLive) "Live Location" else "Shared Location"
            }
        } else if (rawText.startsWith(PREFIX_MEDIA)) {
            val parsedMedia = runCatching {
                val mJson = JSONObject(rawText.removePrefix(PREFIX_MEDIA).trim())
                val mKind = runCatching {
                    MessageKind.valueOf(mJson.optString("kind", "DOCUMENT"))
                }.getOrDefault(MessageKind.DOCUMENT)
                val resolvedUri = BackendConfig.resolveUploadUrl(mJson.optString("uri", ""))
                MediaPayload(
                    kind = mKind,
                    displayName = mJson.optString("name", "Attachment"),
                    mimeType = mJson.optString("mime", "*/*"),
                    sizeBytes = mJson.optLong("size", 0L),
                    localUri = resolvedUri,
                    inlineDataBase64 = mJson.optString("b64", "")
                )
            }.getOrNull()
            if (parsedMedia != null) {
                kind = parsedMedia.kind
                mediaPayload = parsedMedia
                displayText = parsedMedia.displayName
            }
        } else if (hasLatLng) {
            val lat = obj.optDouble("latitude", 0.0)
            val lng = obj.optDouble("longitude", 0.0)
            val label = optStringFirst(obj, "location_label", "locationLabel") ?: "Shared Location"
            kind = MessageKind.LOCATION
            locationPayload = LocationPayload(
                latitude = lat,
                longitude = lng,
                isLive = label.contains("live", ignoreCase = true),
                label = label
            )
            displayText = rawText.ifBlank { label }
        } else if (mediaUrlRaw != null) {
            val resolvedUrl = BackendConfig.resolveUploadUrl(mediaUrlRaw)
            val mKind = inferMediaKind(msgTypeRaw, resolvedUrl)
            kind = mKind
            mediaPayload = MediaPayload(
                kind = mKind,
                displayName = rawText.ifBlank { resolvedUrl.substringAfterLast('/').ifBlank { "Attachment" } },
                mimeType = when (mKind) {
                    MessageKind.IMAGE -> "image/*"
                    MessageKind.VIDEO -> "video/*"
                    MessageKind.AUDIO -> "audio/*"
                    else -> "*/*"
                },
                sizeBytes = 0L,
                localUri = resolvedUrl
            )
            displayText = rawText.ifBlank { mediaPayload.displayName }
        }

        val isReadFlag = optBooleanOrNull(obj, "is_read") ?: optBooleanOrNull(obj, "isRead")
        val statusStr = optStringFirst(obj, "delivery_status", "read_status")
        val status = when {
            isReadFlag == true -> MessageDeliveryStatus.READ
            statusStr.equals("read", ignoreCase = true) -> MessageDeliveryStatus.READ
            statusStr.equals("sent", ignoreCase = true) -> MessageDeliveryStatus.SENT
            else -> MessageDeliveryStatus.DELIVERED
        }

        messages.add(
            ChatMessage(
                id = id,
                sender = sender.trim(),
                senderName = senderName?.trim(),
                receiver = receiver.trim(),
                text = displayText,
                timestampMs = timestampMs,
                kind = kind,
                deliveryStatus = status,
                location = locationPayload,
                media = mediaPayload
            )
        )
    }

    private fun inferMediaKind(msgType: String?, url: String): MessageKind {
        val t = msgType?.trim()?.lowercase(Locale.US).orEmpty()
        return when {
            t in setOf("image", "photo", "img") -> MessageKind.IMAGE
            t in setOf("video", "reel") -> MessageKind.VIDEO
            t in setOf("audio", "voice") -> MessageKind.AUDIO
            t in setOf("document", "file", "pdf") -> MessageKind.DOCUMENT
            url.endsWith(".jpg", true) || url.endsWith(".jpeg", true) ||
                url.endsWith(".png", true) || url.endsWith(".webp", true) -> MessageKind.IMAGE
            url.endsWith(".mp4", true) || url.endsWith(".webm", true) ||
                url.endsWith(".mov", true) -> MessageKind.VIDEO
            url.endsWith(".mp3", true) || url.endsWith(".m4a", true) ||
                url.endsWith(".ogg", true) -> MessageKind.AUDIO
            else -> MessageKind.DOCUMENT
        }
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
        if (!obj.has(key) || obj.isNull(key)) return null
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
