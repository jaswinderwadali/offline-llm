package ai.impact.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Colours for one time of day: a soft vertical wash plus three glows that drift over it. */
private class CalmPalette(val top: Color, val bottom: Color, val glows: List<Color>)

// Day: warm paper fading into a cool morning mist, with peach, sage and lavender light.
private val Day = CalmPalette(
    top = Color(0xFFF8F4ED),
    bottom = Color(0xFFEEF1F4),
    glows = listOf(Color(0xFFFFD8C4).copy(alpha = 0.55f), Color(0xFFCDE8DB).copy(alpha = 0.50f), Color(0xFFDAD6F5).copy(alpha = 0.45f))
)

// Night: deep ink blue with faint indigo, teal and ember glows, quiet enough to read on.
private val Night = CalmPalette(
    top = Color(0xFF13151C),
    bottom = Color(0xFF0D1117),
    glows = listOf(Color(0xFF3C3F92).copy(alpha = 0.22f), Color(0xFF1B5A53).copy(alpha = 0.20f), Color(0xFF6E3524).copy(alpha = 0.15f))
)

/**
 * A calm, slowly breathing gradient behind a screen. It follows the app's light/dark theme, and
 * [accent] (e.g. the persona's colour) lightly tints the first glow so each personality feels
 * its own. Animation is read only while drawing, so it never recomposes the screen.
 */
@Composable
fun Modifier.calmBackground(accent: Color? = null): Modifier {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val palette = if (isDark) Night else Day
    val glows = remember(palette, accent) {
        if (accent == null) palette.glows
        else listOf(blend(palette.glows[0], accent, if (isDark) 0.45f else 0.35f)) + palette.glows.drop(1)
    }
    val transition = rememberInfiniteTransition(label = "calm")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(48_000, easing = LinearEasing), RepeatMode.Restart),
        label = "calmPhase"
    )
    return drawBehind {
        drawRect(Brush.verticalGradient(listOf(palette.top, palette.bottom)))
        val t = phase.value * 2f * PI.toFloat()
        val r = max(size.width, size.height)
        // Each glow orbits its own anchor on a different rhythm, so the pattern never visibly repeats.
        glow(glows[0], 0.15f + 0.08f * sin(t), 0.12f + 0.05f * cos(t), r * 0.62f)
        glow(glows[1], 0.88f + 0.06f * cos(t + 2.1f), 0.48f + 0.07f * sin(2 * t + 1f), r * 0.55f)
        glow(glows[2], 0.30f + 0.09f * sin(t + 4.2f), 0.92f + 0.04f * cos(t), r * 0.60f)
    }
}

private fun DrawScope.glow(color: Color, fx: Float, fy: Float, radius: Float) {
    val center = Offset(size.width * fx, size.height * fy)
    drawCircle(
        Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center = center, radius = radius),
        radius = radius,
        center = center
    )
}

private fun blend(base: Color, tint: Color, amount: Float) = Color(
    red = base.red + (tint.red - base.red) * amount,
    green = base.green + (tint.green - base.green) * amount,
    blue = base.blue + (tint.blue - base.blue) * amount,
    alpha = base.alpha
)
