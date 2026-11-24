package ai.impact.ui.chat

import ai.impact.VoicePhase
import ai.impact.VoiceState
import ai.impact.data.Persona
import ai.impact.ui.components.PersonaAvatar
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

private val Ink = Color(0xFF040507)

// Galaxy-style light: cool blue, violet and cyan, with the personality's own colour mixed in.
private val GlowBlue = Color(0xFF3D7BFF)
private val GlowViolet = Color(0xFF8E5BFF)
private val GlowCyan = Color(0xFF2ED3E6)

/**
 * Hands-free voice conversation: a calm, nearly black screen with large captions, a soft band of
 * flowing light along the bottom that moves with the conversation, and two round controls.
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
        // The screen is dark whatever the app's theme, so the status bar icons turn light.
        val window = (view.context as? android.app.Activity)?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val lightIcons = bars?.isAppearanceLightStatusBars
        val lightNav = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            view.keepScreenOn = false
            lightIcons?.let { bars.isAppearanceLightStatusBars = it }
            lightNav?.let { bars.isAppearanceLightNavigationBars = it }
        }
    }
    val accent = Color(persona.colorStart)
    // One continuous clock for the light. It's read only while drawing, so the screen redraws
    // each frame without recomposing, and nothing jumps when a loop would restart.
    val clock = rememberClock()
    val latest = rememberUpdatedState(state)

    // Swallows taps so the chat underneath can't be touched.
    Box(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
    ) {
        GlowBand(latest, accent, clock, Modifier.align(Alignment.BottomCenter).staggered(reveal, from = 0.1f, slide = 80.dp))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.staggered(reveal, from = 0.45f, slide = (-16).dp)) { TopBar(persona) }
            Box(Modifier.weight(1f).staggered(reveal, from = 0.4f, slide = 20.dp)) { Transcript(state) }
            Box(Modifier.staggered(reveal, from = 0.5f, slide = 32.dp)) { Controls(state.phase, onTap, onEnd) }
            Spacer(Modifier.height(28.dp))
        }
    }
}

// region Motion

/** Seconds since voice mode opened, from the frame clock: smooth, and it never wraps around. */
@Composable
private fun rememberClock(): State<Float> {
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val origin = withFrameNanos { it }
        while (true) withFrameNanos { clock.floatValue = (it - origin) / 1_000_000_000f }
    }
    return clock
}

/** Moves [current] toward [target], covering most of the way in about [seconds]: no jumps, ever. */
private fun ease(current: Float, target: Float, dt: Float, seconds: Float): Float =
    current + (target - current) * (1f - exp(-dt / seconds))

/** The light's state, eased every frame toward what the current phase wants. */
private class GlowMotion {
    var last = -1f
    /** How tall and bright the light is, 0 to 1. */
    var energy = 0.3f
    /** How fast the colours flow along the band. */
    var speed = 0.25f
    var flow = 0f
    var dim = 0f
}

// endregion

/**
 * The band of light along the bottom. Listening, it rises and falls with the user's voice;
 * thinking, its colours flow steadily; speaking, it swells softly; paused, it settles low.
 */
@Composable
private fun GlowBand(state: State<VoiceState>, accent: Color, clock: State<Float>, modifier: Modifier) {
    val motion = remember { GlowMotion() }
    Canvas(modifier.fillMaxWidth().fillMaxHeight(0.62f)) {
        val t = clock.value
        val dt = if (motion.last < 0f) 0f else (t - motion.last).coerceIn(0f, 0.1f)
        motion.last = t
        val phase = state.value.phase
        val target = when (phase) {
            VoicePhase.Listening -> 0.32f + state.value.level * 0.68f
            VoicePhase.Thinking -> 0.42f + 0.08f * sin(t * 1.9f)
            // Two slow waves together read as speech without flickering.
            VoicePhase.Speaking -> 0.55f + 0.16f * sin(t * 5.2f) * sin(t * 2.9f + 1f)
            VoicePhase.Paused -> 0.14f
        }
        motion.energy = ease(motion.energy, target, dt, if (phase == VoicePhase.Listening) 0.09f else 0.35f)
        motion.speed = ease(motion.speed, if (phase == VoicePhase.Thinking) 0.9f else 0.22f, dt, 0.8f)
        motion.flow += motion.speed * dt
        motion.dim = ease(motion.dim, if (phase == VoicePhase.Paused) 1f else 0f, dt, 0.4f)

        val w = size.width
        val h = size.height
        val e = motion.energy
        val f = motion.flow
        val brightness = 1f - 0.45f * motion.dim
        // Each light drifts along the bottom edge at its own pace; together they flow.
        val lights = listOf(
            GlowBlue to 0.0f,
            lerp(GlowViolet, accent, 0.35f) to 2.1f,
            GlowCyan to 4.2f,
            lerp(GlowBlue, accent, 0.55f) to 5.3f
        )
        lights.forEachIndexed { i, (color, offset) ->
            val x = w * (0.5f + 0.42f * sin(f * (0.8f + i * 0.17f) + offset))
            val y = h * (1.02f - 0.08f * cos(f * 0.7f + offset))
            val radius = w * (0.42f + 0.38f * e) * (0.9f + 0.1f * sin(t * 0.6f + i))
            drawCircle(
                Brush.radialGradient(
                    listOf(color.copy(alpha = 0.62f * brightness), color.copy(alpha = 0.22f * brightness), Color.Transparent),
                    center = Offset(x, y), radius = radius
                ),
                radius = radius, center = Offset(x, y)
            )
        }
        // A thin bright horizon where the light meets the edge of the screen.
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.10f * e * brightness)),
                startY = h * 0.85f, endY = h
            )
        )
        // Fades the light into the black above, so the captions sit on a clean background.
        drawRect(Brush.verticalGradient(listOf(Ink, Ink.copy(alpha = 0f)), startY = 0f, endY = h * 0.45f))
    }
}

@Composable
private fun TopBar(persona: Persona) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.08f)) {
            Row(Modifier.padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PersonaAvatar(persona, size = 28.dp)
                Spacer(Modifier.width(8.dp))
                Text(persona.name, style = MaterialTheme.typography.labelLarge, color = Color.White)
                Text("  ·  On this phone", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.5f))
            }
        }
    }
}

/** What the user said, and what's being said back, in large, plain type. */
@Composable
private fun Transcript(state: VoiceState) {
    val heard = state.heard.takeIf { it.isNotBlank() && it != "…" && state.phase != VoicePhase.Paused }
    val main = when (state.phase) {
        VoicePhase.Listening -> if (heard == null) "Go ahead, I'm listening." else ""
        VoicePhase.Thinking -> ""
        VoicePhase.Speaking -> state.reply
        VoicePhase.Paused -> state.notice ?: "Tap the mic when you're ready."
    }
    val scroll = rememberScrollState()
    // A long reply keeps its newest words in view.
    LaunchedEffect(main) { scroll.animateScrollTo(scroll.maxValue) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 28.dp, vertical = 24.dp)
    ) {
        StatusLabel(state.phase)
        Spacer(Modifier.height(18.dp))
        AnimatedContent(heard, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "heard") { text ->
            if (text != null) {
                Text(
                    text,
                    style = MaterialTheme.typography.titleMedium.copy(lineHeight = 26.sp),
                    color = Color.White.copy(alpha = if (state.phase == VoicePhase.Listening) 0.9f else 0.5f),
                    maxLines = if (state.phase == VoicePhase.Listening) 8 else 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (heard != null && main.isNotEmpty()) Spacer(Modifier.height(22.dp))
        AnimatedContent(
            main,
            transitionSpec = { (fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 8 }) togetherWith fadeOut(tween(180)) },
            label = "main"
        ) { text ->
            if (text.isNotEmpty()) {
                Text(
                    text,
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp, lineHeight = 35.sp),
                    color = Color.White,
                    fontWeight = FontWeight.Normal
                )
            }
        }
    }
}

/** "Listening", "Thinking"… with light passing through the word while it's busy. */
@Composable
private fun StatusLabel(phase: VoicePhase) {
    val label = when (phase) {
        VoicePhase.Listening -> "Listening"
        VoicePhase.Thinking -> "Thinking"
        VoicePhase.Speaking -> "Speaking"
        VoicePhase.Paused -> "Paused"
    }
    val shimmer by rememberInfiniteTransition(label = "status").animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1_800, easing = LinearEasing)), label = "shimmer"
    )
    AnimatedContent(label, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "statusLabel") { text ->
        val busy = phase == VoicePhase.Thinking || phase == VoicePhase.Listening
        Text(
            text,
            style = MaterialTheme.typography.titleSmall.copy(
                letterSpacing = 0.4.sp,
                brush = if (busy) Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.45f), Color.White, Color.White.copy(alpha = 0.45f)),
                    start = Offset(shimmer * 300f - 150f, 0f), end = Offset(shimmer * 300f + 150f, 0f)
                ) else null
            ),
            color = Color.White.copy(alpha = 0.6f)
        )
    }
}

/** The main button does the obvious thing right now; the other ends voice mode. */
@Composable
private fun Controls(phase: VoicePhase, onTap: () -> Unit, onEnd: () -> Unit) {
    val (icon, label) = when (phase) {
        VoicePhase.Listening -> Icons.Default.ArrowUpward to "Send now"
        VoicePhase.Speaking -> Icons.Default.Stop to "Interrupt"
        VoicePhase.Thinking -> Icons.Default.Mic to "Thinking…"
        VoicePhase.Paused -> Icons.Default.Mic to "Tap to talk"
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Top) {
        RoundControl(
            icon = icon, label = label,
            background = Color.White, tint = Ink,
            enabled = phase != VoicePhase.Thinking,
            onClick = onTap
        )
        Spacer(Modifier.width(36.dp))
        RoundControl(
            icon = Icons.Default.Close, label = "End",
            background = Color(0xFFE5484D), tint = Color.White,
            onClick = onEnd
        )
    }
}

@Composable
private fun RoundControl(icon: ImageVector, label: String, background: Color, tint: Color, onClick: () -> Unit, enabled: Boolean = true) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = if (enabled) background else Color.White.copy(alpha = 0.12f),
            modifier = Modifier.size(68.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                AnimatedContent(
                    icon,
                    transitionSpec = { (scaleIn(tween(220), initialScale = 0.6f) + fadeIn(tween(220))) togetherWith (scaleOut(tween(150), targetScale = 0.6f) + fadeOut(tween(150))) },
                    label = "controlIcon"
                ) {
                    Icon(it, contentDescription = label, tint = if (enabled) tint else Color.White.copy(alpha = 0.5f), modifier = Modifier.size(28.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
    }
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
