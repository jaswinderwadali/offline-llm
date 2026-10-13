package ai.impact.ui.chat

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// region Block parsing

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class ListItem(val ordered: Boolean, val marker: String, val indent: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data object Rule : MdBlock
}

private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val bulletRegex = Regex("^(\\s*)[-*+•]\\s+(.*)$")
private val orderedRegex = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
private val ruleRegex = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
private val fenceRegex = Regex("^\\s*(```|~~~)\\s*([\\w+#.-]*)\\s*$")

/** Line-based markdown parser. Tolerates half-streamed input (e.g. an unclosed code fence). */
fun parseMarkdown(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    val quote = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }
    fun flushQuote() {
        if (quote.isNotBlank()) blocks += MdBlock.Quote(quote.toString().trim())
        quote.clear()
    }

    val lines = text.replace("\r\n", "\n").lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val fence = fenceRegex.matchEntire(line)
        when {
            fence != null -> {
                flushParagraph(); flushQuote()
                val closer = fence.groupValues[1]
                val code = StringBuilder()
                i++
                while (i < lines.size && lines[i].trim() != closer) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                blocks += MdBlock.Code(fence.groupValues[2], code.toString())
            }
            line.isBlank() -> { flushParagraph(); flushQuote() }
            ruleRegex.matches(line) -> { flushParagraph(); flushQuote(); blocks += MdBlock.Rule }
            line.trimStart().startsWith(">") -> {
                flushParagraph()
                if (quote.isNotEmpty()) quote.append('\n')
                quote.append(line.trimStart().removePrefix(">").removePrefix(" "))
            }
            else -> {
                flushQuote()
                val heading = headingRegex.matchEntire(line.trim())
                val bullet = bulletRegex.matchEntire(line)
                val ordered = orderedRegex.matchEntire(line)
                when {
                    heading != null -> {
                        flushParagraph()
                        blocks += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trimEnd('#', ' '))
                    }
                    bullet != null -> {
                        flushParagraph()
                        blocks += MdBlock.ListItem(false, "", bullet.groupValues[1].length / 2, bullet.groupValues[2])
                    }
                    ordered != null -> {
                        flushParagraph()
                        blocks += MdBlock.ListItem(true, ordered.groupValues[2], ordered.groupValues[1].length / 2, ordered.groupValues[3])
                    }
                    else -> {
                        if (paragraph.isNotEmpty()) paragraph.append('\n')
                        paragraph.append(line.trim())
                    }
                }
            }
        }
        i++
    }
    flushParagraph(); flushQuote()
    return blocks
}

// endregion

// region Inline parsing

data class InlineColors(val code: Color, val codeBackground: Color, val link: Color)

private val linkRegex = Regex("^\\[([^\\]]+)]\\((\\S+?)\\)")

/** Converts inline markdown (bold, italic, strike, code, links) into an [AnnotatedString]. */
fun parseInline(text: String, colors: InlineColors): AnnotatedString = buildAnnotatedString {
    appendInline(text, colors)
}

private fun AnnotatedString.Builder.appendInline(text: String, colors: InlineColors) {
    var i = 0
    val plain = StringBuilder()
    fun flush() { if (plain.isNotEmpty()) { append(plain.toString()); plain.clear() } }

    while (i < text.length) {
        val c = text[i]
        val rest = text.substring(i)

        // Escaped character
        if (c == '\\' && i + 1 < text.length && text[i + 1] in "\\`*_~[]#") {
            plain.append(text[i + 1]); i += 2; continue
        }
        // `code`
        if (c == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i + 1) {
                flush()
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = colors.code, background = colors.codeBackground, fontSize = 0.92.em)) {
                    append(" ${text.substring(i + 1, end)} ")
                }
                i = end + 1; continue
            }
        }
        // **bold** / __bold__ / ~~strike~~
        val pair = listOf("**", "__", "~~").firstOrNull { rest.startsWith(it) }
        if (pair != null) {
            val end = text.indexOf(pair, i + 2)
            val opensWord = pair != "__" || i == 0 || !text[i - 1].isLetterOrDigit()
            val style = if (pair == "~~") SpanStyle(textDecoration = TextDecoration.LineThrough)
            else SpanStyle(fontWeight = FontWeight.Bold)
            if (end > i + 2 && opensWord) {
                flush()
                withStyle(style) { appendInline(text.substring(i + 2, end), colors) }
                i = end + 2; continue
            }
            // Unclosed marker (usually mid-stream): style through to the end instead of showing raw "**".
            if (end < 0 && opensWord && i + 2 < text.length && !text[i + 2].isWhitespace()) {
                flush()
                withStyle(style) { appendInline(text.substring(i + 2), colors) }
                i = text.length; continue
            }
            if (end < 0 && i + 2 >= text.length) { i = text.length; continue }
        }
        // *italic* / _italic_
        if ((c == '*' || c == '_') && i + 1 < text.length && !text[i + 1].isWhitespace()) {
            val boundary = c == '*' || i == 0 || !text[i - 1].isLetterOrDigit()
            var end = text.indexOf(c, i + 1)
            while (end > 0 && end + 1 < text.length && text[end + 1] == c) end = text.indexOf(c, end + 2)
            val closesWord = end > 0 && (c == '*' || end + 1 >= text.length || !text[end + 1].isLetterOrDigit())
            if (boundary && end > i + 1 && closesWord && !text[end - 1].isWhitespace()) {
                flush()
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(text.substring(i + 1, end), colors) }
                i = end + 1; continue
            }
        }
        // [label](url)
        if (c == '[') {
            val match = linkRegex.find(rest)
            if (match != null) {
                flush()
                val url = match.groupValues[2]
                withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)))) {
                    append(match.groupValues[1])
                }
                i += match.value.length; continue
            }
        }
        plain.append(c)
        i++
    }
    flush()
}

// endregion

// region Rendering

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyLarge
) {
    val blocks = remember(text) { parseMarkdown(text) }
    val colors = InlineColors(
        code = MaterialTheme.colorScheme.primary,
        codeBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        link = MaterialTheme.colorScheme.primary
    )

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    parseInline(block.text, colors),
                    color = color,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp)
                        else -> MaterialTheme.typography.titleMedium
                    }.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(top = 4.dp)
                )
                is MdBlock.Paragraph -> Text(parseInline(block.text, colors), color = color, style = style)
                is MdBlock.ListItem -> ListItemRow(block, colors, color, style)
                is MdBlock.Quote -> QuoteBlock(block, colors, style)
                is MdBlock.Code -> CodeBlock(block)
                MdBlock.Rule -> HorizontalDivider(
                    Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}

@Composable
private fun ListItemRow(item: MdBlock.ListItem, colors: InlineColors, color: Color, style: TextStyle) {
    Row(Modifier.padding(start = (item.indent * 16).dp)) {
        Box(Modifier.widthIn(min = 22.dp), contentAlignment = Alignment.TopStart) {
            Text(
                if (item.ordered) "${item.marker}." else listOf("•", "◦", "▪")[item.indent.coerceAtMost(2)],
                style = style,
                color = if (item.ordered) MaterialTheme.colorScheme.primary else color,
                fontWeight = if (item.ordered) FontWeight.SemiBold else FontWeight.Normal
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(parseInline(item.text, colors), color = color, style = style)
    }
}

@Composable
private fun QuoteBlock(quote: MdBlock.Quote, colors: InlineColors, style: TextStyle) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        )
        Spacer(Modifier.width(12.dp))
        Text(
            parseInline(quote.text, colors),
            style = style.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    block.language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(block.code))
                        Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy code", modifier = Modifier.size(16.dp))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                block.code,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(14.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, lineHeight = 20.sp),
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = false
            )
        }
    }
}

// endregion
