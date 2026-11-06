package com.coke.nestspace.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * Lightweight Markdown support shared by the chat surfaces.
 *
 * The agent screen renders the full block set; the floating voice assistant
 * only needs the same parsing and inline spans, so both use this module
 * instead of keeping private copies of the parser.
 */
internal sealed class MarkdownBlock {
    data class Prose(val text: String) : MarkdownBlock()
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Bullet(val text: String) : MarkdownBlock()
    data class Numbered(val number: String, val text: String) : MarkdownBlock()
    data class Quote(val text: String) : MarkdownBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock()
    data class Code(val language: String, val code: String) : MarkdownBlock()
    data object Divider : MarkdownBlock()
}

internal fun parseMarkdown(text: String): List<MarkdownBlock> {
    val lines = text.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    val prose = StringBuilder()
    var code = StringBuilder()
    var inCode = false
    var language = ""

    fun flushProse() {
        val value = prose.toString().trim()
        if (value.isNotEmpty()) blocks += MarkdownBlock.Prose(value)
        prose.setLength(0)
    }

    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()

        if (inCode) {
            if (trimmed.startsWith("```")) {
                blocks += MarkdownBlock.Code(language, code.toString().trimEnd())
                code = StringBuilder()
                inCode = false
                language = ""
            } else {
                code.appendLine(line)
            }
            index++
            continue
        }

        if (trimmed.startsWith("```")) {
            flushProse()
            inCode = true
            language = trimmed.removePrefix("```").trim()
            index++
            continue
        }

        if (trimmed.contains('|') && index + 1 < lines.size &&
            isTableSeparator(lines[index + 1])
        ) {
            flushProse()
            val headers = tableCells(line)
            index += 2
            val rows = mutableListOf<List<String>>()
            while (index < lines.size &&
                lines[index].trim().isNotEmpty() &&
                lines[index].contains('|')
            ) {
                val cells = tableCells(lines[index])
                if (cells.size > 1) rows += cells
                index++
            }
            blocks += MarkdownBlock.Table(headers, rows)
            continue
        }

        val heading = HEADING.find(trimmed)
        if (heading != null) {
            flushProse()
            blocks += MarkdownBlock.Heading(
                heading.groupValues[1].length,
                heading.groupValues[2].trim()
            )
            index++
            continue
        }

        if (HR.matches(trimmed)) {
            flushProse()
            blocks += MarkdownBlock.Divider
            index++
            continue
        }

        val bullet = BULLET.find(line)
        if (bullet != null) {
            flushProse()
            blocks += MarkdownBlock.Bullet(bullet.groupValues[1].trim())
            index++
            continue
        }

        val numbered = NUMBERED.find(trimmed)
        if (numbered != null) {
            flushProse()
            blocks += MarkdownBlock.Numbered(
                numbered.groupValues[1],
                numbered.groupValues[2].trim()
            )
            index++
            continue
        }

        val quote = QUOTE.find(line)
        if (quote != null) {
            flushProse()
            blocks += MarkdownBlock.Quote(quote.groupValues[1].trim())
            index++
            continue
        }

        if (trimmed.isEmpty()) {
            flushProse()
        } else {
            prose.appendLine(line)
        }
        index++
    }

    if (inCode) blocks += MarkdownBlock.Code(language, code.toString().trimEnd())
    flushProse()
    return blocks.ifEmpty { listOf(MarkdownBlock.Prose(text)) }
}

private fun tableCells(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|")
        .split('|')
        .map { it.trim() }

private fun isTableSeparator(line: String): Boolean {
    val cells = tableCells(line)
    return cells.size >= 2 && cells.all { it.matches(Regex(":?-{2,}:?")) }
}

private val HEADING = Regex("^(#{1,6})\\s+(.+)$")
private val BULLET = Regex("^\\s*[-*+]\\s+(.+)$")
private val NUMBERED = Regex("^\\s*(\\d+)[.)]\\s+(.+)$")
private val QUOTE = Regex("^\\s*>\\s?(.*)$")
private val HR = Regex("^([-*_])\\1{2,}$")

@Composable
internal fun markdownAnnotatedString(
    text: String,
    inlineCodeColor: Color,
    linkColor: Color = inlineCodeColor
): AnnotatedString = remember(text, inlineCodeColor, linkColor) {
    buildAnnotatedString {
        var index = 0
        while (index < text.length) {
            when {
                text.startsWith("**", index) -> {
                    val end = text.indexOf("**", index + 2)
                    if (end > index) {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(text.substring(index + 2, end))
                        }
                        index = end + 2
                    } else {
                        append("**")
                        index += 2
                    }
                }

                text.startsWith("~~", index) -> {
                    val end = text.indexOf("~~", index + 2)
                    if (end > index) {
                        withStyle(
                            SpanStyle(textDecoration = TextDecoration.LineThrough)
                        ) {
                            append(text.substring(index + 2, end))
                        }
                        index = end + 2
                    } else {
                        append("~~")
                        index += 2
                    }
                }

                text[index] == '`' -> {
                    val end = text.indexOf('`', index + 1)
                    if (end > index) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                color = inlineCodeColor
                            )
                        ) {
                            append(text.substring(index + 1, end))
                        }
                        index = end + 1
                    } else {
                        append('`')
                        index++
                    }
                }

                text[index] == '[' -> {
                    val labelEnd = text.indexOf("](", index + 1)
                    val urlEnd = if (labelEnd > index) text.indexOf(')', labelEnd + 2) else -1
                    if (labelEnd > index && urlEnd > labelEnd) {
                        withStyle(
                            SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline
                            )
                        ) {
                            append(text.substring(index + 1, labelEnd))
                        }
                        index = urlEnd + 1
                    } else {
                        append('[')
                        index++
                    }
                }

                text[index] == '*' && (index + 1 >= text.length || text[index + 1] != '*') -> {
                    val end = text.indexOf('*', index + 1)
                    if (end > index + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(index + 1, end))
                        }
                        index = end + 1
                    } else {
                        append('*')
                        index++
                    }
                }

                else -> {
                    append(text[index])
                    index++
                }
            }
        }
    }
}
