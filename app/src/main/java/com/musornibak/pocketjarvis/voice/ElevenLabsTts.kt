package com.musornibak.pocketjarvis.voice

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ElevenLabs streaming TTS → mp3 в temp-файл → MediaPlayer.
 * Не true-streaming (нет chunk-плеера), но простой и работает.
 */
class ElevenLabsTts(
    private val ctx: Context,
    private val apiKey: String,
    private val voiceId: String,
    private val modelId: String = "eleven_multilingual_v2",
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun speak(text: String) = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || voiceId.isBlank() || text.isBlank()) return@withContext
        val body = """
            {"text": ${quote(text)}, "model_id": "$modelId",
             "voice_settings": {"stability": 0.4, "similarity_boost": 0.75}}
        """.trimIndent()
        val req = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
            .header("xi-api-key", apiKey)
            .header("Accept", "audio/mpeg")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) {
            resp.close(); return@withContext
        }
        val tmp = File.createTempFile("kuon", ".mp3", ctx.cacheDir)
        resp.body!!.byteStream().use { input ->
            tmp.outputStream().use { out -> input.copyTo(out) }
        }
        playBlocking(tmp)
        tmp.delete()
    }

    private suspend fun playBlocking(file: File) {
        val done = CompletableDeferred<Unit>()
        val mp = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { done.complete(Unit) }
            setOnErrorListener { _, _, _ -> done.complete(Unit); true }
            prepare()
            start()
        }
        try { done.await() } finally { runCatching { mp.release() } }
    }

    private fun quote(s: String): String =
        kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.serializer<String>(), s,
        )
}
