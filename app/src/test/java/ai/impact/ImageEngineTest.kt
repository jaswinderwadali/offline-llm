package ai.impact

import ai.impact.image.ClipTokenizer
import ai.impact.image.EulerAncestralScheduler
import ai.impact.image.Half
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class ImageEngineTest {

    @Test
    fun halfRoundTripsCommonValues() {
        for (v in listOf(0f, 1f, -1f, 0.5f, 2.75f, -3.140625f, 65504f, 6.1035156e-5f)) {
            assertEquals(v, Half.toFloat(Half.fromFloat(v)), 0f)
        }
    }

    @Test
    fun halfRoundsAndSaturates() {
        assertEquals(1.0009766f, Half.toFloat(Half.fromFloat(1.0009f)), 1e-6f)
        assertTrue(Half.toFloat(Half.fromFloat(1e6f)).isInfinite())
        assertTrue(Half.toFloat(Half.fromFloat(Float.NaN)).isNaN())
        // Subnormals survive.
        val tiny = 3e-6f
        assertTrue(abs(Half.toFloat(Half.fromFloat(tiny)) - tiny) < 1e-7f)
    }

    @Test
    fun singleStepStartsAtLastTimestepAndLandsOnPrediction() {
        val scheduler = EulerAncestralScheduler(1)
        assertArrayEquals(intArrayOf(999), scheduler.timesteps)
        // SD's final noise level is ~14.6.
        assertEquals(14.6, scheduler.sigmas[0], 0.1)
        assertEquals(0.0, scheduler.sigmas[1], 0.0)

        val latents = floatArrayOf(2f, -1f, 0.5f)
        val noise = floatArrayOf(0.1f, 0.2f, -0.3f)
        val sigma = scheduler.sigmas[0].toFloat()
        val expected = FloatArray(3) { latents[it] - sigma * noise[it] }
        val denoised = scheduler.step(noise, 0, latents, Random(1))
        // With one step the result is exactly the model's clean-image estimate, with no added noise.
        for (i in expected.indices) {
            assertEquals(expected[i], denoised[i], 1e-4f)
            assertEquals(expected[i], latents[i], 1e-4f)
        }
    }

    @Test
    fun trailingSpacingMatchesDiffusers() {
        val scheduler = EulerAncestralScheduler(20)
        assertEquals(999, scheduler.timesteps.first())
        assertEquals(49, scheduler.timesteps.last())
        assertEquals(50, scheduler.timesteps[0] - scheduler.timesteps[1])
        // Noise levels fall monotonically to zero.
        for (i in 1 until scheduler.sigmas.size) assertTrue(scheduler.sigmas[i] < scheduler.sigmas[i - 1])
    }

    @Test
    fun tokenizerAppliesMergesAndPads() {
        val vocab = mapOf(
            "h" to 1, "i</w>" to 2, "hi</w>" to 3, "!</w>" to 4, "!" to 0,
            "<|startoftext|>" to 49406, "<|endoftext|>" to 49407
        )
        val tokenizer = ClipTokenizer(vocab, listOf("#version: 0.2", "h i</w>"), padTokenId = 49407, maxLength = 8)
        assertEquals(listOf(3, 3, 4), tokenizer.tokenize("  Hi  HI!"))
        assertArrayEquals(intArrayOf(49406, 3, 49407, 49407, 49407, 49407, 49407, 49407), tokenizer.encode("hi"))
    }

    @Test
    fun tokenizerTruncatesLongPrompts() {
        val vocab = mapOf("a</w>" to 7, "<|startoftext|>" to 49406, "<|endoftext|>" to 49407)
        val tokenizer = ClipTokenizer(vocab, emptyList(), padTokenId = 0, maxLength = 77)
        val ids = tokenizer.encode(List(200) { "a" }.joinToString(" "))
        assertEquals(77, ids.size)
        assertEquals(49406, ids.first())
        assertEquals(49407, ids.last())
    }
}
