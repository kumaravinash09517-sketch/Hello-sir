package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.UserSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "hello_sir_prefs")

class SessionStore(private val context: Context) {

    private val keyUserId = stringPreferencesKey("user_id")
    private val keyUserName = stringPreferencesKey("user_name")
    private val keyNotifiedMsgIds = stringSetPreferencesKey("notified_message_ids")

    val sessionFlow: Flow<UserSession?> = context.dataStore.data.map { prefs ->
        val uid = prefs[keyUserId]
        val name = prefs[keyUserName]
        if (!uid.isNullOrBlank() && !name.isNullOrBlank()) {
            UserSession(userId = uid, name = name)
        } else {
            null
        }
    }

    suspend fun saveSession(userId: String, name: String) {
        context.dataStore.edit { prefs ->
            prefs[keyUserId] = userId
            prefs[keyUserName] = name
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit { prefs ->
            prefs.remove(keyUserId)
            prefs.remove(keyUserName)
        }
    }

    suspend fun getNotifiedMessageIds(): Set<String> {
        return context.dataStore.data.first()[keyNotifiedMsgIds] ?: emptySet()
    }

    suspend fun addNotifiedMessageIds(ids: Set<String>) {
        if (ids.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[keyNotifiedMsgIds] ?: emptySet()
            val combined = (current + ids).toList().takeLast(250).toSet()
            prefs[keyNotifiedMsgIds] = combined
        }
    }
}
