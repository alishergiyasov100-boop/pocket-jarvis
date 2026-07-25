package com.musornibak.pocketjarvis.setup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as OsSettings
import androidx.activity.ComponentActivity
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Box(Modifier.fillMaxSize()) {
                SetupScreen()
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
private fun SetupScreen() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val scope = rememberCoroutineScope()

    var osaUrl by remember { mutableStateOf("") }
    var osaToken by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("claude-sonnet-5") }
    var elevenKey by remember { mutableStateOf("") }
    var elevenVoice by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        osaUrl = settings.get(Keys.OSA_URL, BuildConfig.OSA_URL_DEFAULT).first()
        osaToken = settings.get(Keys.OSA_TOKEN, BuildConfig.OSA_TOKEN_DEFAULT).first()
        model = settings.get(Keys.OSA_MODEL, "claude-sonnet-5").first()
        elevenKey = settings.get(Keys.ELEVEN_KEY, BuildConfig.ELEVEN_KEY_DEFAULT).first()
        elevenVoice = settings.get(Keys.ELEVEN_VOICE, BuildConfig.ELEVEN_VOICE_DEFAULT).first()
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
            "JARVIS",
            style = TextStyle(
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1D1D1F),
                letterSpacing = (-1).sp,
            )
        )
        Text(
            "Настройка. Открой один раз — дальше живёт в фоне, будится по Vol+.",
            style = TextStyle(fontSize = 14.sp, color = Color(0xFF6E6E73), lineHeight = 20.sp)
        )

        Section("OSA (Anthropic-совместимый роутер)")
        Field("Base URL", osaUrl) { osaUrl = it }
        Field("API token", osaToken, isSecret = true) { osaToken = it }
        Field("Model", model) { model = it }

        Section("ElevenLabs (голос)")
        Field("API key", elevenKey, isSecret = true) { elevenKey = it }
        Field("Voice ID (Kuon)", elevenVoice) { elevenVoice = it }

        Spacer(Modifier.height(8.dp))

        PillButton("Сохранить") {
            scope.launch {
                settings.set(Keys.OSA_URL, osaUrl.trim())
                settings.set(Keys.OSA_TOKEN, osaToken.trim())
                settings.set(Keys.OSA_MODEL, model.trim())
                settings.set(Keys.ELEVEN_KEY, elevenKey.trim())
                settings.set(Keys.ELEVEN_VOICE, elevenVoice.trim())
            }
        }

        Section("Разрешения (по одному)")
        PillButton("Accessibility (для Vol+ wake)") {
            ctx.startActivity(Intent(OsSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        PillButton("Overlay поверх окон") {
            ctx.startActivity(
                Intent(OsSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
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
