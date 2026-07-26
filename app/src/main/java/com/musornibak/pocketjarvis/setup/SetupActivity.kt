package com.musornibak.pocketjarvis.setup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as OsSettings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.musornibak.pocketjarvis.service.JarvisOverlayService
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.lifecycle.lifecycleScope
import com.musornibak.pocketjarvis.BuildConfig
import com.musornibak.pocketjarvis.data.Keys
import com.musornibak.pocketjarvis.data.Settings
import com.musornibak.pocketjarvis.overlay.JarvisOverlay
import com.musornibak.pocketjarvis.overlay.JarvisState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SetupActivity : ComponentActivity() {
    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val requestNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val requestAudio = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Box(Modifier.fillMaxSize()) {
                SetupScreen(
                    onRequestMic = { requestMic.launch(Manifest.permission.RECORD_AUDIO) },
                    onRequestNotif = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
                DemoOverlayHost()
            }
        }
    }
}

/**
 * Крутит JarvisOverlay поверх Setup, автоматически перебирая состояния каждые 3.5с.
 * Дизайн-превью пока нет реального сервиса.
 */
@Composable
private fun DemoOverlayHost() {
    var state by remember { mutableStateOf(JarvisState.Idle) }
    var transcript by remember { mutableStateOf("") }
    var inputMode by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val demoLines = listOf(
            "Ага, слушаю.",
            "Освободил память — было 3.2 GB, стало 5.1 GB.",
            "Discord отрублен от сети. Включить обратно?",
            "Батарея 42%, температура 38°C. В норме.",
        )
        val demoTyping = "что там с процессором"
        var i = 0
        while (true) {
            inputMode = false
            state = JarvisState.Idle; transcript = ""; delay(2400)
            state = JarvisState.Listening;             delay(1800)
            state = JarvisState.Thinking;              delay(1300)
            transcript = demoLines[i % demoLines.size]
            state = JarvisState.Speaking;              delay(3200)

            // фаза текстового ввода
            transcript = ""; inputMode = true; inputText = ""
            for (n in 1..demoTyping.length) {
                inputText = demoTyping.take(n); delay(70)
            }
            delay(900)
            inputText = ""
            i++
        }
    }

    JarvisOverlay(
        state = state,
        transcript = transcript,
        inputMode = inputMode,
        inputText = inputText,
        onInputChange = { inputText = it },
        onInputSubmit = {},
    )
}

@Composable
private fun SetupScreen(
    onRequestMic: () -> Unit,
    onRequestNotif: () -> Unit,
) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val scope = rememberCoroutineScope()

    var osaUrl by remember { mutableStateOf("") }
    var osaToken by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("claude-haiku-4-5") }
    var fishKey by remember { mutableStateOf("") }
    var fishVoice by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        osaUrl = settings.get(Keys.OSA_URL, BuildConfig.OSA_URL_DEFAULT).first()
        osaToken = settings.get(Keys.OSA_TOKEN, BuildConfig.OSA_TOKEN_DEFAULT).first()
        model = settings.get(Keys.OSA_MODEL, "claude-haiku-4-5").first()
        fishKey = settings.get(Keys.FISH_KEY, BuildConfig.FISH_KEY_DEFAULT).first()
        fishVoice = settings.get(Keys.FISH_VOICE, BuildConfig.FISH_VOICE_DEFAULT).first()
        loaded = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFAFAFA))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            "Haku",
            style = TextStyle(
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1D1D1F),
                letterSpacing = (-1).sp,
            )
        )
        Text(
            "Настройка. Открой один раз — дальше значок висит поверх всего, тап = говоришь.",
            style = TextStyle(fontSize = 14.sp, color = Color(0xFF6E6E73), lineHeight = 20.sp)
        )

        Section("OSA (Anthropic-совместимый роутер)")
        Field("Base URL", osaUrl) { osaUrl = it }
        Field("API token", osaToken, isSecret = true) { osaToken = it }
        Field("Model", model) { model = it }

        Section("Fish Audio (голос Haku)")
        Field("API key", fishKey, isSecret = true) { fishKey = it }
        Field("Reference ID (пусто = дефолт)", fishVoice) { fishVoice = it }

        Spacer(Modifier.height(8.dp))

        PillButton("Сохранить") {
            scope.launch {
                settings.set(Keys.OSA_URL, osaUrl.trim())
                settings.set(Keys.OSA_TOKEN, osaToken.trim())
                settings.set(Keys.OSA_MODEL, model.trim())
                settings.set(Keys.FISH_KEY, fishKey.trim())
                settings.set(Keys.FISH_VOICE, fishVoice.trim())
            }
        }

        Section("Разрешения (по одному)")
        PillButton("Микрофон") { onRequestMic() }
        PillButton("Уведомления") { onRequestNotif() }
        PillButton("Overlay поверх окон") {
            ctx.startActivity(
                Intent(OsSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            )
        }

        Spacer(Modifier.height(8.dp))

        PillButton("Запустить Haku") {
            val micOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val overlayOk = OsSettings.canDrawOverlays(ctx)
            when {
                !micOk -> android.widget.Toast.makeText(ctx, "Сначала разреши Микрофон", android.widget.Toast.LENGTH_LONG).show()
                !overlayOk -> android.widget.Toast.makeText(ctx, "Сначала разреши Overlay поверх окон", android.widget.Toast.LENGTH_LONG).show()
                else -> ContextCompat.startForegroundService(ctx, JarvisOverlayService.startIntent(ctx))
            }
        }

        var crashText by remember { mutableStateOf<String?>(null) }
        PillButton("Показать последний сбой") {
            val f = java.io.File(ctx.filesDir, "last_crash.txt")
            crashText = if (f.exists()) f.readText() else "нет краша"
        }
        crashText?.let {
            Text(
                it,
                style = TextStyle(fontSize = 11.sp, color = Color(0xFFB00020), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
            )
        }
    }
}

@Composable private fun Section(title: String) {
    Text(
        title.uppercase(),
        style = TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF8E8E93),
            letterSpacing = 0.8.sp,
        ),
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun Field(label: String, value: String, isSecret: Boolean = false, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = TextStyle(fontSize = 12.sp, color = Color(0xFF6E6E73)))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = TextStyle(fontSize = 15.sp, color = Color(0xFF1D1D1F)),
                singleLine = true,
                visualTransformation = if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1D1D1F))
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = TextStyle(
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                letterSpacing = (-0.1).sp,
            )
        )
    }
}
