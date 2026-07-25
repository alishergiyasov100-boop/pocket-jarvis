package com.musornibak.pocketjarvis.overlay

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
    voiceOn: Boolean = true,
    onIslandTap: () -> Unit = {},
    onInputChange: (String) -> Unit,
    onInputSubmit: () -> Unit,
    onMicPress: () -> Unit = {},
    onVoiceToggle: () -> Unit = {},
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
                .pointerInput("tap") {
                    detectTapGestures(onTap = { onIslandTap() })
                }
                .pointerInput("drag") {
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
                ArcReactorCore(
                    modifier = Modifier.fillMaxSize(),
                    state = state,
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
                        voiceOn = voiceOn,
                        onInputChange = onInputChange,
                        onInputSubmit = onInputSubmit,
                        onMicPress = onMicPress,
                        onVoiceToggle = onVoiceToggle,
                    )
                }
                // Idle / Listening / Thinking: только шар (без dots)
            }
        }
    }
}

/**
 * JARVIS arc-reactor HUD: 3 концентрических кольца вращаются в разные стороны,
 * центральное ядро дышит/пульсирует, при разговоре — ripple волны.
 * Цвета — cyan+white glow (Iron Man palette).
 */
@Composable
private fun ArcReactorCore(modifier: Modifier, state: JarvisState, slosh: Offset) {
    val t = rememberInfiniteTransition(label = "reactor")

    val outerAngle by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "outer",
    )
    val microAngle by t.animateFloat(
        0f, -360f,
        infiniteRepeatable(tween(4000, easing = LinearEasing)),
        label = "micro",
    )
    val midAngle by t.animateFloat(
        360f, 0f,
        infiniteRepeatable(tween(6000, easing = LinearEasing)),
        label = "mid",
    )
    val innerAngle by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(if (state == JarvisState.Thinking) 1200 else 4200, easing = LinearEasing)),
        label = "inner",
    )
    val scanAngle by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(if (state == JarvisState.Thinking) 1600 else 3400, easing = LinearEasing)),
        label = "scan",
    )
    val wavePhase by t.animateFloat(
        0f, (Math.PI * 2).toFloat(),
        infiniteRepeatable(tween(2200, easing = LinearEasing)),
        label = "wave",
    )
    val pulse by t.animateFloat(
        0.82f, 1.10f,
        infiniteRepeatable(
            tween(if (state == JarvisState.Listening) 560 else 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val bracketBlink by t.animateFloat(
        0.55f, 1f,
        infiniteRepeatable(
            tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bracket",
    )
    val ripples = List(5) { i ->
        t.animateFloat(
            0f, 1f,
            infiniteRepeatable(
                tween(2000, easing = LinearEasing, delayMillis = i * 400),
            ),
            label = "rip$i",
        )
    }
    val sparks = List(10) { i ->
        t.animateFloat(
            0f, 1f,
            infiniteRepeatable(
                tween(1800, easing = LinearEasing, delayMillis = i * 180),
            ),
            label = "sp$i",
        )
    }

    val sloshX by animateFloatAsState(
        slosh.x, spring(dampingRatio = 0.4f, stiffness = 130f), label = "sx",
    )
    val sloshY by animateFloatAsState(
        slosh.y, spring(dampingRatio = 0.4f, stiffness = 130f), label = "sy",
    )

    val cyan = Color(0xFF00E5FF)
    val cyanDim = Color(0xFF0091A8)
    val glow = Color(0xFFB2F5FF)
    val white = Color(0xFFFFF8E7)

    Canvas(modifier = modifier) {
        val cx = size.width / 2f + sloshX
        val cy = size.height / 2f + sloshY
        val r = minOf(size.width, size.height) / 2f - 6.dp.toPx()

        // ripple волны при Speaking (5 слоёв, разные stroke)
        if (state == JarvisState.Speaking) {
            ripples.forEachIndexed { idx, rp ->
                val p = rp.value
                val stroke = (0.9f + (idx % 3) * 0.6f).dp.toPx()
                val col = if (idx % 2 == 0) cyan else white
                drawCircle(
                    color = col.copy(alpha = (1f - p) * 0.6f),
                    radius = r * (0.24f + p * 0.95f),
                    center = Offset(cx, cy),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                )
            }
        }

        // ─── самое внешнее микро-кольцо: 60 микро-tick'ов ─────────────────
        val microR = r * 0.98f
        for (i in 0 until 60) {
            val a = Math.toRadians((i * 6f + microAngle).toDouble())
            val len = 2.dp.toPx()
            val x1 = cx + cos(a).toFloat() * microR
            val y1 = cy + sin(a).toFloat() * microR
            val x2 = cx + cos(a).toFloat() * (microR - len)
            val y2 = cy + sin(a).toFloat() * (microR - len)
            drawLine(
                color = cyanDim.copy(alpha = 0.7f),
                start = Offset(x1, y1),
                end = Offset(x2, y2),
                strokeWidth = 0.8.dp.toPx(),
            )
        }

        // ─── внешнее кольцо: 24 tick с бегущей волной яркости ─────────────
        val outerR = r * 0.90f
        for (i in 0 until 24) {
            val a = Math.toRadians((i * 15f + outerAngle).toDouble())
            val long = i % 3 == 0
            val len = if (long) 10.dp.toPx() else 5.dp.toPx()
            // бегущая волна яркости
            val wave = 0.35f + 0.65f * ((sin(wavePhase - i * 0.42f) + 1f) / 2f)
            val col = (if (long) cyan else cyanDim).copy(alpha = wave)
            val x1 = cx + cos(a).toFloat() * outerR
            val y1 = cy + sin(a).toFloat() * outerR
            val x2 = cx + cos(a).toFloat() * (outerR - len)
            val y2 = cy + sin(a).toFloat() * (outerR - len)
            drawLine(
                color = col,
                start = Offset(x1, y1),
                end = Offset(x2, y2),
                strokeWidth = 1.3.dp.toPx(),
            )
        }
        drawCircle(
            color = cyanDim.copy(alpha = 0.5f),
            radius = outerR,
            center = Offset(cx, cy),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.6.dp.toPx()),
        )

        // ─── scanning ray (радар-луч) ─────────────────────────────────────
        run {
            val a = Math.toRadians(scanAngle.toDouble())
            val ex = cx + cos(a).toFloat() * outerR
            val ey = cy + sin(a).toFloat() * outerR
            drawLine(
                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = listOf(cyan.copy(alpha = 0.9f), Color.Transparent),
                    start = Offset(cx, cy),
                    end = Offset(ex, ey),
                ),
                start = Offset(cx, cy),
                end = Offset(ex, ey),
                strokeWidth = 1.6.dp.toPx(),
            )
        }

        // ─── среднее кольцо: 6 арок-рун ───────────────────────────────────
        val midR = r * 0.66f
        for (i in 0 until 6) {
            val startA = i * 60f + midAngle
            drawArc(
                color = cyan.copy(alpha = 0.85f),
                startAngle = startA + 8f,
                sweepAngle = 44f,
                useCenter = false,
                topLeft = Offset(cx - midR, cy - midR),
                size = androidx.compose.ui.geometry.Size(midR * 2, midR * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx()),
            )
        }

        // ─── sparks: искры пляшут по орбите ───────────────────────────────
        val sparkOrbit = r * 0.78f
        sparks.forEachIndexed { idx, sp ->
            val p = sp.value
            val angle = Math.toRadians((idx * 36f + p * 90f).toDouble())
            val rad = sparkOrbit * (0.65f + 0.35f * p)
            val alpha = (1f - p) * (1f - p) // ease-out
            val sx = cx + cos(angle).toFloat() * rad
            val sy = cy + sin(angle).toFloat() * rad
            drawCircle(
                color = white.copy(alpha = alpha * 0.9f),
                radius = 1.6.dp.toPx() + p * 1.4.dp.toPx(),
                center = Offset(sx, sy),
            )
        }

        // ─── внутреннее кольцо: 3 арки reactor'а ──────────────────────────
        val innerR = r * 0.42f
        for (i in 0 until 3) {
            val startA = i * 120f + innerAngle
            drawArc(
                color = cyan,
                startAngle = startA + 12f,
                sweepAngle = 96f,
                useCenter = false,
                topLeft = Offset(cx - innerR, cy - innerR),
                size = androidx.compose.ui.geometry.Size(innerR * 2, innerR * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
            )
        }

        // ─── ядро: glow + solid center ────────────────────────────────────
        drawCircle(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                colors = listOf(glow, cyan.copy(alpha = 0.6f), Color.Transparent),
                center = Offset(cx, cy),
                radius = r * 0.42f * pulse,
            ),
            radius = r * 0.42f * pulse,
            center = Offset(cx, cy),
        )
        drawCircle(
            color = Color.White,
            radius = r * 0.10f * pulse,
            center = Offset(cx, cy),
        )

        // ─── corner brackets (HUD-скобки в 4 углах) ───────────────────────
        val br = size.width.coerceAtMost(size.height) * 0.42f
        val bLen = 8.dp.toPx()
        val bStroke = 1.5.dp.toPx()
        val bCol = white.copy(alpha = bracketBlink * 0.85f)
        val corners = listOf(
            Offset(cx - br, cy - br) to Pair(1f, 1f),   // top-left
            Offset(cx + br, cy - br) to Pair(-1f, 1f),  // top-right
            Offset(cx - br, cy + br) to Pair(1f, -1f),  // bottom-left
            Offset(cx + br, cy + br) to Pair(-1f, -1f), // bottom-right
        )
        corners.forEach { (o, dir) ->
            drawLine(
                color = bCol,
                start = o,
                end = Offset(o.x + dir.first * bLen, o.y),
                strokeWidth = bStroke,
            )
            drawLine(
                color = bCol,
                start = o,
                end = Offset(o.x, o.y + dir.second * bLen),
                strokeWidth = bStroke,
            )
        }
    }
}

@Composable
private fun ExpandedContent(
    transcript: String,
    inputMode: Boolean,
    inputText: String,
    voiceOn: Boolean,
    onInputChange: (String) -> Unit,
    onInputSubmit: () -> Unit,
    onMicPress: () -> Unit,
    onVoiceToggle: () -> Unit,
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
            // тумблер озвучки (Kuon вкл/выкл)
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (voiceOn) Color(0xFF00E5FF) else Color(0xFF1B1B1F))
                    .clickable { onVoiceToggle() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (voiceOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                    contentDescription = "voice-toggle",
                    tint = if (voiceOn) Color(0xFF0A0A0A) else Color.White,
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
