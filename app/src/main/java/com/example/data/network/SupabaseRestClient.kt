package com.example.data.network

import com.example.data.model.ChatUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Clean Supabase PostgREST integration targeting:
 * https://odgacsgunlkkvgyqknmm.supabase.co/rest/v1
 * Automatically attaches apikey & Bearer headers when configured in BuildConfig
 * and degrades gracefully if keys or tables are not yet provisioned.
 */
class SupabaseRestClient(
    private val httpClient: OkHttpClient = RetrofitClient.externalHttpClient
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun isConfigured(): Boolean = BackendConfig.isSupabaseConfigured()

    suspend fun upsertUserProfile(
        userId: String,
        name: String,
        phone: String = "",
        deviceId: String = "",
        bio: String = "",
        avatarUrl: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        val key = BackendConfig.activeSupabaseKey()
        if (key.isEmpty()) return@withContext false

        val payload = JSONObject().apply {
            put("user_id", userId)
            put("name", name)
            if (phone.isNotBlank()) put("phone", phone)
            if (deviceId.isNotBlank()) put("device_id", deviceId)
            if (bio.isNotBlank()) put("bio", bio)
            if (avatarUrl.isNotBlank()) put("avatar_url", avatarUrl)
            put("last_seen", System.currentTimeMillis())
        }.toString()

        val req = Request.Builder()
            .url("${BackendConfig.SUPABASE_REST_URL}users?on_conflict=user_id")
            .header("apikey", key)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        runCatching {
            httpClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun fetchUsers(): List<ChatUser> = withContext(Dispatchers.IO) {
        val key = BackendConfig.activeSupabaseKey()
        if (key.isEmpty()) return@withContext emptyList()

        val req = Request.Builder()
            .url("${BackendConfig.SUPABASE_REST_URL}users?select=*")
            .header("apikey", key)
            .header("Authorization", "Bearer $key")
            .get()
            .build()

        runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val raw = resp.body?.string().orEmpty()
                parseSupabaseUsers(raw)
            }
        }.getOrDefault(emptyList())
    }

    suspend fun searchUsers(query: String): List<ChatUser> = withContext(Dispatchers.IO) {
        val key = BackendConfig.activeSupabaseKey()
        val clean = query.trim().removePrefix("@")
        if (key.isEmpty() || clean.isEmpty()) return@withContext emptyList()

        val encoded = android.net.Uri.encode("*$clean*")
        val url = "${BackendConfig.SUPABASE_REST_URL}users?select=*&or=(user_id.ilike.$encoded,name.ilike.$encoded,phone.ilike.$encoded)"
        val req = Request.Builder()
            .url(url)
            .header("apikey", key)
            .header("Authorization", "Bearer $key")
            .get()
            .build()

        runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                parseSupabaseUsers(resp.body?.string().orEmpty())
            }
        }.getOrDefault(emptyList())
    }

    suspend fun upsertSocialPost(post: com.example.data.model.SocialPost): Boolean = withContext(Dispatchers.IO) {
        val key = BackendConfig.activeSupabaseKey()
        if (key.isEmpty()) return@withContext false

        val payload = JSONObject().apply {
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
        }.toString()

        val req = Request.Builder()
            .url("${BackendConfig.SUPABASE_REST_URL}posts?on_conflict=id")
            .header("apikey", key)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        runCatching {
            httpClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun fetchSocialPosts(): List<com.example.data.model.SocialPost> = withContext(Dispatchers.IO) {
        val key = BackendConfig.activeSupabaseKey()
        if (key.isEmpty()) return@withContext emptyList()

        val req = Request.Builder()
            .url("${BackendConfig.SUPABASE_REST_URL}posts?select=*&order=timestamp_ms.desc&limit=100")
            .header("apikey", key)
            .header("Authorization", "Bearer $key")
            .get()
            .build()

        runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val raw = resp.body?.string().orEmpty()
                BackendJsonParser.parseFeed("""{"posts": $raw}""").posts
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        fun parseSupabaseUsers(raw: String): List<ChatUser> {
            if (raw.isBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                val out = mutableListOf<ChatUser>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val uid = obj.optString("user_id").ifBlank { obj.optString("id") }.trim()
                    if (uid.isBlank()) continue
                    val name = obj.optString("name").ifBlank { obj.optString("username") }.ifBlank { uid }.trim()
                    val lastSeen = BackendJsonParser.parseAnyTimestamp(obj.opt("last_seen") ?: 0L)
                    val avatar = obj.optString("avatar_url", "").takeIf { it.isNotBlank() }
                        ?.let { BackendConfig.resolveUploadUrl(it) }
                    out.add(
                        ChatUser(
                            userId = uid,
                            name = name,
                            lastSeenEpochMs = lastSeen,
                            avatarUrl = avatar,
                            bio = obj.optString("bio", "").takeIf { it.isNotBlank() },
                            phone = obj.optString("phone", "").takeIf { it.isNotBlank() }
                        )
                    )
                }
                out
            }.getOrDefault(emptyList())
        }
    }
}
