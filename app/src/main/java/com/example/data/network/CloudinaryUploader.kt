package com.example.data.network

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

data class UploadedMediaResult(
    val remoteUrl: String,
    val source: String
)

private class ProgressBytesRequestBody(
    private val bytes: ByteArray,
    private val mediaType: MediaType?,
    private val onProgress: (Int) -> Unit
) : RequestBody() {
    override fun contentType(): MediaType? = mediaType
    override fun contentLength(): Long = bytes.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        val total = bytes.size.coerceAtLeast(1)
        var offset = 0
        val chunkSize = 16 * 1024
        var lastPct = -1
        while (offset < bytes.size) {
            val count = minOf(chunkSize, bytes.size - offset)
            sink.write(bytes, offset, count)
            offset += count
            val pct = ((offset * 98L) / total).toInt().coerceIn(0, 98)
            if (pct != lastPct) {
                lastPct = pct
                onProgress(pct)
            }
        }
    }
}

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
        mediaTypeTag: String = "media",
        onProgress: (Int) -> Unit = {}
    ): UploadedMediaResult? = withContext(Dispatchers.IO) {
        onProgress(2)
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext null

        onProgress(6)
        uploadBytes(
            bytes = bytes,
            fileName = fileName,
            mimeType = mimeType,
            userId = userId,
            mediaTypeTag = mediaTypeTag,
            onProgress = onProgress
        )
    }

    suspend fun uploadBytes(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        userId: String,
        mediaTypeTag: String = "media",
        onProgress: (Int) -> Unit = {}
    ): UploadedMediaResult? = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext null

        // 1. Try signed Cloudinary upload first if credentials are present via BuildConfig
        if (BackendConfig.isCloudinarySigningConfigured()) {
            uploadToCloudinary(bytes, fileName, mimeType, onProgress)?.let { cloudUrl ->
                onProgress(100)
                return@withContext UploadedMediaResult(cloudUrl, "cloudinary")
            }
        }

        // 2. Upload to live Render /api/media/upload and resolve https://rendar2-o9gq.onrender.com/uploads/...
        uploadToRender(bytes, fileName, mimeType, userId, mediaTypeTag, onProgress)?.let { renderUrl ->
            onProgress(100)
            return@withContext UploadedMediaResult(renderUrl, "render")
        }

        null
    }

    private fun uploadToCloudinary(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        onProgress: (Int) -> Unit
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

        val fileBody = ProgressBytesRequestBody(
            bytes = bytes,
            mediaType = mimeType.ifBlank { "application/octet-stream" }.toMediaTypeOrNull(),
            onProgress = onProgress
        )

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName.ifBlank { "upload.bin" }, fileBody)
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
        mediaTypeTag: String,
        onProgress: (Int) -> Unit
    ): String? {
        val safeName = fileName.ifBlank { "file_${System.currentTimeMillis()}" }
        val fileBody = ProgressBytesRequestBody(
            bytes = bytes,
            mediaType = mimeType.ifBlank { "application/octet-stream" }.toMediaTypeOrNull(),
            onProgress = onProgress
        )
        val filePart = MultipartBody.Part.createFormData("file", safeName, fileBody)
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
