package com.musornibak.pocketjarvis.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.musornibak.pocketjarvis.BuildConfig
import com.musornibak.pocketjarvis.data.Keys
import com.musornibak.pocketjarvis.data.Settings
import com.musornibak.pocketjarvis.llm.OrionClient
import com.musornibak.pocketjarvis.voice.FishAudioTts
import com.musornibak.pocketjarvis.voice.HotwordListener
import com.musornibak.pocketjarvis.voice.captureAudio
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Loop: wake («Орион» / Vol+ / BT click) → beep → captureAudio (WAV) →
 * POST orion-brain /voice (whisper + LLM) → Fish TTS → снова hotword.
 */
class OrionService : LifecycleService() {

    private var hotword: HotwordListener? = null
    private var pipelineJob: Job? = null
    private var mediaSession: MediaSessionCompat? = null

    override fun onCreate() {
        super.onCreate()

        val hasMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            stopSelf()
            return
        }

        startForegroundInternal()
        startMediaSession()
        startHotword()
    }

    private fun startMediaSession() {
        mediaSession = MediaSessionCompat(this, "orion").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { triggerVoice() }
                override fun onPause() { triggerVoice() }
                override fun onMediaButtonEvent(intent: Intent): Boolean {
                    val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (key?.action == KeyEvent.ACTION_DOWN) {
                        when (key.keyCode) {
                            KeyEvent.KEYCODE_HEADSETHOOK,
                            KeyEvent.KEYCODE_MEDIA_PLAY,
                            KeyEvent.KEYCODE_MEDIA_PAUSE,
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                triggerVoice(); return true
                            }
                        }
                    }
                    return super.onMediaButtonEvent(intent)
                }
            })
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE
                    )
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f)
                    .build()
            )
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_WAKE) triggerVoice()
        return START_STICKY
    }

    override fun onDestroy() {
        hotword?.stop()
        hotword = null
        mediaSession?.apply { isActive = false; release() }
        mediaSession = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startForegroundInternal() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL, "Orion", NotificationManager.IMPORTANCE_MIN).apply {
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Orion слушает")
            .setContentText("Скажи «Орион», нажми Vol+ или кнопку на наушниках")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun startHotword() {
        hotword?.stop()
        hotword = HotwordListener(this) { triggerVoice() }.also { it.start() }
    }

    private fun triggerVoice() {
        if (pipelineJob?.isActive == true) return
        hotword?.stop()
        pipelineJob = lifecycleScope.launch {
            runCatching { runPipeline() }
            delay(300)
            startHotword()
        }
    }

    private suspend fun runPipeline() {
        val settings = Settings(this)
        val orionUrl = settings.get(Keys.ORION_URL, BuildConfig.ORION_URL_DEFAULT).first()
        val fishKey = settings.get(Keys.FISH_KEY, BuildConfig.FISH_KEY_DEFAULT).first()
        val fishVoice = settings.get(Keys.FISH_VOICE, BuildConfig.FISH_VOICE_DEFAULT).first()

        beep()

        val wav = captureAudio()
        if (wav.size <= 44 + 8000) return // ~<0.25s аудио → скипаем (шум)

        val result = OrionClient(orionUrl).voice(wav, language = "ru")
        val reply = result.reply.ifBlank { result.error.orEmpty() }
        if (reply.isNotEmpty()) {
            FishAudioTts(this, fishKey, fishVoice).speak(reply)
        }
    }

    private fun beep() {
        runCatching {
            val tone = ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 70)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        }
    }

    companion object {
        const val ACTION_WAKE = "com.musornibak.pocketjarvis.WAKE"
        private const val CHANNEL = "orion-fg"
        private const val NOTIF_ID = 42

        fun wakeIntent(ctx: Context) = Intent(ctx, OrionService::class.java)
            .setAction(ACTION_WAKE)

        fun startIntent(ctx: Context) = Intent(ctx, OrionService::class.java)
    }
}
