package ai.impact.image

import org.json.JSONObject
import java.io.File

/**
 * The byte-level BPE tokenizer used by Stable Diffusion's CLIP text encoder, reading the
 * standard `vocab.json` and `merges.txt`. Prompts are padded/truncated to [maxLength] tokens.
 */
class ClipTokenizer(
    private val vocab: Map<String, Int>,
    merges: List<String>,
    /** Padding token. SD 1.x pads with end-of-text; SD 2.x (e.g. SD-Turbo) pads with "!". */
    private val padTokenId: Int = vocab[END] ?: 49407,
    private val maxLength: Int = 77
) {
    companion object {
        private const val START = "<|startoftext|>"
        private const val END = "<|endoftext|>"

        private val PATTERN = Regex(
            """<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+""",
            RegexOption.IGNORE_CASE
        )

        fun load(dir: File, padWithExclamation: Boolean): ClipTokenizer {
            val vocabJson = JSONObject(File(dir, "vocab.json").readText())
            val vocab = HashMap<String, Int>(vocabJson.length() * 2)
            vocabJson.keys().forEach { vocab[it] = vocabJson.getInt(it) }
            val merges = File(dir, "merges.txt").readLines()
            // The tokenizer's own config wins over the guess from the model's name.
            val padToken = readPadToken(File(dir, "special_tokens_map.json"))
                ?: if (padWithExclamation) "!" else END
            val pad = vocab[padToken] ?: vocab[END] ?: 49407
            return ClipTokenizer(vocab, merges, pad)
        }

        private fun readPadToken(file: File): String? = try {
            if (!file.isFile) null else {
                // Either "pad_token": "!" or "pad_token": {"content": "!", ...}.
                when (val pad = JSONObject(file.readText()).opt("pad_token")) {
                    is String -> pad
                    is JSONObject -> pad.optString("content").takeIf { it.isNotEmpty() }
                    else -> null
                }
            }
        } catch (e: Exception) {
            null
        }

        /** GPT-2's reversible byte -> printable-unicode table. */
        private val BYTE_ENCODER: Array<String> = run {
            val printable = (('!'.code..'~'.code) + ('¡'.code..'¬'.code) + ('®'.code..'ÿ'.code)).toMutableList()
            val chars = printable.toMutableList()
            var n = 0
            for (b in 0 until 256) {
                if (b !in printable) {
                    printable.add(b)
                    chars.add(256 + n)
                    n++
                }
            }
            val table = arrayOfNulls<String>(256)
            printable.forEachIndexed { i, b -> table[b] = String(Character.toChars(chars[i])) }
            Array(256) { table[it]!! }
        }
    }

    private val startId = vocab[START] ?: 49406
    private val endId = vocab[END] ?: 49407
    private val unknownId = endId

    private val ranks: Map<Pair<String, String>, Int> = HashMap<Pair<String, String>, Int>().apply {
        var rank = 0
        for (line in merges) {
            if (line.isBlank() || line.startsWith("#version")) continue
            val parts = line.trim().split(' ')
            if (parts.size == 2) put(parts[0] to parts[1], rank++)
        }
    }

    private val cache = HashMap<String, List<String>>()

    /** Token ids including start/end markers, padded to exactly [maxLength]. */
    fun encode(text: String): IntArray {
        val ids = ArrayList<Int>(maxLength)
        ids += startId
        val body = tokenize(text)
        ids += body.take(maxLength - 2)
        ids += endId
        while (ids.size < maxLength) ids += padTokenId
        return ids.toIntArray()
    }

    /** Raw BPE token ids for [text], without start/end markers or padding. */
    fun tokenize(text: String): List<Int> {
        val cleaned = text.replace(Regex("\\s+"), " ").trim().lowercase()
        val out = ArrayList<Int>()
        for (match in PATTERN.findAll(cleaned)) {
            val encoded = match.value.toByteArray(Charsets.UTF_8).joinToString("") { BYTE_ENCODER[it.toInt() and 0xFF] }
            for (piece in bpe(encoded)) out += vocab[piece] ?: unknownId
        }
        return out
    }

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        // Split into code points; the last one carries the end-of-word marker.
        val symbols = ArrayList<String>()
        var i = 0
        while (i < token.length) {
            val cp = token.codePointAt(i)
            symbols += String(Character.toChars(cp))
            i += Character.charCount(cp)
        }
        if (symbols.isEmpty()) return emptyList()
        symbols[symbols.lastIndex] = symbols.last() + "</w>"

        while (symbols.size > 1) {
            var best: Pair<String, String>? = null
            var bestRank = Int.MAX_VALUE
            for (j in 0 until symbols.size - 1) {
                val pair = symbols[j] to symbols[j + 1]
                val rank = ranks[pair] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    best = pair
                }
            }
            val (first, second) = best ?: break
            var j = 0
            val merged = ArrayList<String>(symbols.size)
            while (j < symbols.size) {
                if (j < symbols.size - 1 && symbols[j] == first && symbols[j + 1] == second) {
                    merged += first + second
                    j += 2
                } else {
                    merged += symbols[j]
                    j++
                }
            }
            symbols.clear()
            symbols.addAll(merged)
        }
        cache[token] = symbols
        return symbols
    }
}
