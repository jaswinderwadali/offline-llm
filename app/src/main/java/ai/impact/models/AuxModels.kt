package ai.impact.models

import android.content.Context
import android.net.Uri
import android.util.Log
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

/**
 * The supporting models: document search, speech to text and text to speech. Each kind runs one
 * engine, so every model of a kind is stored with the same file names, whatever it was called
 * where it came from.
 */
enum class AuxKind(val label: String, val requiredFiles: List<String>) {
    /** A BERT-style sentence embedder (WordPiece vocab.txt). */
    Search("document search", listOf(AuxFiles.MODEL, AuxFiles.VOCAB_TXT)),
    /** Whisper, as separate encoder and decoder ONNX files. */
    Listen("speech to text", listOf(AuxFiles.ENCODER, AuxFiles.DECODER, AuxFiles.VOCAB_JSON, AuxFiles.GENERATION)),
    /** An MMS / VITS voice. */
    Speak("text to speech", listOf(AuxFiles.MODEL, AuxFiles.VOCAB_JSON, AuxFiles.CONFIG))
}

object AuxFiles {
    const val MODEL = "model.onnx"
    const val VOCAB_TXT = "vocab.txt"
    const val ENCODER = "encoder.onnx"
    const val DECODER = "decoder.onnx"
    const val VOCAB_JSON = "vocab.json"
    const val GENERATION = "generation_config.json"
    const val CONFIG = "config.json"
}

enum class AuxSource { Suggested, HuggingFace, Imported }

/** A suggested model. Files are pinned to a commit so they can't change under an installed app. */
data class AuxCatalogModel(
    val id: String,
    val name: String,
    val kind: AuxKind,
    val description: String,
    val license: String,
    val repo: String,
    val files: List<DownloadFile>,
    val recommended: Boolean = false
) {
    val sizeBytes: Long get() = files.sumOf { it.size }

    fun toDownloadSpec() = auxDownloadSpec(id, name, kind, AuxSource.Suggested, repo, files)
}

data class InstalledAuxModel(
    val id: String,
    val name: String,
    val kind: AuxKind,
    val dir: File,
    val sizeBytes: Long,
    val source: AuxSource,
    val repo: String?
)

/** A folder being copied in from the phone's storage. */
data class AuxImport(val kind: AuxKind, val name: String, val copiedBytes: Long, val totalBytes: Long) {
    val fraction: Float get() = if (totalBytes > 0) (copiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/** A download whose manifest records every file's size, so a truncated file is caught on any source. */
fun auxDownloadSpec(id: String, name: String, kind: AuxKind, source: AuxSource, repo: String?, files: List<DownloadFile>): DownloadSpec {
    val sizes = JSONObject().apply { files.forEach { put(it.path, it.size) } }
    val manifest = JSONObject()
        .put("name", name)
        .put("kind", kind.name)
        .put("source", source.name)
        .put("files", sizes)
        .apply { repo?.let { put("repo", it) } }
    return DownloadSpec(id, name, files, manifest)
}

/**
 * Downloaded and imported models of one or more [AuxKind]s, each in its own folder under
 * [relativeRoot]. A folder counts as installed once it's complete and has every file its kind needs.
 */
class AuxModelStore(
    context: Context,
    private val scope: CoroutineScope,
    prefsName: String,
    relativeRoot: String,
    private val catalog: List<AuxCatalogModel>
) {
    companion object {
        private const val TAG = "AuxModelStore"
        private const val IMPORTED_PREFIX = "imported-"
        private const val SPACE_MARGIN = 100L * 1024 * 1024
    }

    private val appContext = context.applicationContext

    private val downloader = ModelDownloader(
        context = appContext,
        scope = scope,
        prefsName = prefsName,
        relativeRoot = relativeRoot,
        validate = ::validate,
        onInstalled = { refresh() }
    )

    private val _installed = MutableStateFlow<List<InstalledAuxModel>>(emptyList())
    val installed: StateFlow<List<InstalledAuxModel>> = _installed.asStateFlow()
    val downloads: StateFlow<Map<String, ModelDownload>> = downloader.downloads

    private val _import = MutableStateFlow<AuxImport?>(null)
    val import: StateFlow<AuxImport?> = _import.asStateFlow()
    private var importJob: Job? = null

    init {
        scope.launch(Dispatchers.IO) { refresh() }
    }

    fun dirFor(id: String): File = downloader.dirFor(id)

    fun refresh() {
        val dirs = downloader.root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }.orEmpty()
        _installed.value = dirs.mapNotNull(::read).sortedWith(compareBy({ it.kind }, { it.source != AuxSource.Suggested }, { it.name }))
    }

    private fun read(dir: File): InstalledAuxModel? {
        if (!File(dir, ModelDownloader.COMPLETE).isFile) return null
        val manifest = runCatching { JSONObject(File(dir, ModelDownloader.MANIFEST).readText()) }.getOrNull()
        val suggested = catalog.firstOrNull { it.id == dir.name }
        // Models downloaded before manifests named their kind are recognised by their catalog id.
        val kind = manifest?.optString("kind")?.let { k -> AuxKind.entries.firstOrNull { it.name == k } } ?: suggested?.kind ?: return null
        if (kind.requiredFiles.any { File(dir, it).length() <= 0L }) return null
        val source = manifest?.optString("source")?.let { s -> AuxSource.entries.firstOrNull { it.name == s } }
            ?: if (suggested != null) AuxSource.Suggested else AuxSource.Imported
        return InstalledAuxModel(
            id = dir.name,
            name = suggested?.name ?: manifest?.optString("name")?.takeIf { it.isNotBlank() } ?: dir.name,
            kind = kind,
            dir = dir,
            sizeBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            source = source,
            repo = manifest?.optString("repo")?.takeIf { it.isNotBlank() }
        )
    }

    /** A finished download must match the sizes its manifest promised and have everything its kind needs. */
    private fun validate(dir: File): String? {
        val manifest = runCatching { JSONObject(File(dir, ModelDownloader.MANIFEST).readText()) }.getOrNull()
        val name = manifest?.optString("name") ?: "The model"
        val sizes = manifest?.optJSONObject("files")
        sizes?.keys()?.forEach { path ->
            val expected = sizes.optLong(path)
            if (expected > 0 && File(dir, path).length() != expected) return "$name didn't download completely. Try again."
        }
        val kind = manifest?.optString("kind")?.let { k -> AuxKind.entries.firstOrNull { it.name == k } }
        val missing = kind?.requiredFiles?.filter { File(dir, it).length() <= 0L }.orEmpty()
        return if (missing.isNotEmpty()) "$name is missing ${missing.joinToString()}." else null
    }

    fun find(id: String): InstalledAuxModel? = _installed.value.firstOrNull { it.id == id }

    fun startDownload(spec: DownloadSpec): String? = downloader.start(spec)
    fun startDownload(model: AuxCatalogModel): String? = downloader.start(model.toDownloadSpec())
    fun cancelDownload(id: String) = downloader.cancel(id)
    fun dismissError(id: String) = downloader.dismiss(id)

    fun delete(id: String) {
        downloader.cancel(id)
        try {
            downloader.dirFor(id).deleteRecursively()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't delete $id", e)
        }
        refresh()
    }

    // region Import

    /**
     * Copies a model folder the user picked into app storage, since ONNX Runtime needs real file
     * paths. Files are found by name anywhere in the folder and saved under the names [kind] uses.
     */
    fun startImport(treeUri: Uri, kind: AuxKind, onDone: (String?) -> Unit) {
        if (importJob?.isActive == true) return
        importJob = scope.launch(Dispatchers.IO) {
            val error = try {
                import(treeUri, kind)
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

    private suspend fun import(treeUri: Uri, kind: AuxKind) {
        val picked = DocumentFile.fromTreeUri(appContext, treeUri) ?: throw IOException("That folder can't be opened.")
        val found = buildList { collect(picked, "", 0, this) }.associate { it }
        val plan = AuxLayout.importPlan(kind, found.mapValues { it.value.length() })
            ?: throw IOException(AuxLayout.importHint(kind))
        val name = (picked.name ?: "Imported model").take(60)
        val total = plan.sumOf { found.getValue(it.first).length() }
        if (downloader.freeSpaceBytes() < total + SPACE_MARGIN) {
            throw IOException("Not enough free storage. This model needs about ${formatBytes(total + SPACE_MARGIN)}.")
        }
        val id = IMPORTED_PREFIX + System.currentTimeMillis()
        val target = dirFor(id)
        val staging = File(downloader.root, ".$id.part")
        staging.deleteRecursively()
        staging.mkdirs()
        var copied = 0L
        _import.value = AuxImport(kind, name, 0, total)
        try {
            val buffer = ByteArray(1 shl 20)
            for ((from, to) in plan) {
                val input = appContext.contentResolver.openInputStream(found.getValue(from).uri) ?: throw IOException("Couldn't read $from.")
                input.use { src ->
                    File(staging, to).outputStream().use { dst ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = src.read(buffer)
                            if (read < 0) break
                            dst.write(buffer, 0, read)
                            copied += read
                            _import.value = AuxImport(kind, name, copied, total)
                        }
                    }
                }
            }
            File(staging, ModelDownloader.MANIFEST).writeText(
                JSONObject().put("name", name).put("kind", kind.name).put("source", AuxSource.Imported.name).toString()
            )
            File(staging, ModelDownloader.COMPLETE).writeText(System.currentTimeMillis().toString())
            if (!staging.renameTo(target)) throw IOException("Couldn't save the model.")
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
        refresh()
    }

    /** Every file up to three folders deep, by path relative to the picked folder. */
    private fun collect(dir: DocumentFile, prefix: String, depth: Int, out: MutableList<Pair<String, DocumentFile>>) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            if (child.isDirectory) {
                if (depth < 3) collect(child, "$prefix$name/", depth + 1, out)
            } else {
                out += "$prefix$name" to child
            }
        }
    }

    // endregion
}

/**
 * Which files in a repo or folder make up a model of each kind, and what they're saved as.
 * Pure logic with no I/O, so it's unit-tested.
 */
object AuxLayout {

    /** One way to download a model from a repo, e.g. its 8-bit or full-precision version. */
    data class Variant(val label: String, val files: List<Pair<String, String>>)

    // Smaller, phone-friendly exports first.
    private val ONNX_SUFFIXES = listOf("_quantized", "_qint8_arm64", "_int8", "_uint8", "_q4", "", "_fp16")

    private fun variantLabel(suffix: String) = when (suffix) {
        "_quantized", "_int8", "_uint8", "_qint8_arm64" -> "8-bit · smaller and faster"
        "_q4" -> "4-bit"
        "_fp16" -> "Half precision"
        else -> "Full precision · largest"
    }

    /**
     * The downloadable versions of a [kind] model among [paths] (relative to the repo root or
     * picked folder), smallest first. Empty when a needed file is missing.
     */
    fun variants(kind: AuxKind, paths: Set<String>): List<Variant> {
        fun find(name: String): String? = paths.firstOrNull { it == name } ?: paths.firstOrNull { it.endsWith("/$name") }
        fun onnx(base: String, suffix: String): String? =
            find("onnx/$base$suffix.onnx") ?: find("$base$suffix.onnx")
        val variants = when (kind) {
            AuxKind.Search -> {
                val vocab = find("vocab.txt") ?: return emptyList()
                ONNX_SUFFIXES.mapNotNull { s ->
                    onnx("model", s)?.let { Variant(variantLabel(s), listOf(it to AuxFiles.MODEL, vocab to AuxFiles.VOCAB_TXT)) }
                }
            }
            AuxKind.Listen -> {
                val vocab = find("vocab.json") ?: return emptyList()
                val generation = find("generation_config.json") ?: return emptyList()
                ONNX_SUFFIXES.mapNotNull { s ->
                    val encoder = onnx("encoder_model", s) ?: return@mapNotNull null
                    // The engine runs the plain decoder; "merged" and "with_past" ones need a cache it doesn't keep.
                    val decoder = onnx("decoder_model", s) ?: return@mapNotNull null
                    Variant(
                        variantLabel(s),
                        listOf(encoder to AuxFiles.ENCODER, decoder to AuxFiles.DECODER, vocab to AuxFiles.VOCAB_JSON, generation to AuxFiles.GENERATION)
                    )
                }
            }
            AuxKind.Speak -> {
                val vocab = find("vocab.json") ?: return emptyList()
                val config = find("config.json") ?: return emptyList()
                ONNX_SUFFIXES.mapNotNull { s ->
                    onnx("model", s)?.let { Variant(variantLabel(s), listOf(it to AuxFiles.MODEL, vocab to AuxFiles.VOCAB_JSON, config to AuxFiles.CONFIG)) }
                }
            }
        }
        return variants.distinctBy { v -> v.files.map { it.first } }
    }

    /** What to copy from a picked folder, as (path in folder, saved name); null if it isn't a [kind] model. */
    fun importPlan(kind: AuxKind, files: Map<String, Long>): List<Pair<String, String>>? {
        val nonEmpty = files.filterValues { it > 0 }.keys
        variants(kind, nonEmpty).firstOrNull()?.let { return it.files }
        // A folder that's already in the saved layout (e.g. copied off another phone).
        val flat = kind.requiredFiles.map { name -> (nonEmpty.firstOrNull { it == name || it.endsWith("/$name") } ?: return null) to name }
        return flat
    }

    fun importHint(kind: AuxKind): String = when (kind) {
        AuxKind.Search -> "That folder isn't a supported search model. It needs an ONNX model (e.g. onnx/model_quantized.onnx) and vocab.txt."
        AuxKind.Listen -> "That folder isn't a Whisper ONNX model. It needs encoder_model and decoder_model .onnx files, vocab.json and generation_config.json."
        AuxKind.Speak -> "That folder isn't an MMS / VITS ONNX voice. It needs an ONNX model, vocab.json and config.json."
    }

    /**
     * Why a repo's model can't run here, from its config.json (and tokenizer_config.json), or null
     * if it can. The engines are specific: BERT-style embedders, Whisper, and MMS / VITS voices.
     */
    fun configProblem(kind: AuxKind, config: JSONObject?, tokenizerConfig: JSONObject?): String? {
        val type = config?.optString("model_type").orEmpty().lowercase()
        return when (kind) {
            AuxKind.Search -> when {
                type.isNotEmpty() && type !in setOf("bert", "distilbert", "electra") ->
                    "This is a $type model. Document search runs BERT-style models (like MiniLM, BGE or GTE) that come with a vocab.txt."
                tokenizerConfig?.optBoolean("do_lower_case", true) == false ->
                    "This model is case-sensitive (\"cased\"), which the document search reader doesn't support. Look for an uncased version."
                else -> null
            }
            AuxKind.Listen -> if (type.isNotEmpty() && type != "whisper") {
                "This is a $type model. Speech to text runs Whisper models exported to ONNX."
            } else null
            AuxKind.Speak -> when {
                type.isNotEmpty() && type != "vits" -> "This is a $type model. Text to speech runs MMS / VITS voices exported to ONNX."
                tokenizerConfig?.optBoolean("is_uroman", false) == true ->
                    "This voice needs its text romanised first (uroman), which TinyAI can't do yet. Try a voice for another language."
                else -> null
            }
        }
    }

    /** A quick warning from a repo's name, for search results, before any files are checked. */
    fun nameHint(kind: AuxKind, repoId: String): String? {
        val n = repoId.lowercase()
        return when (kind) {
            AuxKind.Listen -> when {
                "whisper" !in n -> "Not Whisper"
                "large" in n || "medium" in n -> "Too big for phones"
                else -> null
            }
            AuxKind.Speak -> if ("mms-tts" !in n && "vits" !in n) "Not an MMS / VITS voice" else null
            AuxKind.Search -> if ("multilingual" in n || "xlm" in n) "Different tokenizer" else null
        }
    }
}
