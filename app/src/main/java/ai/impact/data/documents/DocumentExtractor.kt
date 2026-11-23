package ai.impact.data.documents

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper

/** The kinds of file the library can read. */
enum class DocumentKind(val label: String) {
    Pdf("PDF"), Word("Word"), Markdown("Markdown"), Text("Text");

    companion object {
        /** From the file name first (pickers often report Markdown as a generic binary), then the type. */
        fun of(name: String, mimeType: String?): DocumentKind? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                ext == "pdf" || mimeType == "application/pdf" -> Pdf
                ext == "docx" || mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> Word
                ext in setOf("md", "markdown", "mdown") || mimeType == "text/markdown" || mimeType == "text/x-markdown" -> Markdown
                ext in setOf("txt", "text", "csv", "log") || mimeType?.startsWith("text/") == true -> Text
                else -> null
            }
        }

        /** Why a file can't be added, in words the user can act on. */
        fun unsupportedReason(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "doc" -> "Old Word (.doc) files can't be read. Open it in Word or Google Docs and save it as .docx."
            "pages", "odt", "rtf" -> "This format can't be read yet. Save it as PDF or .docx and add it again."
            "jpg", "jpeg", "png", "heic", "webp" -> "Photos of documents can't be read yet. Only files with real text work for now."
            else -> "TinyAI can read PDF, Word (.docx), Markdown and text files."
        }

        val pickerTypes = arrayOf(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/*",
            // Markdown files often come through as a generic type; the name decides.
            "application/octet-stream"
        )
    }
}

class ExtractedDocument(val blocks: List<Block>, val pageCount: Int?)

/** Reads the text out of a picked file. Everything happens on the phone. */
class DocumentExtractor(private val context: Context) {

    companion object {
        /** A PDF with less text than this per page is almost certainly scanned images. */
        private const val MIN_CHARS_PER_PAGE = 25
    }

    @Volatile private var pdfReady = false

    fun extract(uri: Uri, kind: DocumentKind): ExtractedDocument = when (kind) {
        DocumentKind.Pdf -> pdf(uri)
        DocumentKind.Word -> open(uri) { DocumentParsers.docx(it) }.let { ExtractedDocument(it.blocks, it.pageCount) }
        DocumentKind.Markdown -> ExtractedDocument(DocumentParsers.markdown(readText(uri)), null)
        DocumentKind.Text -> ExtractedDocument(DocumentParsers.plainText(readText(uri)), null)
    }

    private fun <T> open(uri: Uri, block: (java.io.InputStream) -> T): T =
        (context.contentResolver.openInputStream(uri) ?: throw DocumentException("This file couldn't be opened.")).use(block)

    private fun readText(uri: Uri): String = open(uri) { it.readBytes() }.toString(Charsets.UTF_8).removePrefix("﻿")

    private val hyphenBreak = Regex("(\\p{L})-\\n(\\p{Ll})")
    private val blankLine = Regex("\\n\\s*\\n")

    private fun pdf(uri: Uri): ExtractedDocument {
        if (!pdfReady) {
            PDFBoxResourceLoader.init(context.applicationContext)
            pdfReady = true
        }
        val document = try {
            open(uri) { PDDocument.load(it, MemoryUsageSetting.setupMixed(16L * 1024 * 1024)) }
        } catch (e: InvalidPasswordException) {
            throw DocumentException("This PDF is password-protected. Remove the password and add it again.", e)
        } catch (e: java.io.IOException) {
            throw DocumentException("This PDF couldn't be read. It may be damaged.", e)
        }
        document.use { pdf ->
            val pages = pdf.numberOfPages
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                // Paragraphs end in a blank line, so they can be told apart from wrapped lines.
                addMoreFormatting = true
                paragraphEnd = "\n\n"
            }
            val blocks = ArrayList<Block>()
            var chars = 0
            for (page in 1..pages) {
                stripper.startPage = page
                stripper.endPage = page
                val text = hyphenBreak.replace(stripper.getText(pdf).replace("\r\n", "\n"), "$1$2")
                for (paragraph in text.split(blankLine)) {
                    val joined = paragraph.lines().joinToString(" ") { it.trim() }.replace(Regex("\\s+"), " ").trim()
                    if (joined.isEmpty()) continue
                    chars += joined.length
                    blocks.add(Block(joined, page))
                }
            }
            if (pages > 0 && chars < pages * MIN_CHARS_PER_PAGE) {
                throw DocumentException("No text found. This PDF looks like scanned pages, which can't be read yet.")
            }
            return ExtractedDocument(blocks, pages)
        }
    }
}
