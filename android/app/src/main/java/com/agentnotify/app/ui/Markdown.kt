package com.agentnotify.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentnotify.app.ui.markdown.ColumnAlignment
import com.agentnotify.app.ui.markdown.MarkdownBlock
import com.agentnotify.app.ui.markdown.MarkdownInline
import com.agentnotify.app.ui.markdown.MarkdownListItem
import com.agentnotify.app.ui.markdown.MarkdownParser
import com.agentnotify.app.ui.markdown.isOpenableUri

private val BlockSpacing = 5.dp
private val BodySize = 15.sp
private val BodyLineHeight = 21.sp
private val CodeSize = 13.sp
private val CodeLineHeight = 18.sp
private val MaxTableColumnWidth = 200.dp
private val TableCellHorizontalPadding = 8.dp
private val TableCellVerticalPadding = 6.dp

/**
 * Renders a Markdown message body.
 *
 * Dialect and known limitations are documented on [MarkdownParser]. Links are only
 * made tappable for schemes accepted by [isOpenableUri]; images are shown as tappable
 * labels because the app does not bundle a remote image loader.
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    MarkdownBlocks(blocks, rememberMarkdownStyle(), modifier)
}

private class MarkdownStyle(
    val link: SpanStyle,
    val code: SpanStyle,
    val codeBackground: Color,
    val quoteBar: Color,
    val tableBorder: Color,
    val tableHeaderBackground: Color,
    val checkboxTint: Color,
)

@Composable
private fun rememberMarkdownStyle(): MarkdownStyle {
    val colors = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    return remember(colors, dark) {
        // Light theme keeps the original black tints; a black tint is invisible on a dark surface.
        val codeTint = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f)
        val codeSpanTint = if (dark) Color.White.copy(alpha = 0.13f) else Color.Black.copy(alpha = 0.08f)
        MarkdownStyle(
            link = SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline),
            code = SpanStyle(fontFamily = FontFamily.Monospace, background = codeSpanTint),
            codeBackground = codeTint,
            quoteBar = colors.primary.copy(alpha = 0.45f),
            tableBorder = colors.outlineVariant,
            tableHeaderBackground = colors.surfaceVariant.copy(alpha = 0.55f),
            checkboxTint = colors.primary,
        )
    }
}

@Composable
private fun MarkdownBlocks(
    blocks: List<MarkdownBlock>,
    style: MarkdownStyle,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(BlockSpacing)) {
        blocks.forEach { BlockView(it, style) }
    }
}

@Composable
private fun BlockView(block: MarkdownBlock, style: MarkdownStyle) {
    when (block) {
        is MarkdownBlock.Heading -> {
            val size = (22 - block.level * 2).sp
            Text(
                text = block.content.toAnnotatedString(style),
                fontSize = size,
                lineHeight = size * 1.35f,
                fontWeight = FontWeight.Bold,
            )
        }
        is MarkdownBlock.Paragraph -> Text(
            text = block.content.toAnnotatedString(style),
            fontSize = BodySize,
            lineHeight = BodyLineHeight,
        )
        is MarkdownBlock.CodeBlock -> CodeBlockView(block, style)
        is MarkdownBlock.BlockQuote -> BlockQuoteView(block, style)
        is MarkdownBlock.ListBlock -> ListBlockView(block, style)
        is MarkdownBlock.Table -> TableView(block, style)
        MarkdownBlock.ThematicBreak -> HorizontalDivider(Modifier.padding(vertical = 3.dp))
    }
}

@Composable
private fun CodeBlockView(block: MarkdownBlock.CodeBlock, style: MarkdownStyle) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(style.codeBackground)
            .horizontalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        Text(
            text = block.code,
            fontFamily = FontFamily.Monospace,
            fontSize = CodeSize,
            lineHeight = CodeLineHeight,
            softWrap = false,
        )
    }
}

@Composable
private fun BlockQuoteView(block: MarkdownBlock.BlockQuote, style: MarkdownStyle) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(style.quoteBar),
        )
        Spacer(Modifier.width(10.dp))
        MarkdownBlocks(block.children, style, Modifier.weight(1f))
    }
}

@Composable
private fun ListBlockView(block: MarkdownBlock.ListBlock, style: MarkdownStyle) {
    Column(verticalArrangement = Arrangement.spacedBy(if (block.tight) BlockSpacing else BlockSpacing * 2)) {
        block.items.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth()) {
                ListItemMarker(block, item, index, style)
                MarkdownBlocks(item.children, style, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ListItemMarker(
    block: MarkdownBlock.ListBlock,
    item: MarkdownListItem,
    index: Int,
    style: MarkdownStyle,
) {
    val checked = item.checked
    Box(
        modifier = Modifier.width(if (block.ordered) 26.dp else 18.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        when {
            checked != null -> Icon(
                imageVector = if (checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                contentDescription = if (checked) "Completed task" else "Incomplete task",
                tint = style.checkboxTint,
                modifier = Modifier.padding(top = 3.dp).size(15.dp),
            )
            block.ordered -> Text(
                text = "${block.start + index}.",
                fontSize = BodySize,
                lineHeight = BodyLineHeight,
            )
            else -> Text(text = "•", fontSize = BodySize, lineHeight = BodyLineHeight)
        }
    }
}

@Composable
private fun TableView(table: MarkdownBlock.Table, style: MarkdownStyle) {
    // Measure with the exact style Text will resolve, otherwise inherited metrics such as
    // the Material3 bodyLarge letter spacing make columns a little too narrow and cells wrap.
    val bodyStyle = LocalTextStyle.current.merge(TextStyle(fontSize = BodySize, lineHeight = BodyLineHeight))
    val headerStyle = bodyStyle.copy(fontWeight = FontWeight.SemiBold)
    val header = remember(table, style) { table.header.map { it.toAnnotatedString(style) } }
    val rows = remember(table, style) { table.rows.map { row -> row.map { it.toAnnotatedString(style) } } }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widths = remember(header, rows, density, bodyStyle) {
        val padding = with(density) { (TableCellHorizontalPadding * 2 + 1.dp).roundToPx() }
        val maximum = with(density) { MaxTableColumnWidth.roundToPx() }
        List(table.alignments.size) { column ->
            val natural = maxOf(
                measurer.measure(
                    text = header.getOrElse(column) { AnnotatedString("") },
                    style = headerStyle,
                    softWrap = false,
                    constraints = Constraints(),
                ).size.width,
                rows.maxOfOrNull { row ->
                    measurer.measure(
                        text = row.getOrElse(column) { AnnotatedString("") },
                        style = bodyStyle,
                        softWrap = false,
                        constraints = Constraints(),
                    ).size.width
                } ?: 0,
            )
            with(density) { (natural + padding).coerceAtMost(maximum).toDp() }
        }
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, style.tableBorder, RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState()),
    ) {
        Column {
            TableRowView(header, widths, table.alignments, style, header = true)
            rows.forEach { row ->
                HorizontalDivider(color = style.tableBorder)
                TableRowView(row, widths, table.alignments, style, header = false)
            }
        }
    }
}

@Composable
private fun TableRowView(
    cells: List<AnnotatedString>,
    widths: List<Dp>,
    alignments: List<ColumnAlignment>,
    style: MarkdownStyle,
    header: Boolean,
) {
    Row(
        Modifier
            .height(IntrinsicSize.Min)
            .background(if (header) style.tableHeaderBackground else Color.Transparent),
    ) {
        widths.forEachIndexed { column, width ->
            if (column > 0) VerticalDivider(color = style.tableBorder, modifier = Modifier.fillMaxHeight())
            Text(
                text = cells.getOrElse(column) { AnnotatedString("") },
                modifier = Modifier
                    .width(width)
                    .padding(horizontal = TableCellHorizontalPadding, vertical = TableCellVerticalPadding),
                fontSize = BodySize,
                lineHeight = BodyLineHeight,
                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = alignments.getOrElse(column) { ColumnAlignment.DEFAULT }.toTextAlign(),
            )
        }
    }
}

private fun ColumnAlignment.toTextAlign(): TextAlign = when (this) {
    ColumnAlignment.CENTER -> TextAlign.Center
    ColumnAlignment.RIGHT -> TextAlign.End
    else -> TextAlign.Start
}

private fun List<MarkdownInline>.toAnnotatedString(style: MarkdownStyle): AnnotatedString =
    buildAnnotatedString { appendInlines(this@toAnnotatedString, style) }

private fun AnnotatedString.Builder.appendInlines(nodes: List<MarkdownInline>, style: MarkdownStyle) {
    nodes.forEach { node ->
        when (node) {
            is MarkdownInline.Text -> append(node.text)
            is MarkdownInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInlines(node.children, style)
            }
            is MarkdownInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInlines(node.children, style)
            }
            is MarkdownInline.Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendInlines(node.children, style)
            }
            is MarkdownInline.CodeSpan -> withStyle(style.code) { append(node.code) }
            is MarkdownInline.Link ->
                if (isOpenableUri(node.destination)) {
                    withLink(LinkAnnotation.Url(node.destination, TextLinkStyles(style.link))) {
                        appendInlines(node.children, style)
                    }
                } else {
                    appendInlines(node.children, style)
                }
            is MarkdownInline.Image -> {
                val label = node.alt.ifBlank { imageLabel(node.destination) }
                if (isOpenableUri(node.destination)) {
                    withLink(LinkAnnotation.Url(node.destination, TextLinkStyles(style.link))) { append(label) }
                } else {
                    append(label)
                }
            }
            MarkdownInline.SoftBreak -> append(' ')
            MarkdownInline.HardBreak -> append('\n')
        }
    }
}

private fun imageLabel(destination: String): String =
    destination.substringBefore('?').substringAfterLast('/').ifBlank { "image" }
