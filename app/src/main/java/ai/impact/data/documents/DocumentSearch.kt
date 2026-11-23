package ai.impact.data.documents

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Where a passage in a reply came from, for the source chips under it. */
data class DocumentSource(val documentId: Long, val documentName: String, val pageStart: Int?, val pageEnd: Int?) {
    val label: String get() = when {
        pageStart == null -> documentName
        pageEnd == null || pageEnd == pageStart -> "$documentName · p. $pageStart"
        else -> "$documentName · pp. $pageStart–$pageEnd"
    }
}

/** A passage that matched a search, best first. */
data class SearchHit(
    val chunkId: Long,
    val source: DocumentSource,
    val heading: String?,
    val text: String,
    val score: Float
)

object Vectors {

    /** Averages the token vectors the attention mask marks as real, then scales to length 1. */
    fun meanPool(hidden: FloatArray, mask: IntArray, dim: Int, offset: Int = 0): FloatArray {
        val out = FloatArray(dim)
        var count = 0
        for (t in mask.indices) {
            if (mask[t] == 0) continue
            count++
            val base = offset + t * dim
            for (d in 0 until dim) out[d] += hidden[base + d]
        }
        if (count > 0) for (d in 0 until dim) out[d] /= count
        return normalize(out)
    }

    fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x * x
        val norm = sqrt(sum).toFloat()
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }

    /** Cosine similarity of two unit vectors. */
    fun dot(a: FloatArray, b: FloatArray, bOffset: Int = 0): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[bOffset + i]
        return sum
    }

    fun toBytes(v: FloatArray): ByteArray =
        ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(v) }.array()

    fun fromBytes(bytes: ByteArray): FloatArray {
        val out = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }
}

/**
 * Every passage's vector in one flat array, so a search is a single pass of dot products. Even
 * a large library (10,000 passages) is about 15 MB and searches in a few milliseconds, so there's
 * no need for an approximate index on a phone.
 */
class VectorIndex(val ids: LongArray, val documentIds: LongArray, val vectors: FloatArray, val dim: Int) {

    val size: Int get() = ids.size

    /**
     * The [k] best passages scoring at least [minScore], best first. Passages from documents in
     * [excluded] are skipped.
     */
    fun search(query: FloatArray, k: Int, minScore: Float, excluded: Set<Long> = emptySet()): List<Pair<Long, Float>> {
        if (size == 0) return emptyList()
        val best = java.util.PriorityQueue<Pair<Long, Float>>(k + 1, compareBy { it.second })
        for (i in 0 until size) {
            if (excluded.isNotEmpty() && documentIds[i] in excluded) continue
            val score = Vectors.dot(query, vectors, i * dim)
            if (score < minScore) continue
            if (best.size < k) best.add(ids[i] to score)
            else if (score > best.peek()!!.second) { best.poll(); best.add(ids[i] to score) }
        }
        return best.sortedByDescending { it.second }
    }
}

/** How passages found in the user's documents are put in front of the model. */
object DocumentContext {

    /** Below this, all-MiniLM-L6-v2 matches are mostly noise. */
    const val MIN_SCORE = 0.32f

    /**
     * A search query for [message]. A short follow-up ("and chapter 2?") means little alone,
     * so it's searched together with the user's previous message.
     */
    fun query(message: String, previousUserMessage: String?): String {
        val words = message.trim().split(Regex("\\s+")).size
        return if (words < 6 && !previousUserMessage.isNullOrBlank()) "${previousUserMessage.trim()}\n${message.trim()}" else message.trim()
    }

    /**
     * The instruction and excerpts for the prompt, fitting [maxChars], and the hits that made it
     * in (only those get a source chip). Null when nothing fits.
     */
    fun format(hits: List<SearchHit>, maxChars: Int): Pair<String, List<SearchHit>>? {
        val header = "Excerpts from the user's own documents are below. Use them to answer when they're " +
            "relevant, and say which document (and page) you used. If they don't hold the answer, say so " +
            "briefly before answering from general knowledge. Never invent quotes or page numbers."
        val body = StringBuilder()
        val used = ArrayList<SearchHit>()
        for (hit in hits) {
            val label = "[${used.size + 1}] ${hit.source.label}" + (hit.heading?.let { " — $it" } ?: "")
            val room = maxChars - header.length - body.length - label.length - 4
            if (room < 200) break
            val text = if (hit.text.length <= room) hit.text else hit.text.take(room).substringBeforeLast(' ') + "…"
            body.append("\n\n").append(label).append(":\n").append(text)
            used.add(hit)
        }
        if (used.isEmpty()) return null
        return header + body to used
    }

    /** One chip per document and page range, in the order they were used. */
    fun sources(hits: List<SearchHit>): List<DocumentSource> = hits.map { it.source }.distinct()
}

/**
 * Saves a reply's sources with its text in chat history, the way [ai.impact.data.skills.SkillTag]
 * saves a skill, so the source chips come back when the conversation is reopened.
 */
object SourcesTag {

    private val pattern = Regex("""^\[\[sources:([^\]\n]*)]]\n""")

    class Decoded(val sources: List<DocumentSource>, val text: String)

    fun encode(sources: List<DocumentSource>, text: String): String {
        if (sources.isEmpty()) return text
        val json = org.json.JSONArray()
        sources.forEach { s ->
            json.put(
                org.json.JSONObject().put("d", s.documentId).put("n", s.documentName)
                    .put("a", s.pageStart ?: -1).put("b", s.pageEnd ?: -1)
            )
        }
        return "[[sources:${java.net.URLEncoder.encode(json.toString(), "UTF-8")}]]\n$text"
    }

    fun decode(stored: String): Decoded {
        val match = pattern.find(stored) ?: return Decoded(emptyList(), stored)
        val sources = runCatching {
            val json = org.json.JSONArray(java.net.URLDecoder.decode(match.groupValues[1], "UTF-8"))
            List(json.length()) { i ->
                val o = json.getJSONObject(i)
                DocumentSource(
                    o.getLong("d"), o.getString("n"),
                    o.optInt("a", -1).takeIf { it >= 0 }, o.optInt("b", -1).takeIf { it >= 0 }
                )
            }
        }.getOrDefault(emptyList())
        return Decoded(sources, stored.substring(match.range.last + 1))
    }
}
