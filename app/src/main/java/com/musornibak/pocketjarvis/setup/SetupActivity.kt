package com.musornibak.pocketjarvis.setup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as OsSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.musornibak.pocketjarvis.service.OrionService

class SetupActivity : ComponentActivity() {
    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val requestNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SetupScreen(
                onRequestMic = { requestMic.launch(Manifest.permission.RECORD_AUDIO) },
                onRequestNotif = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
        }
    }
}

@Composable
private fun SetupScreen(
    onRequestMic: () -> Unit,
    onRequestNotif: () -> Unit,
) {
    val ctx = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFAFAFA))
            .padding(horizontal = 28.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            "Orion",
            style = TextStyle(
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1D1D1F),
                letterSpacing = (-1.5).sp,
            )
        )
        Text(
            "Скажи «Орион» — и говори. Или Vol+ / кнопка на наушниках.",
            style = TextStyle(fontSize = 14.sp, color = Color(0xFF6E6E73), lineHeight = 20.sp)
        )

        Spacer(Modifier.height(12.dp))

        PillButton("1. Микрофон") { onRequestMic() }
        PillButton("2. Уведомления") { onRequestNotif() }
        PillButton("3. Vol+ через Accessibility (опционально)") {
            ctx.startActivity(Intent(OsSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        PillButton("4. Отключить оптимизацию батареи") {
            runCatching {
                ctx.startActivity(
                    Intent(OsSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${ctx.packageName}"))
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        PillButton("Запустить Orion", accent = true) {
            val micOk = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!micOk) {
                android.widget.Toast.makeText(
                    ctx, "Сначала разреши микрофон", android.widget.Toast.LENGTH_LONG
                ).show()
            } else {
                ContextCompat.startForegroundService(ctx, OrionService.startIntent(ctx))
                android.widget.Toast.makeText(
                    ctx, "Orion слушает. Скажи «Орион».", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

@Composable
private fun PillButton(label: String, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (accent) Color(0xFF1D1D1F) else Color.White)
            .clickable { onClick() }
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = TextStyle(
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (accent) Color.White else Color(0xFF1D1D1F),
                letterSpacing = (-0.1).sp,
            )
        )
    }
}
