package com.musornibak.pocketjarvis.voice

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class FishAudioTts(
    private val ctx: Context,
    private val apiKey: String,
    private val referenceId: String,
    private val model: String = "s2.1-pro-free",
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun speak(text: String) = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || text.isBlank()) return@withContext
        val refField = if (referenceId.isBlank()) "" else ",\"reference_id\":${quote(referenceId)}"
        val body = """
            {"text":${quote(text)},"format":"mp3","mp3_bitrate":128,
             "latency":"normal","normalize":true$refField}
        """.trimIndent()
        val req = Request.Builder()
            .url("https://api.fish.audio/v1/tts")
            .header("Authorization", "Bearer $apiKey")
            .header("model", model)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) {
            resp.close(); return@withContext
        }
        val tmp = File.createTempFile("haku", ".mp3", ctx.cacheDir)
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
        Json.encodeToString(String.serializer(), s)
}
