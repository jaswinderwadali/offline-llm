package ai.impact.ui.image

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import android.widget.Toast
import ai.impact.image.GeneratedImage
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Decoded thumbnails and full images, bounded by bytes so scrolling a big gallery stays smooth. */
internal object ImageCache {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}

/**
 * Loads [image] off the main thread. [maxSide] 0 means full resolution (needed for pixel-exact
 * viewing); thumbnails pass their on-screen size so the grid never holds full images.
 */
@Composable
internal fun rememberImageBitmap(
    image: GeneratedImage,
    maxSide: Int,
    decode: (GeneratedImage, Int) -> Bitmap?
): ImageBitmap? {
    val key = "${image.id}@$maxSide"
    val state = produceState(initialValue = ImageCache.get(key)?.asImageBitmap(), key) {
        if (value != null) return@produceState
        val bitmap = withContext(Dispatchers.IO) {
            // Start the GPU upload off the main thread (a no-op for hardware bitmaps) so the first frame is smooth.
            decode(image, maxSide)?.also { it.prepareToDraw() }
        } ?: return@produceState
        ImageCache.put(key, bitmap)
        value = bitmap.asImageBitmap()
    }
    return state.value
}

/** A thumbnail that fades in once decoded, over a neutral tile so the grid never jumps. */
@Composable
internal fun Thumbnail(
    image: GeneratedImage,
    sizePx: Int,
    decode: (GeneratedImage, Int) -> Bitmap?,
    modifier: Modifier = Modifier
) {
    val bitmap = rememberImageBitmap(image, sizePx, decode)
    val alpha by animateFloatAsState(if (bitmap != null) 1f else 0f, tween(220), label = "thumb")
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = image.prompt,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize().alpha(alpha)
            )
        }
    }
}

internal fun Context.shareImage(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "No app available to share with", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Returns a "save to Photos" action. Android 10+ saves straight away; older versions ask for
 * the storage permission first.
 */
@Composable
internal fun rememberSaveToPhotos(save: (GeneratedImage) -> Unit): (GeneratedImage) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<GeneratedImage?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val image = pending
        pending = null
        if (granted && image != null) save(image)
        else if (!granted) Toast.makeText(context, "Storage permission is needed to save to Photos", Toast.LENGTH_SHORT).show()
    }
    return remember(save) {
        { image ->
            val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            if (needsPermission) {
                pending = image
                launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                save(image)
            }
        }
    }
}

internal fun formatDuration(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return if (seconds < 60) "${seconds}s" else String.format(Locale.US, "%dm %02ds", seconds / 60, seconds % 60)
}

internal fun formatClock(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
}
