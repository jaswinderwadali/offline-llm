package ai.impact.data

/**
 * A model reply split into its hidden reasoning (`<think>…</think>` or `<thinking>…</thinking>`,
 * as emitted by Qwen3, DeepSeek-R1 and similar models) and the visible answer.
 */
data class ParsedResponse(
    val thinking: String?,
    val isThinkingComplete: Boolean,
    val answer: String
) {
    val hasThinking: Boolean get() = thinking != null
}

object ResponseParser {

    private val openTag = Regex("<think(?:ing)?>", RegexOption.IGNORE_CASE)
    private val closeTag = Regex("</think(?:ing)?>", RegexOption.IGNORE_CASE)
    private val tagPrefixes = listOf("<think>", "<thinking>", "</think>", "</thinking>")

    fun parse(raw: String): ParsedResponse {
        val open = openTag.find(raw)
        val close = closeTag.find(raw, open?.range?.last?.plus(1) ?: 0)

        return when {
            open != null && close != null -> ParsedResponse(
                thinking = raw.substring(open.range.last + 1, close.range.first).trim(),
                isThinkingComplete = true,
                answer = (raw.substring(0, open.range.first) + raw.substring(close.range.last + 1)).cleanAnswer()
            )
            open != null -> ParsedResponse(
                thinking = stripPartialTag(raw.substring(open.range.last + 1)).trim(),
                isThinkingComplete = false,
                answer = raw.substring(0, open.range.first).cleanAnswer()
            )
            // Some chat templates pre-fill "<think>", so the model only emits the closing tag.
            close != null -> ParsedResponse(
                thinking = raw.substring(0, close.range.first).trim(),
                isThinkingComplete = true,
                answer = raw.substring(close.range.last + 1).cleanAnswer()
            )
            else -> ParsedResponse(thinking = null, isThinkingComplete = false, answer = stripPartialTag(raw).cleanAnswer())
        }
    }

    /** True once the closing reasoning tag has appeared in [raw]. */
    fun hasFinishedThinking(raw: String): Boolean = closeTag.containsMatchIn(raw)

    private fun String.cleanAnswer(): String = stripPartialTag(this).trim()

    /** Hides a half-streamed tag such as "<thi" at the end so it never flashes on screen. */
    private fun stripPartialTag(text: String): String {
        val lt = text.lastIndexOf('<')
        if (lt < 0) return text
        val tail = text.substring(lt).lowercase()
        return if (tagPrefixes.any { it.startsWith(tail) && it != tail }) text.substring(0, lt) else text
    }
}
