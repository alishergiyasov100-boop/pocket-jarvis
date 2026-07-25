package com.musornibak.pocketjarvis.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
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
import com.musornibak.pocketjarvis.voice.ElevenLabsTts
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

    // reactive state для Compose
    private val stateVar = mutableStateOf(JarvisState.Idle)
    private val transcriptVar = mutableStateOf("")

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_WAKE) {
            triggerWake()
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
            .setContentText("Скажи «Джарвис» или нажми Vol+")
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
                    inputMode = false,
                    inputText = "",
                    onInputChange = {},
                    onInputSubmit = {},
                    onMicPress = { triggerWake() },
                )
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP }
        wm.addView(view, lp)
        overlayView = view
    }

    private fun triggerWake() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Разреши микрофон в настройках", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentJob?.isActive == true) return
        currentJob = lifecycleScope.launch {
            runCatching { runPipeline() }.onFailure {
                transcriptVar.value = "Ошибка: ${it.message?.take(120)}"
            }
        }
    }

    private suspend fun runPipeline() {
        val settings = Settings(this)
        val osaUrl = settings.get(Keys.OSA_URL, BuildConfig.OSA_URL_DEFAULT).first()
        val osaToken = settings.get(Keys.OSA_TOKEN, BuildConfig.OSA_TOKEN_DEFAULT).first()
        val model = settings.get(Keys.OSA_MODEL, "claude-sonnet-5").first()
        val elevenKey = settings.get(Keys.ELEVEN_KEY, BuildConfig.ELEVEN_KEY_DEFAULT).first()
        val elevenVoice = settings.get(Keys.ELEVEN_VOICE, BuildConfig.ELEVEN_VOICE_DEFAULT).first()

        stateVar.value = JarvisState.Listening
        transcriptVar.value = "Слушаю…"
        val user = captureOnce(this).trim()
        if (user.isBlank()) {
            transcriptVar.value = ""
            stateVar.value = JarvisState.Idle
            return
        }
        transcriptVar.value = user

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
        if (reply.isNotEmpty()) {
            ElevenLabsTts(this, elevenKey, elevenVoice).speak(reply)
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
