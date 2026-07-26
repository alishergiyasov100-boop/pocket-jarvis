package com.musornibak.pocketjarvis.overlay

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

enum class JarvisState { Idle, Listening, Thinking, Speaking }

/**
 * Минимальный пассивный индикатор. Никакого UI, никакого ввода.
 * Просто маленький кружок который показывает что Haku на связи.
 * Тап = разовая активация (как альтернатива Vol+).
 * Drag = переместить.
 */
@Composable
fun JarvisOverlay(
    state: JarvisState,
    onTap: () -> Unit,
) {
    var offset by remember { mutableStateOf(IntOffset.Zero) }

    val infinite = rememberInfiniteTransition(label = "haku")
    val pulse by infinite.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val spin by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
        ),
        label = "spin",
    )

    val scale = when (state) {
        JarvisState.Listening, JarvisState.Speaking -> pulse
        else -> 1f
    }
    val rotate = if (state == JarvisState.Thinking) spin else 0f
    val core = when (state) {
        JarvisState.Idle -> Color(0xFFFFFFFF)
        JarvisState.Listening -> Color(0xFF7BE495)
        JarvisState.Thinking -> Color(0xFFB1B1B6)
        JarvisState.Speaking -> Color(0xFF80D8FF)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { offset },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 40.dp)
                .size(22.dp)
                .graphicsLayer {
                    scaleX = scale; scaleY = scale; rotationZ = rotate
                }
                .clip(CircleShape)
                .background(Color(0xFF0A0A0A))
                .pointerInput(Unit) {
                    detectDragGestures { _, drag ->
                        offset = IntOffset(
                            (offset.x + drag.x).roundToInt(),
                            (offset.y + drag.y).roundToInt(),
                        )
                    }
                }
                .clickable { onTap() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(8.dp)) {
                drawCircle(color = core)
            }
        }
    }
}
