package com.uronic0x.fluorine.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uronic0x.fluorine.protocol.CitationPoint
import com.uronic0x.fluorine.protocol.SearchSource
import com.uronic0x.fluorine.ui.theme.MonoFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full Markdown + LaTeX mathematical formula renderer for Jetpack Compose.
 * Supports:
 * - Headings (# .. ######)
 * - Fenced code blocks (```lang ... ```) with copy button
 * - Block LaTeX math ($$ ... $$, \[ ... \], \begin{equation/align/math/gather/pmatrix/bmatrix/cases})
 * - Inline LaTeX math ($ ... $, \( ... \))
 * - Pipe tables (| col1 | col2 |)
 * - Blockquotes (> ...)
 * - Unordered, ordered, and task lists (- [ ], - [x], 1.)
 * - Horizontal rules (---, ***)
 * - Inline bold, italic, strikethrough, inline code, links, and CitationPoints
 */
@Composable
fun MarkdownMathText(
    text: String,
    modifier: Modifier = Modifier,
    citations: List<CitationPoint> = emptyList(),
    enableMarkdown: Boolean = true,
    enableLatex: Boolean = true,
    baseStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    onOutsideTap: (() -> Unit)? = null
) {
    if (!enableMarkdown && !enableLatex) {
        SelectionContainer {
            Text(
                text = text,
                style = baseStyle,
                color = textColor,
                modifier = modifier
            )
        }
        return
    }

    val blocks = remember(text, citations, enableMarkdown, enableLatex) {
        val annotatedSource = injectInlineCitations(text, citations)
        parseMarkdownBlocks(annotatedSource, enableMarkdown, enableLatex)
    }

    SelectionContainer {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            blocks.forEach { block ->
                RenderMarkdownBlock(
                    block = block,
                    enableMarkdown = enableMarkdown,
                    enableLatex = enableLatex,
                    baseStyle = baseStyle,
                    textColor = textColor,
                    onOutsideTap = onOutsideTap
                )
            }
        }
    }
}

internal fun injectInlineCitations(text: String, citations: List<CitationPoint>): String {
    if (citations.isEmpty() || text.isEmpty()) return text
    val totalCodePoints = text.codePointCount(0, text.length)
    val mapped = citations.mapNotNull { pt ->
        if (pt.indexes.isEmpty()) return@mapNotNull null
        val charOffset = if (pt.endIndex >= 0) {
            val cp = pt.endIndex.coerceIn(0, totalCodePoints)
            text.offsetByCodePoints(0, cp)
        } else {
            pt.offset.coerceIn(0, text.length)
        }
        Triple(charOffset, pt.indexes, pt.url)
    }.sortedByDescending { it.first }
    if (mapped.isEmpty()) return text
    val sb = StringBuilder(text)
    for ((charPos, indexes, url) in mapped) {
        val badge = if (url.isNotBlank()) {
            indexes.joinToString("") { "[[$it]]($url)" }
        } else {
            indexes.joinToString("") { "[$it]" }
        }
        sb.insert(charPos.coerceIn(0, sb.length), badge)
    }
    return sb.toString()
}

/**
 * 生成「复制用」纯文本：正文在 citation 偏移处插入 `[n]` 角标，
 * 文末追加引用列表（编号 + 标题 + URL），与设备渲染角标语义一致。
 */
internal fun buildCitationCopyText(
    text: String,
    citations: List<CitationPoint>,
    sources: List<SearchSource> = emptyList()
): String {
    if (text.isEmpty()) return text

    val withBadges = if (citations.isEmpty()) {
        text
    } else {
        val totalCodePoints = text.codePointCount(0, text.length)
        val mapped = citations.mapNotNull { pt ->
            if (pt.indexes.isEmpty()) return@mapNotNull null
            val charOffset = if (pt.endIndex >= 0) {
                val cp = pt.endIndex.coerceIn(0, totalCodePoints)
                text.offsetByCodePoints(0, cp)
            } else {
                pt.offset.coerceIn(0, text.length)
            }
            Triple(charOffset, pt.indexes, pt)
        }.sortedByDescending { it.first }
        if (mapped.isEmpty()) {
            text
        } else {
            val sb = StringBuilder(text)
            for ((charPos, indexes, _) in mapped) {
                val badge = indexes.joinToString("") { "[$it]" }
                sb.insert(charPos.coerceIn(0, sb.length), badge)
            }
            sb.toString()
        }
    }

    if (sources.isEmpty()) return withBadges

    // 用 citations 的 url/title 优先建立编号 -> 引用条目映射，
    // 缺号时回退 sources 列表（位置下标即 UI 展示编号）。
    val indexToRef = linkedMapOf<Int, Pair<String, String>>()
    for (c in citations) {
        for (idx in c.indexes) {
            if (idx > 0 && !indexToRef.containsKey(idx)) {
                indexToRef[idx] = (c.title.ifBlank { c.url }) to c.url
            }
        }
    }
    val sourceRefs = mutableListOf<Pair<String, String>>()
    if (indexToRef.isEmpty()) {
        sources.forEachIndexed { i, s ->
            sourceRefs.add((s.title.ifBlank { s.url }) to s.url)
        }
    } else {
        val maxIdx = indexToRef.keys.maxOrNull() ?: 0
        for (i in 1..maxIdx) {
            val ref = indexToRef[i]
            if (ref != null && ref.second.isNotBlank()) {
                sourceRefs.add(ref)
            }
        }
    }

    if (sourceRefs.isEmpty()) return withBadges

    return buildString {
        append(withBadges)
        append("\n\n---\n")
        append("引用来源：\n")
        sourceRefs.forEachIndexed { i, (title, url) ->
            append("[${i + 1}] ${title.ifBlank { url }}\n")
            if (url.isNotBlank()) append("    $url\n")
        }
    }.trimEnd()
}

sealed interface MdBlock {
    data class Heading(val level: Int, val content: String) : MdBlock
    data class Paragraph(val content: String) : MdBlock
    data class CodeBlock(val language: String, val code: String) : MdBlock
    data class MathBlock(val latex: String) : MdBlock
    data class BlockQuote(val content: String) : MdBlock
    data class ListBlock(val items: List<MdListItem>) : MdBlock
    data class TableBlock(val headers: List<String>, val rows: List<List<String>>) : MdBlock
    data object Divider : MdBlock
}

data class MdListItem(
    val bullet: String,
    val content: String,
    val indentLevel: Int = 0,
    val checked: Boolean? = null
)

internal fun parseMarkdownBlocks(
    raw: String,
    enableMarkdown: Boolean = true,
    enableLatex: Boolean = true
): List<MdBlock> {
    val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
    if (!enableMarkdown) {
        return listOf(MdBlock.Paragraph(normalized))
    }

    val lines = normalized.split("\n")
    val blocks = mutableListOf<MdBlock>()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        if (trimmed.isEmpty()) {
            i++
            continue
        }

        // 1. Fenced code block ``` or ~~~
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            val fence = trimmed.take(3)
            val lang = trimmed.removePrefix(fence).trim()
            if ((lang.equals("math", ignoreCase = true) || lang.equals("latex", ignoreCase = true)) && enableLatex) {
                val mathLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                    mathLines.add(lines[i])
                    i++
                }
                if (i < lines.size) i++
                blocks.add(MdBlock.MathBlock(mathLines.joinToString("\n").trim()))
                continue
            }
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                codeLines.add(lines[i])
                i++
            }
            if (i < lines.size) i++
            blocks.add(MdBlock.CodeBlock(lang, codeLines.joinToString("\n")))
            continue
        }

        // 2. Display Math Block: $$ ... $$ or \[ ... \] or \begin{equation/align/...}
        if (enableLatex) {
            if (trimmed.startsWith("$$")) {
                if (trimmed.length > 4 && trimmed.endsWith("$$") && trimmed.indexOf("$$", 2) == trimmed.length - 2) {
                    val inner = trimmed.substring(2, trimmed.length - 2).trim()
                    blocks.add(MdBlock.MathBlock(inner))
                    i++
                    continue
                }
                val mathLines = mutableListOf<String>()
                val firstRemainder = trimmed.removePrefix("$$").trim()
                if (firstRemainder.isNotEmpty()) mathLines.add(firstRemainder)
                i++
                while (i < lines.size) {
                    val cur = lines[i]
                    val curTrim = cur.trim()
                    if (curTrim.endsWith("$$")) {
                        val beforeEnd = curTrim.removeSuffix("$$").trim()
                        if (beforeEnd.isNotEmpty()) mathLines.add(beforeEnd)
                        i++
                        break
                    } else {
                        mathLines.add(cur)
                        i++
                    }
                }
                blocks.add(MdBlock.MathBlock(mathLines.joinToString("\n").trim()))
                continue
            }

            if (trimmed.startsWith("\\[")) {
                if (trimmed.endsWith("\\]") && trimmed.length > 4) {
                    val inner = trimmed.removePrefix("\\[").removeSuffix("\\]").trim()
                    blocks.add(MdBlock.MathBlock(inner))
                    i++
                    continue
                }
                val mathLines = mutableListOf<String>()
                val firstRemainder = trimmed.removePrefix("\\[").trim()
                if (firstRemainder.isNotEmpty()) mathLines.add(firstRemainder)
                i++
                while (i < lines.size) {
                    val cur = lines[i].trim()
                    if (cur.endsWith("\\]")) {
                        val beforeEnd = cur.removeSuffix("\\]").trim()
                        if (beforeEnd.isNotEmpty()) mathLines.add(beforeEnd)
                        i++
                        break
                    } else {
                        mathLines.add(lines[i])
                        i++
                    }
                }
                blocks.add(MdBlock.MathBlock(mathLines.joinToString("\n").trim()))
                continue
            }

            val envMatch = Regex("""^\\begin\{(equation\*?|align\*?|aligned|gather\*?|math|displaymath|pmatrix|bmatrix|matrix|vmatrix|cases)\}""").find(trimmed)
            if (envMatch != null) {
                val envName = envMatch.groupValues[1]
                val endTag = "\\end{$envName}"
                val mathLines = mutableListOf<String>()
                while (i < lines.size) {
                    val cur = lines[i]
                    mathLines.add(cur)
                    i++
                    if (cur.contains(endTag)) break
                }
                blocks.add(MdBlock.MathBlock(mathLines.joinToString("\n").trim()))
                continue
            }
        }

        // 3. Horizontal Rule
        if (Regex("""^(\*{3,}|-{3,}|_{3,})$""").matches(trimmed)) {
            blocks.add(MdBlock.Divider)
            i++
            continue
        }

        // 4. Heading (# .. ######)
        val headingMatch = Regex("""^(#{1,6})\s+(.+)$""").find(trimmed)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            val content = headingMatch.groupValues[2].trim()
            blocks.add(MdBlock.Heading(level, content))
            i++
            continue
        }

        // 5. Pipe Table
        if (trimmed.startsWith("|") && trimmed.endsWith("|") && i + 1 < lines.size) {
            val nextTrimmed = lines[i + 1].trim()
            if (nextTrimmed.startsWith("|") && Regex("""^\|[\s:|-]+\|$""").matches(nextTrimmed)) {
                val headers = parseTableCells(trimmed)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size) {
                    val rowLine = lines[i].trim()
                    if (rowLine.startsWith("|") && rowLine.endsWith("|")) {
                        rows.add(parseTableCells(rowLine))
                        i++
                    } else {
                        break
                    }
                }
                blocks.add(MdBlock.TableBlock(headers, rows))
                continue
            }
        }

        // 6. Blockquote
        if (trimmed.startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                quoteLines.add(lines[i].trim().removePrefix(">").trimStart())
                i++
            }
            blocks.add(MdBlock.BlockQuote(quoteLines.joinToString("\n")))
            continue
        }

        // 7. Lists (-, *, +, or 1.)
        val listMatch = matchListItem(line)
        if (listMatch != null) {
            val items = mutableListOf<MdListItem>()
            while (i < lines.size) {
                val curMatch = matchListItem(lines[i])
                if (curMatch != null) {
                    items.add(curMatch)
                    i++
                } else if (lines[i].startsWith("  ") && items.isNotEmpty() && lines[i].trim().isNotEmpty()) {
                    val last = items.removeAt(items.lastIndex)
                    items.add(last.copy(content = last.content + "\n" + lines[i].trim()))
                    i++
                } else {
                    break
                }
            }
            blocks.add(MdBlock.ListBlock(items))
            continue
        }

        // 8. Paragraph (consume until blank line or block boundary)
        val paraLines = mutableListOf<String>()
        while (i < lines.size) {
            val cur = lines[i]
            val curTrim = cur.trim()
            if (curTrim.isEmpty()) break
            if (paraLines.isNotEmpty() && (
                    curTrim.startsWith("```") ||
                        curTrim.startsWith("~~~") ||
                        (enableLatex && (curTrim.startsWith("$$") || curTrim.startsWith("\\["))) ||
                        Regex("""^(#{1,6})\s+""").containsMatchIn(curTrim) ||
                        Regex("""^(\*{3,}|-{3,}|_{3,})$""").matches(curTrim) ||
                        curTrim.startsWith(">") ||
                        matchListItem(cur) != null ||
                        (curTrim.startsWith("|") && curTrim.endsWith("|") && i + 1 < lines.size &&
                            Regex("""^\|[\s:|-]+\|$""").matches(lines[i + 1].trim()))
                    )
            ) {
                break
            }
            // Check inline $$ ... $$ within a paragraph line
            paraLines.add(cur)
            i++
        }
        if (paraLines.isNotEmpty()) {
            val joined = paraLines.joinToString("\n")
            if (enableLatex && joined.contains("$$")) {
                splitParagraphOnDisplayMath(joined, blocks)
            } else {
                blocks.add(MdBlock.Paragraph(joined))
            }
        }
    }

    return blocks
}

private fun splitParagraphOnDisplayMath(text: String, out: MutableList<MdBlock>) {
    var cursor = 0
    while (cursor < text.length) {
        val openIdx = text.indexOf("$$", cursor)
        if (openIdx < 0) {
            val tail = text.substring(cursor).trim()
            if (tail.isNotEmpty()) out.add(MdBlock.Paragraph(tail))
            break
        }
        val closeIdx = text.indexOf("$$", openIdx + 2)
        if (closeIdx < 0) {
            val tail = text.substring(cursor).trim()
            if (tail.isNotEmpty()) out.add(MdBlock.Paragraph(tail))
            break
        }
        val before = text.substring(cursor, openIdx).trim()
        if (before.isNotEmpty()) out.add(MdBlock.Paragraph(before))
        val math = text.substring(openIdx + 2, closeIdx).trim()
        if (math.isNotEmpty()) out.add(MdBlock.MathBlock(math))
        cursor = closeIdx + 2
    }
}

private fun parseTableCells(line: String): List<String> {
    return line.trim()
        .removePrefix("|")
        .removeSuffix("|")
        .split("|")
        .map { it.trim() }
}

private fun matchListItem(line: String): MdListItem? {
    val leadingSpaces = line.takeWhile { it == ' ' || it == '\t' }.length
    val indent = (leadingSpaces / 2).coerceIn(0, 4)
    val trimmed = line.trim()

    val taskMatch = Regex("""^[-*+]\s+\[([ xX])\]\s+(.+)$""").find(trimmed)
    if (taskMatch != null) {
        val checked = taskMatch.groupValues[1].equals("x", ignoreCase = true)
        return MdListItem(
            bullet = if (checked) "☑" else "☐",
            content = taskMatch.groupValues[2],
            indentLevel = indent,
            checked = checked
        )
    }

    val ulMatch = Regex("""^([-*+•])\s+(.+)$""").find(trimmed)
    if (ulMatch != null) {
        return MdListItem(
            bullet = "•",
            content = ulMatch.groupValues[2],
            indentLevel = indent
        )
    }

    val olMatch = Regex("""^(\d+\.)\s+(.+)$""").find(trimmed)
    if (olMatch != null) {
        return MdListItem(
            bullet = olMatch.groupValues[1],
            content = olMatch.groupValues[2],
            indentLevel = indent
        )
    }
    return null
}

@Composable
private fun RenderMarkdownBlock(
    block: MdBlock,
    enableMarkdown: Boolean,
    enableLatex: Boolean,
    baseStyle: TextStyle,
    textColor: Color,
    onOutsideTap: (() -> Unit)?
) {
    when (block) {
        is MdBlock.Heading -> {
            val headingStyle = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                2 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                3 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                4 -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                else -> baseStyle.copy(fontWeight = FontWeight.SemiBold)
            }
            RichInlineAndMathRow(
                text = block.content,
                enableMarkdown = enableMarkdown,
                enableLatex = enableLatex,
                style = headingStyle,
                textColor = textColor,
                onOutsideTap = onOutsideTap,
                modifier = Modifier.padding(top = if (block.level <= 2) 4.dp else 2.dp)
            )
        }

        is MdBlock.Paragraph -> {
            RichInlineAndMathRow(
                text = block.content,
                enableMarkdown = enableMarkdown,
                enableLatex = enableLatex,
                style = baseStyle,
                textColor = textColor,
                onOutsideTap = onOutsideTap
            )
        }

        is MdBlock.CodeBlock -> {
            CodeBlockCard(language = block.language, code = block.code)
        }

        is MdBlock.MathBlock -> {
            DisplayMathCard(latex = block.latex, textColor = textColor)
        }

        is MdBlock.BlockQuote -> {
            val borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .drawBehind {
                        drawLine(
                            color = borderColor,
                            start = Offset(0f, 0f),
                            end = Offset(0f, size.height),
                            strokeWidth = 3.dp.toPx()
                        )
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                RichInlineAndMathRow(
                    text = block.content,
                    enableMarkdown = enableMarkdown,
                    enableLatex = enableLatex,
                    style = baseStyle.copy(fontStyle = FontStyle.Italic),
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    onOutsideTap = onOutsideTap
                )
            }
        }

        is MdBlock.ListBlock -> {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                block.items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (item.indentLevel * 14).dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = item.bullet,
                            style = baseStyle.copy(fontWeight = FontWeight.SemiBold),
                            color = if (item.checked == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .widthIn(min = 20.dp)
                                .padding(end = 6.dp)
                        )
                        RichInlineAndMathRow(
                            text = item.content,
                            enableMarkdown = enableMarkdown,
                            enableLatex = enableLatex,
                            style = baseStyle,
                            textColor = textColor,
                            onOutsideTap = onOutsideTap,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        is MdBlock.TableBlock -> {
            MarkdownTableCard(
                headers = block.headers,
                rows = block.rows,
                enableMarkdown = enableMarkdown,
                enableLatex = enableLatex,
                baseStyle = baseStyle,
                textColor = textColor,
                onOutsideTap = onOutsideTap
            )
        }

        MdBlock.Divider -> {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Composable
private fun CodeBlockCard(language: String, code: String) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language.ifBlank { "code" }.lowercase(),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            clipboard.setText(AnnotatedString(code))
                            copied = true
                            scope.launch {
                                delay(1600)
                                copied = false
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                        contentDescription = "复制代码",
                        modifier = Modifier.size(13.dp),
                        tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (copied) "已复制" else "复制",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = MonoFontFamily,
                        lineHeight = 19.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun MarkdownTableCard(
    headers: List<String>,
    rows: List<List<String>>,
    enableMarkdown: Boolean,
    enableLatex: Boolean,
    baseStyle: TextStyle,
    textColor: Color,
    onOutsideTap: (() -> Unit)?
) {
    val colCount = maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .width(IntrinsicSize.Max)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                for (c in 0 until colCount) {
                    Box(modifier = Modifier.widthIn(min = 84.dp, max = 240.dp)) {
                        RichInlineAndMathRow(
                            text = headers.getOrNull(c).orEmpty(),
                            enableMarkdown = enableMarkdown,
                            enableLatex = enableLatex,
                            style = baseStyle.copy(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            textColor = textColor,
                            onOutsideTap = onOutsideTap
                        )
                    }
                }
            }
            rows.forEachIndexed { idx, row ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (idx % 2 == 1) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                            } else {
                                Color.Transparent
                            }
                        )
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    for (c in 0 until colCount) {
                        Box(modifier = Modifier.widthIn(min = 84.dp, max = 240.dp)) {
                            RichInlineAndMathRow(
                                text = row.getOrNull(c).orEmpty(),
                                enableMarkdown = enableMarkdown,
                                enableLatex = enableLatex,
                                style = baseStyle.copy(fontSize = 13.sp),
                                textColor = textColor,
                                onOutsideTap = onOutsideTap
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// Inline Markdown + Inline Math segmentation
// ============================================================================

internal sealed interface InlineSegment {
    data class RichText(val text: String) : InlineSegment
    data class InlineMath(val latex: String) : InlineSegment
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RichInlineAndMathRow(
    text: String,
    enableMarkdown: Boolean,
    enableLatex: Boolean,
    style: TextStyle,
    textColor: Color,
    onOutsideTap: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val segments = remember(text, enableLatex) {
        if (enableLatex) splitInlineMathSegments(text) else listOf(InlineSegment.RichText(text))
    }

    if (segments.size == 1 && segments[0] is InlineSegment.RichText) {
        InlineMarkdownText(
            text = (segments[0] as InlineSegment.RichText).text,
            enableMarkdown = enableMarkdown,
            style = style,
            textColor = textColor,
            onOutsideTap = onOutsideTap,
            modifier = modifier
        )
        return
    }

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.Center
    ) {
        segments.forEach { seg ->
            when (seg) {
                is InlineSegment.RichText -> {
                    if (seg.text.isNotEmpty()) {
                        InlineMarkdownText(
                            text = seg.text,
                            enableMarkdown = enableMarkdown,
                            style = style,
                            textColor = textColor,
                            onOutsideTap = onOutsideTap,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }
                is InlineSegment.InlineMath -> {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .padding(vertical = 1.dp)
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)) {
                            MathFormulaView(
                                latex = seg.latex,
                                fontSize = (style.fontSize.value.takeIf { !it.isNaN() && it > 0f } ?: 15f).sp,
                                textColor = textColor
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun splitInlineMathSegments(input: String): List<InlineSegment> {
    val result = mutableListOf<InlineSegment>()
    val sb = StringBuilder()
    var i = 0
    while (i < input.length) {
        // 1. Escaped \$
        if (input[i] == '\\' && i + 1 < input.length && input[i + 1] == '$') {
            sb.append('$')
            i += 2
            continue
        }
        // 2. \( ... \)
        if (input[i] == '\\' && i + 1 < input.length && input[i + 1] == '(') {
            val end = input.indexOf("\\)", i + 2)
            if (end > i + 2) {
                if (sb.isNotEmpty()) {
                    result.add(InlineSegment.RichText(sb.toString()))
                    sb.clear()
                }
                result.add(InlineSegment.InlineMath(input.substring(i + 2, end).trim()))
                i = end + 2
                continue
            }
        }
        // 3. $ ... $ (single dollar, non-currency heuristic)
        if (input[i] == '$' && (i + 1 >= input.length || input[i + 1] != '$')) {
            val end = findClosingInlineDollar(input, i + 1)
            if (end > i + 1) {
                val candidate = input.substring(i + 1, end)
                if (looksLikeInlineMath(candidate)) {
                    if (sb.isNotEmpty()) {
                        result.add(InlineSegment.RichText(sb.toString()))
                        sb.clear()
                    }
                    result.add(InlineSegment.InlineMath(candidate.trim()))
                    i = end + 1
                    continue
                }
            }
        }
        sb.append(input[i])
        i++
    }
    if (sb.isNotEmpty()) {
        result.add(InlineSegment.RichText(sb.toString()))
    }
    return result
}

private fun findClosingInlineDollar(text: String, start: Int): Int {
    var i = start
    while (i < text.length) {
        val ch = text[i]
        if (ch == '\n') return -1
        if (ch == '\\') {
            i += 2
            continue
        }
        if (ch == '$') {
            return i
        }
        i++
    }
    return -1
}

private fun looksLikeInlineMath(candidate: String): Boolean {
    if (candidate.isBlank()) return false
    if (candidate.first().isWhitespace() && candidate.last().isWhitespace()) return false
    return true
}

@Composable
private fun InlineMarkdownText(
    text: String,
    enableMarkdown: Boolean,
    style: TextStyle,
    textColor: Color,
    onOutsideTap: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val codeFg = MaterialTheme.colorScheme.onSurface

    val annotated = remember(text, enableMarkdown, primaryColor, codeBg, codeFg, textColor) {
        if (enableMarkdown) {
            buildInlineAnnotatedString(
                text = text,
                primaryColor = primaryColor,
                codeBg = codeBg,
                codeFg = codeFg
            )
        } else {
            AnnotatedString(text)
        }
    }

    val hasLinks = remember(annotated) {
        annotated.getStringAnnotations(tag = "URL", start = 0, end = annotated.length).isNotEmpty()
    }

    if (hasLinks) {
        @Suppress("DEPRECATION")
        ClickableText(
            text = annotated,
            style = style.copy(color = textColor),
            modifier = modifier,
            onClick = { offset ->
                val urlAnn = annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                    .firstOrNull()
                if (urlAnn != null) {
                    runCatching { uriHandler.openUri(urlAnn.item) }
                } else {
                    onOutsideTap?.invoke()
                }
            }
        )
    } else {
        Text(
            text = annotated,
            style = style,
            color = textColor,
            modifier = modifier
        )
    }
}

/** 匹配引用角标标签：单个 `[1]` 或连续多个 `[1][2]`。 */
private val CitationLabelRegex = Regex("""^(?:\[\d{1,3}])+$""")

private fun buildInlineAnnotatedString(
    text: String,
    primaryColor: Color,
    codeBg: Color,
    codeFg: Color
): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        // 1. Inline code `...`
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i) {
                val code = text.substring(i + 1, end)
                withStyle(
                    SpanStyle(
                        fontFamily = MonoFontFamily,
                        background = codeBg,
                        color = codeFg,
                        fontSize = 13.sp
                    )
                ) {
                    append("\u00A0$code\u00A0")
                }
                i = end + 1
                continue
            }
        }

        // 2. Bold + Italic ***...***
        if (text.startsWith("***", i)) {
            val end = text.indexOf("***", i + 3)
            if (end > i + 3) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                    append(text.substring(i + 3, end))
                }
                i = end + 3
                continue
            }
        }

        // 3. Bold **...** or __...__
        if (text.startsWith("**", i) || text.startsWith("__", i)) {
            val delim = text.substring(i, i + 2)
            val end = text.indexOf(delim, i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(
                        buildInlineAnnotatedString(
                            text = text.substring(i + 2, end),
                            primaryColor = primaryColor,
                            codeBg = codeBg,
                            codeFg = codeFg
                        )
                    )
                }
                i = end + 2
                continue
            }
        }

        // 4. Strikethrough ~~...~~
        if (text.startsWith("~~", i)) {
            val end = text.indexOf("~~", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    append(text.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }

        // 5. Italic *...*
        if (text[i] == '*' && (i + 1 < text.length && !text[i + 1].isWhitespace())) {
            val end = text.indexOf('*', i + 1)
            if (end > i + 1 && !text[end - 1].isWhitespace()) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }

        // 6. Markdown link [label](url)（支持嵌套方括号标签，如引用角标 [[1]](url)）
        if (text[i] == '[') {
            // 扫描匹配的外层 ']'（depth 计数兼顾内层 [n]）
            var depth = 0
            var matchClose = -1
            var k = i
            while (k < text.length) {
                val ch = text[k]
                if (ch == '[') {
                    depth++
                } else if (ch == ']') {
                    depth--
                    if (depth == 0) {
                        matchClose = k
                        break
                    }
                }
                k++
            }
            if (matchClose > i + 1 && matchClose + 1 < text.length && text[matchClose + 1] == '(') {
                val closeParen = text.indexOf(')', matchClose + 2)
                if (closeParen > matchClose + 2) {
                    val label = text.substring(i + 1, matchClose)
                    val url = text.substring(matchClose + 2, closeParen).trim()
                    pushStringAnnotation(tag = "URL", annotation = url)
                    if (CitationLabelRegex.matches(label)) {
                        // 引用角标标签（[1] / [1][2] ...）：渲染为上标角标
                        withStyle(
                            SpanStyle(
                                color = primaryColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                baselineShift = BaselineShift.Superscript
                            )
                        ) {
                            append(label)
                        }
                    } else {
                        withStyle(
                            SpanStyle(
                                color = primaryColor,
                                textDecoration = TextDecoration.Underline,
                                fontWeight = FontWeight.Medium
                            )
                        ) {
                            append(label)
                        }
                    }
                    pop()
                    i = closeParen + 1
                    continue
                }
            }

            // Citation badge [1] or [12]（无链接的纯角标）
            if (matchClose in (i + 2)..(i + 4)) {
                val inside = text.substring(i + 1, matchClose)
                if (inside.all { it.isDigit() }) {
                    withStyle(
                        SpanStyle(
                            color = primaryColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            baselineShift = BaselineShift.Superscript
                        )
                    ) {
                        append("[$inside]")
                    }
                    i = matchClose + 1
                    continue
                }
            }
        }

        append(text[i])
        i++
    }
}

// ============================================================================
// Native LaTeX Formula Parser & Visual Renderer
// ============================================================================

@Composable
private fun DisplayMathCard(
    latex: String,
    textColor: Color
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    clipboard.setText(AnnotatedString(latex))
                    copied = true
                    scope.launch {
                        delay(1200)
                        copied = false
                    }
                }
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center
            ) {
                MathFormulaView(
                    latex = latex,
                    fontSize = 17.sp,
                    textColor = textColor
                )
            }
            if (copied) {
                Text(
                    text = "LaTeX 已复制",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
        }
    }
}

sealed interface MathNode {
    data class Symbol(val text: String, val isItalicVariable: Boolean = false) : MathNode
    data class TextRun(val text: String) : MathNode
    data class Group(val children: List<MathNode>) : MathNode
    data class Fraction(val numerator: MathNode, val denominator: MathNode) : MathNode
    data class Sqrt(val degree: MathNode?, val radicand: MathNode) : MathNode
    data class SubSup(val base: MathNode, val sub: MathNode?, val sup: MathNode?) : MathNode
    data class BigOp(val operatorSymbol: String, val lower: MathNode?, val upper: MathNode?) : MathNode
    data class MatrixNode(val env: String, val rows: List<List<MathNode>>) : MathNode
    data class Accent(val accentSymbol: String, val base: MathNode) : MathNode
    data class Overline(val base: MathNode) : MathNode
}

@Composable
fun MathFormulaView(
    latex: String,
    fontSize: TextUnit = 16.sp,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    val lines = remember(latex) {
        splitTopLevelLatexLines(latex)
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        lines.forEach { lineLatex ->
            val ast = remember(lineLatex) {
                runCatching { LatexMathParser(lineLatex).parseExpression() }
                    .getOrElse { MathNode.Symbol(lineLatex) }
            }
            RenderMathNode(
                node = ast,
                fontSize = fontSize,
                textColor = textColor
            )
        }
    }
}

private fun splitTopLevelLatexLines(latex: String): List<String> {
    val cleaned = latex.trim()
        .replace(Regex("""^\\begin\{(equation\*?|align\*?|aligned|gather\*?|math|displaymath)\}"""), "")
        .replace(Regex("""\\end\{(equation\*?|align\*?|aligned|gather\*?|math|displaymath)\}$"""), "")
        .trim()
    if (cleaned.startsWith("\\begin{")) {
        return listOf(cleaned)
    }
    val result = mutableListOf<String>()
    val sb = StringBuilder()
    var braceDepth = 0
    var i = 0
    while (i < cleaned.length) {
        if (cleaned[i] == '{') braceDepth++
        else if (cleaned[i] == '}') braceDepth = (braceDepth - 1).coerceAtLeast(0)

        if (braceDepth == 0 && i + 1 < cleaned.length && cleaned[i] == '\\' && cleaned[i + 1] == '\\') {
            val segment = sb.toString().replace("&", " ").trim()
            if (segment.isNotEmpty()) result.add(segment)
            sb.clear()
            i += 2
            continue
        }
        if (braceDepth == 0 && cleaned[i] == '&') {
            sb.append(' ')
            i++
            continue
        }
        sb.append(cleaned[i])
        i++
    }
    val tail = sb.toString().trim()
    if (tail.isNotEmpty()) result.add(tail)
    return if (result.isEmpty()) listOf(cleaned) else result
}

@Composable
private fun RenderMathNode(
    node: MathNode,
    fontSize: TextUnit,
    textColor: Color
) {
    val smallerSize = (fontSize.value * 0.76f).coerceAtLeast(10f).sp
    val tinySize = (fontSize.value * 0.68f).coerceAtLeast(9f).sp

    when (node) {
        is MathNode.Symbol -> {
            Text(
                text = node.text,
                fontSize = fontSize,
                color = textColor,
                fontStyle = if (node.isItalicVariable) FontStyle.Italic else FontStyle.Normal,
                fontWeight = FontWeight.Normal,
                lineHeight = (fontSize.value * 1.15f).sp
            )
        }

        is MathNode.TextRun -> {
            Text(
                text = node.text,
                fontSize = fontSize,
                color = textColor,
                lineHeight = (fontSize.value * 1.15f).sp
            )
        }

        is MathNode.Group -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                node.children.forEach { child ->
                    RenderMathNode(child, fontSize, textColor)
                }
            }
        }

        is MathNode.Fraction -> {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .width(IntrinsicSize.Max)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 3.dp)
                ) {
                    RenderMathNode(node.numerator, smallerSize, textColor)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.2.dp)
                        .background(textColor.copy(alpha = 0.85f))
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 3.dp)
                ) {
                    RenderMathNode(node.denominator, smallerSize, textColor)
                }
            }
        }

        is MathNode.Sqrt -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 1.dp)
            ) {
                if (node.degree != null) {
                    Box(modifier = Modifier.padding(bottom = 8.dp)) {
                        RenderMathNode(node.degree, tinySize, textColor)
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .drawBehind {
                            val stroke = 1.3.dp.toPx()
                            val w = size.width
                            val h = size.height
                            val tickStartX = 0f
                            val tickMidY = h * 0.62f
                            val valleyX = 5.dp.toPx()
                            val topX = 10.dp.toPx()
                            val path = Path().apply {
                                moveTo(tickStartX, tickMidY)
                                lineTo(valleyX, h - stroke)
                                lineTo(topX, stroke)
                                lineTo(w, stroke)
                            }
                            drawPath(path, color = textColor, style = Stroke(width = stroke))
                        }
                        .padding(start = 11.dp, top = 3.dp, end = 2.dp)
                ) {
                    RenderMathNode(node.radicand, fontSize, textColor)
                }
            }
        }

        is MathNode.SubSup -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RenderMathNode(node.base, fontSize, textColor)
                if (node.sub != null && node.sup != null) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                        horizontalAlignment = Alignment.Start,
                        modifier = Modifier.padding(start = 1.dp)
                    ) {
                        RenderMathNode(node.sup, tinySize, textColor)
                        RenderMathNode(node.sub, tinySize, textColor)
                    }
                } else if (node.sup != null) {
                    Box(modifier = Modifier.padding(bottom = (fontSize.value * 0.38f).dp, start = 1.dp)) {
                        RenderMathNode(node.sup, tinySize, textColor)
                    }
                } else if (node.sub != null) {
                    Box(modifier = Modifier.padding(top = (fontSize.value * 0.35f).dp, start = 1.dp)) {
                        RenderMathNode(node.sub, tinySize, textColor)
                    }
                }
            }
        }

        is MathNode.BigOp -> {
            val isLim = node.operatorSymbol == "lim"
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 3.dp)
            ) {
                if (node.upper != null) {
                    RenderMathNode(node.upper, tinySize, textColor)
                }
                Text(
                    text = node.operatorSymbol,
                    fontSize = if (isLim) fontSize else (fontSize.value * 1.25f).sp,
                    fontWeight = if (isLim) FontWeight.Medium else FontWeight.Normal,
                    color = textColor
                )
                if (node.lower != null) {
                    RenderMathNode(node.lower, tinySize, textColor)
                }
            }
        }

        is MathNode.MatrixNode -> {
            val leftDelim = when (node.env) {
                "pmatrix" -> "("
                "bmatrix" -> "["
                "Bmatrix" -> "{"
                "vmatrix" -> "|"
                "Vmatrix" -> "‖"
                "cases" -> "{"
                else -> ""
            }
            val rightDelim = when (node.env) {
                "pmatrix" -> ")"
                "bmatrix" -> "]"
                "Bmatrix" -> "}"
                "vmatrix" -> "|"
                "Vmatrix" -> "‖"
                else -> ""
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 2.dp)
            ) {
                if (leftDelim.isNotEmpty()) {
                    Text(
                        text = leftDelim,
                        fontSize = (fontSize.value * (1.15f + 0.25f * node.rows.size.coerceAtMost(4))).sp,
                        fontWeight = FontWeight.Light,
                        color = textColor
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    node.rows.forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            row.forEach { cell ->
                                RenderMathNode(cell, smallerSize, textColor)
                            }
                        }
                    }
                }
                if (rightDelim.isNotEmpty()) {
                    Text(
                        text = rightDelim,
                        fontSize = (fontSize.value * (1.15f + 0.25f * node.rows.size.coerceAtMost(4))).sp,
                        fontWeight = FontWeight.Light,
                        color = textColor
                    )
                }
            }
        }

        is MathNode.Accent -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = node.accentSymbol,
                    fontSize = tinySize,
                    color = textColor,
                    lineHeight = tinySize
                )
                RenderMathNode(node.base, fontSize, textColor)
            }
        }

        is MathNode.Overline -> {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(IntrinsicSize.Max)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(textColor)
                )
                RenderMathNode(node.base, fontSize, textColor)
            }
        }
    }
}

internal class LatexMathParser(private val src: String) {
    private var pos = 0

    fun parseExpression(stopAtCloseBrace: Boolean = false): MathNode {
        val nodes = mutableListOf<MathNode>()
        while (pos < src.length) {
            skipWhitespace()
            if (pos >= src.length) break
            val ch = src[pos]
            if (stopAtCloseBrace && ch == '}') {
                break
            }
            val atom = parseAtom() ?: break
            val withScripts = parseSubSupIfPresent(atom)
            nodes.add(withScripts)
        }
        return when (nodes.size) {
            0 -> MathNode.Symbol("")
            1 -> nodes[0]
            else -> MathNode.Group(mergeAdjacentSymbols(nodes))
        }
    }

    private fun mergeAdjacentSymbols(nodes: List<MathNode>): List<MathNode> {
        val out = mutableListOf<MathNode>()
        for (node in nodes) {
            val prev = out.lastOrNull()
            if (prev is MathNode.Symbol && node is MathNode.Symbol && prev.isItalicVariable == node.isItalicVariable) {
                out[out.lastIndex] = MathNode.Symbol(prev.text + node.text, prev.isItalicVariable)
            } else {
                out.add(node)
            }
        }
        return out
    }

    private fun parseSubSupIfPresent(base: MathNode): MathNode {
        var sub: MathNode? = null
        var sup: MathNode? = null
        while (pos < src.length) {
            skipWhitespace()
            if (pos >= src.length) break
            when (src[pos]) {
                '_' -> {
                    pos++
                    sub = parseSingleArg()
                }
                '^' -> {
                    pos++
                    sup = parseSingleArg()
                }
                '\'' -> {
                    val primes = StringBuilder()
                    while (pos < src.length && src[pos] == '\'') {
                        primes.append('′')
                        pos++
                    }
                    sup = MathNode.Symbol(primes.toString())
                }
                else -> break
            }
        }
        if (sub == null && sup == null) return base
        return if (base is MathNode.BigOp) {
            base.copy(
                lower = sub ?: base.lower,
                upper = sup ?: base.upper
            )
        } else {
            MathNode.SubSup(base = base, sub = sub, sup = sup)
        }
    }

    private fun parseSingleArg(): MathNode {
        skipWhitespace()
        if (pos >= src.length) return MathNode.Symbol("")
        if (src[pos] == '{') {
            pos++
            val inner = parseExpression(stopAtCloseBrace = true)
            if (pos < src.length && src[pos] == '}') pos++
            return inner
        }
        return parseAtom() ?: MathNode.Symbol("")
    }

    private fun parseOptionalBracketArg(): MathNode? {
        skipWhitespace()
        if (pos >= src.length || src[pos] != '[') return null
        pos++
        val start = pos
        var depth = 1
        while (pos < src.length && depth > 0) {
            if (src[pos] == '[') depth++
            else if (src[pos] == ']') depth--
            pos++
        }
        val content = src.substring(start, (pos - 1).coerceAtLeast(start))
        return LatexMathParser(content).parseExpression()
    }

    private fun parseAtom(): MathNode? {
        skipWhitespace()
        if (pos >= src.length) return null
        val ch = src[pos]

        if (ch == '{') {
            pos++
            val inner = parseExpression(stopAtCloseBrace = true)
            if (pos < src.length && src[pos] == '}') pos++
            return inner
        }

        if (ch == '\\') {
            pos++
            if (pos >= src.length) return MathNode.Symbol("")
            val next = src[pos]
            if (!next.isLetter()) {
                pos++
                val escaped = when (next) {
                    '{', '}', '%', '$', '&', '#', '_' -> next.toString()
                    ',', ';', ':', '!' -> " "
                    ' ' -> " "
                    else -> next.toString()
                }
                return MathNode.Symbol(escaped)
            }
            val cmdStart = pos
            while (pos < src.length && src[pos].isLetter()) pos++
            val cmd = src.substring(cmdStart, pos)
            return handleCommand(cmd)
        }

        pos++
        val mapped = when (ch) {
            '-' -> " − "
            '+' -> " + "
            '=' -> " = "
            '*' -> "·"
            else -> ch.toString()
        }
        return MathNode.Symbol(mapped, isItalicVariable = ch.isLetter())
    }

    private fun handleCommand(cmd: String): MathNode {
        return when (cmd) {
            "frac", "dfrac", "tfrac", "cfrac" -> {
                val num = parseSingleArg()
                val den = parseSingleArg()
                MathNode.Fraction(num, den)
            }
            "binom", "tbinom", "dbinom" -> {
                val top = parseSingleArg()
                val bot = parseSingleArg()
                MathNode.MatrixNode("pmatrix", listOf(listOf(top), listOf(bot)))
            }
            "sqrt" -> {
                val deg = parseOptionalBracketArg()
                val rad = parseSingleArg()
                MathNode.Sqrt(deg, rad)
            }
            "sum", "prod", "coprod", "int", "iint", "iiint", "oint", "bigcup", "bigcap", "bigoplus", "bigotimes", "lim", "limsup", "liminf", "max", "min", "sup", "inf", "argmax", "argmin" -> {
                val sym = BIG_OP_SYMBOLS[cmd] ?: cmd
                MathNode.BigOp(sym, null, null)
            }
            "text", "textbf", "textit", "textrm", "mathrm", "mathbf", "mathcal", "mathbb", "mathfrak", "mathsf", "mathtt", "operatorname" -> {
                val rawText = readBraceRawString()
                if (cmd == "mathbb") {
                    MathNode.TextRun(toBlackboardBold(rawText))
                } else {
                    MathNode.TextRun(rawText)
                }
            }
            "overline", "bar" -> {
                MathNode.Overline(parseSingleArg())
            }
            "hat", "widehat" -> MathNode.Accent("^", parseSingleArg())
            "tilde", "widetilde" -> MathNode.Accent("~", parseSingleArg())
            "vec", "overrightarrow" -> MathNode.Accent("→", parseSingleArg())
            "dot" -> MathNode.Accent("˙", parseSingleArg())
            "ddot" -> MathNode.Accent("¨", parseSingleArg())
            "left", "right", "big", "Big", "bigg", "Bigg" -> {
                skipWhitespace()
                if (pos < src.length) {
                    val delim = if (src[pos] == '\\') {
                        pos++
                        val s = pos
                        while (pos < src.length && src[pos].isLetter()) pos++
                        val dCmd = src.substring(s, pos)
                        if (dCmd.isEmpty() && pos < src.length) {
                            val c = src[pos]
                            pos++
                            c.toString()
                        } else {
                            LATEX_SYMBOLS[dCmd] ?: dCmd
                        }
                    } else {
                        val c = src[pos]
                        pos++
                        if (c == '.') "" else c.toString()
                    }
                    MathNode.Symbol(delim)
                } else {
                    MathNode.Symbol("")
                }
            }
            "begin" -> {
                val env = readBraceRawString().trim()
                val endMarker = "\\end{$env}"
                val endIdx = src.indexOf(endMarker, pos)
                val body = if (endIdx >= pos) {
                    val b = src.substring(pos, endIdx)
                    pos = endIdx + endMarker.length
                    b
                } else {
                    val b = src.substring(pos)
                    pos = src.length
                    b
                }
                val rows = body.split("\\\\")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { rowStr ->
                        rowStr.split("&").map { cellStr ->
                            LatexMathParser(cellStr.trim()).parseExpression()
                        }
                    }
                MathNode.MatrixNode(env, rows)
            }
            "quad" -> MathNode.Symbol("  ")
            "qquad" -> MathNode.Symbol("    ")
            else -> {
                val sym = LATEX_SYMBOLS[cmd] ?: cmd
                MathNode.Symbol(sym)
            }
        }
    }

    private fun readBraceRawString(): String {
        skipWhitespace()
        if (pos >= src.length) return ""
        if (src[pos] != '{') {
            val c = src[pos]
            pos++
            return c.toString()
        }
        pos++
        val start = pos
        var depth = 1
        while (pos < src.length && depth > 0) {
            if (src[pos] == '{') depth++
            else if (src[pos] == '}') depth--
            pos++
        }
        return src.substring(start, (pos - 1).coerceAtLeast(start))
    }

    private fun skipWhitespace() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }
}

private fun toBlackboardBold(input: String): String {
    val sb = StringBuilder()
    for (ch in input) {
        sb.append(
            when (ch) {
                'R' -> "ℝ"
                'N' -> "ℕ"
                'Z' -> "ℤ"
                'Q' -> "ℚ"
                'C' -> "ℂ"
                'E' -> "𝔼"
                'P' -> "ℙ"
                'F' -> "𝔽"
                else -> ch.toString()
            }
        )
    }
    return sb.toString()
}

private val BIG_OP_SYMBOLS = mapOf(
    "sum" to "∑",
    "prod" to "∏",
    "coprod" to "∐",
    "int" to "∫",
    "iint" to "∬",
    "iiint" to "∭",
    "oint" to "∮",
    "bigcup" to "⋃",
    "bigcap" to "⋂",
    "bigoplus" to "⨁",
    "bigotimes" to "⨂",
    "lim" to "lim",
    "limsup" to "lim sup",
    "liminf" to "lim inf",
    "max" to "max",
    "min" to "min",
    "sup" to "sup",
    "inf" to "inf",
    "argmax" to "arg max",
    "argmin" to "arg min"
)

private val LATEX_SYMBOLS = mapOf(
    // Greek lowercase
    "alpha" to "α",
    "beta" to "β",
    "gamma" to "γ",
    "delta" to "δ",
    "epsilon" to "ϵ",
    "varepsilon" to "ε",
    "zeta" to "ζ",
    "eta" to "η",
    "theta" to "θ",
    "vartheta" to "ϑ",
    "iota" to "ι",
    "kappa" to "κ",
    "lambda" to "λ",
    "mu" to "μ",
    "nu" to "ν",
    "xi" to "ξ",
    "pi" to "π",
    "varpi" to "ϖ",
    "rho" to "ρ",
    "varrho" to "ϱ",
    "sigma" to "σ",
    "varsigma" to "ς",
    "tau" to "τ",
    "upsilon" to "υ",
    "phi" to "ϕ",
    "varphi" to "φ",
    "chi" to "χ",
    "psi" to "ψ",
    "omega" to "ω",
    // Greek uppercase
    "Gamma" to "Γ",
    "Delta" to "Δ",
    "Theta" to "Θ",
    "Lambda" to "Λ",
    "Xi" to "Ξ",
    "Pi" to "Π",
    "Sigma" to "Σ",
    "Upsilon" to "Υ",
    "Phi" to "Φ",
    "Psi" to "Ψ",
    "Omega" to "Ω",
    // Relations & Operators
    "times" to " × ",
    "div" to " ÷ ",
    "cdot" to " · ",
    "cdots" to "⋯",
    "ldots" to "…",
    "dots" to "…",
    "vdots" to "⋮",
    "ddots" to "⋱",
    "pm" to " ± ",
    "mp" to " ∓ ",
    "leq" to " ≤ ",
    "le" to " ≤ ",
    "geq" to " ≥ ",
    "ge" to " ≥ ",
    "neq" to " ≠ ",
    "ne" to " ≠ ",
    "approx" to " ≈ ",
    "equiv" to " ≡ ",
    "sim" to " ∼ ",
    "simeq" to " ≃ ",
    "cong" to " ≅ ",
    "propto" to " ∝ ",
    "infty" to "∞",
    "partial" to "∂",
    "nabla" to "∇",
    "forall" to "∀",
    "exists" to "∃",
    "nexists" to "∄",
    "emptyset" to "∅",
    "varnothing" to "∅",
    "in" to " ∈ ",
    "notin" to " ∉ ",
    "ni" to " ∋ ",
    "subset" to " ⊂ ",
    "subseteq" to " ⊆ ",
    "supset" to " ⊃ ",
    "supseteq" to " ⊇ ",
    "cup" to " ∪ ",
    "cap" to " ∩ ",
    "setminus" to " ∖ ",
    "wedge" to " ∧ ",
    "land" to " ∧ ",
    "vee" to " ∨ ",
    "lor" to " ∨ ",
    "neg" to "¬",
    "lnot" to "¬",
    "oplus" to " ⊕ ",
    "otimes" to " ⊗ ",
    "perp" to " ⊥ ",
    "parallel" to " ∥ ",
    "angle" to "∠",
    "triangle" to "△",
    "degree" to "°",
    "circ" to "∘",
    "bullet" to "•",
    "star" to "⋆",
    // Arrows
    "to" to " → ",
    "rightarrow" to " → ",
    "leftarrow" to " ← ",
    "leftrightarrow" to " ↔ ",
    "Rightarrow" to " ⇒ ",
    "Leftarrow" to " ⇐ ",
    "Leftrightarrow" to " ⇔ ",
    "iff" to " ⇔ ",
    "implies" to " ⇒ ",
    "impliedby" to " ⇐ ",
    "mapsto" to " ↦ ",
    "uparrow" to "↑",
    "downarrow" to "↓",
    // Functions
    "sin" to "sin",
    "cos" to "cos",
    "tan" to "tan",
    "cot" to "cot",
    "sec" to "sec",
    "csc" to "csc",
    "arcsin" to "arcsin",
    "arccos" to "arccos",
    "arctan" to "arctan",
    "sinh" to "sinh",
    "cosh" to "cosh",
    "tanh" to "tanh",
    "ln" to "ln",
    "log" to "log",
    "exp" to "exp",
    "det" to "det",
    "dim" to "dim",
    "ker" to "ker",
    "deg" to "deg",
    "gcd" to "gcd",
    "mod" to " mod ",
    "pmod" to " mod ",
    // Brackets
    "langle" to "⟨",
    "rangle" to "⟩",
    "lceil" to "⌈",
    "rceil" to "⌉",
    "lfloor" to "⌊",
    "rfloor" to "⌋",
    "lbrace" to "{",
    "rbrace" to "}",
    "mid" to " | ",
    "hbar" to "ℏ",
    "ell" to "ℓ",
    "Re" to "ℜ",
    "Im" to "ℑ",
    "aleph" to "ℵ"
)
