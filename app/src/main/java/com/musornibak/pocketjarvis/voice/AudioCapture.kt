package com.musornibak.pocketjarvis.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

private const val SAMPLE_RATE = 16000
private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
private const val MAX_SECONDS = 8            // hard cap
private const val SILENCE_MS_TO_STOP = 1200  // конец после 1.2с тишины
private const val PREROLL_MS = 250           // минимум записи
private const val SILENCE_RMS = 350          // порог RMS для «тишины»

/**
 * Запись с микрофона до конца речи или до MAX_SECONDS. Возвращает WAV bytes (16kHz mono 16-bit).
 * VAD — простой RMS-порог; работает в тихой обстановке.
 */
@SuppressLint("MissingPermission")
suspend fun captureAudio(): ByteArray = withContext(Dispatchers.IO) {
    val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
    val bufSize = maxOf(minBuf, SAMPLE_RATE * 2 / 5) // ~200ms
    val rec = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        SAMPLE_RATE, CHANNEL, ENCODING, bufSize
    )
    val pcm = ByteArrayOutputStream()
    val chunk = ByteArray(bufSize)
    try {
        rec.startRecording()
        val startedAt = System.currentTimeMillis()
        var lastVoiceAt = startedAt
        while (true) {
            val n = rec.read(chunk, 0, chunk.size)
            if (n <= 0) break
            pcm.write(chunk, 0, n)
            val now = System.currentTimeMillis()
            val rms = chunkRms(chunk, n)
            if (rms > SILENCE_RMS) lastVoiceAt = now
            val elapsed = now - startedAt
            if (elapsed >= MAX_SECONDS * 1000) break
            if (elapsed > PREROLL_MS && now - lastVoiceAt > SILENCE_MS_TO_STOP) break
        }
    } finally {
        runCatching { rec.stop() }
        runCatching { rec.release() }
    }
    wrapWav(pcm.toByteArray(), SAMPLE_RATE, 1, 16)
}

private fun chunkRms(buf: ByteArray, n: Int): Int {
    var sum = 0L
    var i = 0
    while (i + 1 < n) {
        val s = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
        sum += abs(s.toShort().toInt())
        i += 2
    }
    val samples = n / 2
    return if (samples == 0) 0 else (sum / samples).toInt()
}

private fun wrapWav(pcm: ByteArray, sr: Int, ch: Int, bits: Int): ByteArray {
    val byteRate = sr * ch * bits / 8
    val blockAlign = ch * bits / 8
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray())
        putInt(36 + pcm.size)
        put("WAVEfmt ".toByteArray())
        putInt(16)
        putShort(1)              // PCM
        putShort(ch.toShort())
        putInt(sr)
        putInt(byteRate)
        putShort(blockAlign.toShort())
        putShort(bits.toShort())
        put("data".toByteArray())
        putInt(pcm.size)
    }.array()
    return header + pcm
}
