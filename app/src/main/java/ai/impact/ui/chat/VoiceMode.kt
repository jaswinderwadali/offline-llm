package ai.impact.ui.chat

import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.SizeTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.produceState
import androidx.compose.runtime.DisposableEffect
import ai.impact.ChatViewModel
import ai.impact.VoicePhase
import ai.impact.VoiceState
import ai.impact.data.Persona
import ai.impact.ui.components.PersonaAvatar
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val Night = Color(0xFF07080D)

/**
 * Hands-free voice conversation, on top of the chat: an aurora in the personality's colours, a
 * glowing orb that follows the conversation, live captions, and one big button that does the
 * obvious thing at each moment.
 */
@Composable
fun VoiceModeOverlay(
    state: VoiceState,
    persona: Persona,
    onTap: () -> Unit,
    onEnd: () -> Unit,
    /** 0 to 1 as voice mode opens (back to 0 as it closes); its parts arrive one after another. */
    reveal: Float = 1f,
    /** False while it's closing: Back goes to the chat again. */
    active: Boolean = true
) {
    BackHandler(enabled = active, onBack = onEnd)
    // The screen stays on for the whole session, so the phone never sleeps mid-conversation.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val start = Color(persona.colorStart)
    val end = Color(persona.colorEnd)
    val time by rememberInfiniteTransition(label = "time").animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(9_000, easing = LinearEasing)),
        label = "clock"
    )
    // Swallows taps so the chat underneath can't be touched.
    Box(
        Modifier
            .fillMaxSize()
            .background(Night)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
    ) {
        Aurora(start, end, time, dim = state.phase == VoicePhase.Paused)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.staggered(reveal, from = 0.45f, slide = (-24).dp)) { Header(persona, onEnd) }
            Spacer(Modifier.weight(0.7f))
            Box(
                Modifier
                    .staggered(reveal, from = 0.15f, scaleFrom = 0.35f)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
                contentAlignment = Alignment.Center
            ) {
                Orb(state, start, end, time)
                Text(persona.emoji, fontSize = 52.sp)
            }
            Spacer(Modifier.height(20.dp))
            Box(Modifier.staggered(reveal, from = 0.35f, slide = 12.dp)) { PhaseChip(state.phase, state.since, start) }
            Spacer(Modifier.height(18.dp))
            Box(Modifier.staggered(reveal, from = 0.4f, scaleFrom = 0.6f)) { Waveform(state, start, end, time) }
            Spacer(Modifier.weight(0.5f))
            Box(Modifier.staggered(reveal, from = 0.5f, slide = 16.dp)) { Captions(state) }
            Spacer(Modifier.weight(0.5f))
            Box(Modifier.staggered(reveal, from = 0.55f, slide = 40.dp)) { Controls(state, start, end, onTap) }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Two soft blobs of the personality's colours drifting slowly behind everything. */
@Composable
private fun Aurora(start: Color, end: Color, time: Float, dim: Boolean) {
    val strength by animateFloatAsState(if (dim) 0.18f else 0.42f, tween(800), label = "aurora")
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        fun blob(color: Color, cx: Float, cy: Float, r: Float) = drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = strength), Color.Transparent), center = Offset(cx, cy), radius = r),
            radius = r, center = Offset(cx, cy)
        )
        blob(start, w * (0.25f + 0.12f * sin(time)), h * (0.28f + 0.06f * cos(time)), w * 0.95f)
        blob(end, w * (0.78f + 0.10f * cos(time * 2)), h * (0.62f + 0.07f * sin(time)), w * 0.9f)
        // A darker floor keeps the captions and controls readable.
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Night.copy(alpha = 0.85f)), startY = h * 0.55f, endY = h))
    }
}

@Composable
private fun Header(persona: Persona, onEnd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PersonaAvatar(persona, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(persona.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text("Hands-free · on this phone", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
        }
        Surface(onClick = onEnd, shape = CircleShape, color = Color.White.copy(alpha = 0.12f), modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, contentDescription = "Close voice mode", tint = Color.White)
            }
        }
    }
}

/**
 * A glowing sphere: it breathes while idle, swells with the user's voice, pulses while speaking
 * and spins its rim while thinking.
 */
@Composable
private fun Orb(state: VoiceState, start: Color, end: Color, time: Float) {
    val level by animateFloatAsState(if (state.phase == VoicePhase.Listening) state.level else 0f, tween(80), label = "level")
    val energy = when (state.phase) {
        VoicePhase.Listening -> 0.15f + level * 0.85f
        VoicePhase.Speaking -> 0.35f + 0.25f * abs(sin(time * 9)) * abs(cos(time * 5))
        VoicePhase.Thinking -> 0.3f + 0.1f * sin(time * 6)
        VoicePhase.Paused -> 0.05f
    }
    val spin = if (state.phase == VoicePhase.Thinking) time * 6 else time
    val alpha by animateFloatAsState(if (state.phase == VoicePhase.Paused) 0.55f else 1f, tween(500), label = "orbAlpha")
    Canvas(Modifier.size(280.dp)) {
        val c = center
        val base = size.minDimension * 0.26f
        // Outer glow, wider with more energy.
        drawCircle(
            Brush.radialGradient(listOf(start.copy(alpha = 0.55f * alpha), end.copy(alpha = 0.18f * alpha), Color.Transparent), c, base * (1.9f + energy * 0.5f)),
            radius = base * (1.9f + energy * 0.5f), center = c
        )
        // Rings that ripple outward with the voice.
        for (i in 0 until 3) {
            val t = ((time / (2 * PI).toFloat()) * 3 + i / 3f) % 1f
            drawCircle(
                color = lerp(start, end, t).copy(alpha = (1 - t) * 0.35f * energy * alpha),
                radius = base * (1.05f + t * (0.5f + energy * 0.6f)),
                center = c,
                style = Stroke(width = 2.dp.toPx())
            )
        }
        // A rotating rim of light.
        rotate(Math.toDegrees(spin.toDouble()).toFloat(), c) {
            drawCircle(
                Brush.sweepGradient(listOf(start, end, Color.White.copy(alpha = 0.9f), start), c),
                radius = base * (1.08f + energy * 0.08f), center = c, alpha = alpha,
                style = Stroke(width = 3.dp.toPx())
            )
        }
        // The sphere: lit from the top left.
        val r = base * (1f + energy * 0.08f)
        drawCircle(
            Brush.radialGradient(
                listOf(lerp(end, Color.White, 0.35f), start, lerp(start, Night, 0.55f)),
                center = Offset(c.x - r * 0.35f, c.y - r * 0.4f), radius = r * 1.6f
            ),
            radius = r, center = c, alpha = alpha
        )
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent), Offset(c.x - r * 0.35f, c.y - r * 0.45f), r * 0.5f),
            radius = r * 0.5f, center = Offset(c.x - r * 0.35f, c.y - r * 0.45f)
        )
    }
}

@Composable
private fun PhaseChip(phase: VoicePhase, since: Long, accent: Color) {
    // Listening and thinking have time limits, so they show how long they've been going.
    val limit = when (phase) {
        VoicePhase.Listening -> ChatViewModel.VOICE_LISTEN_LIMIT_MS
        VoicePhase.Thinking -> ChatViewModel.VOICE_THINK_LIMIT_MS
        else -> null
    }
    val elapsed by produceState(0L, phase, since) {
        while (true) {
            value = System.currentTimeMillis() - since
            delay(500)
        }
    }
    val label = when (phase) {
        VoicePhase.Listening -> "Listening"
        VoicePhase.Thinking -> "Thinking"
        VoicePhase.Speaking -> "Speaking"
        VoicePhase.Paused -> "Paused"
    }
    val blink by rememberInfiniteTransition(label = "blink").animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot"
    )
    Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.1f)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(if (phase == VoicePhase.Paused) Color.White.copy(alpha = 0.5f) else accent.copy(alpha = blink))
            )
            Spacer(Modifier.width(8.dp))
            AnimatedContent(phase, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "phase") { _ ->
                Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.Medium)
            }
            if (limit != null) {
                // Turns amber in the last 30 seconds before the limit.
                val nearLimit = limit - elapsed < 30_000
                Spacer(Modifier.width(8.dp))
                Text(
                    "${clock(elapsed)} / ${clock(limit)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (nearLimit) Color(0xFFFFB547) else Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/** Bars that move with the user's voice, or ripple while the reply is spoken. */
@Composable
private fun Waveform(state: VoiceState, start: Color, end: Color, time: Float) {
    val level by animateFloatAsState(if (state.phase == VoicePhase.Listening) state.level else 0f, tween(80), label = "bars")
    val bars = 28
    Canvas(Modifier.width(220.dp).height(36.dp)) {
        val gap = size.width / bars
        val barWidth = gap * 0.45f
        for (i in 0 until bars) {
            // Taller in the middle, like a voice print.
            val shape = sin(PI.toFloat() * (i + 0.5f) / bars)
            val wobble = 0.5f + 0.5f * sin(time * 7 + i * 0.9f) * cos(time * 3 + i * 0.4f)
            val amount = when (state.phase) {
                VoicePhase.Listening -> 0.08f + level * shape * (0.5f + 0.5f * wobble)
                VoicePhase.Speaking -> 0.12f + 0.55f * shape * wobble
                VoicePhase.Thinking -> 0.08f + 0.1f * abs(sin(time * 4 + i * 0.5f))
                VoicePhase.Paused -> 0.06f
            }.coerceIn(0.04f, 1f)
            val h = size.height * amount
            val x = i * gap + (gap - barWidth) / 2
            drawRoundRect(
                color = lerp(start, end, i / (bars - 1f)).copy(alpha = if (state.phase == VoicePhase.Paused) 0.35f else 0.9f),
                topLeft = Offset(x, (size.height - h) / 2),
                size = androidx.compose.ui.geometry.Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2)
            )
        }
    }
}

/** What the user said, and what's being said back. */
@Composable
private fun Captions(state: VoiceState) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp).height(190.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        val heard = state.heard.takeIf { it.isNotBlank() && it != "…" }
        if (heard != null && state.phase != VoicePhase.Paused) {
            Surface(shape = RoundedCornerShape(18.dp), color = Color.White.copy(alpha = 0.08f)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text("You", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
                    Text(heard, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val main = when (state.phase) {
            VoicePhase.Listening -> if (heard == null) "Go ahead, I'm listening." else ""
            VoicePhase.Thinking -> ""
            VoicePhase.Speaking -> state.reply
            VoicePhase.Paused -> state.notice ?: "Tap the mic when you're ready."
        }
        AnimatedContent(main, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "caption") { text ->
            Text(
                text,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 30.sp
            )
        }
    }
}

/**
 * One big button that does what makes sense right now. While thinking it becomes a glowing pill
 * with a shimmer and bouncing dots, and turns back into the button when it's time to talk.
 * (The close button at the top ends voice mode.)
 */
@Composable
private fun Controls(state: VoiceState, start: Color, end: Color, onTap: () -> Unit) {
    val thinking = state.phase == VoicePhase.Thinking
    Box(Modifier.fillMaxWidth().height(116.dp), contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = thinking,
            transitionSpec = {
                (fadeIn(tween(260)) + scaleIn(tween(320), initialScale = 0.7f)) togetherWith
                    (fadeOut(tween(180)) + scaleOut(tween(220), targetScale = 0.7f)) using
                    SizeTransform(clip = false)
            },
            label = "controls"
        ) { isThinking ->
            if (isThinking) ThinkingPill(start, end) else MainButton(state.phase, start, end, onTap)
        }
    }
}

@Composable
private fun MainButton(phase: VoicePhase, start: Color, end: Color, onTap: () -> Unit) {
    val (icon, label) = when (phase) {
        VoicePhase.Listening -> Icons.Default.Check to "Done talking"
        VoicePhase.Speaking -> Icons.Default.Stop to "Interrupt"
        else -> Icons.Default.Mic to "Tap to talk"
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = onTap, shape = CircleShape, color = Color.Transparent, modifier = Modifier.size(88.dp)) {
            Box(
                Modifier.fillMaxSize().background(Brush.linearGradient(listOf(start, end)), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    icon,
                    transitionSpec = { (scaleIn(initialScale = 0.4f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.4f) + fadeOut()) },
                    label = "icon"
                ) { Icon(it, contentDescription = label, tint = Color.White, modifier = Modifier.size(36.dp)) }
            }
        }
        Spacer(Modifier.height(8.dp))
        AnimatedContent(label, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "label") {
            Text(it, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
        }
    }
}

/**
 * Shown while the reply is being made: a soft pill with light sweeping across it in the
 * personality's colours, three dots bouncing in turn, and a breathing glow behind.
 */
@Composable
private fun ThinkingPill(start: Color, end: Color) {
    val transition = rememberInfiniteTransition(label = "thinking")
    val sweep by transition.animateFloat(
        initialValue = -0.6f, targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing)), label = "sweep"
    )
    val bounce by transition.animateFloat(
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing)), label = "bounce"
    )
    val glow by transition.animateFloat(
        initialValue = 0.35f, targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(1_100), RepeatMode.Reverse), label = "glow"
    )
    Box(contentAlignment = Alignment.Center) {
        // A breathing halo in the personality's colours.
        Box(
            Modifier
                .size(width = 260.dp, height = 84.dp)
                .alpha(glow)
                .background(Brush.radialGradient(listOf(start.copy(alpha = 0.55f), end.copy(alpha = 0.2f), Color.Transparent)), RoundedCornerShape(50))
        )
        Box(
            Modifier
                .size(width = 220.dp, height = 60.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.08f))
                .drawBehind {
                    // A band of light moving left to right.
                    val x = size.width * sweep
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, start.copy(alpha = 0.55f), end.copy(alpha = 0.7f), Color.Transparent),
                            startX = x - size.width * 0.45f, endX = x + size.width * 0.45f
                        )
                    )
                }
                .border(1.dp, Brush.linearGradient(listOf(start.copy(alpha = 0.7f), end.copy(alpha = 0.7f))), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (i in 0 until 3) {
                    // Each dot rises and falls in turn.
                    val phase = ((bounce - i * 0.33f) % 1f + 1f) % 1f
                    val lift = if (phase < 0.5f) sin(phase * 2 * PI.toFloat()) else 0f
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .graphicsLayer { translationY = -lift * 8.dp.toPx() }
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(lerp(start, end, i / 2f).copy(alpha = 0.6f + 0.4f * lift))
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text("Thinking", style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** 75_000 ms → "1:15". */
private fun clock(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * Part of voice mode arriving late in the reveal: it fades in from [from] (0 to 1 of the reveal)
 * while sliding by [slide] or growing from [scaleFrom], so the screen assembles itself.
 */
private fun Modifier.staggered(reveal: Float, from: Float, slide: Dp = 0.dp, scaleFrom: Float = 1f): Modifier = graphicsLayer {
    val t = ((reveal - from) / (1f - from)).coerceIn(0f, 1f)
    // Ease out, so each part settles softly.
    val eased = 1f - (1f - t) * (1f - t) * (1f - t)
    alpha = eased
    translationY = slide.toPx() * (1f - eased)
    val scale = scaleFrom + (1f - scaleFrom) * eased
    scaleX = scale
    scaleY = scale
}

