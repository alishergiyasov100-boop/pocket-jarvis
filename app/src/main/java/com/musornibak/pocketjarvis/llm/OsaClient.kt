package com.musornibak.pocketjarvis.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Anthropic-совместимый SSE клиент под OSA-роутер.
 * POST {baseUrl}/messages с stream:true, парсим content_block_delta.
 */
class OsaClient(
    private val baseUrl: String,
    private val token: String,
    private val model: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    fun stream(userText: String, systemPrompt: String? = null): Flow<String> = flow {
        val system = systemPrompt ?: DEFAULT_SYSTEM
        val body = """
            {
              "model": "$model",
              "max_tokens": 1024,
              "stream": true,
              "system": ${jsonString(system)},
              "messages": [
                {"role":"user","content": ${jsonString(userText)}}
              ]
            }
        """.trimIndent()

        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/messages")
            .apply {
                if (token.isNotBlank()) {
                    header("x-api-key", token)
                    header("Authorization", "Bearer $token")
                }
                header("anthropic-version", "2023-06-01")
                header("Content-Type", "application/json")
                header("Accept", "text/event-stream")
            }
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                emit("[OSA ${resp.code}: ${resp.body?.string()?.take(200) ?: ""}]")
                return@use
            }
            val reader = resp.body!!.source()
            while (!reader.exhausted()) {
                val line = reader.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                if (payload.isEmpty()) continue
                runCatching {
                    val obj = json.parseToJsonElement(payload).jsonObject
                    val type = obj["type"]?.jsonPrimitive?.content
                    if (type == "content_block_delta") {
                        val text = obj["delta"]?.jsonObject
                            ?.get("text")?.jsonPrimitive?.content
                        if (!text.isNullOrEmpty()) emit(text)
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun jsonString(s: String): String =
        Json.encodeToString(String.serializer(), s)

    companion object {
        private const val DEFAULT_SYSTEM =
            "Ты Orion — голосовой ассистент на телефоне пользователя. Отвечай коротко, " +
                "по-русски, без markdown. Максимум 2-3 предложения. Тон — спокойный, чуть " +
                "ироничный, как Джарвис у Тони Старка."
    }
}
