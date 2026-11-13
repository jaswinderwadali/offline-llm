package ai.impact.image

import ai.impact.data.ResponseParser

/** Rewrites a short idea into a detailed image prompt, using whatever text model is loaded. */
fun interface PromptEnhancer {
    /** The improved prompt, or null when no text model is free to do it. */
    suspend fun enhance(idea: String, styleHint: String): String?
}

/** Instructions for the text model and cleanup of its reply; pure logic so it's unit-tested. */
object PromptEnhancement {

    /** CLIP reads at most 77 tokens, so longer prompts are silently cut off by the image model. */
    private const val MAX_CHARS = 320

    fun systemPrompt(styleHint: String): String = buildString {
        append("You write prompts for a Stable Diffusion image generator. ")
        append("Rewrite the user's idea as ONE image prompt: a comma-separated list of concrete visual details ")
        append("covering the subject, setting, lighting, colors, composition, camera or art style, and quality keywords. ")
        append("Keep the user's subject and intent exactly; never add people, text, words or logos they didn't ask for. ")
        append("Use at most 50 words. ")
        if (styleHint.isNotBlank()) append(styleHint.trim()).append(' ')
        append("Reply with the prompt only: no introduction, no explanation, no quotes, no line breaks.")
    }

    /**
     * Pulls the prompt out of a model reply: drops reasoning blocks, preambles like
     * "Here's your prompt:", labels, quotes and markdown, and trims to what CLIP can read.
     * Returns null if nothing usable is left.
     */
    fun clean(raw: String): String? {
        val answer = ResponseParser.parse(raw).answer
        val lines = answer.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val line = lines.firstOrNull { !isPreamble(it) } ?: return null
        // Markdown first, so a bold label like "**Prompt:**" is recognised as a label.
        var text = line
            .replace(Regex("""[*_`#]+"""), "")
            .trim()
            .replace(Regex("""^(enhanced |improved |final |image )?prompt\s*[:：-]\s*""", RegexOption.IGNORE_CASE), "")
            .trim()
            .trim('"', '\'', '“', '”', '‘', '’')
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (text.length > MAX_CHARS) {
            // Cut at the last comma (or space) before the limit so no detail is left half-written.
            val cut = text.substring(0, MAX_CHARS)
            val at = cut.lastIndexOf(',').takeIf { it > MAX_CHARS / 2 } ?: cut.lastIndexOf(' ')
            text = cut.substring(0, if (at > 0) at else MAX_CHARS).trimEnd(',', ' ')
        }
        return text.takeIf { it.length >= 3 }
    }

    private fun isPreamble(line: String): Boolean {
        val lower = line.lowercase()
        return line.endsWith(":") ||
            lower.startsWith("here is") || lower.startsWith("here's") || lower.startsWith("sure") ||
            lower.startsWith("certainly") || lower.startsWith("okay") || lower.startsWith("ok,")
    }

    /** A hint matched to what the image model was trained on. */
    fun styleHintFor(modelName: String): String {
        val n = modelName.lowercase()
        return when {
            "anything" in n || "anime" in n || "waifu" in n || "counterfeit" in n ->
                "This model draws anime illustrations: start with \"masterpiece, best quality\" and use short tag-style phrases."
            "turbo" in n -> "This model renders photos and general images: describe the scene vividly in short phrases."
            "realis" in n || "photo" in n || "epicreal" in n ->
                "This model is photorealistic: include camera and lighting terms such as \"photo, 35mm, soft natural light, detailed\"."
            else -> ""
        }
    }
}
