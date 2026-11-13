package ai.impact.ui.chat

import android.graphics.Bitmap
import android.os.Build
import ai.impact.ChatMessage
import ai.impact.ChatMode
import ai.impact.GenerationState
import ai.impact.data.Persona
import ai.impact.image.GeneratedImage
import ai.impact.image.GenerationPhase
import ai.impact.ui.components.PersonaAvatar
import ai.impact.ui.image.formatClock
import ai.impact.ui.image.rememberImageBitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** How the chat finds, draws and acts on images it generated. */
class ChatImageActions(
    val find: (String) -> GeneratedImage?,
    val decode: (GeneratedImage, Int) -> Bitmap?,
    val onOpen: (GeneratedImage) -> Unit,
    val onShare: (GeneratedImage) -> Unit,
    val onSave: (GeneratedImage) -> Unit
)

private val BubbleShape = RoundedCornerShape(6.dp, 22.dp, 22.dp, 22.dp)
private val MaxImageWidth = 280.dp

/** Chooses whether sending makes a chat reply or an image. */
@Composable
internal fun ModeSwitch(
    mode: ChatMode,
    enabled: Boolean,
    hasImageModel: Boolean,
    onChange: (ChatMode) -> Unit,
    onGetImageModel: () -> Unit
) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ModeChip("Chat", Icons.Outlined.ChatBubbleOutline, mode == ChatMode.Text, enabled) { onChange(ChatMode.Text) }
            ModeChip("Image", Icons.Outlined.Image, mode == ChatMode.Image, enabled) { onChange(ChatMode.Image) }
        }
        AnimatedVisibility(
            visible = mode == ChatMode.Image && !hasImageModel,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Images need an image model on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onGetImageModel) { Text("Get one") }
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
        shape = RoundedCornerShape(50)
    )
}

/**
 * An image the assistant made, sized from its stored dimensions before it decodes so the list
 * never jumps, then faded in. While painting it shows the live preview and progress instead.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ImageMessageBubble(message: ChatMessage, request: String?, persona: Persona, images: ChatImageActions) {
    val progress = message.imageProgress
    val image = message.imageId?.let(images.find)
    val width = progress?.request?.width ?: message.imageWidth ?: image?.width ?: 1
    val height = progress?.request?.height ?: message.imageHeight ?: image?.height ?: 1
    // The caption is the enhanced prompt; skip it when it just repeats the request.
    val caption = message.text.takeIf { it.isNotBlank() && !it.equals(request?.trim(), ignoreCase = true) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        PersonaAvatar(persona, size = 28.dp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val bubbleWidth = if (maxWidth < MaxImageWidth) maxWidth else MaxImageWidth
                val sidePx = with(LocalDensity.current) { bubbleWidth.roundToPx() }
                var menuOpen by remember { mutableStateOf(false) }
                Box(
                    Modifier
                        .width(bubbleWidth)
                        .aspectRatio(width.toFloat() / height)
                        .clip(BubbleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .combinedClickable(
                            enabled = image != null,
                            onClick = { image?.let(images.onOpen) },
                            onLongClick = { menuOpen = true }
                        )
                ) {
                    when {
                        progress != null -> PaintingState(progress)
                        image != null -> DecodedImage(image, sidePx, images)
                        else -> Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Outlined.HideImage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            Text("This image was deleted", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (image != null) {
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Share") },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                onClick = { menuOpen = false; images.onShare(image) }
                            )
                            DropdownMenuItem(
                                text = { Text("Save to Photos") },
                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                onClick = { menuOpen = false; images.onSave(image) }
                            )
                        }
                    }
                }
            }
            if (caption != null) Caption(caption)
            if (image != null && progress == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    SmallAction(Icons.Default.Share, "Share") { images.onShare(image) }
                    SmallAction(Icons.Default.Download, "Save to Photos") { images.onSave(image) }
                    SmallAction(Icons.Default.OpenInFull, "View full screen") { images.onOpen(image) }
                }
            }
        }
        Spacer(Modifier.width(24.dp))
    }
}

@Composable
private fun DecodedImage(image: GeneratedImage, sidePx: Int, images: ChatImageActions) {
    // Decoded at the bubble's size (not the full image), off the main thread, and cached.
    val bitmap = rememberImageBitmap(image, sidePx, images.decode)
    val alpha by animateFloatAsState(if (bitmap != null) 1f else 0f, tween(260), label = "imageFade")
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = image.prompt,
            // The bubble has the image's exact aspect ratio, so nothing is cropped.
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.High,
            modifier = Modifier
                .fillMaxSize()
                .alpha(alpha)
        )
    }
}

@Composable
private fun PaintingState(state: GenerationState.Running) {
    val elapsed by produceState(0L, state.startedAt) {
        while (true) {
            value = System.currentTimeMillis() - state.startedAt
            delay(500)
        }
    }
    val fraction by animateFloatAsState(state.progress, tween(400), label = "paintProgress")
    val shimmer = rememberInfiniteTransition(label = "paintShimmer").animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "paintShimmerOffset"
    )
    Box(Modifier.fillMaxSize()) {
        val preview = state.preview
        if (preview != null) {
            Image(
                bitmap = remember(preview) { preview.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(6.dp) else Modifier)
            )
        } else {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp)
            )
        }
        val base = MaterialTheme.colorScheme.surfaceContainerHigh
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = if (preview == null) listOf(Color.Transparent, base.copy(alpha = 0.6f), Color.Transparent)
                        else listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent),
                        start = Offset(shimmer.value * 600f, 0f),
                        end = Offset(shimmer.value * 600f + 450f, 450f)
                    )
                )
        )
        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            contentColor = Color.White,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (val phase = state.phase) {
                            GenerationPhase.EnhancingPrompt -> "Polishing your prompt…"
                            GenerationPhase.EncodingPrompt -> "Reading your prompt…"
                            is GenerationPhase.Denoising -> if (phase.total == 1) "Painting…" else "Step ${phase.step + 1} of ${phase.total}"
                            GenerationPhase.Decoding -> "Adding final details…"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(formatClock(elapsed), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.2f),
                    drawStopIndicator = {}
                )
            }
        }
    }
}

@Composable
private fun Caption(text: String) {
    var expanded by remember(text) { mutableStateOf(false) }
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickableCompat { expanded = !expanded }
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .animateContentSize(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Default.AutoFixHigh,
            contentDescription = "Enhanced prompt",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.combinedClickable(onClick = onClick)

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}
