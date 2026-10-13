package ai.impact

import ai.impact.data.ResponseParser
import ai.impact.ui.chat.MdBlock
import ai.impact.ui.chat.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseParserTest {

    @Test
    fun plainAnswerHasNoThinking() {
        val parsed = ResponseParser.parse("Hello there!")
        assertNull(parsed.thinking)
        assertEquals("Hello there!", parsed.answer)
    }

    @Test
    fun completeThinkBlockIsSeparated() {
        val parsed = ResponseParser.parse("<think>\nUser seems sad.\n</think>\n\nI'm here for you.")
        assertEquals("User seems sad.", parsed.thinking)
        assertTrue(parsed.isThinkingComplete)
        assertEquals("I'm here for you.", parsed.answer)
    }

    @Test
    fun thinkingTagVariantIsSupported() {
        val parsed = ResponseParser.parse("<thinking>plan</thinking>Answer")
        assertEquals("plan", parsed.thinking)
        assertEquals("Answer", parsed.answer)
    }

    @Test
    fun streamingThinkIsIncompleteAndHasNoAnswer() {
        val parsed = ResponseParser.parse("<think>Let me consider")
        assertEquals("Let me consider", parsed.thinking)
        assertFalse(parsed.isThinkingComplete)
        assertEquals("", parsed.answer)
    }

    @Test
    fun halfStreamedClosingTagIsHidden() {
        val parsed = ResponseParser.parse("<think>Let me consider</thi")
        assertEquals("Let me consider", parsed.thinking)
    }

    @Test
    fun halfStreamedOpeningTagIsHidden() {
        assertEquals("", ResponseParser.parse("<thi").answer)
    }

    @Test
    fun prefilledTemplateWithOnlyClosingTag() {
        val parsed = ResponseParser.parse("reasoning here</think>Final")
        assertEquals("reasoning here", parsed.thinking)
        assertEquals("Final", parsed.answer)
    }

    @Test
    fun ordinaryAngleBracketsSurvive() {
        assertEquals("3 < 5 and a <b>", ResponseParser.parse("3 < 5 and a <b>").answer)
    }
}

class MarkdownParserTest {

    @Test
    fun parsesHeadingsListsAndParagraphs() {
        val blocks = parseMarkdown(
            """
            ## Plan
            Here is **what** to do:
            - Breathe
            - Walk
              - Slowly
            1. First
            2. Second
            """.trimIndent()
        )
        assertEquals(MdBlock.Heading(2, "Plan"), blocks[0])
        assertEquals(MdBlock.Paragraph("Here is **what** to do:"), blocks[1])
        assertEquals(MdBlock.ListItem(false, "", 0, "Breathe"), blocks[2])
        assertEquals(MdBlock.ListItem(false, "", 1, "Slowly"), blocks[4])
        assertEquals(MdBlock.ListItem(true, "2", 0, "Second"), blocks[6])
    }

    @Test
    fun boldLineIsNotTreatedAsBullet() {
        val blocks = parseMarkdown("**Tip:** drink water")
        assertEquals(listOf(MdBlock.Paragraph("**Tip:** drink water")), blocks)
    }

    @Test
    fun unclosedCodeFenceStillRendersAsCode() {
        val blocks = parseMarkdown("Try this:\n```kotlin\nval x = 1")
        assertEquals(MdBlock.Code("kotlin", "val x = 1"), blocks[1])
    }

    @Test
    fun quotesAndRules() {
        val blocks = parseMarkdown("> Be kind\n> to yourself\n\n---\nDone")
        assertEquals(MdBlock.Quote("Be kind\nto yourself"), blocks[0])
        assertEquals(MdBlock.Rule, blocks[1])
        assertEquals(MdBlock.Paragraph("Done"), blocks[2])
    }
}

class InlineMarkdownTest {
    private val colors = ai.impact.ui.chat.InlineColors(
        androidx.compose.ui.graphics.Color.Red, androidx.compose.ui.graphics.Color.Gray, androidx.compose.ui.graphics.Color.Blue
    )
    private fun render(s: String) = ai.impact.ui.chat.parseInline(s, colors)

    @Test
    fun markersAreRemoved() {
        assertEquals("bold and italic and code", render("**bold** and *italic* and `code`").text.replace(" ", ""))
    }

    @Test
    fun unclosedBoldMidStreamHidesMarkers() {
        val out = render("2. **Pomodo")
        assertEquals("2. Pomodo", out.text)
        assertTrue(out.spanStyles.any { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold })
    }

    @Test
    fun trailingBareMarkerIsHidden() {
        assertEquals("Next ", render("Next **").text)
    }

    @Test
    fun loneAsteriskAndSnakeCaseStayLiteral() {
        assertEquals("5 * 3 = 15", render("5 * 3 = 15").text)
        assertEquals("my_var_name", render("my_var_name").text)
    }

    @Test
    fun linksKeepTheirLabel() {
        assertEquals("see docs here", render("see [docs](https://example.com) here").text)
    }
}
