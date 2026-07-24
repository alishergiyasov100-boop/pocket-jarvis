package com.musornibak.pocketjarvis.overlay

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

enum class JarvisState { Idle, Listening, Thinking, Speaking }

@Composable
fun JarvisOverlay(
    state: JarvisState,
    transcript: String,
    inputMode: Boolean,
    inputText: String,
    onInputChange: (String) -> Unit,
    onInputSubmit: () -> Unit,
) {
    val expanded = state == JarvisState.Speaking || inputMode || transcript.isNotEmpty()

    val targetW = when {
        expanded -> 340.dp
        state == JarvisState.Idle -> 124.dp
        else -> 110.dp
    }
    val targetH = when {
        expanded -> if (inputMode) 210.dp else 190.dp
        state == JarvisState.Idle -> 36.dp
        else -> 110.dp
    }
    val targetCorner = when {
        expanded -> 34.dp
        state == JarvisState.Idle -> 20.dp
        else -> 60.dp
    }

    val morph = spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.55f, stiffness = 220f)
    val w by animateDpAsState(targetW, morph, label = "w")
    val h by animateDpAsState(targetH, morph, label = "h")
    val corner by animateDpAsState(targetCorner, morph, label = "c")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .width(w)
                .height(h)
                .clip(RoundedCornerShape(corner))
                .background(Color(0xFF0A0A0A)),
        ) {
            if (state != JarvisState.Idle) {
                MetaballLayer(
                    modifier = Modifier.fillMaxSize(),
                    intense = state == JarvisState.Speaking || state == JarvisState.Listening,
                )
            }

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    expanded -> ExpandedContent(
                        transcript = transcript,
                        inputMode = inputMode,
                        inputText = inputText,
                        onInputChange = onInputChange,
                        onInputSubmit = onInputSubmit,
                    )
                    state == JarvisState.Thinking -> ThreeDots()
                    else -> Unit
                }
            }
        }
    }
}

/**
 * Настоящий metaball: несколько «капель» блюрятся через RenderEffect, потом
 * alpha-threshold через ColorMatrix — края бинаризуются, капли сливаются в
 * жидкость (классический CSS gooey-effect трюк, но нативно через RenderNode).
 */
@Composable
private fun MetaballLayer(modifier: Modifier, intense: Boolean) {
    val t = rememberInfiniteTransition(label = "meta")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(if (intense) 3400 else 5600, easing = LinearEasing),
        ),
        label = "phase",
    )
    val breath by t.animateFloat(
        initialValue = 0.86f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(1700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )

    val gooeyEffect = remember {
        val m = ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f,
                0f, 0f, 0f, 22f, -8f * 255f,
            )
        )
        val filter = ColorMatrixColorFilter(m)
        RenderEffect.createChainEffect(
            RenderEffect.createColorFilterEffect(filter),
            RenderEffect.createBlurEffect(26f, 26f, Shader.TileMode.DECAL),
        ).asComposeRenderEffect()
    }

    Box(
        modifier = modifier
            .padding(4.dp)
            .graphicsLayer { renderEffect = gooeyEffect },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = minOf(size.width, size.height) / 2f
            val base = r * 0.42f * breath
            val orbit = r * 0.28f

            fun blob(colorIdx: Int, angleOffset: Float, radiusMul: Float, orbitMul: Float) {
                val a = phase * (0.7f + colorIdx * 0.13f) + angleOffset
                val ox = cx + cos(a) * orbit * orbitMul
                val oy = cy + sin(a * 1.3f) * orbit * 0.55f * orbitMul
                drawCircle(
                    color = paletteCool[colorIdx],
                    radius = base * radiusMul,
                    center = Offset(ox, oy),
                )
            }

            drawCircle(
                color = Color(0xFF0F1830),
                radius = r * 0.95f,
                center = Offset(cx, cy),
            )
            blob(0, 0.0f, 1.05f, 0.35f)
            blob(1, 2.1f, 0.90f, 0.85f)
            blob(2, 4.2f, 0.78f, 0.95f)
            blob(3, 1.1f, 0.62f, 0.65f)
        }
    }
}

private val paletteCool = listOf(
    Color(0xFFA9C6FF), // холодный голубой
    Color(0xFFC7B4FF), // сиреневый
    Color(0xFFE8EEFF), // белёсый лёд
    Color(0xFFFFB8D6), // редкий розовый акцент
)

@Composable
private fun ExpandedContent(
    transcript: String,
    inputMode: Boolean,
    inputText: String,
    onInputChange: (String) -> Unit,
    onInputSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = transcript.ifEmpty { " " },
            style = TextStyle(
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                letterSpacing = (-0.1).sp,
                lineHeight = 22.sp,
            ),
            maxLines = 5,
            modifier = Modifier.weight(1f, fill = false),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF1B1B1F))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                BasicTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    textStyle = TextStyle(
                        fontSize = 14.sp,
                        color = Color.White,
                        letterSpacing = (-0.1).sp,
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (inputText.isEmpty()) {
                            Text(
                                "напиши…",
                                style = TextStyle(fontSize = 14.sp, color = Color(0xFF6A6A70)),
                            )
                        }
                        inner()
                    },
                )
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable { onInputSubmit() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "↑",
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0A0A0A),
                    )
                )
            }
        }
    }
}

@Composable
private fun ThreeDots() {
    val t = rememberInfiniteTransition(label = "3d")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0, 160, 320).forEach { delayMs ->
            val a by t.animateFloat(
                0.25f, 1f,
                infiniteRepeatable(
                    animation = tween(560, delayMillis = delayMs, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "d$delayMs",
            )
            Box(
                Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = a))
            )
        }
    }
}
