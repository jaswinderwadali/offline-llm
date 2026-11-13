package ai.impact.image

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class GeneratedImage(
    val id: String,
    val file: File,
    /** What the user typed. */
    val prompt: String,
    /** The text model's rewrite that was actually used, when prompt enhancement was on. */
    val enhancedPrompt: String?,
    val negativePrompt: String,
    val modelName: String,
    val width: Int,
    val height: Int,
    val steps: Int,
    val guidance: Float,
    val seed: Long,
    val durationMillis: Long,
    val createdAt: Long
)

/** Generated images, kept privately as PNG files with a JSON sidecar of how each was made. */
class ImageGallery(context: Context) {

    companion object {
        private const val TAG = "ImageGallery"
        private const val ALBUM = "TinyAI"
    }

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "generated")

    private val _images = MutableStateFlow<List<GeneratedImage>>(emptyList())
    /** Newest first. */
    val images: StateFlow<List<GeneratedImage>> = _images.asStateFlow()

    fun refresh() {
        val list = dir.listFiles { f -> f.extension == "json" }?.mapNotNull(::read) ?: emptyList()
        _images.value = list.sortedByDescending { it.createdAt }
    }

    private fun read(meta: File): GeneratedImage? = try {
        val png = File(meta.parentFile, meta.nameWithoutExtension + ".png")
        if (!png.isFile) null else {
            val j = JSONObject(meta.readText())
            GeneratedImage(
                id = meta.nameWithoutExtension,
                file = png,
                prompt = j.optString("prompt"),
                enhancedPrompt = j.optString("enhancedPrompt").takeIf { it.isNotBlank() },
                negativePrompt = j.optString("negativePrompt"),
                modelName = j.optString("model"),
                width = j.optInt("width"),
                height = j.optInt("height"),
                steps = j.optInt("steps"),
                guidance = j.optDouble("guidance", 0.0).toFloat(),
                seed = j.optLong("seed"),
                durationMillis = j.optLong("durationMillis"),
                createdAt = j.optLong("createdAt")
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "Unreadable image metadata ${meta.name}", e)
        null
    }

    fun save(bitmap: Bitmap, request: GenerationRequest, modelName: String, durationMillis: Long): GeneratedImage {
        dir.mkdirs()
        val createdAt = System.currentTimeMillis()
        val id = "img_$createdAt"
        val png = File(dir, "$id.png")
        png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "$id.json").writeText(
            JSONObject()
                .put("prompt", request.originalPrompt ?: request.prompt)
                .put("enhancedPrompt", if (request.originalPrompt != null) request.prompt else null)
                .put("negativePrompt", request.negativePrompt)
                .put("model", modelName)
                .put("width", bitmap.width)
                .put("height", bitmap.height)
                .put("steps", request.steps)
                .put("guidance", request.guidance.toDouble())
                .put("seed", request.seed)
                .put("durationMillis", durationMillis)
                .put("createdAt", createdAt)
                .toString()
        )
        val image = read(File(dir, "$id.json"))!!
        _images.value = listOf(image) + _images.value
        return image
    }

    fun delete(id: String) {
        File(dir, "$id.png").delete()
        File(dir, "$id.json").delete()
        _images.value = _images.value.filterNot { it.id == id }
    }

    fun deleteAll() {
        dir.deleteRecursively()
        _images.value = emptyList()
    }

    /** A chooser that sends the full-resolution PNG to another app. */
    fun shareIntent(image: GeneratedImage): Intent {
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", image.file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            // Lets the share sheet show a thumbnail of what's being shared.
            clipData = ClipData.newRawUri(image.prompt.take(80), uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share image").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * Copies the image into the device's Pictures/TinyAI album. Android 9 and older need the
     * storage permission to have been granted first.
     */
    fun saveToPhotos(image: GeneratedImage) {
        val displayName = "TinyAI_${image.createdAt}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = appContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Couldn't create the photo.")
            try {
                resolver.openOutputStream(uri)?.use { out -> image.file.inputStream().use { it.copyTo(out) } }
                    ?: throw IOException("Couldn't write the photo.")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val album = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
            album.mkdirs()
            val out = File(album, displayName)
            image.file.copyTo(out, overwrite = true)
            MediaScannerConnection.scanFile(appContext, arrayOf(out.absolutePath), arrayOf("image/png"), null)
        }
    }

    /**
     * Decodes for display at full resolution, or downsampled by powers of two to roughly
     * [maxSide]. On Android 8+ the pixels go straight to GPU memory (a hardware bitmap), so
     * scrolling and zooming never re-upload them; such bitmaps are draw-only, which is all the UI needs.
     */
    fun decode(image: GeneratedImage, maxSide: Int = 0): Bitmap? = try {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Bitmap.Config.HARDWARE else Bitmap.Config.ARGB_8888
            inScaled = false
            if (maxSide > 0) {
                var sample = 1
                while (maxOf(image.width, image.height) / (sample * 2) >= maxSide) sample *= 2
                inSampleSize = sample
            }
        }
        BitmapFactory.decodeFile(image.file.absolutePath, options)
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't decode ${image.id}", e)
        null
    }
}
