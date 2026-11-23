package ai.impact.data.documents

import ai.impact.models.DownloadFile
import ai.impact.models.DownloadSpec
import ai.impact.models.ModelDownload
import ai.impact.models.ModelDownloader
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer

/**
 * The small model that turns text into vectors for document search: all-MiniLM-L6-v2
 * (Apache-2.0), 8-bit, about 23 MB. It's downloaded once and runs on the phone with ONNX Runtime.
 */
class SearchModelStore(context: Context, scope: CoroutineScope) {

    companion object {
        const val ID = "all-minilm-l6-v2"
        const val NAME = "Document search model"
        const val DIM = 384
        // Pinned to a commit so the files can't change under an installed app.
        private const val BASE = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/1110a243fdf4706b3f48f1d95db1a4f5529b4d41"
        const val MODEL_FILE = "model.onnx"
        const val VOCAB_FILE = "vocab.txt"
        private const val MODEL_SIZE = 23_026_053L
        private const val VOCAB_SIZE = 231_508L

        val spec = DownloadSpec(
            id = ID,
            name = NAME,
            files = listOf(
                DownloadFile("$BASE/onnx/model_qint8_arm64.onnx", MODEL_FILE, MODEL_SIZE),
                DownloadFile("$BASE/$VOCAB_FILE", VOCAB_FILE, VOCAB_SIZE)
            ),
            manifest = JSONObject().put("name", NAME).put("repo", "sentence-transformers/all-MiniLM-L6-v2")
        )
    }

    private val downloader = ModelDownloader(
        context = context,
        scope = scope,
        prefsName = "search_models",
        relativeRoot = "search_models",
        validate = { dir ->
            val model = File(dir, MODEL_FILE)
            val vocab = File(dir, VOCAB_FILE)
            when {
                model.length() != MODEL_SIZE -> "The search model didn't download completely. Try again."
                vocab.length() != VOCAB_SIZE -> "The search model's word list didn't download completely. Try again."
                else -> null
            }
        },
        onInstalled = { refresh() }
    )

    private val _installed = MutableStateFlow(false)
    val installed: StateFlow<Boolean> = _installed.asStateFlow()

    val download: StateFlow<Map<String, ModelDownload>> = downloader.downloads

    val dir: File get() = downloader.dirFor(ID)

    init {
        scope.launch(Dispatchers.IO) { refresh() }
    }

    fun refresh() {
        _installed.value = File(dir, ModelDownloader.COMPLETE).isFile &&
            File(dir, MODEL_FILE).length() == MODEL_SIZE && File(dir, VOCAB_FILE).length() == VOCAB_SIZE
    }

    fun startDownload(): String? = downloader.start(spec)

    /** Deletes the downloaded files. Call through [DocumentLibrary.deleteSearchModel], which closes it first. */
    fun delete() {
        downloader.cancel(ID)
        dir.deleteRecursively()
        refresh()
    }

    val sizeBytes: Long get() = spec.sizeBytes

    fun cancelDownload() = downloader.cancel(ID)

    fun dismissError() = downloader.dismiss(ID)
}

/**
 * Turns text into 384-number vectors that are close together when the meaning is. Loaded on
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
                    val inputs = mapOf("input_ids" to idTensor, "attention_mask" to maskTensor, "token_type_ids" to typeTensor)
                    session.run(inputs).use { result ->
                        val hidden = (result.get(0) as OnnxTensor).floatBuffer
                        val flat = FloatArray(hidden.remaining()).also { hidden.get(it) }
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
