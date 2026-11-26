package com.example.data.network

import com.example.BuildConfig

/**
 * Centralized public backend & service endpoints for Hello Sir.
 * Sensitive API keys/secrets are injected at build time via BuildConfig (.env / AI Studio Secrets).
 */
object BackendConfig {
    const val RENDER_BASE_URL = "https://rendar2-o9gq.onrender.com/"
    const val API_BASE_URL = "https://rendar2-o9gq.onrender.com/api/"
    const val UPLOADS_BASE_URL = "https://rendar2-o9gq.onrender.com/uploads/"
    const val WS_URL = "wss://rendar2-o9gq.onrender.com/ws"

    const val SUPABASE_PROJECT_REF = "odgacsgunlkkvgyqknmm"
    const val SUPABASE_BASE_URL = "https://odgacsgunlkkvgyqknmm.supabase.co"
    const val SUPABASE_REST_URL = "https://odgacsgunlkkvgyqknmm.supabase.co/rest/v1/"

    const val CLOUDINARY_CLOUD_NAME = "dvjhguyzo"
    const val CLOUDINARY_UPLOAD_URL = "https://api.cloudinary.com/v1_1/$CLOUDINARY_CLOUD_NAME/auto/upload"

    val supabasePublishableKey: String
        get() = sanitizeConfigValue(BuildConfig.SUPABASE_PUBLISHABLE_KEY)

    val supabaseSecretKey: String
        get() = sanitizeConfigValue(BuildConfig.SUPABASE_SECRET_KEY)

    val cloudinaryApiKey: String
        get() = sanitizeConfigValue(BuildConfig.CLOUDINARY_API_KEY)

    val cloudinaryApiSecret: String
        get() = sanitizeConfigValue(BuildConfig.CLOUDINARY_API_SECRET)

    fun isCloudinarySigningConfigured(): Boolean {
        return cloudinaryApiKey.isNotEmpty() && cloudinaryApiSecret.isNotEmpty()
    }

    fun activeSupabaseKey(): String {
        return supabasePublishableKey.ifEmpty { supabaseSecretKey }
    }

    fun isSupabaseConfigured(): Boolean = activeSupabaseKey().isNotEmpty()

    fun resolveUploadUrl(rawPathOrUrl: String?): String {
        val raw = rawPathOrUrl?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val lower = raw.lowercase()
        if (lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("content://") ||
            lower.startsWith("file://") ||
            lower.startsWith("data:")
        ) {
            return raw
        }
        val cleanPath = raw.trimStart('/')
        return if (cleanPath.startsWith("uploads/")) {
            "${RENDER_BASE_URL}$cleanPath"
        } else {
            "${UPLOADS_BASE_URL}$cleanPath"
        }
    }

    fun deriveVideoThumbnailUrl(videoUrl: String?): String {
        val raw = videoUrl?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val lower = raw.lowercase()
        if (lower.contains("res.cloudinary.com/") && lower.contains("/video/upload/")) {
            val withPoster = raw.replaceFirst("/video/upload/", "/video/upload/so_0,w_480,c_limit/")
            val queryIdx = withPoster.indexOf('?')
            val basePart = if (queryIdx >= 0) withPoster.substring(0, queryIdx) else withPoster
            val dotIdx = basePart.lastIndexOf('.')
            val slashIdx = basePart.lastIndexOf('/')
            return if (dotIdx > slashIdx) {
                "${basePart.substring(0, dotIdx)}.jpg"
            } else {
                "$basePart.jpg"
            }
        }
        return ""
    }

    private fun sanitizeConfigValue(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("YOUR_") || trimmed.startsWith("MY_") || trimmed.endsWith("...")) {
            return ""
        }
        return trimmed
    }
}
