package ai.impact.data.documents

/** A paragraph or heading read from a document, with the page it's on when the format has pages. */
data class Block(val text: String, val page: Int? = null, val isHeading: Boolean = false)

/** A passage to search: a few paragraphs under one heading, small enough for the search model. */
data class Chunk(
    val text: String,
    /** The nearest heading above it, which helps both search and the reader. */
    val heading: String?,
    val pageStart: Int?,
    val pageEnd: Int?
) {
    /** What the search model reads: the heading gives a passage its context. */
    val embeddingText: String get() = if (heading.isNullOrBlank()) text else "$heading\n$text"
}

/**
 * Splits a document into overlapping passages along paragraph and heading boundaries.
 *
 * Sizes are in search-model tokens. all-MiniLM-L6-v2 reads at most 256, so passages aim a bit
 * under that (leaving room for the heading) instead of the 300–500 often quoted for bigger
 * models; a longer passage would be silently cut off when embedded. Neighbouring passages share
 * about 15% so an idea that straddles a boundary is still found whole in one of them.
 */
class TextChunker(
    private val countTokens: (String) -> Int,
    private val targetTokens: Int = 220,
    private val maxTokens: Int = 250,
    private val overlapTokens: Int = 32,
    /** A heading only starts a new passage once the current one has this much in it. */
    private val minTokens: Int = 60,
    /** A new page starts a new passage from this much, so citations name the right page. */
    private val minPageTokens: Int = 30
) {

    private class Unit(
        val text: String,
        val tokens: Int,
        val page: Int?,
        val startsParagraph: Boolean,
        /** The section it's in; a passage is labelled with its first new sentence's. */
        val heading: String? = null,
        /** Repeated from the previous passage as overlap; it doesn't count toward this one's pages. */
        val carried: Boolean = false
    )

    fun chunk(blocks: List<Block>): List<Chunk> {
        val chunks = ArrayList<Chunk>()
        val current = ArrayList<Unit>()
        var currentTokens = 0
        // Whether the passage being built holds anything beyond overlap already emitted.
        var hasNew = false
        var heading: String? = null

        fun emit() {
            if (current.isEmpty()) return
            val text = buildString {
                current.forEachIndexed { i, unit ->
                    if (i > 0) append(if (unit.startsParagraph) "\n\n" else " ")
                    append(unit.text)
                }
            }
            val own = current.filterNot { it.carried }.ifEmpty { current }
            val pages = own.mapNotNull { it.page }
            chunks.add(Chunk(text, own.first().heading, pages.minOrNull(), pages.maxOrNull()))
        }

        fun flush(keepOverlap: Boolean) {
            emit()
            // Carry the last sentences over, so the next passage starts with some context: only from
            // the section the next passage is in, and never the whole passage, which would repeat it.
            val carried = ArrayList<Unit>()
            if (keepOverlap) {
                var tokens = 0
                val newUnits = current.count { !it.carried }
                for (unit in current.asReversed()) {
                    if (unit.carried || unit.heading != heading || carried.size + 1 >= newUnits) break
                    if (tokens + unit.tokens > overlapTokens) break
                    carried.add(0, Unit(unit.text, unit.tokens, unit.page, unit.startsParagraph, unit.heading, carried = true))
                    tokens += unit.tokens
                }
            }
            current.clear()
            current.addAll(carried)
            currentTokens = carried.sumOf { it.tokens }
            hasNew = false
        }

        for (block in blocks) {
            val text = block.text.trim()
            if (text.isEmpty()) continue
            if (block.isHeading) {
                // A new section starts a new passage, unless the current one is too short to stand alone.
                if (hasNew && currentTokens >= minTokens) flush(keepOverlap = false)
                else if (!hasNew) { current.clear(); currentTokens = 0 }
                heading = text.take(200)
                continue
            }
            // A new page starts a new passage once there's enough to stand alone, so a citation
            // points at the page the fact is on rather than "pp. 1–2".
            val lastPage = current.lastOrNull()?.page
            if (hasNew && block.page != null && lastPage != null && block.page != lastPage && currentTokens >= minPageTokens) {
                flush(keepOverlap = true)
            }
            units(text, block.page).forEachIndexed { index, unit ->
                val fresh = Unit(unit.text, unit.tokens, unit.page, startsParagraph = index == 0, heading = heading)
                if (current.isNotEmpty() && currentTokens + fresh.tokens > targetTokens) flush(keepOverlap = true)
                // Overlap from the last passage can't push a new one past its limit.
                while (current.isNotEmpty() && currentTokens + fresh.tokens > maxTokens) {
                    currentTokens -= current.removeAt(0).tokens
                }
                current.add(fresh)
                currentTokens += fresh.tokens
                hasNew = true
            }
        }
        if (hasNew) emit()
        return chunks
    }

    private val sentenceEnd = Regex("(?<=[.!?…])[\"')\\]]*\\s+(?=[\\p{Lu}\\p{N}\"'(\\[])")

    /**
     * A paragraph's sentences, and runs of words for a sentence too long to fit. Sentences are the
     * unit, so the overlap between passages is whole sentences rather than a cut-off fragment.
     */
    private fun units(paragraph: String, page: Int?): List<Unit> {
        val out = ArrayList<Unit>()
        for (sentence in paragraph.split(sentenceEnd)) {
            val s = sentence.trim()
            if (s.isEmpty()) continue
            val sentenceTokens = countTokens(s)
            if (sentenceTokens <= targetTokens) {
                out.add(Unit(s, sentenceTokens, page, false))
            } else {
                out.addAll(wordRuns(s, page))
            }
        }
        return out
    }

    private fun wordRuns(text: String, page: Int?): List<Unit> {
        val out = ArrayList<Unit>()
        val run = StringBuilder()
        var runTokens = 0
        for (word in text.split(' ')) {
            if (word.isEmpty()) continue
            val wordTokens = countTokens(word)
            if (runTokens + wordTokens > targetTokens && run.isNotEmpty()) {
                out.add(Unit(run.toString(), runTokens, page, false))
                run.setLength(0)
                runTokens = 0
            }
            if (run.isNotEmpty()) run.append(' ')
            run.append(word)
            runTokens += wordTokens
        }
        if (run.isNotEmpty()) out.add(Unit(run.toString(), runTokens, page, false))
        return out
    }
}
