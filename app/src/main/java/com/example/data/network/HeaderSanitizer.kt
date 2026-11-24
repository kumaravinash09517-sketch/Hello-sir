package com.example.data.network

import okhttp3.Interceptor
import okhttp3.Response
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Strict HTTP header safety guard ensuring non-ASCII characters (e.g. Devanagari/Hindi
 * names/IDs like "अविनाश", emojis, or control characters) never cause OkHttp
 * IllegalArgumentException crashes in X-User-Id or any other HTTP header.
 */
object HeaderSanitizer {

    fun sanitizeHeaderValue(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val code = ch.code
            when {
                // OkHttp allows '\t' (0x09) and printable US-ASCII 0x20..0x7E
                code == 0x09 || code in 0x20..0x7E -> sb.append(ch)
                code == 0x0A || code == 0x0D -> sb.append(' ')
                else -> {
                    val encoded = URLEncoder.encode(ch.toString(), StandardCharsets.UTF_8.name())
                    sb.append(encoded)
                }
            }
        }
        return sb.toString().trim()
    }

    fun sanitizeHeaderName(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val code = ch.code
            if (code in 0x21..0x7E) sb.append(ch)
        }
        return sb.toString().ifEmpty { "X-Custom" }
    }
}

class SafeHeaderInterceptor(
    private val userIdProvider: () -> String?
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        // Re-write existing headers through strict ASCII sanitizer
        val existingHeaders = original.headers
        for (i in 0 until existingHeaders.size) {
            val name = existingHeaders.name(i)
            val safeValue = HeaderSanitizer.sanitizeHeaderValue(existingHeaders.value(i))
            builder.header(HeaderSanitizer.sanitizeHeaderName(name), safeValue)
        }

        // Always attach sanitized X-User-Id if a session is active
        userIdProvider()?.takeIf { it.isNotBlank() }?.let { uid ->
            builder.header("X-User-Id", HeaderSanitizer.sanitizeHeaderValue(uid))
        }

        return chain.proceed(builder.build())
    }
}
