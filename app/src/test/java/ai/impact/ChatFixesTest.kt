package ai.impact

import ai.impact.data.ReplyLimiter
import ai.impact.ui.chat.MdBlock
import ai.impact.ui.chat.isPreviewable
import ai.impact.ui.chat.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFixesTest {

    private val longThought = (1..400).joinToString(" ") { "step$it" }

    @Test
    fun untaggedThinkingIsNotCutAsIfItWereTheAnswer() {
        // Some reasoning models start thinking without "<think>", closing it later.
        assertNull(ReplyLimiter.clip(longThought, limit = 50, mayThink = true))
        // Once the thought closes, only the answer counts.
        assertNull(ReplyLimiter.clip("$longThought</think>A short answer.", limit = 50, mayThink = true))
        val longAnswer = (1..200).joinToString(" ") { "word$it" }
        assertNotNull(ReplyLimiter.clip("$longThought</think>$longAnswer", limit = 50, mayThink = true))
        // A model that doesn't reason is still held to the limit.
        assertNotNull(ReplyLimiter.clip(longThought, limit = 50))
    }

    @Test
    fun anUnfencedHtmlPageBecomesOnePreviewableBlock() {
        val blocks = parseMarkdown("Here's your page:\n\n<!DOCTYPE html>\n<html>\n<body><h1>Hi</h1></body>\n</html>\n\nEnjoy!")
        assertEquals(3, blocks.size)
        val code = blocks[1] as MdBlock.Code
        assertEquals("html", code.language)
        assertTrue(code.closed)
        assertTrue(code.code.startsWith("<!DOCTYPE html>") && code.code.endsWith("</html>"))
    }

    @Test
    fun aStreamingCodeBlockIsNotPreviewedUntilItCloses() {
        val open = parseMarkdown("```html\n<div>half") .single() as MdBlock.Code
        assertFalse(open.closed)
        val done = parseMarkdown("```html\n<div>done</div>\n```").single() as MdBlock.Code
        assertTrue(done.closed)
    }

    @Test
    fun recognisesPagesAndDrawingsWorthPreviewing() {
        assertTrue(isPreviewable("html", "<div></div>"))
        assertTrue(isPreviewable("svg", "<svg></svg>"))
        assertTrue(isPreviewable("", "<!doctype html><html></html>"))
        assertTrue(isPreviewable("xml", "<svg viewBox='0 0 1 1'></svg>"))
        assertFalse(isPreviewable("kotlin", "val x = \"<html>\""))
        assertFalse(isPreviewable("css", "body { color: red }"))
    }
}
