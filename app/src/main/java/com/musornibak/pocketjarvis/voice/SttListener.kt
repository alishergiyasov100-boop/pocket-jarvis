package com.musornibak.pocketjarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Hotword listener: непрерывный цикл, ищет "орион/orion" в partial results и триггерит.
 */

private val HOTWORD_PATTERNS = listOf(
    "орион", "ореон", "орлеон", "арион", "орьон", "орьён",
    "orion", "arion", "orean",
)

private fun looksLikeHotword(text: String): Boolean {
    val low = text.lowercase()
    return HOTWORD_PATTERNS.any { low.contains(it) }
}

class HotwordListener(
    private val ctx: Context,
    private val onWake: () -> Unit,
) {
    private var recog: SpeechRecognizer? = null
    private var running = false

    fun start() {
        if (running) return
        running = true
        launch()
    }

    fun stop() {
        running = false
        recog?.destroy()
        recog = null
    }

    private fun launch() {
        if (!running) return
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recog = r
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800)
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { restart() }
            override fun onResults(results: Bundle?) {
                match(results); restart()
            }
            override fun onPartialResults(partialResults: Bundle?) { match(partialResults) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        runCatching { r.startListening(intent) }
    }

    private fun match(bundle: Bundle?) {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        if (list.any { looksLikeHotword(it) }) {
            running = false
            recog?.destroy(); recog = null
            onWake()
        }
    }

    private fun restart() {
        recog?.destroy(); recog = null
        if (!running) return
        android.os.Handler(ctx.mainLooper).postDelayed({ launch() }, 250)
    }
}
