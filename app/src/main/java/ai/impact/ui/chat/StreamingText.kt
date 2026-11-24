package ai.impact.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import kotlinx.coroutines.flow.first
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Slowest reveal speed, in characters per second, so short gaps still read as steady typing. */
private const val MIN_CHARS_PER_SECOND = 48f
/** How hard the reveal chases the text that has arrived: the backlog drains in roughly 1/this seconds. */
private const val CATCH_UP = 5f
/** How long a freshly revealed character takes to fade to full ink. */
private const val FADE_SECONDS = 0.14f
/** Markdown markers are revealed together with what follows so they never flash on screen. */
private const val MARKERS = "*_`~#"

/**
 * Turns bursty token arrival into an even, frame-paced reveal.
 *
 * [visible] is how many characters to draw; the characters between [settled] and [visible]
 * are still fading in, which [fade] reports for [fadeTail].
 */
@Stable
class SmoothReveal internal constructor(initial: Int) {
    var visible by mutableIntStateOf(initial)
        private set
    private var settled by mutableFloatStateOf(initial.toFloat())
    private var cursor = initial.toFloat()

    /** Number of trailing characters that are still fading in. */
    val fade: Float get() = (visible - settled).coerceAtLeast(0f)

    /** True once everything that has arrived is on screen at full ink. */
    val isIdle: Boolean get() = fade < 0.5f

    internal suspend fun run(target: State<String>) {
        var lastNanos = 0L
        while (true) {
            val length = target.value.length
            if (length < visible) snapTo(length)
            if (visible >= length && fade < 0.5f) {
                settled = visible.toFloat()
                lastNanos = 0L
                // Nothing to animate: sleep until more text arrives (or it is replaced).
                snapshotFlow { target.value.length }.first { it != visible }
                continue
            }
            withFrameNanos { now ->
                val dt = if (lastNanos == 0L) 1f / 60f else ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
                lastNanos = now
                val text = target.value
                if (text.length < visible) {
                    snapTo(text.length)
                    return@withFrameNanos
                }
                val backlog = text.length - cursor
                val speed = max(MIN_CHARS_PER_SECOND, backlog * CATCH_UP)
                cursor = min(text.length.toFloat(), cursor + speed * dt)

                var next = cursor.toInt()
                while (next < text.length && text[next] in MARKERS) next++
                if (next in 1 until text.length && text[next - 1].isHighSurrogate()) next++
                if (next > cursor) cursor = next.toFloat()
                visible = next

                settled += (visible - settled) * (1f - exp(-dt / FADE_SECONDS))
            }
        }
    }

    private fun snapTo(length: Int) {
        visible = length
        cursor = length.toFloat()
        settled = length.toFloat()
    }
}

/**
 * Remembers a [SmoothReveal] for [text]. Text that is already complete when first shown
 * (history, or scrolling back to a message) appears at once; live text types in smoothly.
 */
@Composable
fun rememberSmoothReveal(key: Any, text: String, streaming: Boolean): SmoothReveal {
    val reveal = remember(key) { SmoothReveal(if (streaming && text.length < 80) 0 else text.length) }
    val latest = rememberUpdatedState(text)
    LaunchedEffect(reveal) { reveal.run(latest) }
    return reveal
}

/** Fades the last [chars] characters from full [color] down to transparent, like wet ink. */
fun AnnotatedString.fadeTail(chars: Float, color: Color): AnnotatedString {
    if (chars < 0.5f || isEmpty()) return this
    val total = length
    val count = min(ceil(chars).toInt(), total)
    return buildAnnotatedString {
        append(this@fadeTail)
        for (fromEnd in 0 until count) {
            val t = ((fromEnd + 0.5f) / chars).coerceIn(0f, 1f)
            val alpha = t * t * (3f - 2f * t) // smoothstep
            val index = total - 1 - fromEnd
            addStyle(SpanStyle(color = color.copy(alpha = color.alpha * alpha)), index, index + 1)
        }
    }
}
