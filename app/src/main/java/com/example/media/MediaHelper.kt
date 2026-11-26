package com.example.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import com.example.data.model.MediaPayload
import com.example.data.model.MessageKind
import com.example.data.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

object MediaHelper {

    fun inspectPickedUri(
        context: Context,
        uri: Uri,
        forcedKind: MessageKind? = null
    ): MediaPayload {
        val resolver = context.contentResolver
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val mimeType = resolver.getType(uri) ?: when (forcedKind) {
            MessageKind.IMAGE -> "image/*"
            MessageKind.VIDEO -> "video/*"
            MessageKind.AUDIO -> "audio/*"
            else -> "application/octet-stream"
        }

        var displayName = "Attachment"
        var sizeBytes = 0L

        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) {
                        cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        sizeBytes = cursor.getLong(sizeIdx)
                    }
                }
            }

        val kind = forcedKind ?: when {
            mimeType.startsWith("image/") -> MessageKind.IMAGE
            mimeType.startsWith("video/") -> MessageKind.VIDEO
            mimeType.startsWith("audio/") -> MessageKind.AUDIO
            else -> MessageKind.DOCUMENT
        }

        val inlineBase64 = when (kind) {
            MessageKind.IMAGE -> encodeCompactImageThumbnail(context, uri)
            MessageKind.VIDEO -> encodeCompactVideoThumbnail(context, uri)
            else -> ""
        }

        return MediaPayload(
            kind = kind,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            localUri = uri.toString(),
            inlineDataBase64 = inlineBase64
        )
    }

    private fun encodeCompactVideoThumbnail(context: Context, uri: Uri): String {
        return runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val frame = retriever.getFrameAtTime(
                    500_000L,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: retriever.frameAtTime ?: return@runCatching ""
                val maxDim = 220
                val ratio = minOf(
                    maxDim.toFloat() / frame.width.coerceAtLeast(1),
                    maxDim.toFloat() / frame.height.coerceAtLeast(1),
                    1f
                )
                val scaled = if (ratio < 1f) {
                    Bitmap.createScaledBitmap(
                        frame,
                        (frame.width * ratio).toInt().coerceAtLeast(1),
                        (frame.height * ratio).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    frame
                }
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 50, out)
                if (scaled != frame) scaled.recycle()
                frame.recycle()
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrDefault("")
    }

    private fun encodeCompactImageThumbnail(context: Context, uri: Uri): String {
        return runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

            var sampleSize = 1
            val maxDim = 320
            while (bounds.outWidth / sampleSize > maxDim * 2 || bounds.outHeight / sampleSize > maxDim * 2) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return@runCatching ""

            val ratio = minOf(
                maxDim.toFloat() / bitmap.width.coerceAtLeast(1),
                maxDim.toFloat() / bitmap.height.coerceAtLeast(1),
                1f
            )
            val scaled = if (ratio < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 65, out)
            if (scaled != bitmap) scaled.recycle()
            bitmap.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrDefault("")
    }

    fun openMediaUri(context: Context, uriString: String, mimeType: String) {
        if (uriString.isBlank()) return
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uriString), mimeType.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun openLocationInMaps(context: Context, latitude: Double, longitude: Double, label: String) {
        val geoUri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude(${Uri.encode(label)})")
        val mapIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching {
            context.startActivity(mapIntent)
            true
        }.getOrDefault(false)

        if (!launched) {
            val webUri = Uri.parse("https://maps.google.com/?q=$latitude,$longitude")
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, webUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
    }

    suspend fun downloadVideoToGallery(
        context: Context,
        urlOrUri: String,
        title: String = "HelloSir_Reel"
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanSource = urlOrUri.trim()
            require(cleanSource.isNotBlank()) { "Video source is missing." }

            val safeNameBase = title
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .trim('_')
                .ifBlank { "HelloSir_Reel" }
                .take(40)
            val fileName = "${safeNameBase}_${System.currentTimeMillis()}.mp4"

            fun copyStream(input: InputStream, output: OutputStream) {
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
                output.flush()
            }

            fun writeIntoOutput(output: OutputStream) {
                if (cleanSource.startsWith("http://", true) || cleanSource.startsWith("https://", true)) {
                    val req = Request.Builder().url(cleanSource).get().build()
                    RetrofitClient.externalHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw IllegalStateException("Download failed (HTTP ${resp.code})")
                        }
                        val bodyStream = resp.body?.byteStream()
                            ?: throw IllegalStateException("Empty video response.")
                        bodyStream.use { input -> copyStream(input, output) }
                    }
                } else {
                    val parsed = Uri.parse(cleanSource)
                    val input = context.contentResolver.openInputStream(parsed)
                        ?: throw IllegalStateException("Could not read local video.")
                    input.use { stream -> copyStream(stream, output) }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/HelloSir")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val itemUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Unable to create MediaStore video entry.")
                try {
                    resolver.openOutputStream(itemUri)?.use { out ->
                        writeIntoOutput(out)
                    } ?: throw IllegalStateException("Unable to open gallery stream.")
                    val doneValues = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    resolver.update(itemUri, doneValues, null, null)
                    "Saved to Movies/HelloSir/$fileName"
                } catch (t: Throwable) {
                    runCatching { resolver.delete(itemUri, null, null) }
                    throw t
                }
            } else {
                @Suppress("DEPRECATION")
                val moviesDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "HelloSir"
                ).apply { if (!exists()) mkdirs() }
                val destFile = File(moviesDir, fileName)
                destFile.outputStream().use { out -> writeIntoOutput(out) }
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(destFile.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )
                "Saved to Movies/HelloSir/$fileName"
            }
        }
    }
}
