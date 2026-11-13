package ai.impact.ui.image

import android.app.Activity
import android.graphics.Bitmap
import ai.impact.image.GeneratedImage
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min

private const val DOUBLE_TAP_ZOOM = 2.5f
/** On-screen pixels per image pixel above which the image is drawn unsmoothed, showing true pixels. */
private const val PIXEL_GRID_THRESHOLD = 4f

@Composable
fun ImageViewerScreen(
    images: List<GeneratedImage>,
    initialImageId: String,
    decode: (GeneratedImage, Int) -> Bitmap?,
    onBack: () -> Unit,
    onShare: (GeneratedImage) -> Unit,
    onSave: (GeneratedImage) -> Unit,
    onRemix: (GeneratedImage) -> Unit,
    onDelete: (GeneratedImage) -> Unit
) {
    BackHandler(onBack = onBack)
    if (images.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    LightSystemBarIcons()

    val initialPage = remember { images.indexOfFirst { it.id == initialImageId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = initialPage) { images.size }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<GeneratedImage?>(null) }
    val clipboard = LocalClipboardManager.current
    val current = images.getOrNull(pager.currentPage) ?: images.last()

    LaunchedEffect(pager.currentPage) { zoomed = false }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pager,
            key = { images[it].id },
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            pageSpacing = 16.dp,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val image = images[page]
            // A small decode shows instantly; the full-resolution bitmap replaces it for exact pixels.
            val full = rememberImageBitmap(image, 0, decode)
            val placeholder = rememberImageBitmap(image, 256, decode)
            val bitmap = full ?: placeholder
            if (bitmap != null) {
                ZoomableImage(
                    bitmap = bitmap,
                    sourceWidth = image.width.takeIf { it > 0 } ?: bitmap.width,
                    sourceHeight = image.height.takeIf { it > 0 } ?: bitmap.height,
                    contentDescription = image.prompt,
                    onTap = { chromeVisible = !chromeVisible },
                    onZoomChanged = { if (page == pager.currentPage) zoomed = it }
                )
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text(
                    "${pager.currentPage + 1} of ${images.size}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { confirmDelete = current }) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = Color.White)
                }
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            DetailsPanel(
                image = current,
                onShare = { onShare(current) },
                onSave = { onSave(current) },
                onCopyPrompt = { clipboard.setText(AnnotatedString(current.prompt)) },
                onRemix = { onRemix(current) }
            )
        }
    }

    confirmDelete?.let { image ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this image?") },
            text = { Text("It's removed from this app. A copy saved to Photos is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(image)
                    confirmDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }
}

/** The viewer is always black, so status/navigation bar icons must be light whatever the app theme. */
@Composable
private fun LightSystemBarIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            previousStatus?.let { controller.isAppearanceLightStatusBars = it }
            previousNav?.let { controller.isAppearanceLightNavigationBars = it }
        }
    }
}

@Composable
private fun DetailsPanel(
    image: GeneratedImage,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onCopyPrompt: () -> Unit,
    onRemix: () -> Unit
) {
    var expanded by remember(image.id) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 12.dp)
    ) {
        Text(
            image.prompt,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            maxLines = if (expanded) 12 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .animateContentSize()
                .clickable { expanded = !expanded }
        )
        Spacer(Modifier.height(6.dp))
        Text(
            listOfNotNull(
                image.modelName.takeIf { it.isNotBlank() },
                "${image.width} × ${image.height}",
                "${image.steps} ${if (image.steps == 1) "step" else "steps"}",
                "Seed ${image.seed}",
                formatDuration(image.durationMillis)
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.7f)
        )
        if (expanded && image.enhancedPrompt != null) {
            Text(
                "Enhanced: ${image.enhancedPrompt}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (expanded) {
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(image.createdAt)),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            ViewerAction(Icons.Default.Share, "Share", onShare)
            ViewerAction(Icons.Default.Download, "Save", onSave)
            ViewerAction(Icons.Default.ContentCopy, "Copy prompt", onCopyPrompt)
            ViewerAction(Icons.Default.Refresh, "Remix", onRemix)
        }
    }
}

@Composable
private fun ViewerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, contentColor = Color.White, shape = RoundedCornerShape(16.dp)) {
        Column(
            Modifier
                .width(76.dp)
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/**
 * Fits the image to the screen, then supports pinch zoom, panning (clamped so the image never
 * leaves the screen) and double-tap zoom. Single-finger drags at 1x are left to the pager.
 *
 * [sourceWidth]/[sourceHeight] are the image's true size, so the layout is identical while a
 * downsampled placeholder is showing and nothing jumps when the full image arrives.
 */
@Composable
private fun ZoomableImage(
    bitmap: ImageBitmap,
    sourceWidth: Int,
    sourceHeight: Int,
    contentDescription: String,
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clipToBounds()
    ) {
        val containerW = constraints.maxWidth.toFloat()
        val containerH = constraints.maxHeight.toFloat()
        val fit = min(containerW / sourceWidth, containerH / sourceHeight)
        val fittedW = sourceWidth * fit
        val fittedH = sourceHeight * fit
        // Allow zooming in until each image pixel covers ~10 screen pixels.
        val maxScale = max(4f, 10f / fit)

        fun clamp(o: Offset, s: Float): Offset {
            val maxX = max(0f, (fittedW * s - containerW) / 2f)
            val maxY = max(0f, (fittedH * s - containerH) / 2f)
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }

        fun zoomTo(target: Float, focus: Offset) {
            val startScale = scale
            val startOffset = offset
            val center = Offset(containerW / 2f, containerH / 2f)
            // Keep the tapped point under the finger as the zoom animates.
            val focusFromCenter = focus - center
            scope.launch {
                animate(startScale, target, animationSpec = tween(260)) { value, _ ->
                    val k = value / startScale
                    scale = value
                    offset = clamp((startOffset - focusFromCenter) * k + focusFromCenter, value)
                    onZoomChanged(value > 1.01f)
                }
                if (target <= 1f) offset = Offset.Zero
            }
        }

        val onScreenPixelSize = fit * scale
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            // Smooth when fitted; past ~4x, draw real pixels crisp instead of blurring them.
            filterQuality = if (onScreenPixelSize >= PIXEL_GRID_THRESHOLD) FilterQuality.None else FilterQuality.High,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(fit) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { tap -> zoomTo(if (scale > 1.01f) 1f else DOUBLE_TAP_ZOOM, tap) }
                    )
                }
                .pointerInput(fit, maxScale) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val fingers = event.changes.count { it.pressed }
                            if (fingers > 1 || scale > 1.01f) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val newScale = (scale * zoom).coerceIn(1f, maxScale)
                                if (centroid != Offset.Unspecified) {
                                    val focus = centroid - Offset(containerW / 2f, containerH / 2f)
                                    val k = newScale / scale
                                    offset = clamp((offset - focus) * k + focus + pan, newScale)
                                }
                                scale = newScale
                                if (newScale <= 1f) offset = Offset.Zero
                                onZoomChanged(newScale > 1.01f)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
    }
}
