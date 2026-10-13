package ai.impact.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The model's reasoning, shown above its answer.
 * Live: a shimmering "Thinking…" header with a timer and a faded preview of the newest thoughts.
 * Finished: a compact "Thought for Ns" chip that expands to the full reasoning.
 */
@Composable
fun ThinkingBlock(
    messageId: Long,
    thinking: String,
    isLive: Boolean,
    isComplete: Boolean,
    startedAt: Long,
    thinkingMillis: Long?,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }

    Column(modifier.animateContentSize()) {
        AnimatedContent(
            targetState = isLive,
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
            label = "thinkingHeader"
        ) { live ->
            if (live) {
                LiveThinkingHeader(startedAt)
            } else {
                ThoughtChip(
                    label = when {
                        thinkingMillis != null -> "Thought for ${formatDuration(thinkingMillis)}"
                        isComplete -> "Thoughts"
                        else -> "Thinking stopped"
                    },
                    expanded = expanded,
                    onClick = { expanded = !expanded }
                )
            }
        }

        if (isLive) {
            LivePreview(thinking)
        }

        AnimatedVisibility(
            visible = !isLive && expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            ThoughtBody(thinking.ifBlank { "No reasoning was recorded." })
        }
    }
}

@Composable
private fun LiveThinkingHeader(startedAt: Long) {
    var elapsed by remember { mutableLongStateOf(System.currentTimeMillis() - startedAt) }
    LaunchedEffect(startedAt) {
        while (true) {
            elapsed = System.currentTimeMillis() - startedAt
            delay(250)
        }
    }

    val transition = rememberInfiniteTransition(label = "thinking")
    val pulse by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse"
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing)),
        label = "spin"
    )
    val shimmer by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "shimmer"
    )

    val base = MaterialTheme.colorScheme.onSurfaceVariant
    val highlight = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Box(
            Modifier
                .size(26.dp)
                .scale(pulse)
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.sweepGradient(
                        listOf(highlight.copy(alpha = 0.25f), highlight.copy(alpha = 0.05f), highlight.copy(alpha = 0.25f))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = highlight,
                modifier = Modifier
                    .size(15.dp)
                    .rotate(spin)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            "Thinking…",
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.SemiBold,
                brush = Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = androidx.compose.ui.geometry.Offset(shimmer * 300f - 150f, 0f),
                    end = androidx.compose.ui.geometry.Offset(shimmer * 300f + 150f, 0f)
                )
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            formatDuration(elapsed),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

/** Last few lines of the live reasoning, fading out at the top like a teleprompter. */
@Composable
private fun LivePreview(thinking: String) {
    if (thinking.isBlank()) return
    val fade = thinking.length > 160
    Row(
        Modifier
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
            .height(IntrinsicSize.Min)
    ) {
        ThoughtRail()
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 76.dp)
                .clipToBounds()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (fade) {
                        drawRect(
                            brush = Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Black),
                            blendMode = BlendMode.DstIn
                        )
                    }
                },
            contentAlignment = Alignment.BottomStart
        ) {
            // Measured at full height and bottom-aligned, so the newest lines stay visible.
            Text(
                thinking.takeLast(600),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.wrapContentHeight(Alignment.Bottom, unbounded = true)
            )
        }
    }
}

@Composable
private fun ThoughtChip(label: String, expanded: Boolean, onClick: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Hide reasoning" else "Show reasoning",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(rotation)
            )
        }
    }
}

@Composable
private fun ThoughtBody(thinking: String) {
    Row(
        Modifier
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp)
            .height(IntrinsicSize.Min)
    ) {
        ThoughtRail()
        Spacer(Modifier.width(12.dp))
        MarkdownText(
            thinking,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ThoughtRail() {
    Box(
        Modifier
            .width(2.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(1.dp))
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f))
                )
            )
    )
}

private fun formatDuration(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(1)
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}
