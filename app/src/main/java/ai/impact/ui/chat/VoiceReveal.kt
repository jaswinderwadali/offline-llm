package ai.impact.ui.chat

import ai.impact.VoiceState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.hypot
import kotlin.math.max

/** Material's emphasized easing: quick to start, long gentle landing. */
private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Shows [content] while voice mode is on, growing it as a circle out of [origin] (the composer's
 * mic button) and shrinking it back into the mic when it ends, so moving between typing and
 * talking feels like one continuous motion.
 */
@Composable
internal fun VoiceReveal(
    voice: VoiceState?,
    origin: Offset?,
    content: @Composable (state: VoiceState, reveal: Float, active: Boolean) -> Unit
) {
    // The last state, so the overlay can keep showing while it closes.
    var last by remember { mutableStateOf(voice) }
    if (voice != null) last = voice
    val progress = remember { Animatable(if (voice != null) 1f else 0f) }
    LaunchedEffect(voice != null) {
        if (voice != null) progress.animateTo(1f, tween(560, easing = Emphasized))
        else progress.animateTo(0f, tween(420, easing = Emphasized))
    }
    val shown = last
    if (shown == null || (voice == null && progress.value == 0f)) return
    Box(Modifier.fillMaxSize().clip(CircleReveal(origin, progress.value))) {
        content(shown, progress.value, voice != null)
    }
}

/** A circle around [origin] whose radius grows from nothing to covering every corner. */
private class CircleReveal(private val origin: Offset?, private val fraction: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        // Without a known button, grow from the bottom centre, where the composer is.
        val c = origin ?: Offset(size.width / 2, size.height)
        val farthest = max(
            max(hypot(c.x, c.y), hypot(size.width - c.x, c.y)),
            max(hypot(c.x, size.height - c.y), hypot(size.width - c.x, size.height - c.y))
        )
        val r = farthest * fraction
        return Outline.Generic(Path().apply { addOval(Rect(c, r)) })
    }
}
