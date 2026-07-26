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
    val OSA_URL = stringPreferencesKey("osa_url")
    val OSA_TOKEN = stringPreferencesKey("osa_token")
    val OSA_MODEL = stringPreferencesKey("osa_model")
    val FISH_KEY = stringPreferencesKey("fish_key")
    val FISH_VOICE = stringPreferencesKey("fish_voice")
    val VOICE_ON = stringPreferencesKey("voice_on")
}

class Settings(private val ctx: Context) {
    fun get(k: Preferences.Key<String>, default: String = ""): Flow<String> =
        ctx.ds.data.map { it[k] ?: default }

    suspend fun set(k: Preferences.Key<String>, v: String) {
        ctx.ds.edit { it[k] = v }
    }
}
