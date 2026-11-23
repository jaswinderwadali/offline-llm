package ai.impact.speech

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Turns Whisper's token ids back into text. Whisper uses GPT-2's byte-level BPE: each token is
 * a run of bytes written with printable stand-in characters, so decoding maps them back to bytes.
 */
class WhisperVocab(private val idToToken: Map<Int, String>, private val firstSpecialId: Int) {

    companion object {
        fun fromJson(json: String): WhisperVocab {
            val obj = JSONObject(json)
            val map = HashMap<Int, String>(obj.length())
            var maxId = 0
            for (key in obj.keys()) {
                val id = obj.getInt(key)
                map[id] = key
                if (id > maxId) maxId = id
            }
            // "<|endoftext|>" is the first special token; everything from it on isn't text.
            val endOfText = obj.optInt("<|endoftext|>", maxId + 1)
            return WhisperVocab(map, endOfText)
        }

        /** GPT-2's map from stand-in characters back to the bytes they represent. */
        private val byteDecoder: Map<Char, Int> = run {
            val bs = ArrayList<Int>()
            bs.addAll('!'.code..'~'.code)
            bs.addAll('¡'.code..'¬'.code)
            bs.addAll('®'.code..'ÿ'.code)
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0 until 256) {
                if (b !in bs) {
                    bs.add(b)
                    cs.add(256 + n)
                    n++
                }
            }
            bs.indices.associate { cs[it].toChar() to bs[it] }
        }
    }

    fun decode(ids: List<Int>): String {
        val bytes = java.io.ByteArrayOutputStream()
        for (id in ids) {
            if (id >= firstSpecialId) continue
            val token = idToToken[id] ?: continue
            for (ch in token) byteDecoder[ch]?.let(bytes::write)
        }
        return bytes.toString(Charsets.UTF_8.name()).trim()
    }
}

/**
 * MMS-TTS's tokenizer: one id per character the voice knows, with a blank (id 0) before, between
 * and after them, as the model was trained. Characters it doesn't know are dropped.
 */
class MmsTokenizer(private val vocab: Map<String, Int>) {

    companion object {
        fun fromJson(json: String): MmsTokenizer {
            val obj = JSONObject(json)
            return MmsTokenizer(obj.keys().asSequence().associateWith { obj.getInt(it) })
        }
    }

    fun encode(text: String): LongArray {
        val known = text.lowercase().map { it.toString() }.mapNotNull { vocab[it] }
        val out = LongArray(known.size * 2 + 1)
        known.forEachIndexed { i, id -> out[i * 2 + 1] = id.toLong() }
        return out
    }
}

/** What's worth reading aloud from a reply: no markdown symbols, code or links. */
object SpeakableText {

    private val codeBlock = Regex("```[\\s\\S]*?(```|$)")
    private val link = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val url = Regex("https?://\\S+")
    private val symbols = Regex("[*_`#>~|]")
    private val bullet = Regex("(?m)^\\s*(?:[-•]|\\d+[.)])\\s+")
    private val spaces = Regex("[ \\t]+")

    fun from(markdown: String): String = markdown
        .replace(codeBlock, " ")
        .replace(link, "$1")
        .replace(url, "a link")
        .replace(bullet, "")
        .replace(symbols, "")
        .replace(spaces, " ")
        .lines().map { it.trim() }.filter { it.isNotEmpty() }
        // A list item becomes its own sentence so the voice pauses between them.
        .joinToString(" ") { if (it.last() in ".!?:;,") it else "$it." }
        .trim()

    private val sentenceEnd = Regex("(?<=[.!?])\\s+")

    /** Sentences grouped up to about [maxChars], so the first one can play while the rest are made. */
    fun chunks(text: String, maxChars: Int = 220): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (sentence in text.split(sentenceEnd)) {
            if (current.isNotEmpty() && current.length + sentence.length > maxChars) {
                out.add(current.toString().trim())
                current.setLength(0)
            }
            current.append(sentence).append(' ')
        }
        if (current.isNotBlank()) out.add(current.toString().trim())
        return out
    }
}

/**
 * Decides when someone has started and stopped talking, from the loudness of short audio frames.
 * It learns the room's background level first, so it works in a quiet room and a noisy one.
 */
class SpeechDetector(
    private val frameMillis: Int = 30,
    /** How long a pause ends the utterance: three seconds, so a moment to think doesn't cut the user off. */
    private val endSilenceMillis: Int = SpeechPause.MILLIS,
    /** Give up when nobody speaks for this long. */
    private val noSpeechMillis: Int = 8_000,
    private val maxSpeechMillis: Int = 28_000
) {
    enum class State { Waiting, Speaking, Done, NoSpeech }

    var state = State.Waiting
        private set
    private var noise = 0.0
    private var frames = 0
    private var silentFrames = 0
    private var speechFrames = 0

    /** Loudness (RMS) of one frame of samples in -1..1. */
    fun rms(frame: FloatArray, count: Int = frame.size): Double {
        var sum = 0.0
        for (i in 0 until count) sum += frame[i] * frame[i]
        return sqrt(sum / max(1, count))
    }

    /** Feeds one frame's loudness; returns the new state. */
    fun feed(level: Double): State {
        if (state == State.Done || state == State.NoSpeech) return state
        frames++
        // The first quarter second sets the background level; it then drifts slowly with the room.
        noise = if (frames <= 8) max(noise, level) else if (state == State.Waiting) noise * 0.95 + level * 0.05 else noise
        val threshold = max(0.012, noise * 2.5)
        val loud = level > threshold
        when (state) {
            State.Waiting -> {
                speechFrames = if (loud) speechFrames + 1 else 0
                // A few loud frames in a row, not a single click.
                if (frames > 8 && speechFrames >= 3) state = State.Speaking
                else if (frames * frameMillis >= noSpeechMillis) state = State.NoSpeech
            }
            State.Speaking -> {
                speechFrames++
                silentFrames = if (loud) 0 else silentFrames + 1
                if (silentFrames * frameMillis >= endSilenceMillis || speechFrames * frameMillis >= maxSpeechMillis) state = State.Done
            }
            else -> Unit
        }
        return state
    }
}
