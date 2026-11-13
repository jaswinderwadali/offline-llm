package ai.impact

import ai.impact.image.PromptEnhancement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptEnhancementTest {

    @Test
    fun keepsAPlainPrompt() {
        val reply = "a red fox in a snowy forest, golden hour, soft rim light, detailed fur, 35mm photo"
        assertEquals(reply, PromptEnhancement.clean(reply))
    }

    @Test
    fun dropsPreamblesLabelsAndQuotes() {
        assertEquals(
            "a cozy cabin, snowy pine woods, warm window light, dusk",
            PromptEnhancement.clean("Here's an enhanced prompt for you:\n\n\"a cozy cabin, snowy pine woods, warm window light, dusk\"")
        )
        assertEquals(
            "masterpiece, best quality, a girl reading under a cherry tree",
            PromptEnhancement.clean("**Prompt:** masterpiece, best quality, a girl reading under a cherry tree")
        )
        assertEquals("lighthouse on a cliff, storm", PromptEnhancement.clean("Sure!\nPrompt: `lighthouse on a cliff, storm`"))
    }

    @Test
    fun ignoresReasoningAndTrailingExplanation() {
        val reply = "<think>The user wants a city. I should add lighting.</think>\n" +
            "floating city above clouds, sunrise, volumetric light\n\nThis prompt adds lighting and atmosphere."
        assertEquals("floating city above clouds, sunrise, volumetric light", PromptEnhancement.clean(reply))
    }

    @Test
    fun trimsToWhatTheImageModelCanRead() {
        val long = List(80) { "detail $it" }.joinToString(", ")
        val cleaned = PromptEnhancement.clean(long)!!
        assertTrue(cleaned.length <= 320)
        // Cut on a comma boundary, never mid-phrase.
        assertTrue(long.startsWith(cleaned))
        assertTrue(!cleaned.endsWith(","))
        assertTrue(long.substring(cleaned.length).startsWith(","))
    }

    @Test
    fun returnsNullWhenNothingUsable() {
        assertNull(PromptEnhancement.clean(""))
        assertNull(PromptEnhancement.clean("<think>hmm</think>"))
        assertNull(PromptEnhancement.clean("Here is the prompt:"))
    }

    @Test
    fun matchesStyleHintToModel() {
        assertTrue(PromptEnhancement.styleHintFor("Anything V5 (int8)").contains("masterpiece"))
        assertTrue(PromptEnhancement.styleHintFor("epicrealism-int8-onnx").contains("photo"))
        assertEquals("", PromptEnhancement.styleHintFor("my-custom-model"))
        assertTrue(PromptEnhancement.systemPrompt("").contains("50 words"))
    }
}
