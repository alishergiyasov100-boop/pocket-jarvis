package com.musornibak.pocketjarvis.overlay

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class JarvisState { Idle, Listening, Thinking, Speaking }

/**
 * Apple Intelligence Siri 2026 стиль.
 * Anchor: TOP-CENTER, у Dynamic Island. Три morph-формы:
 *  - Idle       → узкая чёрная pill (сливается с Island)
 *  - Listening  → круглый orb с внутренним «масляным» радужным blob
 *  - Thinking   → orb + 3 dot pulse внутри
 *  - Speaking   → развёрнутый большой squircle с текстом
 * Все переходы через animateDpAsState / animateFloatAsState (spring).
 */
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

    val targetW: androidx.compose.ui.unit.Dp = when {
        expanded -> 340.dp
        state == JarvisState.Idle -> 124.dp
        else -> 110.dp
    }
    val targetH: androidx.compose.ui.unit.Dp = when {
        expanded -> if (inputMode) 200.dp else 172.dp
        state == JarvisState.Idle -> 36.dp
        else -> 110.dp
    }
    val targetCorner: androidx.compose.ui.unit.Dp = when {
        expanded -> 34.dp
        state == JarvisState.Idle -> 20.dp
        else -> 60.dp
    }

    val w by animateDpAsState(targetW, morphSpec(), label = "w")
    val h by animateDpAsState(targetH, morphSpec(), label = "h")
    val corner by animateDpAsState(targetCorner, morphSpec(), label = "c")

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
            // Внутренний масляный blob виден когда НЕ idle
            if (state != JarvisState.Idle) {
                OilBlob(
                    modifier = Modifier.fillMaxSize(),
                    intense = state == JarvisState.Speaking,
                )
            }

            // Содержимое поверх
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
                    )
                    state == JarvisState.Thinking -> ThreeDots()
                    state == JarvisState.Listening -> {
                        // orb с масляным блобом внутри, ничего сверху
                    }
                    state == JarvisState.Idle -> {
                        // pill без содержимого
                    }
                    else -> Unit
                }
            }
        }
    }
}

private fun <T> morphSpec(): SpringSpec<T> =
    spring(dampingRatio = 0.72f, stiffness = 320f)

/**
 * «Масляный» blob — sweepGradient радужный + медленное вращение + лёгкий blur.
 * Ощущение glossy oil-slick как у Siri Apple Intelligence.
 */
@Composable
private fun OilBlob(modifier: Modifier = Modifier, intense: Boolean) {
    val t = rememberInfiniteTransition(label = "oil")
    val angle by t.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (intense) 4200 else 6800, easing = LinearEasing),
        ),
        label = "angle",
    )
    val breath by t.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )

    Box(
        modifier = modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(50))
            .blur(2.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize().rotate(angle)) {
            drawRect(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color(0xFFFF3B30), // red
                        Color(0xFFFF9500), // orange
                        Color(0xFFFFCC00), // yellow
                        Color(0xFF34C759), // green
                        Color(0xFF00C7BE), // teal
                        Color(0xFF007AFF), // blue
                        Color(0xFFAF52DE), // purple
                        Color(0xFFFF2D55), // pink
                        Color(0xFFFF3B30), // wrap
                    ),
                    center = Offset(size.width / 2f * breath, size.height / 2f),
                ),
                alpha = 0.95f,
            )
        }
        // Тонкая тёмная виньетка сверху для глубины
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color(0x66000000)),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = size.minDimension * 0.75f,
                    tileMode = TileMode.Clamp,
                ),
            )
        }
    }
}

@Composable
private fun ExpandedContent(
    transcript: String,
    inputMode: Boolean,
    inputText: String,
    onInputChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (inputMode) {
            BasicTextField(
                value = inputText,
                onValueChange = onInputChange,
                textStyle = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    letterSpacing = (-0.1).sp,
                    lineHeight = 22.sp,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                text = transcript.ifEmpty { " " },
                style = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    letterSpacing = (-0.1).sp,
                    lineHeight = 22.sp,
                ),
                maxLines = 6,
            )
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
