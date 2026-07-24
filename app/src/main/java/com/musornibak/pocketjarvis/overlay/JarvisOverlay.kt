package com.musornibak.pocketjarvis.overlay

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
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
    onMicPress: () -> Unit = {},
) {
    val expanded = state == JarvisState.Speaking || inputMode || transcript.isNotEmpty()

    val targetW = when {
        expanded -> 340.dp
        state == JarvisState.Idle -> 124.dp
        else -> 118.dp
    }
    val targetH = when {
        expanded -> if (inputMode) 210.dp else 190.dp
        state == JarvisState.Idle -> 36.dp
        else -> 118.dp
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

    // ─── физика: drag + throw + spring-back к якорю (top-center) ──────────
    val offsetAnim = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()
    var slosh by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        offsetAnim.value.x.roundToInt(),
                        offsetAnim.value.y.roundToInt(),
                    )
                }
                .width(w)
                .height(h)
                .clip(RoundedCornerShape(corner))
                .background(Color(0xFF0A0A0A))
                .pointerInput(Unit) {
                    val decay = splineBasedDecay<Offset>(this)
                    val tracker = VelocityTracker()
                    detectDragGestures(
                        onDragStart = {
                            tracker.resetTracking()
                            scope.launch { offsetAnim.stop() }
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            scope.launch {
                                offsetAnim.snapTo(offsetAnim.value + drag)
                            }
                            // жидкость отстаёт от контейнера — inertia
                            slosh = -drag * 0.35f
                        },
                        onDragEnd = {
                            val v = tracker.calculateVelocity()
                            val throwVel = Offset(v.x, v.y)
                            slosh = Offset.Zero
                            scope.launch {
                                // бросок с натуральным замедлением
                                offsetAnim.animateDecay(throwVel, decay)
                                // затем пружина обратно к Dynamic Island
                                offsetAnim.animateTo(
                                    Offset.Zero,
                                    spring(dampingRatio = 0.6f, stiffness = 180f),
                                )
                            }
                        },
                        onDragCancel = {
                            slosh = Offset.Zero
                            scope.launch {
                                offsetAnim.animateTo(
                                    Offset.Zero,
                                    spring(dampingRatio = 0.6f, stiffness = 180f),
                                )
                            }
                        },
                    )
                },
        ) {
            if (state != JarvisState.Idle) {
                MetaballLayer(
                    modifier = Modifier.fillMaxSize(),
                    intense = state == JarvisState.Speaking || state == JarvisState.Thinking,
                    slosh = slosh,
                )
            }

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (expanded) {
                    ExpandedContent(
                        transcript = transcript,
                        inputMode = inputMode,
                        inputText = inputText,
                        onInputChange = onInputChange,
                        onInputSubmit = onInputSubmit,
                        onMicPress = onMicPress,
                    )
                }
                // Idle / Listening / Thinking: только шар (без dots)
            }
        }
    }
}

/**
 * Настоящий metaball через RenderEffect: чистый blur + alpha-threshold.
 * Классический gooey-трюк, но нативно в RenderNode → 60fps.
 *
 * `slosh` — offset жидкости внутри контейнера (для inertia при drag/throw).
 */
@Composable
private fun MetaballLayer(modifier: Modifier, intense: Boolean, slosh: Offset) {
    val t = rememberInfiniteTransition(label = "meta")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(if (intense) 3200 else 5400, easing = LinearEasing),
        ),
        label = "phase",
    )
    val breath by t.animateFloat(
        initialValue = 0.86f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )

    // сглаживаем slosh чтобы жидкость плавно возвращалась
    val sloshX by animateFloatAsState(
        slosh.x, spring(dampingRatio = 0.45f, stiffness = 140f), label = "sx",
    )
    val sloshY by animateFloatAsState(
        slosh.y, spring(dampingRatio = 0.45f, stiffness = 140f), label = "sy",
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
            val orbit = r * 0.30f

            fun blob(colorIdx: Int, angleOffset: Float, radiusMul: Float, orbitMul: Float) {
                val a = phase * (0.7f + colorIdx * 0.13f) + angleOffset
                val ox = cx + cos(a) * orbit * orbitMul + sloshX
                val oy = cy + sin(a * 1.3f) * orbit * 0.55f * orbitMul + sloshY
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
    Color(0xFFA9C6FF),
    Color(0xFFC7B4FF),
    Color(0xFFE8EEFF),
    Color(0xFFFFB8D6),
)

@Composable
private fun ExpandedContent(
    transcript: String,
    inputMode: Boolean,
    inputText: String,
    onInputChange: (String) -> Unit,
    onInputSubmit: () -> Unit,
    onMicPress: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 16.dp),
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
            // микрофон
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1B1B1F))
                    .clickable { onMicPress() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = "voice",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
            // отправить
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable { onInputSubmit() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowUpward,
                    contentDescription = "send",
                    tint = Color(0xFF0A0A0A),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
