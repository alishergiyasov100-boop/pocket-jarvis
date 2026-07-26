package com.musornibak.pocketjarvis.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings as OsSettings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.musornibak.pocketjarvis.BuildConfig
import com.musornibak.pocketjarvis.data.Keys
import com.musornibak.pocketjarvis.data.Settings
import com.musornibak.pocketjarvis.llm.OsaClient
import com.musornibak.pocketjarvis.overlay.JarvisOverlay
import com.musornibak.pocketjarvis.overlay.JarvisState
import com.musornibak.pocketjarvis.voice.FishAudioTts
import com.musornibak.pocketjarvis.voice.captureOnce
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class JarvisOverlayService : LifecycleService(),
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    override val viewModelStore = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private lateinit var wm: WindowManager
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private val stateVar = mutableStateOf(JarvisState.Idle)
    private val transcriptVar = mutableStateOf("")
    private val inputModeVar = mutableStateOf(false)
    private val inputTextVar = mutableStateOf("")
    private val voiceOnVar = mutableStateOf(true)

    private var currentJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            Toast.makeText(this, "Сначала разреши микрофон в Setup", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        if (!OsSettings.canDrawOverlays(this)) {
            Toast.makeText(this, "Сначала разреши Overlay в Setup", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        runCatching { startForegroundInternal() }.onFailure {
            Toast.makeText(this, "FG-сервис не стартовал: ${it.message?.take(80)}", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        runCatching { addOverlay() }.onFailure {
            Toast.makeText(this, "Overlay не поднялся: ${it.message?.take(80)}", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        lifecycleScope.launch {
            val v = Settings(this@JarvisOverlayService).get(Keys.VOICE_ON, "1").first()
            voiceOnVar.value = v != "0"
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_WAKE) {
            triggerVoice()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        overlayView?.let { runCatching { wm.removeView(it) } }
        overlayView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startForegroundInternal() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL, "JARVIS", NotificationManager.IMPORTANCE_MIN).apply {
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("JARVIS активен")
            .setContentText("Тап по островку — говоришь или пишешь")
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

    private fun addOverlay() {
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@JarvisOverlayService)
            setViewTreeViewModelStoreOwner(this@JarvisOverlayService)
            setViewTreeSavedStateRegistryOwner(this@JarvisOverlayService)
            setContent {
                JarvisOverlay(
                    state = stateVar.value,
                    transcript = transcriptVar.value,
                    inputMode = inputModeVar.value,
                    inputText = inputTextVar.value,
                    voiceOn = voiceOnVar.value,
                    onIslandTap = { toggleInputMode() },
                    onMicPress = { closeInputMode(); triggerVoice() },
                    onInputChange = { inputTextVar.value = it },
                    onInputSubmit = {
                        val t = inputTextVar.value.trim()
                        if (t.isNotEmpty()) {
                            closeInputMode()
                            triggerText(t)
                        }
                    },
                    onVoiceToggle = { setVoiceOn(!voiceOnVar.value) },
                )
            }
        }
        val lp = defaultParams(focusable = false)
        wm.addView(view, lp)
        overlayView = view
        overlayParams = lp
    }

    private fun defaultParams(focusable: Boolean): WindowManager.LayoutParams {
        val flags = if (focusable) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
    }

    private fun applyFocusable(focusable: Boolean) {
        val v = overlayView ?: return
        val lp = defaultParams(focusable)
        overlayParams = lp
        runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun toggleInputMode() {
        if (currentJob?.isActive == true) return
        val next = !inputModeVar.value
        inputModeVar.value = next
        applyFocusable(next)
        if (!next) inputTextVar.value = ""
    }

    private fun closeInputMode() {
        if (inputModeVar.value) {
            inputModeVar.value = false
            inputTextVar.value = ""
            applyFocusable(false)
        }
    }

    private fun setVoiceOn(on: Boolean) {
        voiceOnVar.value = on
        lifecycleScope.launch {
            Settings(this@JarvisOverlayService).set(Keys.VOICE_ON, if (on) "1" else "0")
        }
    }

    private fun triggerVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Разреши микрофон в настройках", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentJob?.isActive == true) return
        currentJob = lifecycleScope.launch {
            runCatching { runPipeline(preText = null) }.onFailure {
                transcriptVar.value = "Ошибка: ${it.message?.take(120)}"
            }
        }
    }

    private fun triggerText(text: String) {
        if (currentJob?.isActive == true) return
        currentJob = lifecycleScope.launch {
            runCatching { runPipeline(preText = text) }.onFailure {
                transcriptVar.value = "Ошибка: ${it.message?.take(120)}"
            }
        }
    }

    private suspend fun runPipeline(preText: String?) {
        val settings = Settings(this)
        val osaUrl = settings.get(Keys.OSA_URL, BuildConfig.OSA_URL_DEFAULT).first()
        val osaToken = settings.get(Keys.OSA_TOKEN, BuildConfig.OSA_TOKEN_DEFAULT).first()
        val model = settings.get(Keys.OSA_MODEL, "claude-haiku-4-5").first()
        val fishKey = settings.get(Keys.FISH_KEY, BuildConfig.FISH_KEY_DEFAULT).first()
        val fishVoice = settings.get(Keys.FISH_VOICE, BuildConfig.FISH_VOICE_DEFAULT).first()

        val user = if (preText != null) {
            transcriptVar.value = preText
            preText
        } else {
            stateVar.value = JarvisState.Listening
            transcriptVar.value = "Слушаю…"
            val u = captureOnce(this).trim()
            if (u.isBlank()) {
                transcriptVar.value = ""
                stateVar.value = JarvisState.Idle
                return
            }
            transcriptVar.value = u
            u
        }

        stateVar.value = JarvisState.Thinking
        val client = OsaClient(osaUrl, osaToken, model)
        val sb = StringBuilder()
        runCatching {
            client.stream(user).collect { chunk ->
                sb.append(chunk)
                transcriptVar.value = sb.toString()
                if (stateVar.value != JarvisState.Speaking) {
                    stateVar.value = JarvisState.Speaking
                }
            }
        }.onFailure { err ->
            transcriptVar.value = "Ошибка: ${err.message?.take(120)}"
            stateVar.value = JarvisState.Speaking
        }

        val reply = sb.toString().trim()
        if (reply.isNotEmpty() && voiceOnVar.value) {
            FishAudioTts(this, fishKey, fishVoice).speak(reply)
        }
        kotlinx.coroutines.delay(1400)
        transcriptVar.value = ""
        stateVar.value = JarvisState.Idle
    }

    companion object {
        const val ACTION_WAKE = "com.musornibak.pocketjarvis.WAKE"
        private const val CHANNEL = "jarvis-fg"
        private const val NOTIF_ID = 42

        fun wakeIntent(ctx: Context) = Intent(ctx, JarvisOverlayService::class.java)
            .setAction(ACTION_WAKE)

        fun startIntent(ctx: Context) = Intent(ctx, JarvisOverlayService::class.java)
    }
}
