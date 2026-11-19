package ai.impact.data

import kotlin.math.abs

/** The reply lengths offered in Settings. Named presets read better than a raw word count. */
enum class ReplyLength(val label: String, val words: Int, val description: String) {
    Brief("Brief", 50, "A line or two. Fastest."),
    Short("Short", 100, "A quick paragraph."),
    Balanced("Balanced", 150, "A full answer without rambling."),
    Detailed("Detailed", 250, "Room for steps and examples."),
    InDepth("In-depth", 400, "Long explanations. Slowest on most phones.");

    companion object {
        val Default = Balanced

        /** The preset closest to a saved word count (older versions saved any value from 50 to 500). */
        fun nearest(words: Int): ReplyLength = entries.minBy { abs(it.words - words) }
    }
}

/**
 * Keeps a streaming reply within the chosen length. The model is asked to stay under the limit,
 * but small on-device models often don't, and the engine has no token cap, so the chat stops the
 * reply itself once the visible answer runs over and trims it back to a whole sentence.
 */
object ReplyLimiter {

    private val closeTag = Regex("</think(?:ing)?>", RegexOption.IGNORE_CASE)
    private val openTag = Regex("<think(?:ing)?>", RegexOption.IGNORE_CASE)
    private val word = Regex("\\S+")
    // A sentence end (with any closing quote or bracket) followed by a space, or a paragraph break.
    private val sentenceEnds = Regex("[.!?…][\"')\\]]*(?=\\s|$)|\\n\\n")

    /** Lets the model finish the sentence it's on before cutting. */
    private fun hardLimit(limit: Int) = limit + maxOf(10, limit / 10)

    /**
     * Returns [raw] trimmed to fit [limit] words once its visible answer has gone past it, or
     * null while it still fits. Reasoning inside think tags never counts.
     */
    fun clip(raw: String, limit: Int): String? {
        val answerStart = answerStart(raw) ?: return null
        val answer = raw.substring(answerStart)
        val words = word.findAll(answer).toList()
        if (words.size <= hardLimit(limit)) return null

        // Cut after the last whole sentence that ends within the limit, if that keeps most of it.
        val limitEnd = words[limit - 1].range.last + 1
        val sentenceEnd = sentenceEnds.findAll(answer.substring(0, limitEnd)).lastOrNull()?.let { it.range.last + 1 }
        val keep = if (sentenceEnd != null && sentenceEnd >= limitEnd * 0.6) {
            answer.substring(0, sentenceEnd).trimEnd()
        } else {
            answer.substring(0, limitEnd).trimEnd().trimEnd(',', ';', ':', '-') + "…"
        }
        return raw.substring(0, answerStart) + keep
    }

    /** Where the visible answer begins, or null while the model is still reasoning. */
    private fun answerStart(raw: String): Int? {
        closeTag.find(raw)?.let { return it.range.last + 1 }
        return if (openTag.containsMatchIn(raw)) null else 0
    }
}
