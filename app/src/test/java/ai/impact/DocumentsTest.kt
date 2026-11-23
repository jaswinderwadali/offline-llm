package ai.impact

import ai.impact.data.documents.Block
import ai.impact.data.documents.DocumentContext
import ai.impact.data.documents.DocumentParsers
import ai.impact.data.documents.DocumentSource
import ai.impact.data.documents.SearchHit
import ai.impact.data.documents.SourcesTag
import ai.impact.data.documents.TextChunker
import ai.impact.data.documents.VectorIndex
import ai.impact.data.documents.Vectors
import ai.impact.data.documents.WordPieceTokenizer
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentsTest {

    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8)

    private val tokenizer by lazy { resource("minilm-vocab.txt").useLines { WordPieceTokenizer.fromVocabLines(it) } }

    /** Ids from the Hugging Face tokenizer the model was trained with (tokenizers 0.23). */
    @Test
    fun tokenizerMatchesTheReferenceExactly() {
        val cases = JSONArray(resource("wordpiece-cases.json").readText())
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val expected = case.getJSONArray("ids").let { a -> IntArray(a.length()) { a.getInt(it) } }
            assertArrayEquals(case.getString("text"), expected, tokenizer.encode(case.getString("text"), maxLength = 512))
        }
    }

    @Test
    fun encodingIsCutToTheModelsLimit() {
        val ids = tokenizer.encode("word ".repeat(400), maxLength = 256)
        assertEquals(256, ids.size)
        assertEquals(tokenizer.cls, ids.first())
        assertEquals(tokenizer.sep, ids.last())
    }

    private val chunker by lazy { TextChunker(tokenizer::count) }

    private fun paragraph(seed: Int, sentences: Int = 6) = (1..sentences).joinToString(" ") {
        "Sentence $it of paragraph $seed explains one more detail about the topic at hand."
    }

    @Test
    fun passagesStayUnderTheModelsLimitAndOverlap() {
        val blocks = (1..12).map { Block(paragraph(it), page = (it + 1) / 2) }
        val chunks = chunker.chunk(blocks)
        assertTrue(chunks.size > 3)
        chunks.forEach { assertTrue("${tokenizer.count(it.embeddingText)} tokens", tokenizer.count(it.embeddingText) <= 254) }
        // Each passage opens with the end of the one before.
        for (i in 1 until chunks.size) {
            val firstSentence = chunks[i].text.split(". ").first()
            assertTrue("passage $i should overlap", chunks[i - 1].text.contains(firstSentence))
        }
        assertEquals(1, chunks.first().pageStart)
        assertEquals(6, chunks.last().pageEnd)
    }

    @Test
    fun headingsStartNewPassagesAndLabelThem() {
        val blocks = listOf(
            Block("Introduction", isHeading = true),
            Block(paragraph(1)), Block(paragraph(2)),
            Block("Methods", isHeading = true),
            Block(paragraph(3))
        )
        val chunks = chunker.chunk(blocks)
        assertEquals("Introduction", chunks.first().heading)
        val methods = chunks.filter { it.heading == "Methods" }
        assertEquals(1, methods.size)
        // No overlap carries across a section break.
        assertTrue(!methods.single().text.contains("paragraph 2"))
        assertTrue(methods.single().embeddingText.startsWith("Methods\n"))
    }

    @Test
    fun aHugeParagraphIsSplitBySentenceThenWord() {
        val wall = "word ".repeat(1200).trim()
        val chunks = chunker.chunk(listOf(Block(wall)))
        assertTrue(chunks.size >= 5)
        chunks.forEach { assertTrue(tokenizer.count(it.text) <= 250) }
    }

    @Test
    fun plainTextJoinsWrappedLines() {
        val blocks = DocumentParsers.plainText("First line\nwraps here.\n\n\nSecond paragraph.\r\n")
        assertEquals(listOf("First line wraps here.", "Second paragraph."), blocks.map { it.text })
    }

    @Test
    fun markdownKeepsStructureAndDropsMarkup() {
        val md = """
            # Black holes
            They form when **massive** stars [collapse](https://example.com).

            ## Types
            - Stellar
            - *Supermassive*

            Event Horizon
            =============
            ```
            val g = 9.8
            ```
        """.trimIndent()
        val blocks = DocumentParsers.markdown(md)
        assertEquals(Block("Black holes", isHeading = true), blocks[0])
        assertEquals("They form when massive stars collapse.", blocks[1].text)
        assertEquals(Block("Types", isHeading = true), blocks[2])
        assertEquals("• Stellar\n• Supermassive", blocks[3].text)
        assertEquals(Block("Event Horizon", isHeading = true), blocks[4])
        assertEquals("val g = 9.8", blocks[5].text)
    }

    private fun docx(body: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml")); zip.write("<Types/>".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("""<?xml version="1.0"?><w:document xmlns:w="x"><w:body>$body</w:body></w:document>""".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun wordParagraphsHeadingsAndPages() {
        val body = """
            <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Overview</w:t></w:r></w:p>
            <w:p><w:r><w:t xml:space="preserve">Fish &amp; chips </w:t></w:r><w:r><w:t>are tasty.</w:t></w:r></w:p>
            <w:p><w:r><w:lastRenderedPageBreak/><w:t>Second</w:t><w:tab/><w:t>page.</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>A</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
        """
        val content = DocumentParsers.docx(ByteArrayInputStream(docx(body)))
        assertEquals(Block("Overview", page = 1, isHeading = true), content.blocks[0])
        assertEquals(Block("Fish & chips are tasty.", page = 1), content.blocks[1])
        assertEquals(Block("Second page.", page = 2), content.blocks[2])
        assertEquals(2, content.pageCount)
    }

    @Test
    fun wordWithoutLayoutHasNoPageNumbers() {
        val content = DocumentParsers.docx(ByteArrayInputStream(docx("<w:p><w:r><w:t>Just text.</w:t></w:r></w:p>")))
        assertNull(content.blocks.single().page)
        assertNull(content.pageCount)
    }

    @Test
    fun vectorsRoundTripAndRank() {
        val a = Vectors.normalize(floatArrayOf(1f, 0f, 0f))
        val b = Vectors.normalize(floatArrayOf(0.8f, 0.6f, 0f))
        val c = Vectors.normalize(floatArrayOf(0f, 0f, 1f))
        assertArrayEquals(b, Vectors.fromBytes(Vectors.toBytes(b)), 0f)
        // Passages 1 and 2 are in document 10, passage 3 in document 20.
        val index = VectorIndex(longArrayOf(1, 2, 3), longArrayOf(10, 10, 20), a + b + c, 3)
        val hits = index.search(a, k = 2, minScore = 0.1f)
        assertEquals(listOf(1L, 2L), hits.map { it.first })
        assertEquals(1f, hits.first().second, 1e-6f)
        // An unselected document is never searched.
        assertEquals(emptyList<Long>(), index.search(a, k = 2, minScore = 0.1f, excluded = setOf(10L)).map { it.first })
        assertEquals(listOf(3L), index.search(c, k = 2, minScore = 0.1f, excluded = setOf(10L)).map { it.first })
    }

    @Test
    fun meanPoolingIgnoresPadding() {
        // Two real tokens and one pad, dim 2.
        val hidden = floatArrayOf(1f, 3f, 3f, 1f, 100f, 100f)
        val pooled = Vectors.meanPool(hidden, intArrayOf(1, 1, 0), dim = 2)
        assertEquals(pooled[0], pooled[1], 1e-6f)
    }

    @Test
    fun shortFollowUpsSearchWithContext() {
        assertEquals("What does chapter one say about tides?\nand chapter 2?", DocumentContext.query("and chapter 2?", "What does chapter one say about tides?"))
        assertEquals("How do tides work on other planets in general?", DocumentContext.query("How do tides work on other planets in general?", "x"))
    }

    @Test
    fun excerptsFitTheBudgetAndCiteTheirSource() {
        fun hit(id: Long, text: String, page: Int) = SearchHit(id, DocumentSource(9, "notes.pdf", page, page), null, text, 0.6f)
        val hits = listOf(hit(1, "a".repeat(900), 3), hit(2, "b".repeat(900), 4), hit(3, "c".repeat(900), 5))
        val (prompt, used) = DocumentContext.format(hits, maxChars = 2200)!!
        assertTrue(prompt.length <= 2200)
        assertTrue(prompt.contains("[1] notes.pdf · p. 3"))
        assertEquals(2, used.size)
        assertEquals("notes.pdf · pp. 3–5", DocumentSource(1, "notes.pdf", 3, 5).label)
        assertNull(DocumentContext.format(hits, maxChars = 300))
    }

    @Test
    fun sourcesSurviveBeingSaved() {
        val sources = listOf(DocumentSource(4, "Q3 [draft] report.pdf", 2, 3), DocumentSource(5, "notes.md", null, null))
        val stored = SourcesTag.encode(sources, "[[skill:x]]\nThe answer.")
        val decoded = SourcesTag.decode(stored)
        assertEquals(sources, decoded.sources)
        assertEquals("[[skill:x]]\nThe answer.", decoded.text)
        assertEquals("Plain reply", SourcesTag.decode("Plain reply").text)
        assertEquals("Plain reply", SourcesTag.encode(emptyList(), "Plain reply"))
    }

    @Test
    fun aNewPageStartsANewPassageWithItsOwnPageNumber() {
        // The device test PDF: a short page 1, and the fact being asked about on page 2.
        val blocks = listOf(
            Block("Kestrel Field Manual", page = 1),
            Block("Chapter 1. The Zorvian lantern festival is held every year on 14 March in the hill town of Pellaro. Visitors release exactly seven hundred paper lanterns over the Vessa river at dusk.", page = 1),
            Block("The festival was founded in 1871 by the glassmaker Ilsa Marrow.", page = 1),
            Block("Chapter 2. Station procedures", page = 2),
            Block("The emergency radio code for Kestrel Station is BLUE-HERON-42.", page = 2)
        )
        val chunks = chunker.chunk(blocks)
        assertEquals(2, chunks.size)
        assertEquals(1, chunks[0].pageStart); assertEquals(1, chunks[0].pageEnd)
        val code = chunks.single { it.text.contains("BLUE-HERON-42") }
        // Overlap from page 1 may lead in, but the citation is page 2 alone.
        assertEquals(2, code.pageStart); assertEquals(2, code.pageEnd)
    }

    @Test
    fun shortPagesStillShareAPassage() {
        val chunks = chunker.chunk(listOf(Block("Title page.", page = 1), Block("Contents follow here.", page = 2)))
        assertEquals(1, chunks.size)
        assertEquals(1, chunks[0].pageStart); assertEquals(2, chunks[0].pageEnd)
    }

    @Test
    fun aShortSectionKeepsItsOwnHeadingAndIsntRepeated() {
        // The device test .docx: a short first page, a page break, then a new heading.
        val blocks = listOf(
            Block("Orchard notes", page = 1, isHeading = true),
            Block("Our orchard grows the rare Quillon apple, which ripens in late October.", page = 1),
            Block("Each Quillon tree yields about 85 kilograms of fruit in a good year.", page = 1),
            Block("Pests", page = 2, isHeading = true),
            Block("The main pest is the copper moth. We control it with pheromone traps placed every 12 metres.", page = 2)
        )
        val chunks = chunker.chunk(blocks)
        assertEquals(2, chunks.size)
        assertEquals("Orchard notes", chunks[0].heading)
        assertEquals(1, chunks[0].pageStart)
        assertEquals("Pests", chunks[1].heading)
        assertEquals(2, chunks[1].pageStart)
        // Nothing from the orchard section leaks into the pests passage.
        assertTrue(!chunks[1].text.contains("Quillon"))
    }
}

