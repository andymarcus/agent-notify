package com.agentnotify.app.ui.markdown

/** Column alignment declared by a GFM table delimiter row. */
enum class ColumnAlignment { DEFAULT, LEFT, CENTER, RIGHT }

/** Block level node of a parsed Markdown document. */
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val content: List<MarkdownInline>) : MarkdownBlock

    data class Paragraph(val content: List<MarkdownInline>) : MarkdownBlock

    /** Fenced or indented code. [language] is the fence info string, if any. */
    data class CodeBlock(val language: String?, val code: String) : MarkdownBlock

    data class BlockQuote(val children: List<MarkdownBlock>) : MarkdownBlock

    data class ListBlock(
        val ordered: Boolean,
        val start: Int,
        val tight: Boolean,
        val items: List<MarkdownListItem>,
    ) : MarkdownBlock

    data class Table(
        val header: List<List<MarkdownInline>>,
        val rows: List<List<List<MarkdownInline>>>,
        val alignments: List<ColumnAlignment>,
    ) : MarkdownBlock

    data object ThematicBreak : MarkdownBlock
}

/** One list item. [checked] is non-null only for GFM task list items. */
data class MarkdownListItem(val checked: Boolean?, val children: List<MarkdownBlock>)

/** Inline level node of a parsed Markdown document. */
sealed interface MarkdownInline {
    data class Text(val text: String) : MarkdownInline

    data class Emphasis(val children: List<MarkdownInline>) : MarkdownInline

    data class Strong(val children: List<MarkdownInline>) : MarkdownInline

    data class Strikethrough(val children: List<MarkdownInline>) : MarkdownInline

    data class CodeSpan(val code: String) : MarkdownInline

    data class Link(
        val destination: String,
        val title: String?,
        val children: List<MarkdownInline>,
    ) : MarkdownInline

    data class Image(val destination: String, val alt: String, val title: String?) : MarkdownInline

    data object SoftBreak : MarkdownInline

    data object HardBreak : MarkdownInline
}

/** Flattens inline content to plain text, used for image alt text and accessibility labels. */
fun List<MarkdownInline>.plainText(): String = joinToString("") { node ->
    when (node) {
        is MarkdownInline.Text -> node.text
        is MarkdownInline.CodeSpan -> node.code
        is MarkdownInline.Emphasis -> node.children.plainText()
        is MarkdownInline.Strong -> node.children.plainText()
        is MarkdownInline.Strikethrough -> node.children.plainText()
        is MarkdownInline.Link -> node.children.plainText()
        is MarkdownInline.Image -> node.alt
        MarkdownInline.SoftBreak -> " "
        MarkdownInline.HardBreak -> "\n"
    }
}

/**
 * True when a link destination uses a scheme the app is willing to hand to the system
 * URI handler. Everything else (relative paths, `javascript:`, `intent:`, custom schemes)
 * is rendered as inert text so a notification body cannot launch arbitrary intents.
 */
fun isOpenableUri(destination: String): Boolean {
    val trimmed = destination.trim()
    if (!trimmed.contains(':')) return false
    return trimmed.substringBefore(':').lowercase() in OPENABLE_SCHEMES
}

private val OPENABLE_SCHEMES = setOf("http", "https", "mailto", "tel")
