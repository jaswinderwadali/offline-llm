package ai.impact

import ai.impact.speech.MmsTokenizer
import ai.impact.speech.SpeakableText
import ai.impact.speech.SpeechDetector
import ai.impact.speech.WhisperFeatures
import ai.impact.speech.WhisperVocab
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Checked against Hugging Face's WhisperFeatureExtractor and VitsTokenizer (transformers 4.x). */
class SpeechTest {

    private fun resourceText(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes().toString(Charsets.UTF_8)
    private val ref by lazy { JSONObject(resourceText("speech-ref.json")) }

    private fun tone(): FloatArray {
        val bytes = javaClass.classLoader!!.getResourceAsStream("speech-tone.f32")!!.readBytes()
        val out = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    @Test
    fun melSpectrogramMatchesTheReference() {
        val mel = WhisperFeatures.compute(tone())
        assertEquals(WhisperFeatures.N_MELS * WhisperFeatures.N_FRAMES, mel.size)
        val samples = ref.getJSONObject("tone_mel_samples")
        for (key in samples.keys()) {
            val (m, f) = key.split(",").map(String::toInt)
            val expected = samples.getDouble(key)
            val actual = mel[m * WhisperFeatures.N_FRAMES + f].toDouble()
            assertEquals("mel[$m,$f]", expected, actual, 2e-3)
        }
        assertEquals(ref.getDouble("tone_mel_mean"), mel.average(), 2e-3)
        assertEquals(ref.getDouble("tone_mel_max"), mel.max().toDouble(), 2e-3)
    }

    @Test
    fun whisperTokensDecodeToText() {
        val vocab = WhisperVocab.fromJson(ref.getJSONObject("whisper_vocab_subset").toString())
        val ids = ref.getJSONArray("roundtrip_tokens").let { a -> List(a.length()) { a.getInt(it) } }
        // The two prompt tokens and anything special are left out.
        assertEquals(ref.getString("roundtrip_text"), vocab.decode(ids))
    }

    @Test
    fun mmsTokensMatchTheReference() {
        val tokenizer = MmsTokenizer.fromJson(resourceText("mms-vocab.json"))
        val cases = ref.getJSONArray("mms_tokens")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val expected = case.getJSONArray("ids").let { a -> LongArray(a.length()) { a.getLong(it) } }
            assertArrayEquals(case.getString("text"), expected, tokenizer.encode(case.getString("text")))
        }
    }

    @Test
    fun repliesAreReadWithoutMarkup() {
        val spoken = SpeakableText.from("**Sure!** Here's how:\n\n1. Mix the *flour*\n- Add [eggs](https://x.com)\n```\ncode()\n```\nSee https://example.com")
        assertEquals("Sure! Here's how: Mix the flour. Add eggs. See a link.", spoken)
        val chunks = SpeakableText.chunks("One. ".repeat(80).trim(), maxChars = 100)
        assertTrue(chunks.size > 3)
        chunks.forEach { assertTrue(it.length <= 100) }
    }

    private fun frame(amplitude: Double, frames: Int = 480) = FloatArray(frames) { (amplitude * sin(2 * PI * 300 * it / 16000.0)).toFloat() }

    @Test
    fun detectorFindsTheStartAndEndOfSpeech() {
        val d = SpeechDetector()
        // Quiet room, then speech, then a pause.
        repeat(20) { d.feed(d.rms(frame(0.003))) }
        assertEquals(SpeechDetector.State.Waiting, d.state)
        repeat(30) { d.feed(d.rms(frame(0.2))) }
        assertEquals(SpeechDetector.State.Speaking, d.state)
        // A two-second pause to think isn't the end (frames are 30 ms).
        repeat(66) { d.feed(d.rms(frame(0.003))) }
        assertEquals(SpeechDetector.State.Speaking, d.state)
        // Talking again resets the pause.
        repeat(10) { d.feed(d.rms(frame(0.2))) }
        repeat(66) { d.feed(d.rms(frame(0.003))) }
        assertEquals(SpeechDetector.State.Speaking, d.state)
        // Three seconds of quiet is.
        repeat(35) { d.feed(d.rms(frame(0.003))) }
        assertEquals(SpeechDetector.State.Done, d.state)
    }

    @Test
    fun detectorGivesUpWhenNobodySpeaks() {
        val d = SpeechDetector()
        repeat(400) { d.feed(d.rms(frame(0.003))) }
        assertEquals(SpeechDetector.State.NoSpeech, d.state)
    }

    @Test
    fun aSingleClickIsntSpeech() {
        val d = SpeechDetector()
        repeat(20) { d.feed(d.rms(frame(0.003))) }
        d.feed(d.rms(frame(0.5)))
        repeat(5) { d.feed(d.rms(frame(0.003))) }
        assertEquals(SpeechDetector.State.Waiting, d.state)
        assertTrue(abs(d.rms(frame(0.0))) < 1e-9)
    }
}
