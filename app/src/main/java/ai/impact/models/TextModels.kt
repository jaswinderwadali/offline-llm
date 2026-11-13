package ai.impact.models

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/** A chat model (a single .gguf file) offered in the in-app catalog. */
data class CatalogTextModel(
    val id: String,
    val name: String,
    val description: String,
    val sizeBytes: Long,
    val minRamGb: Int,
    val url: String,
    val recommended: Boolean = false
) {
    val fileName: String get() = url.substringBefore('?').substringAfterLast('/')
}

/** A downloaded chat model, ready to load. */
data class InstalledTextModel(
    val id: String,
    val name: String,
    val file: File,
    val sizeBytes: Long,
    /** Hugging Face repo it came from, for models not in the catalog. */
    val sourceRepo: String? = null
)

object TextModelCatalog {
    private fun qwen(quant: String, size: Long, description: String, recommended: Boolean = false, minRam: Int = 4) =
        CatalogTextModel(
            id = "qwen2.5-1.5b-$quant",
            name = "Qwen 2.5 1.5B (${quant.uppercase()})",
            description = description,
            sizeBytes = size,
            minRamGb = minRam,
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-$quant.gguf",
            recommended = recommended
        )

    val models = listOf(
        qwen("q4_k_m", 1_117_320_736, "Excellent balance of quality and speed for most phones.", recommended = true),
        CatalogTextModel(
            id = "llama-3.2-3b-q4_k_m",
            name = "Llama 3.2 3B (Q4_K_M)",
            description = "Meta's highly capable small model. Smarter, needs a phone with 6 GB+ RAM.",
            sizeBytes = 2_019_373_920,
            minRamGb = 6,
            url = "https://huggingface.co/hugging-quants/Llama-3.2-3B-Instruct-Q4_K_M-GGUF/resolve/main/llama-3.2-3b-instruct-q4_k_m.gguf",
            recommended = true
        ),
        qwen("q2_k", 752_880_160, "Smallest and fastest. Good for older phones.", minRam = 3),
        qwen("q3_k_m", 924_455_968, "Very efficient with decent intelligence.", minRam = 3),
        qwen("q5_k_m", 1_285_494_304, "Higher quality, slightly more memory."),
        qwen("q6_k", 1_464_178_720, "Near-full quality, needs more processing power."),
        qwen("q8_0", 1_894_532_128, "Maximum quality of the quantised versions.", minRam = 6)
    )

    fun find(id: String): CatalogTextModel? = models.firstOrNull { it.id == id }
}

fun CatalogTextModel.toDownloadSpec(): DownloadSpec {
    val manifest = JSONObject().put("name", name).put("file", fileName).put("source", "catalog")
    return DownloadSpec(id, name, listOf(DownloadFile(url, fileName, sizeBytes)), manifest)
}

/** Every GGUF file starts with these four bytes; anything else (e.g. an HTML error page) isn't a model. */
internal fun isGguf(file: File): Boolean = try {
    file.inputStream().use { input ->
        val magic = ByteArray(4)
        input.read(magic) == 4 && String(magic, Charsets.US_ASCII) == "GGUF"
    }
} catch (e: Exception) {
    false
}

/** Downloaded chat models, each in `text_models/<id>/<file>.gguf` with a small manifest. */
class TextModelStore(context: Context, scope: CoroutineScope) {

    companion object {
        private const val TAG = "TextModelStore"
    }

    private val downloader = ModelDownloader(
        context = context,
        scope = scope,
        prefsName = "text_models",
        relativeRoot = "text_models",
        validate = { dir ->
            val gguf = dir.listFiles()?.firstOrNull { it.extension.equals("gguf", true) }
            when {
                gguf == null || gguf.length() == 0L -> "The download finished but the model file is missing."
                !isGguf(gguf) -> "The downloaded file isn't a GGUF model."
                else -> null
            }
        },
        onInstalled = { refresh() }
    )

    private val _installed = MutableStateFlow<List<InstalledTextModel>>(emptyList())
    val installed: StateFlow<List<InstalledTextModel>> = _installed.asStateFlow()

    val downloads: StateFlow<Map<String, ModelDownload>> = downloader.downloads

    init {
        scope.launch(Dispatchers.IO) { refresh() }
    }

    fun refresh() {
        val dirs = downloader.root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList()
        _installed.value = dirs.mapNotNull(::read).sortedBy { it.name.lowercase() }
    }

    private fun read(dir: File): InstalledTextModel? {
        if (!File(dir, ModelDownloader.COMPLETE).isFile) return null
        return try {
            val json = File(dir, ModelDownloader.MANIFEST).takeIf { it.isFile }?.readText()?.let(::JSONObject) ?: JSONObject()
            val file = json.optString("file").takeIf { it.isNotBlank() }?.let { File(dir, it) }
                ?: dir.listFiles()?.firstOrNull { it.extension.equals("gguf", true) }
                ?: return null
            if (!file.isFile) return null
            InstalledTextModel(
                id = dir.name,
                name = json.optString("name").ifBlank { file.nameWithoutExtension },
                file = file,
                sizeBytes = file.length(),
                sourceRepo = json.optString("repo").takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable model folder ${dir.name}", e)
            null
        }
    }

    fun find(id: String): InstalledTextModel? = _installed.value.firstOrNull { it.id == id }

    /** The installed model stored at [file], if any (used to recognise the saved model at launch). */
    fun byFile(file: File): InstalledTextModel? = _installed.value.firstOrNull { it.file.absolutePath == file.absolutePath }

    fun startDownload(spec: DownloadSpec): String? = downloader.start(spec)

    fun cancelDownload(id: String) = downloader.cancel(id)

    fun dismissDownloadError(id: String) = downloader.dismiss(id)

    fun delete(id: String) {
        downloader.cancel(id)
        downloader.dirFor(id).deleteRecursively()
        refresh()
    }
}
