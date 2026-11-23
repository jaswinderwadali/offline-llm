package ai.impact.data.documents

import java.text.Normalizer

/**
 * BERT's uncased WordPiece tokenizer, as used by the all-MiniLM-L6-v2 search model: clean the
 * text, lowercase and strip accents, split on spaces and punctuation, then break each word into
 * the longest pieces the vocabulary knows ("unaffable" → "una", "##ffa", "##ble").
 *
 * It must match the reference tokenizer exactly, or the model sees different input than it was
 * trained on and search quality quietly drops; see WordPieceTokenizerTest.
 */
class WordPieceTokenizer(private val vocab: Map<String, Int>) {

    companion object {
        /** Reads a vocab.txt file, one token per line; a token's id is its line number. */
        fun fromVocabLines(lines: Sequence<String>): WordPieceTokenizer {
            val vocab = HashMap<String, Int>()
            lines.forEachIndexed { index, line -> vocab[line.trimEnd('\r', '\n')] = index }
            return WordPieceTokenizer(vocab)
        }

        private const val MAX_WORD_CHARS = 100
    }

    private val unk = vocab.getValue("[UNK]")
    val cls = vocab.getValue("[CLS]")
    val sep = vocab.getValue("[SEP]")
    val pad = vocab["[PAD]"] ?: 0

    /** Token ids for [text] with [CLS] and [SEP], cut to [maxLength] in total. */
    fun encode(text: String, maxLength: Int = 256): IntArray {
        val pieces = tokenize(text)
        val kept = pieces.size.coerceAtMost(maxLength - 2)
        return IntArray(kept + 2).also { ids ->
            ids[0] = cls
            for (i in 0 until kept) ids[i + 1] = pieces[i]
            ids[kept + 1] = sep
        }
    }

    /** How many pieces [text] becomes, not counting [CLS] and [SEP]. */
    fun count(text: String): Int = tokenize(text).size

    fun tokenize(text: String): List<Int> {
        val out = ArrayList<Int>()
        for (word in basicTokens(text)) wordPieces(word, out)
        return out
    }

    private fun basicTokens(text: String): List<String> {
        // Clean, and give each CJK character its own word.
        val cleaned = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == 0 || cp == 0xFFFD || isControl(cp) -> Unit
                isWhitespace(cp) -> cleaned.append(' ')
                isCjk(cp) -> cleaned.append(' ').appendCodePoint(cp).append(' ')
                else -> cleaned.appendCodePoint(cp)
            }
        }
        val words = ArrayList<String>()
        for (raw in cleaned.split(' ')) {
            if (raw.isEmpty()) continue
            val word = stripAccents(raw.lowercase())
            // Punctuation splits words and becomes a token of its own.
            val current = StringBuilder()
            var j = 0
            while (j < word.length) {
                val cp = word.codePointAt(j)
                j += Character.charCount(cp)
                if (isPunctuation(cp)) {
                    if (current.isNotEmpty()) { words.add(current.toString()); current.setLength(0) }
                    words.add(String(Character.toChars(cp)))
                } else {
                    current.appendCodePoint(cp)
                }
            }
            if (current.isNotEmpty()) words.add(current.toString())
        }
        return words
    }

    private fun wordPieces(word: String, out: MutableList<Int>) {
        if (word.codePointCount(0, word.length) > MAX_WORD_CHARS) {
            out.add(unk)
            return
        }
        val pieces = ArrayList<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found = -1
            while (start < end) {
                val piece = if (start == 0) word.substring(start, end) else "##" + word.substring(start, end)
                val id = vocab[piece]
                if (id != null) { found = id; break }
                // Step back one code point, never splitting a surrogate pair.
                end = word.offsetByCodePoints(end, -1)
            }
            if (found == -1) {
                out.add(unk)
                return
            }
            pieces.add(found)
            start = end
        }
        out.addAll(pieces)
    }

    private fun stripAccents(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    private fun isWhitespace(cp: Int): Boolean =
        cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code ||
            Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

    private fun isControl(cp: Int): Boolean {
        if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
        val type = Character.getType(cp)
        return type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() ||
            type == Character.PRIVATE_USE.toInt() || type == Character.SURROGATE.toInt()
    }

    private fun isPunctuation(cp: Int): Boolean {
        // BERT counts all non-letter, non-number ASCII as punctuation, like ^ $ and `.
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return when (Character.getType(cp).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    private fun isCjk(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF || cp in 0x2A700..0x2B73F ||
            cp in 0x2B740..0x2B81F || cp in 0x2B820..0x2CEAF || cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F
}
