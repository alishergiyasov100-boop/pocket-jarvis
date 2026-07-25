package com.musornibak.pocketjarvis.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.ds by preferencesDataStore("jarvis")

object Keys {
    val OSA_URL = stringPreferencesKey("osa_url")           // e.g. http://10.0.0.5:8000/v1
    val OSA_TOKEN = stringPreferencesKey("osa_token")
    val OSA_MODEL = stringPreferencesKey("osa_model")       // claude-sonnet-5
    val ELEVEN_KEY = stringPreferencesKey("eleven_key")
    val ELEVEN_VOICE = stringPreferencesKey("eleven_voice") // voice_id of Kuon
    val VOICE_ON = stringPreferencesKey("voice_on")         // "1" / "0"
}

class Settings(private val ctx: Context) {
    fun get(k: Preferences.Key<String>, default: String = ""): Flow<String> =
        ctx.ds.data.map { it[k] ?: default }

    suspend fun set(k: Preferences.Key<String>, v: String) {
        ctx.ds.edit { it[k] = v }
    }
}
