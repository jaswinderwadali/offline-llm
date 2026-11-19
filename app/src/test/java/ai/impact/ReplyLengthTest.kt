package ai.impact

import ai.impact.data.Persona
import ai.impact.data.PersonaCategory
import ai.impact.data.ReplyLength
import ai.impact.data.ReplyLimiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyLengthTest {

    private fun words(text: String) = Regex("\\S+").findAll(text).count()

    /** [count] words in sentences of [perSentence] words each. */
    private fun sentences(count: Int, perSentence: Int = 10): String =
        (1..count).joinToString(" ") { i -> if (i % perSentence == 0) "word$i." else "word$i" }

    @Test
    fun replyWithinTheLimitIsLeftAlone() {
        assertNull(ReplyLimiter.clip(sentences(50), 50))
        // A little overrun is allowed so the model can finish its sentence.
        assertNull(ReplyLimiter.clip(sentences(58), 50))
    }

    @Test
    fun longReplyIsCutAtTheLastWholeSentence() {
        val clipped = ReplyLimiter.clip(sentences(80), 50)
        assertNotNull(clipped)
        assertEquals(50, words(clipped!!))
        assertTrue(clipped.endsWith("word50."))
    }

    @Test
    fun replyWithNoSentenceBreakIsCutAtTheLimitWithEllipsis() {
        val run = (1..80).joinToString(" ") { "w$it" }
        val clipped = ReplyLimiter.clip(run, 50)!!
        assertEquals(50, words(clipped))
        assertTrue(clipped.endsWith("w50…"))
    }

    @Test
    fun reasoningNeverCountsTowardsTheLimit() {
        val thinking = "<think>" + (1..300).joinToString(" ") { "t$it" } + "</think>"
        // Long reasoning, short answer: fine.
        assertNull(ReplyLimiter.clip(thinking + "Short answer.", 50))
        // Still reasoning: never cut.
        assertNull(ReplyLimiter.clip("<think>" + (1..300).joinToString(" ") { "t$it" }, 50))
        // Long answer after reasoning: the reasoning is kept, the answer is cut.
        val clipped = ReplyLimiter.clip(thinking + sentences(80), 50)!!
        assertTrue(clipped.startsWith(thinking))
        assertEquals(50, words(clipped.removePrefix(thinking)))
    }

    @Test
    fun savedWordCountsSnapToTheNearestPreset() {
        assertEquals(ReplyLength.Balanced, ReplyLength.nearest(149))
        assertEquals(ReplyLength.Balanced, ReplyLength.nearest(150))
        assertEquals(ReplyLength.InDepth, ReplyLength.nearest(350))
        assertEquals(ReplyLength.Brief, ReplyLength.nearest(10))
        assertEquals(ReplyLength.InDepth, ReplyLength.nearest(500))
        assertEquals(150, ReplyLength.Default.words)
    }

    @Test
    fun promptStatesTheLimit() {
        val persona = Persona("p", "Pal", "🙂", "", "", "Be kind.", 0, 0, PersonaCategory.Social)
        val prompt = persona.fullSystemPrompt(null, 100)
        assertTrue(prompt.contains("under 100 words"))
        assertTrue(prompt.contains("about 5 sentences"))
    }
}
