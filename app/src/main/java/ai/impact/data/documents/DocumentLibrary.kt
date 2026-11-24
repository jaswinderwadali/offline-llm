package ai.impact.data.documents

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The user's documents, read and indexed on the phone for chat to search.
 *
 * Adding a file reads its text, splits it into passages and stores each passage with its vector.
 * Documents are indexed one at a time in the background; one interrupted by the app closing
 * starts over next launch. Nothing is ever uploaded.
 */
class DocumentLibrary(context: Context, private val scope: CoroutineScope, private val searchModel: SearchModelStore) {

    companion object {
        private const val TAG = "DocumentLibrary"
        const val MAX_FILE_BYTES = 50L * 1024 * 1024
        /** About 1,800 pages of dense text; beyond that, indexing on a phone takes too long. */
        const val MAX_CHUNKS = 4_000
        private const val EMBED_BATCH = 16
        /** Queued instead of a document id: re-score every passage with the active search model. */
        private const val REEMBED = -1L
    }

    private val appContext = context.applicationContext
    private val dao = DocumentsDatabase.create(appContext).dao()
    private val extractor = DocumentExtractor(appContext)

    val documents: StateFlow<List<DocumentEntity>> =
        dao.observeDocuments().stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Indexing progress (0–1) for documents being indexed right now. */
    private val _progress = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val progress: StateFlow<Map<Long, Float>> = _progress.asStateFlow()

    /** Whether there's anything to search: the model is installed and a document is ready. */
    val searchable: StateFlow<Boolean> = combine(documents, searchModel.installed) { docs, installed ->
        installed && docs.any { it.status == DocumentStatus.READY }
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private val queue = Channel<Long>(Channel.UNLIMITED)
    private var current: Pair<Long, Job>? = null

    private val embedderLock = Mutex()
    private var embedder: Embedder? = null
    private var embedderId: String? = null

    private val _searchModelLoaded = MutableStateFlow(false)
    /** Whether the search model is in memory. It loads on first use, so this is about freeing RAM. */
    val searchModelLoaded: StateFlow<Boolean> = _searchModelLoaded.asStateFlow()

    private val indexLock = Mutex()
    private var index: VectorIndex? = null

    init {
        scope.launch {
            for (id in queue) {
                val job = scope.launch(Dispatchers.Default) { if (id == REEMBED) reembedAll() else indexDocument(id) }
                current = id to job
                job.join()
                current = null
            }
        }
        // Finish anything the app was closed in the middle of, once the model is there.
        scope.launch {
            searchModel.installed.first { it }
            dao.withStatus(DocumentStatus.INDEXING).forEach { queue.send(it.id) }
        }
        // Whenever the active search model changes (picked, or the picked one deleted), the stored
        // vectors were made by another model and have to be made again to be comparable.
        scope.launch {
            searchModel.active.collect { model ->
                if (model != null && model.id != searchModel.indexedWith) queue.send(REEMBED)
            }
        }
    }

    /**
     * Adds a picked file and queues it for indexing. Returns why it can't be added, or null.
     */
    suspend fun add(uri: Uri): String? = withContext(Dispatchers.IO) {
        if (!searchModel.installed.value) return@withContext "Download the search model first."
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
        var size = 0L
        try {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read file details", e)
        }
        val mime = appContext.contentResolver.getType(uri)
        if (DocumentKind.of(name, mime) == null) return@withContext DocumentKind.unsupportedReason(name)
        if (size > MAX_FILE_BYTES) return@withContext "This file is over ${MAX_FILE_BYTES / (1024 * 1024)} MB. Try a smaller one."
        // Keep access to the file so indexing can resume if the app is closed part-way.
        try {
            appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.i(TAG, "No persistable permission for $uri; indexing must finish this session")
        }
        val id = dao.insertDocument(
            DocumentEntity(
                name = name, uri = uri.toString(), mimeType = mime, sizeBytes = size,
                status = DocumentStatus.INDEXING, addedAt = System.currentTimeMillis()
            )
        )
        queue.send(id)
        null
    }

    /** Reads it again from the original file, for a document that failed. */
    fun retry(id: Long) {
        scope.launch {
            val doc = dao.document(id) ?: return@launch
            dao.setStatus(id, DocumentStatus.INDEXING, null, doc.pageCount, 0)
            queue.send(id)
        }
    }

    fun delete(id: Long) {
        current?.takeIf { it.first == id }?.second?.cancel()
        scope.launch {
            val doc = dao.document(id)
            dao.deleteDocument(id)
            invalidate()
            doc?.let { releaseAccess(it.uri) }
        }
    }

    fun deleteAll() {
        current?.second?.cancel()
        scope.launch {
            documents.value.forEach { releaseAccess(it.uri) }
            dao.deleteAll()
            invalidate()
        }
    }

    /** The passages that best match [query], best first; empty when nothing is close enough. */
    suspend fun search(
        query: String,
        k: Int = 5,
        minScore: Float = DocumentContext.MIN_SCORE,
        /** Documents the user unselected for this chat. */
        excluded: Set<Long> = emptySet()
    ): List<SearchHit> {
        if (!searchable.value || query.isBlank()) return emptyList()
        val embedder = embedder() ?: return emptyList()
        val vector = embedder.embed(query)
        _searchModelLoaded.value = true
        val ranked = withContext(Dispatchers.Default) { loadIndex(vector.size).search(vector, k, minScore, excluded) }
        if (ranked.isEmpty()) return emptyList()
        val rows = dao.chunks(ranked.map { it.first }).associateBy { it.id }
        return ranked.mapNotNull { (id, score) ->
            val row = rows[id] ?: return@mapNotNull null
            SearchHit(id, DocumentSource(row.documentId, row.documentName, row.pageStart, row.pageEnd), row.heading, row.text, score)
        }
    }

    private suspend fun indexDocument(id: Long) {
        val doc = dao.document(id) ?: return
        if (doc.status != DocumentStatus.INDEXING) return
        _progress.value = _progress.value + (id to 0f)
        try {
            dao.deleteChunks(id)
            val kind = DocumentKind.of(doc.name, doc.mimeType) ?: throw DocumentException(DocumentKind.unsupportedReason(doc.name))
            val extracted = withContext(Dispatchers.IO) { extractor.extract(Uri.parse(doc.uri), kind) }
            val embedder = embedder() ?: throw DocumentException("Download the search model first.")
            val tokenizer = embedder.tokenizer()
            _searchModelLoaded.value = true
            val chunks = TextChunker(tokenizer::count).chunk(extracted.blocks)
            if (chunks.isEmpty()) throw DocumentException("No text found in this file.")
            if (chunks.size > MAX_CHUNKS) {
                throw DocumentException("This document is too long to search on a phone. Try splitting it into smaller files.")
            }
            val entities = ArrayList<ChunkEntity>(chunks.size)
            for (batch in chunks.withIndex().chunked(EMBED_BATCH)) {
                val vectors = embedder.embed(batch.map { it.value.embeddingText })
                batch.forEachIndexed { i, (ordinal, chunk) ->
                    entities.add(
                        ChunkEntity(
                            documentId = id, ordinal = ordinal, heading = chunk.heading, text = chunk.text,
                            pageStart = chunk.pageStart, pageEnd = chunk.pageEnd, embedding = Vectors.toBytes(vectors[i])
                        )
                    )
                }
                _progress.value = _progress.value + (id to entities.size.toFloat() / chunks.size)
            }
            // Deleted while indexing: don't bring it back.
            if (dao.document(id) == null) return
            dao.insertChunks(entities)
            dao.setStatus(id, DocumentStatus.READY, null, extracted.pageCount, entities.size)
            invalidate()
            // Its text is stored now, so the file itself is no longer needed. Android caps how many
            // files an app may keep access to, so give it back.
            releaseAccess(doc.uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DocumentException) {
            dao.setStatus(id, DocumentStatus.FAILED, e.message, null, 0)
        } catch (e: SecurityException) {
            dao.setStatus(id, DocumentStatus.FAILED, "TinyAI no longer has access to this file. Delete it and add it again.", null, 0)
        } catch (e: OutOfMemoryError) {
            dao.setStatus(id, DocumentStatus.FAILED, "This file is too big to read on this phone.", null, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Indexing ${doc.name} failed", e)
            dao.setStatus(id, DocumentStatus.FAILED, "This file couldn't be read.", null, 0)
        } finally {
            _progress.value = _progress.value - id
        }
    }

    private suspend fun embedder(): Embedder? = embedderLock.withLock {
        val model = searchModel.active.value
        if (embedderId != model?.id) {
            embedder?.close()
            embedder = null
            embedderId = null
        }
        embedder ?: model?.let { Embedder(it.dir).also { e -> embedder = e; embedderId = it.id } }
    }

    /** Makes [id] the search model; stored passages are re-scored with it in the background. */
    fun selectSearchModel(id: String) {
        // Re-scoring for the previous pick is wasted work now; a new pass is queued for this one.
        current?.takeIf { it.first == REEMBED }?.second?.cancel()
        searchModel.select(id)
        _searchModelLoaded.value = false
    }

    /** Re-scores every ready document's passages with the active model, from their stored text. */
    private suspend fun reembedAll() {
        val model = searchModel.active.value ?: return
        if (model.id == searchModel.indexedWith) return
        val embedder = embedder() ?: return
        // Until every passage is done the vectors are a mix, so an interrupted pass is redone.
        searchModel.indexedWith = ""
        try {
            for (doc in dao.withStatus(DocumentStatus.READY)) {
                _progress.value = _progress.value + (doc.id to 0f)
                try {
                    val chunks = dao.chunkTexts(doc.id)
                    var done = 0
                    for (batch in chunks.chunked(EMBED_BATCH)) {
                        val vectors = embedder.embed(batch.map { it.embeddingText })
                        batch.forEachIndexed { i, chunk -> dao.setEmbedding(chunk.id, Vectors.toBytes(vectors[i])) }
                        done += batch.size
                        _progress.value = _progress.value + (doc.id to done.toFloat() / chunks.size)
                    }
                } finally {
                    _progress.value = _progress.value - doc.id
                }
            }
            _searchModelLoaded.value = true
            searchModel.indexedWith = model.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't re-score documents with ${model.name}", e)
        } finally {
            invalidate()
        }
    }

    /** Loads the search model now, so the first search after this is instant. */
    fun loadSearchModel() {
        scope.launch {
            try {
                embedder()?.embed("warm up")
                _searchModelLoaded.value = embedder != null
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't load the search model", e)
            }
        }
    }

    /**
     * Frees the search model's memory. Searching or indexing loads it again on its own, so this
     * never turns document search off.
     */
    fun unloadSearchModel() {
        scope.launch {
            // Indexing is mid-way through using it; it'll be released when that's done instead.
            if (current != null) return@launch
            embedderLock.withLock {
                embedder?.close()
                embedder = null
                embedderId = null
            }
            _searchModelLoaded.value = false
        }
    }

    /**
     * Removes a downloaded search model. Indexed documents stay: another installed model takes
     * over (re-scoring them), or they work again once a model is back.
     */
    fun deleteSearchModel(id: String) {
        scope.launch {
            if (searchModel.active.value?.id == id) {
                current?.second?.cancel()
                embedderLock.withLock {
                    embedder?.close()
                    embedder = null
                    embedderId = null
                }
                _searchModelLoaded.value = false
            }
            withContext(Dispatchers.IO) { searchModel.delete(id) }
        }
    }

    /** Passages scored by a model with [dim]-number vectors; any others (mid re-scoring) can't be compared. */
    private suspend fun loadIndex(dim: Int): VectorIndex = indexLock.withLock {
        index?.takeIf { it.dim == dim } ?: run {
            val rows = dao.readyVectors().filter { it.embedding.size == dim * 4 }
            val vectors = FloatArray(rows.size * dim)
            rows.forEachIndexed { i, row -> Vectors.fromBytes(row.embedding).copyInto(vectors, i * dim) }
            VectorIndex(LongArray(rows.size) { rows[it].id }, LongArray(rows.size) { rows[it].documentId }, vectors, dim).also { index = it }
        }
    }

    private suspend fun invalidate() = indexLock.withLock { index = null }

    private fun releaseAccess(uri: String) {
        try {
            appContext.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // It was never persisted; nothing to release.
        }
    }
}
