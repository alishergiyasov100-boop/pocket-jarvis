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
    // pulse при поглощении Dynamic Island'ом
    val absorbAnim = remember { Animatable(1f) }
    val absorbHalo = remember { Animatable(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        // halo при поглощении Dynamic Island'ом
        if (absorbHalo.value > 0.01f) {
            Canvas(
                modifier = Modifier
                    .size((w.value + 60).dp, (h.value + 60).dp),
            ) {
                val a = absorbHalo.value
                drawCircle(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFA9C6FF).copy(alpha = 0.55f * a),
                            Color.Transparent,
                        ),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.minDimension * (0.35f + 0.4f * (1f - a)),
                    ),
                    radius = size.minDimension * 0.7f,
                    center = Offset(size.width / 2f, size.height / 2f),
                )
            }
        }

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
                    val decay = exponentialDecay<Offset>(frictionMultiplier = 0.45f)
                    val tracker = VelocityTracker()

                    suspend fun returnAndAbsorb() {
                        var absorbed = false
                        offsetAnim.animateTo(
                            Offset.Zero,
                            spring(dampingRatio = 0.5f, stiffness = 80f),
                        ) {
                            val d = value.getDistance()
                            if (!absorbed && d < 60f) {
                                absorbed = true
                                // жидкость плещет внутрь по направлению обратного движения
                                val inward = if (d > 0.1f) -value / d * 60f else Offset.Zero
                                slosh = inward
                                scope.launch {
                                    absorbAnim.snapTo(1.18f)
                                    absorbAnim.animateTo(
                                        1f,
                                        spring(dampingRatio = 0.35f, stiffness = 420f),
                                    )
                                }
                                scope.launch {
                                    absorbHalo.snapTo(1f)
                                    absorbHalo.animateTo(0f, tween(520))
                                }
                            }
                        }
                        slosh = Offset.Zero
                    }

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
                            slosh = -drag * 0.55f
                        },
                        onDragEnd = {
                            val v = tracker.calculateVelocity()
                            val throwVel = Offset(v.x, v.y)
                            slosh = throwVel * 0.0004f
                            scope.launch {
                                offsetAnim.animateDecay(throwVel, decay)
                                returnAndAbsorb()
                            }
                        },
                        onDragCancel = {
                            slosh = Offset.Zero
                            scope.launch { returnAndAbsorb() }
                        },
                    )
                }
                .graphicsLayer {
                    scaleX = absorbAnim.value
                    scaleY = 2f - absorbAnim.value
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
    val speed = if (intense) 1.4f else 0.75f

    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween((3800 / speed).toInt(), easing = LinearEasing),
        ),
        label = "phase",
    )
    val wobble by t.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
        ),
        label = "wobble",
    )
    // индивидуальные breath — каждая капля дышит своим темпом
    val breaths = List(6) { idx ->
        t.animateFloat(
            initialValue = 0.72f,
            targetValue = 1.18f,
            animationSpec = infiniteRepeatable(
                animation = tween(950 + idx * 220, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "b$idx",
        )
    }

    val sloshX by animateFloatAsState(
        slosh.x, spring(dampingRatio = 0.38f, stiffness = 120f), label = "sx",
    )
    val sloshY by animateFloatAsState(
        slosh.y, spring(dampingRatio = 0.38f, stiffness = 120f), label = "sy",
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
            RenderEffect.createBlurEffect(28f, 28f, Shader.TileMode.DECAL),
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
            val orbit = r * 0.34f

            fun blob(idx: Int, angleOffset: Float, orbitMul: Float, speedMul: Float) {
                val a = phase * speedMul + angleOffset
                val wob = cos(wobble * 1.7f + idx * 0.9f) * r * 0.06f
                val ox = cx + cos(a) * orbit * orbitMul + sin(wobble + idx) * r * 0.08f + sloshX
                val oy = cy + sin(a * 1.15f) * orbit * 0.65f * orbitMul + cos(wobble * 0.8f + idx) * r * 0.06f + sloshY
                drawCircle(
                    color = paletteCool[idx % paletteCool.size],
                    radius = (r * 0.36f * breaths[idx].value) + wob,
                    center = Offset(ox, oy),
                )
            }

            drawCircle(
                color = Color(0xFF0F1830),
                radius = r * 0.98f,
                center = Offset(cx, cy),
            )
            blob(0, 0.0f, 0.30f, 0.85f)
            blob(1, 2.1f, 0.85f, 1.10f)
            blob(2, 4.2f, 0.95f, 0.70f)
            blob(3, 1.1f, 0.65f, 1.35f)
            blob(4, 3.5f, 0.55f, 0.95f)
            blob(5, 5.7f, 0.75f, 1.20f)
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
