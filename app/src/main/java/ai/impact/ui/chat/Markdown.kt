package ai.impact.ui.chat

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    data class Code(val language: String, val code: String) : MdBlock {
        /** False while it's still streaming in (no closing fence yet); a preview waits for the whole page. */
        var closed: Boolean = true
            internal set
    }
    data object Rule : MdBlock
}

private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val bulletRegex = Regex("^(\\s*)[-*+•]\\s+(.*)$")
private val orderedRegex = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
private val ruleRegex = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
private val fenceRegex = Regex("^\\s*(```|~~~)\\s*([\\w+#.-]*)\\s*$")
private val htmlStartRegex = Regex("^\\s*(<!doctype\\s+html|<html[\\s>])", RegexOption.IGNORE_CASE)

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
                blocks += MdBlock.Code(fence.groupValues[2], code.toString()).apply { closed = i < lines.size }
            }
            // A whole HTML page written without a code fence would otherwise show as mangled paragraphs.
            htmlStartRegex.containsMatchIn(line) -> {
                flushParagraph(); flushQuote()
                val code = StringBuilder()
                var closed = false
                while (i < lines.size) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    if (lines[i].contains("</html>", ignoreCase = true)) { closed = true; break }
                    i++
                }
                blocks += MdBlock.Code("html", code.toString()).apply { this.closed = closed }
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
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    /** Trailing characters still fading in while a reply streams (see [SmoothReveal]). */
    fadeChars: Float = 0f
) {
    val blocks = remember(text) { parseMarkdown(text) }
    val colors = InlineColors(
        code = MaterialTheme.colorScheme.primary,
        codeBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        link = MaterialTheme.colorScheme.primary
    )
    val quoteColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEachIndexed { index, block ->
            val isLast = index == blocks.lastIndex
            fun inline(raw: String, ink: Color = color) =
                parseInline(raw, colors).let { if (isLast) it.fadeTail(fadeChars, ink) else it }
            when (block) {
                is MdBlock.Heading -> Text(
                    inline(block.text),
                    color = color,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp)
                        else -> MaterialTheme.typography.titleMedium
                    }.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(top = 4.dp)
                )
                is MdBlock.Paragraph -> Text(inline(block.text), color = color, style = style)
                is MdBlock.ListItem -> ListItemRow(block, inline(block.text), color, style)
                is MdBlock.Quote -> QuoteBlock(inline(block.text, quoteColor), quoteColor, style)
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
private fun ListItemRow(item: MdBlock.ListItem, text: AnnotatedString, color: Color, style: TextStyle) {
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
        Text(text, color = color, style = style)
    }
}

@Composable
private fun QuoteBlock(text: AnnotatedString, color: Color, style: TextStyle) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        )
        Spacer(Modifier.width(12.dp))
        Text(text, style = style.copy(fontStyle = FontStyle.Italic), color = color)
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    // Pages and drawings can be seen rendered, once they've finished streaming in.
    val previewable = block.closed && isPreviewable(block.language, block.code)
    var showPreview by rememberSaveable(block.code.hashCode()) { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }
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
                if (previewable) {
                    CodePreviewSwitch(showPreview, onChange = { showPreview = it })
                    IconButton(onClick = { fullScreen = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.OpenInFull, contentDescription = "Open preview full screen", modifier = Modifier.size(16.dp))
                    }
                }
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
            AnimatedContent(
                targetState = previewable && showPreview,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
                label = "codePreview"
            ) { preview ->
                if (preview) {
                    HtmlPreview(
                        block.code,
                        Modifier
                            .fillMaxWidth()
                            .height(380.dp)
                            .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                    )
                } else {
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
    }
    if (fullScreen) {
        HtmlPreviewDialog(block.code, title = "${block.language.ifBlank { "html" }.uppercase()} preview", onDismiss = { fullScreen = false })
    }
}

/** A small two-way switch between the source and the rendered page. */
@Composable
private fun CodePreviewSwitch(preview: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(2.dp)
    ) {
        listOf(false to "Code", true to "Preview").forEach { (value, label) ->
            val selected = preview == value
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onChange(value) }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

// endregion
