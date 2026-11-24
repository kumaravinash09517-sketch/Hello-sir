package com.example.data.network

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

data class UploadedMediaResult(
    val remoteUrl: String,
    val source: String
)

/**
 * Handles media uploads for images, videos, reels, stories, avatars, and chat attachments.
 * Uses Cloudinary signed upload when API Key & Secret are configured in BuildConfig,
 * and Render /api/media/upload (resolving /uploads/<filename>) as primary/fallback storage.
 */
class MediaUploadService(
    private val context: Context,
    private val apiService: ApiService = RetrofitClient.apiService,
    private val httpClient: OkHttpClient = RetrofitClient.externalHttpClient
) {

    suspend fun uploadUri(
        uri: Uri,
        fileName: String,
        mimeType: String,
        userId: String,
        mediaTypeTag: String = "media"
    ): UploadedMediaResult? = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext null

        uploadBytes(
            bytes = bytes,
            fileName = fileName,
            mimeType = mimeType,
            userId = userId,
            mediaTypeTag = mediaTypeTag
        )
    }

    suspend fun uploadBytes(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        userId: String,
        mediaTypeTag: String = "media"
    ): UploadedMediaResult? = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext null

        // 1. Try signed Cloudinary upload first if credentials are present via BuildConfig
        if (BackendConfig.isCloudinarySigningConfigured()) {
            uploadToCloudinary(bytes, fileName, mimeType)?.let { cloudUrl ->
                return@withContext UploadedMediaResult(cloudUrl, "cloudinary")
            }
        }

        // 2. Upload to live Render /api/media/upload and resolve https://rendar2-o9gq.onrender.com/uploads/...
        uploadToRender(bytes, fileName, mimeType, userId, mediaTypeTag)?.let { renderUrl ->
            return@withContext UploadedMediaResult(renderUrl, "render")
        }

        null
    }

    private fun uploadToCloudinary(
        bytes: ByteArray,
        fileName: String,
        mimeType: String
    ): String? {
        val apiKey = BackendConfig.cloudinaryApiKey
        val apiSecret = BackendConfig.cloudinaryApiSecret
        if (apiKey.isEmpty() || apiSecret.isEmpty()) return null

        val timestampSec = (System.currentTimeMillis() / 1000L).toString()
        val folder = "hello_sir"
        val signature = computeCloudinarySignature(
            params = mapOf("folder" to folder, "timestamp" to timestampSec),
            apiSecret = apiSecret
        )

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                fileName.ifBlank { "upload.bin" },
                bytes.toRequestBody(mimeType.ifBlank { "application/octet-stream" }.toMediaTypeOrNull())
            )
            .addFormDataPart("api_key", apiKey)
            .addFormDataPart("timestamp", timestampSec)
            .addFormDataPart("folder", folder)
            .addFormDataPart("signature", signature)
            .build()

        val request = Request.Builder()
            .url(BackendConfig.CLOUDINARY_UPLOAD_URL)
            .post(requestBody)
            .build()

        return runCatching {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                json.optString("secure_url")
                    .takeIf { it.isNotBlank() }
                    ?: json.optString("url").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private suspend fun uploadToRender(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        userId: String,
        mediaTypeTag: String
    ): String? {
        val safeName = fileName.ifBlank { "file_${System.currentTimeMillis()}" }
        val filePart = MultipartBody.Part.createFormData(
            "file",
            safeName,
            bytes.toRequestBody(mimeType.ifBlank { "application/octet-stream" }.toMediaTypeOrNull())
        )
        val userPart = userId.toRequestBody("text/plain".toMediaTypeOrNull())
        val typePart = mediaTypeTag.toRequestBody("text/plain".toMediaTypeOrNull())

        return runCatching {
            val response = apiService.uploadMedia(filePart, userPart, typePart)
            if (!response.isSuccessful) return@runCatching null
            val raw = response.body()?.string().orEmpty()
            parseRenderUploadResponse(raw)
        }.getOrNull()
    }

    companion object {
        fun computeCloudinarySignature(params: Map<String, String>, apiSecret: String): String {
            val sortedParams = params.entries
                .filter { it.value.isNotBlank() }
                .sortedBy { it.key }
                .joinToString("&") { "${it.key}=${it.value}" }
            val toSign = "$sortedParams$apiSecret"
            val digest = MessageDigest.getInstance("SHA-1").digest(toSign.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { byte ->
                String.format(Locale.US, "%02x", byte.toInt() and 0xFF)
            }
        }

        fun parseRenderUploadResponse(rawBody: String): String? {
            if (rawBody.isBlank()) return null
            return runCatching {
                val obj = JSONObject(rawBody)
                val root = obj.optJSONObject("data") ?: obj
                val candidate = listOf("secure_url", "media_url", "mediaUrl", "url", "path", "filename")
                    .firstNotNullOfOrNull { key ->
                        root.optString(key, "").trim().takeIf { it.isNotBlank() && !it.equals("null", true) }
                    }
                candidate?.let { BackendConfig.resolveUploadUrl(it) }
            }.getOrNull()
        }
    }
}
