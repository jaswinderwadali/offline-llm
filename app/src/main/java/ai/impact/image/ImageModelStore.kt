package ai.impact.image

import android.content.Context
import android.net.Uri
import android.util.Log
import ai.impact.models.DownloadFile
import ai.impact.models.DownloadSpec
import ai.impact.models.ModelDownload
import ai.impact.models.ModelDownloader
import ai.impact.models.formatBytes
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

data class ModelImport(val name: String, val copiedBytes: Long, val totalBytes: Long) {
    val fraction: Float get() = if (totalBytes > 0) (copiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

fun CatalogImageModel.toDownloadSpec() =
    DownloadSpec(id, name, files.map { (path, size) -> DownloadFile(baseUrl + path, path, size) })

fun HubCandidate.toDownloadSpec(): DownloadSpec {
    val defaults = ImageModelCatalog.guessDefaults("$repo/$root")
    val manifest = JSONObject()
        .put("name", name)
        .put("source", "huggingface")
        .put("repo", repo)
        .put("revision", revision)
        .put("root", root)
        .put("steps", defaults.steps)
        .put("maxSteps", defaults.maxSteps)
        .put("guidance", defaults.guidance.toDouble())
        .put("padWithExclamation", defaults.padWithExclamation)
    return DownloadSpec(id, name, files.map { DownloadFile(fileUrl(it.path), it.path, it.size) }, manifest)
}

/**
 * Image models on disk. Each lives in its own folder (in the diffusers ONNX layout) under the
 * app's external files directory, which the system DownloadManager can write to directly and
 * which is removed with the app.
 */
class ImageModelStore(context: Context, private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "ImageModelStore"
        private const val PREFS = "image_models"
        private const val KEY_LOADING = "loading_model"
        private const val MANIFEST = ModelDownloader.MANIFEST
        private const val COMPLETE = ModelDownloader.COMPLETE
        private const val IMPORTED_PREFIX = "imported-"
        private const val SPACE_MARGIN = 200L * 1024 * 1024
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val downloader = ModelDownloader(
        context = appContext,
        scope = scope,
        prefsName = PREFS,
        relativeRoot = "image_models",
        validate = { dir ->
            val missing = StableDiffusionPipeline.missingFiles(dir)
            if (missing.isEmpty()) null else "The download finished but some files are missing."
        },
        onInstalled = { refresh() }
    )

    val root: File = downloader.root

    private val _installed = MutableStateFlow<List<InstalledImageModel>>(emptyList())
    val installed: StateFlow<List<InstalledImageModel>> = _installed.asStateFlow()

    val downloads: StateFlow<Map<String, ModelDownload>> = downloader.downloads

    private val _import = MutableStateFlow<ModelImport?>(null)
    val import: StateFlow<ModelImport?> = _import.asStateFlow()

    private var importJob: Job? = null

    init {
        scope.launch(Dispatchers.IO) { refresh() }
    }

    fun dirFor(id: String) = downloader.dirFor(id)

    // region Installed models

    fun refresh() {
        val folders = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
        _installed.value = folders.mapNotNull(::readInstalled).sortedBy { it.name.lowercase() }
    }

    private fun readInstalled(dir: File): InstalledImageModel? {
        val catalog = ImageModelCatalog.find(dir.name)
        if (catalog != null) {
            if (!File(dir, COMPLETE).isFile) return null
            return InstalledImageModel(catalog.id, catalog.name, folderSize(dir), imported = false, catalog.defaults)
        }
        // Imported and Hugging Face models describe themselves; dot-folders are in-progress work.
        if (dir.name.startsWith(".")) return null
        val manifest = File(dir, MANIFEST).takeIf { it.isFile } ?: return null
        return try {
            val json = JSONObject(manifest.readText())
            val name = json.getString("name")
            val defaults = ImageModelCatalog.guessDefaults(name).let {
                it.copy(
                    steps = json.optInt("steps", it.steps),
                    maxSteps = json.optInt("maxSteps", it.maxSteps),
                    guidance = json.optDouble("guidance", it.guidance.toDouble()).toFloat(),
                    padWithExclamation = json.optBoolean("padWithExclamation", it.padWithExclamation)
                )
            }
            val source = json.optString("source", "import")
            InstalledImageModel(
                dir.name, name, folderSize(dir),
                imported = source == "import",
                defaults = defaults,
                sourceRepo = json.optString("repo").takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Bad manifest in ${dir.name}", e)
            null
        }
    }

    private fun folderSize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun delete(id: String) {
        downloader.cancel(id)
        dirFor(id).deleteRecursively()
        refresh()
    }

    /** Name of a model whose load never finished. Still set at launch means loading it killed the app. */
    val interruptedLoad: String? get() = prefs.getString(KEY_LOADING, null)

    fun setLoading(name: String?) {
        // commit(), not apply(): the process can be killed mid-load before an async write lands.
        prefs.edit().putString(KEY_LOADING, name).commit()
    }

    // endregion

    // region Downloads

    /** Returns an error message, or null once the download has been queued. */
    fun startDownload(spec: DownloadSpec): String? = downloader.start(spec)

    fun cancelDownload(id: String) = downloader.cancel(id)

    fun dismissDownloadError(id: String) = downloader.dismiss(id)

    // endregion

    // region Import

    /**
     * Copies a model folder the user picked into app storage, since ONNX Runtime needs real
     * file paths (and sibling weight files) rather than content URIs.
     */
    fun startImport(treeUri: Uri, onDone: (String?) -> Unit) {
        if (importJob?.isActive == true) return
        importJob = scope.launch(Dispatchers.IO) {
            val error = try {
                import(treeUri)
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Import failed", e)
                e.message ?: "The folder couldn't be imported."
            } finally {
                _import.value = null
            }
            withContext(Dispatchers.Main) { onDone(error) }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
        _import.value = null
    }

    private suspend fun import(treeUri: Uri) {
        val picked = DocumentFile.fromTreeUri(appContext, treeUri) ?: throw IOException("That folder can't be opened.")
        val modelRoot = findModelRoot(picked)
            ?: throw IOException(
                "That folder isn't a Stable Diffusion ONNX model. It needs text_encoder, unet, vae_decoder and tokenizer folders."
            )
        val name = (modelRoot.name ?: picked.name ?: "Imported model").take(60)
        val files = buildList { collect(modelRoot, "", this) }
            .filter { (path, _) -> path.substringBefore('/') in setOf("text_encoder", "unet", "vae_decoder", "tokenizer") }
        val total = files.sumOf { it.second.length() }
        if (downloader.freeSpaceBytes() < total + SPACE_MARGIN) {
            throw IOException("Not enough free storage. This model needs about ${formatBytes(total + SPACE_MARGIN)}.")
        }

        val id = IMPORTED_PREFIX + System.currentTimeMillis()
        val target = dirFor(id)
        val staging = File(root, ".$id.part")
        staging.deleteRecursively()
        var copied = 0L
        _import.value = ModelImport(name, 0, total)
        try {
            val buffer = ByteArray(1 shl 20)
            for ((path, doc) in files) {
                val out = File(staging, path)
                out.parentFile?.mkdirs()
                val input = appContext.contentResolver.openInputStream(doc.uri) ?: throw IOException("Couldn't read $path.")
                input.use { src ->
                    out.outputStream().use { dst ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = src.read(buffer)
                            if (read < 0) break
                            dst.write(buffer, 0, read)
                            copied += read
                            _import.value = ModelImport(name, copied, total)
                        }
                    }
                }
            }
            val missing = StableDiffusionPipeline.missingFiles(staging)
            if (missing.isNotEmpty()) throw IOException("The folder is missing ${missing.joinToString()}.")
            val defaults = ImageModelCatalog.guessDefaults(name)
            File(staging, MANIFEST).writeText(
                JSONObject()
                    .put("name", name)
                    .put("steps", defaults.steps)
                    .put("maxSteps", defaults.maxSteps)
                    .put("guidance", defaults.guidance.toDouble())
                    .put("padWithExclamation", defaults.padWithExclamation)
                    .toString()
            )
            if (!staging.renameTo(target)) throw IOException("Couldn't save the model.")
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
        refresh()
    }

    /** The picked folder, or a direct child of it, that holds the model's component folders. */
    private fun findModelRoot(dir: DocumentFile): DocumentFile? {
        fun isModel(d: DocumentFile): Boolean {
            val children = d.listFiles().mapNotNull { it.name }.toSet()
            return listOf("text_encoder", "unet", "vae_decoder", "tokenizer").all { it in children }
        }
        if (isModel(dir)) return dir
        return dir.listFiles().filter { it.isDirectory }.firstOrNull(::isModel)
    }

    private fun collect(dir: DocumentFile, prefix: String, out: MutableList<Pair<String, DocumentFile>>) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            if (child.isDirectory) collect(child, "$prefix$name/", out) else out += "$prefix$name" to child
        }
    }

    // endregion
}
