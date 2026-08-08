package com.musornibak.pocketjarvis.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Клиент к orion-brain на Termux: POST audio → {transcript, reply}. */
class OrionClient(private val baseUrl: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    data class Result(val transcript: String, val reply: String, val error: String? = null)

    suspend fun voice(wavBytes: ByteArray, language: String = "ru"): Result =
        withContext(Dispatchers.IO) {
            val url = baseUrl.trimEnd('/') + "/voice"
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("language", language)
                .addFormDataPart(
                    "file", "audio.wav",
                    wavBytes.toRequestBody("audio/wav".toMediaType())
                )
                .build()
            val req = Request.Builder().url(url).post(body).build()
            runCatching {
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        return@use Result("", "", "HTTP ${resp.code}: ${text.take(200)}")
                    }
                    val obj = json.parseToJsonElement(text).jsonObject
                    Result(
                        transcript = obj["transcript"]?.jsonPrimitive?.content.orEmpty(),
                        reply = obj["reply"]?.jsonPrimitive?.content.orEmpty(),
                    )
                }
            }.getOrElse { Result("", "", "NET: ${it.javaClass.simpleName}: ${it.message}") }
        }
}
