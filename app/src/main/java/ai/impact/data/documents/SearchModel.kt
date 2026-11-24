package ai.impact.data.documents

import ai.impact.models.AuxCatalogModel
import ai.impact.models.AuxFiles
import ai.impact.models.AuxKind
import ai.impact.models.AuxModelStore
import ai.impact.models.DownloadFile
import ai.impact.models.InstalledAuxModel
import ai.impact.models.ModelDownload
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer

/**
 * The models that turn text into vectors for document search. One is active at a time; the
 * suggested default is all-MiniLM-L6-v2 (Apache-2.0, 8-bit, about 23 MB). Others can come from
 * the suggestions, Hugging Face, or a folder on the phone. Everything runs with ONNX Runtime.
 */
class SearchModelStore(context: Context, scope: CoroutineScope) {

    companion object {
        const val ID = "all-minilm-l6-v2"
        const val NAME = "MiniLM L6 (English)"
        const val MODEL_FILE = AuxFiles.MODEL
        const val VOCAB_FILE = AuxFiles.VOCAB_TXT
        private const val KEY_ACTIVE = "active_model"
        private const val KEY_INDEXED_WITH = "indexed_with"

        private fun suggestion(id: String, name: String, repo: String, sha: String, model: String, modelSize: Long, description: String, license: String, recommended: Boolean = false) =
            "https://huggingface.co/$repo/resolve/$sha".let { base ->
                AuxCatalogModel(
                    id, name, AuxKind.Search, description, license, repo,
                    listOf(DownloadFile("$base/$model", MODEL_FILE, modelSize), DownloadFile("$base/vocab.txt", VOCAB_FILE, 231_508L)),
                    recommended
                )
            }

        val catalog: List<AuxCatalogModel> = listOf(
            // Pinned to a commit so the files can't change under an installed app.
            suggestion(
                ID, NAME, "sentence-transformers/all-MiniLM-L6-v2", "1110a243fdf4706b3f48f1d95db1a4f5529b4d41",
                "onnx/model_qint8_arm64.onnx", 23_026_053L,
                "Small and quick. A good fit for most phones.", "Apache-2.0", recommended = true
            ),
            suggestion(
                "all-minilm-l12-v2", "MiniLM L12 (English)", "Xenova/all-MiniLM-L12-v2", "beeb2e4b69e95f188a15cc2e90d09fd035dac229",
                "onnx/model_quantized.onnx", 34_014_366L,
                "Twice as deep as L6: finds the right passage more often, a little slower.", "Apache-2.0"
            ),
            suggestion(
                "bge-small-en-v1.5", "BGE Small v1.5 (English)", "Xenova/bge-small-en-v1.5", "ea104dacec62c0de699686887e3f920caeb4f3e3",
                "onnx/model_quantized.onnx", 34_014_426L,
                "One of the best small search models. Same speed as MiniLM L12.", "MIT"
            )
        )

        /** The default download, offered on the Documents screen. */
        val spec = catalog.first().toDownloadSpec()
    }

    private val prefs = context.applicationContext.getSharedPreferences("search_model", Context.MODE_PRIVATE)
    val store = AuxModelStore(context, scope, prefsName = "search_models", relativeRoot = "search_models", catalog = catalog)

    val models: StateFlow<List<InstalledAuxModel>> = store.installed
    val download: StateFlow<Map<String, ModelDownload>> = store.downloads

    private val selectedId = MutableStateFlow(prefs.getString(KEY_ACTIVE, ID) ?: ID)

    /** The model searches use: the chosen one, or any installed one if that's gone. */
    val active: StateFlow<InstalledAuxModel?> = combine(store.installed, selectedId) { installed, id ->
        installed.firstOrNull { it.id == id } ?: installed.firstOrNull()
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Whether there's a search model to use at all. */
    val installed: StateFlow<Boolean> = active.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)

    val dir: File? get() = active.value?.dir

    fun select(id: String) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
        selectedId.value = id
    }

    /**
     * The model the stored passage vectors were made with. Documents indexed before models could
     * be switched were all made with the default.
     */
    var indexedWith: String
        get() = prefs.getString(KEY_INDEXED_WITH, ID) ?: ID
        set(value) = prefs.edit().putString(KEY_INDEXED_WITH, value).apply()

    fun startDownload(): String? = store.startDownload(spec)

    /** Deletes the downloaded files. Call through [DocumentLibrary.deleteSearchModel], which closes it first. */
    fun delete(id: String) = store.delete(id)

    val sizeBytes: Long get() = active.value?.sizeBytes ?: spec.sizeBytes

    fun cancelDownload() = store.cancelDownload(ID)

    fun dismissError() = store.dismissError(ID)
}

/**
 * Turns text into vectors (384 numbers for the suggested models) that are close together when the meaning is. Loaded on
 * first use and kept, since it's small (about 50 MB of RAM) and every chat message needs it.
 */
class Embedder(private val modelDir: File) {

    companion object {
        /** The model was trained on at most 256 pieces; anything after is ignored. */
        const val MAX_TOKENS = 256
        private const val BATCH = 8
    }

    private val env = OrtEnvironment.getEnvironment()
    private val mutex = Mutex()
    private var session: OrtSession? = null
    private var tokenizerOrNull: WordPieceTokenizer? = null

    suspend fun tokenizer(): WordPieceTokenizer = mutex.withLock { loadLocked().second }

    /** One unit vector per text, in order. */
    suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.Default) {
        mutex.withLock {
            val (session, tokenizer) = loadLocked()
            val encoded = texts.map { tokenizer.encode(it, MAX_TOKENS) }
            val out = arrayOfNulls<FloatArray>(texts.size)
            // Similar lengths share a batch, so little time goes to padding.
            val order = encoded.indices.sortedBy { encoded[it].size }
            for (batch in order.chunked(BATCH)) {
                val vectors = run(session, batch.map { encoded[it] })
                batch.forEachIndexed { i, index -> out[index] = vectors[i] }
            }
            out.map { it!! }
        }
    }

    suspend fun embed(text: String): FloatArray = embed(listOf(text)).single()

    private fun run(session: OrtSession, batch: List<IntArray>): List<FloatArray> {
        val length = batch.maxOf { it.size }
        val shape = longArrayOf(batch.size.toLong(), length.toLong())
        val ids = LongArray(batch.size * length)
        val mask = LongArray(batch.size * length)
        batch.forEachIndexed { b, tokens ->
            tokens.forEachIndexed { t, id ->
                ids[b * length + t] = id.toLong()
                mask[b * length + t] = 1
            }
        }
        val types = LongArray(batch.size * length)
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { idTensor ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { maskTensor ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(types), shape).use { typeTensor ->
                    // Some exports (e.g. DistilBERT) have no token_type_ids input.
                    val inputs = mapOf("input_ids" to idTensor, "attention_mask" to maskTensor, "token_type_ids" to typeTensor)
                        .filterKeys { it in session.inputNames }
                    session.run(inputs).use { result ->
                        val output = result.get(0) as OnnxTensor
                        val hidden = output.floatBuffer
                        val flat = FloatArray(hidden.remaining()).also { hidden.get(it) }
                        // A model that already pools gives one vector per text.
                        if (output.info.shape.size == 2) {
                            val dim = flat.size / batch.size
                            return batch.indices.map { b -> Vectors.normalize(flat.copyOfRange(b * dim, (b + 1) * dim)) }
                        }
                        val dim = flat.size / (batch.size * length)
                        return batch.indices.map { b ->
                            val rowMask = IntArray(length) { t -> mask[b * length + t].toInt() }
                            Vectors.meanPool(flat, rowMask, dim, offset = b * length * dim)
                        }
                    }
                }
            }
        }
    }

    private fun loadLocked(): Pair<OrtSession, WordPieceTokenizer> {
        val s = session
        val t = tokenizerOrNull
        if (s != null && t != null) return s to t
        val tokenizer = File(modelDir, SearchModelStore.VOCAB_FILE).useLines { WordPieceTokenizer.fromVocabLines(it) }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val created = options.use { env.createSession(File(modelDir, SearchModelStore.MODEL_FILE).absolutePath, it) }
        session = created
        tokenizerOrNull = tokenizer
        return created to tokenizer
    }

    /** Frees the model; waits for a search or indexing batch that's using it to finish first. */
    suspend fun close() = mutex.withLock {
        session?.close()
        session = null
    }
}
