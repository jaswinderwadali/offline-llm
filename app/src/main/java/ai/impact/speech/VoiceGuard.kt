package ai.impact.speech

/**
 * Catches what goes wrong in a voice conversation before it's acted on or read aloud: speech
 * recognition that made things up from noise, and replies that are empty, cut off or stuck in a
 * loop. Small on-device models do all of these now and then; voice mode recovers instead of
 * sending nonsense or reading it out.
 */
object VoiceGuard {

    /** What a reply check decided. */
    sealed interface Reply {
        /** Fine to read aloud; [text] may have had a loop or stray tokens trimmed off. */
        data class Ok(val text: String) : Reply
        /** Nothing to say: blank, or the model ran out while still thinking. */
        data object Empty : Reply
        /** Nonsense: the same few words over and over. */
        data object Garbled : Reply
    }

    // Captions Whisper learned from subtitles and produces from noise, not speech.
    private val subtitleArtifacts = listOf(
        "thanks for watching", "thank you for watching", "please subscribe", "like and subscribe",
        "subtitles by", "captions by", "amara.org", "transcribed by", "see you in the next video"
    )
    private val annotation = Regex("""\[[^\]]*]|\([^)]*\)|\*[^*]*\*|♪+""")
    private val specialToken = Regex("""<\|[^|>]*\|>|</?s>|<unk>|\[(?:BLANK_AUDIO|MUSIC|NOISE)]""", RegexOption.IGNORE_CASE)
    private val wordPattern = Regex("""[\p{L}\p{N}']+""")

    /**
     * What the user said, cleaned up, or null when it isn't something to send: no real words,
     * a subtitle artifact, or one sound repeated ("ah, ah, ah, ah, ah, ah").
     */
    fun cleanTranscript(text: String): String? {
        val cleaned = text.replace(specialToken, " ").replace(annotation, " ").replace(Regex("\\s+"), " ").trim()
        val words = words(cleaned)
        if (words.isEmpty()) return null
        val lower = cleaned.lowercase()
        if (subtitleArtifacts.any { it in lower } && words.size <= 10) return null
        if (isLoop(words)) return null
        return cleaned
    }

    /** Checks a reply's answer (thinking already removed) before it's read aloud. */
    fun checkReply(answer: String): Reply {
        var text = answer.replace(specialToken, " ")
        // A model that starts writing the next turn itself has said all it should.
        Regex("""(?im)^\s*(?:user|assistant|system)\s*:""").find(text)?.let { text = text.substring(0, it.range.first) }
        text = dropRepeatedSentences(text).trim()
        val words = words(text)
        if (words.isEmpty()) return Reply.Empty
        if (isLoop(words)) return Reply.Garbled
        return Reply.Ok(text)
    }

    private fun words(text: String) = wordPattern.findAll(text.lowercase()).map { it.value }.toList()

    /**
     * Whether the words are mostly one short phrase repeated: few distinct words for the length,
     * or the same 1–4 word phrase back to back many times.
     */
    fun isLoop(words: List<String>): Boolean {
        if (words.size >= 6 && words.toSet().size.toDouble() / words.size < 0.3) return true
        // The same n-word phrase back to back, checked at every alignment.
        for (n in 1..4) {
            for (offset in 0 until n) {
                var run = 1
                var i = offset + n
                while (i + n <= words.size) {
                    if (words.subList(i, i + n) == words.subList(i - n, i)) {
                        run++
                        // Four or more in a row, making up at least half of everything said.
                        if (run >= 4 && run * n * 2 >= words.size) return true
                    } else {
                        run = 1
                    }
                    i += n
                }
            }
        }
        return false
    }

    private val sentenceSplit = Regex("""(?<=[.!?])\s+""")

    /** Keeps each sentence once, so a reply that repeats itself is read once. */
    fun dropRepeatedSentences(text: String): String {
        val seen = HashSet<String>()
        return text.split(sentenceSplit).filter { sentence ->
            val key = words(sentence).joinToString(" ")
            key.isEmpty() || seen.add(key)
        }.joinToString(" ")
    }
}
