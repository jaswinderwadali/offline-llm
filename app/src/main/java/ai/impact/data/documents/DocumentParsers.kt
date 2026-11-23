package ai.impact.data.documents

import java.io.InputStream
import java.util.zip.ZipInputStream

/** Text formats read without any library: plain text, Markdown and Word (.docx). */
object DocumentParsers {

    private val blankLine = Regex("\\n\\s*\\n")

    /** Paragraphs are separated by blank lines; single line breaks are just wrapping. */
    fun plainText(text: String): List<Block> = text
        .replace("\r\n", "\n").replace('\r', '\n')
        .split(blankLine)
        .map { paragraph -> paragraph.lines().joinToString(" ") { it.trim() }.trim() }
        .filter { it.isNotEmpty() }
        .map { Block(it) }

    private val mdHeading = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val setextUnderline = Regex("^\\s{0,3}(=+|-+)\\s*$")
    private val listItem = Regex("^\\s*(?:[-*+]|\\d+[.)])\\s+")
    private val image = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val link = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val emphasis = Regex("(\\*\\*|__|\\*|_|~~)(?=\\S)(.+?)(?<=\\S)\\1")
    private val inlineCode = Regex("`([^`]+)`")
    private val htmlTag = Regex("</?[a-zA-Z][^>]*>")

    /** Markdown headings become headings; list items and code keep their own lines. */
    fun markdown(text: String): List<Block> {
        val blocks = ArrayList<Block>()
        val paragraph = ArrayList<String>()
        var inCode = false
        val code = StringBuilder()

        fun endParagraph() {
            if (paragraph.isEmpty()) return
            val lines = paragraph.map(::inline).filter { it.isNotBlank() }
            // Lists read better one item per line; prose rejoins its wrapped lines.
            val joined = if (paragraph.any { listItem.containsMatchIn(it) }) lines.joinToString("\n") else lines.joinToString(" ")
            if (joined.isNotBlank()) blocks.add(Block(joined.trim()))
            paragraph.clear()
        }

        val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
        for ((i, line) in lines.withIndex()) {
            if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) {
                if (inCode) {
                    if (code.isNotBlank()) blocks.add(Block(code.toString().trimEnd()))
                    code.setLength(0)
                } else {
                    endParagraph()
                }
                inCode = !inCode
                continue
            }
            if (inCode) {
                code.append(line).append('\n')
                continue
            }
            val heading = mdHeading.find(line)
            if (heading != null) {
                endParagraph()
                blocks.add(Block(inline(heading.groupValues[2]), isHeading = true))
                continue
            }
            // "Title\n=====" style headings.
            if (paragraph.size == 1 && setextUnderline.matches(line) && i > 0) {
                blocks.add(Block(inline(paragraph.removeAt(0)), isHeading = true))
                continue
            }
            if (line.isBlank()) endParagraph() else paragraph.add(line.trim())
        }
        if (inCode && code.isNotBlank()) blocks.add(Block(code.toString().trimEnd()))
        endParagraph()
        return blocks
    }

    private fun inline(line: String): String {
        var t = line.removePrefix(">").trim()
        t = listItem.replaceFirst(t, "• ")
        t = image.replace(t) { it.groupValues[1] }
        t = link.replace(t) { it.groupValues[1] }
        t = inlineCode.replace(t) { it.groupValues[1] }
        repeat(2) { t = emphasis.replace(t) { it.groupValues[2] } }
        t = htmlTag.replace(t, "")
        return t.trim()
    }

    /**
     * A .docx file's paragraphs. Pages come from the page breaks Word saves with the file, so
     * they match what the user saw in Word; a document never opened in Word has no page numbers.
     */
    fun docx(stream: InputStream): DocxContent {
        ZipInputStream(stream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") {
                    return docxXml(zip.readBytes().toString(Charsets.UTF_8))
                }
            }
        }
        throw DocumentException("This Word file has no text in it, or it isn't a .docx file.")
    }

    class DocxContent(val blocks: List<Block>, val pageCount: Int?)

    private val tag = Regex("<(/?)(w:[A-Za-z]+)([^>]*?)(/?)>")
    private val styleValue = Regex("w:val=\"([^\"]*)\"")
    private val breakType = Regex("w:type=\"([^\"]*)\"")

    fun docxXml(xml: String): DocxContent {
        val blocks = ArrayList<Block>()
        val paragraph = StringBuilder()
        var style: String? = null
        var inText = false
        var page = 1
        var sawPageBreak = false
        var pos = 0
        // Word saves where each page began when it laid the file out. When those marks exist they
        // already include explicit page breaks, so counting both would number pages twice.
        val rendered = xml.contains("w:lastRenderedPageBreak")

        fun endParagraph() {
            val text = paragraph.toString().replace(Regex("[ \\t]+"), " ").trim()
            if (text.isNotEmpty()) {
                val isHeading = style?.let { it.startsWith("Heading", true) || it.equals("Title", true) } == true
                blocks.add(Block(text, page, isHeading))
            }
            paragraph.setLength(0)
            style = null
        }

        for (match in tag.findAll(xml)) {
            if (inText) paragraph.append(decodeXml(xml.substring(pos, match.range.first)))
            pos = match.range.last + 1
            val closing = match.groupValues[1] == "/"
            val name = match.groupValues[2]
            val attrs = match.groupValues[3]
            val selfClosing = match.groupValues[4] == "/"
            when (name) {
                "w:t" -> inText = !closing && !selfClosing
                "w:p" -> if (closing) endParagraph() else if (!selfClosing) { paragraph.setLength(0); style = null }
                "w:pStyle" -> style = styleValue.find(attrs)?.groupValues?.get(1)
                "w:tab" -> if (!closing) paragraph.append(' ')
                "w:br", "w:cr" -> if (!closing) {
                    if (breakType.find(attrs)?.groupValues?.get(1) == "page") {
                        if (!rendered) { page++; sawPageBreak = true }
                    } else paragraph.append(' ')
                }
                "w:lastRenderedPageBreak" -> { page++; sawPageBreak = true }
                // Table cells become separate words on the same line.
                "w:tc" -> if (closing) paragraph.append(' ')
            }
        }
        val pages = if (sawPageBreak) page else null
        return DocxContent(if (sawPageBreak) blocks else blocks.map { it.copy(page = null) }, pages)
    }

    private val entity = Regex("&(#x?[0-9a-fA-F]+|amp|lt|gt|quot|apos);")

    fun decodeXml(text: String): String = entity.replace(text) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> {
                val cp = if (e.startsWith("#x")) e.drop(2).toIntOrNull(16) else e.drop(1).toIntOrNull()
                cp?.let { String(Character.toChars(it)) } ?: m.value
            }
        }
    }
}

/** A document couldn't be read; the message is shown to the user as is. */
class DocumentException(message: String, cause: Throwable? = null) : Exception(message, cause)
