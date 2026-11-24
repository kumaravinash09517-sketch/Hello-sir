package com.example.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.UserSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private val Context.encryptedDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "hello_sir_locked_vault"
)

/**
 * Enforces strict single-device session locking using Android Keystore AES-256-GCM
 * encryption backed by Jetpack DataStore. Once locked, the device remains bound to
 * that single user until explicitly reset via the hidden Profile/Settings logout.
 */
class SessionManager(private val context: Context) {

    private val keyEncryptedSession = stringPreferencesKey("encrypted_session_blob")
    private val keyDeviceLockId = stringPreferencesKey("persistent_device_lock_id")
    private val keySessionLocked = booleanPreferencesKey("is_single_device_locked")
    private val keyNotifiedMsgIds = stringSetPreferencesKey("notified_message_ids")

    val sessionFlow: Flow<UserSession?> = context.encryptedDataStore.data.map { prefs ->
        val locked = prefs[keySessionLocked] ?: false
        val blob = prefs[keyEncryptedSession]
        if (!locked || blob.isNullOrBlank()) {
            null
        } else {
            decryptSession(blob)
        }
    }

    suspend fun getOrCreateDeviceLockId(): String {
        val existing = context.encryptedDataStore.data.first()[keyDeviceLockId]
        if (!existing.isNullOrBlank()) return existing
        val generated = "DEV-${UUID.randomUUID().toString().take(12).uppercase()}"
        context.encryptedDataStore.edit { prefs ->
            prefs[keyDeviceLockId] = generated
        }
        return generated
    }

    suspend fun isDeviceLocked(): Boolean {
        val prefs = context.encryptedDataStore.data.first()
        return (prefs[keySessionLocked] == true) && !prefs[keyEncryptedSession].isNullOrBlank()
    }

    suspend fun lockSession(userId: String, name: String, phone: String = ""): UserSession {
        val deviceLockId = getOrCreateDeviceLockId()
        val now = System.currentTimeMillis()
        val session = UserSession(
            userId = userId.trim(),
            name = name.trim(),
            phone = phone.trim(),
            deviceLockId = deviceLockId,
            lockedAtMs = now
        )
        val payload = JSONObject().apply {
            put("user_id", session.userId)
            put("name", session.name)
            put("phone", session.phone)
            put("device_lock_id", session.deviceLockId)
            put("locked_at_ms", session.lockedAtMs)
        }.toString()

        val encrypted = encryptString(payload)
        context.encryptedDataStore.edit { prefs ->
            prefs[keyEncryptedSession] = encrypted
            prefs[keySessionLocked] = true
        }
        return session
    }

    suspend fun resetSessionLock() {
        context.encryptedDataStore.edit { prefs ->
            prefs.remove(keyEncryptedSession)
            prefs[keySessionLocked] = false
        }
    }

    suspend fun getNotifiedMessageIds(): Set<String> {
        return context.encryptedDataStore.data.first()[keyNotifiedMsgIds] ?: emptySet()
    }

    suspend fun addNotifiedMessageIds(ids: Set<String>) {
        if (ids.isEmpty()) return
        context.encryptedDataStore.edit { prefs ->
            val current = prefs[keyNotifiedMsgIds] ?: emptySet()
            prefs[keyNotifiedMsgIds] = (current + ids).toList().takeLast(300).toSet()
        }
    }

    private fun encryptString(plainText: String): String {
        val secretKey = getOrCreateAesKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(1 + iv.size + encryptedBytes.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(encryptedBytes, 0, combined, 1 + iv.size, encryptedBytes.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decryptSession(encodedBlob: String): UserSession? {
        return runCatching {
            val combined = Base64.decode(encodedBlob, Base64.NO_WRAP)
            val ivLen = combined[0].toInt() and 0xFF
            val iv = combined.copyOfRange(1, 1 + ivLen)
            val cipherBytes = combined.copyOfRange(1 + ivLen, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateAesKey(), GCMParameterSpec(128, iv))
            val plain = String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
            val obj = JSONObject(plain)
            val uid = obj.optString("user_id")
            val name = obj.optString("name")
            if (uid.isBlank() || name.isBlank()) return@runCatching null
            UserSession(
                userId = uid,
                name = name,
                phone = obj.optString("phone", ""),
                deviceLockId = obj.optString("device_lock_id"),
                lockedAtMs = obj.optLong("locked_at_ms", 0L)
            )
        }.getOrNull()
    }

    private fun getOrCreateAesKey(): SecretKey {
        return runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) return@runCatching existing

            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            keyGenerator.generateKey()
        }.getOrElse {
            // Deterministic fallback key only when AndroidKeyStore is unavailable (e.g., JVM unit tests)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("${context.packageName}_hello_sir_vault".toByteArray(Charsets.UTF_8))
            SecretKeySpec(digest, "AES")
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "hello_sir_device_lock_aes_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
