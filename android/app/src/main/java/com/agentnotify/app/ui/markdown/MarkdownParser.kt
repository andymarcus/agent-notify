package com.agentnotify.app.ui.markdown

/**
 * Markdown parser for message bodies.
 *
 * Dialect: CommonMark 0.31.2 block and inline structure, plus the GitHub Flavored
 * Markdown extensions for tables, strikethrough, task list items and extended
 * (bare `http(s)://` / `www.`) autolinks.
 *
 * Deliberately not supported:
 *  - raw HTML blocks and inline HTML (rendered as literal text)
 *  - HTML entity references (`&amp;` stays literal)
 *  - multi-line link reference definitions, and definitions nested inside
 *    block quotes or list items; definitions must sit at the top level of the
 *    document, one per line
 *  - footnotes, front matter and heading anchors
 *  - bare email autolinks (`<user@host>` works, `user@host` does not)
 *
 * The parser is pure Kotlin with no Android dependencies so it can be unit tested
 * on the JVM; see [com.agentnotify.app.ui.MarkdownText] for the Compose renderer.
 */
object MarkdownParser {

    fun parse(source: String): List<MarkdownBlock> {
        val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
        val expanded = normalized.split("\n").map(::expandTabs)
        val definitions = mutableMapOf<String, LinkDefinition>()
        val lines = stripLinkDefinitions(expanded, definitions)
        return BlockParser(InlineParser(definitions)).parse(lines)
    }
}

internal data class LinkDefinition(val destination: String, val title: String?)

// ---------------------------------------------------------------------------
// Shared line helpers
// ---------------------------------------------------------------------------

private const val TAB_WIDTH = 4

internal fun expandTabs(line: String): String {
    if (!line.contains('\t')) return line
    val out = StringBuilder(line.length + TAB_WIDTH)
    for (c in line) {
        if (c == '\t') {
            do out.append(' ') while (out.length % TAB_WIDTH != 0)
        } else {
            out.append(c)
        }
    }
    return out.toString()
}

private fun indentWidth(line: String): Int = line.takeWhile { it == ' ' }.length

private fun stripIndent(line: String, amount: Int): String {
    var i = 0
    while (i < amount && i < line.length && line[i] == ' ') i++
    return line.substring(i)
}

private fun fenceMarker(line: String): String? {
    if (indentWidth(line) >= 4) return null
    val trimmed = line.trimStart()
    val ch = trimmed.firstOrNull() ?: return null
    if (ch != '`' && ch != '~') return null
    val run = trimmed.takeWhile { it == ch }
    if (run.length < 3) return null
    // A backtick info string may not contain further backticks.
    if (ch == '`' && trimmed.drop(run.length).contains('`')) return null
    return run
}

private fun closesFence(fence: String, line: String): Boolean {
    if (indentWidth(line) >= 4) return false
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed[0] != fence[0]) return false
    val run = trimmed.takeWhile { it == fence[0] }
    return run.length >= fence.length && trimmed.length == run.length
}

private val ATX_HEADING = Regex(""" {0,3}(#{1,6})( .*|)""")
private val THEMATIC_BREAK = Regex(""" {0,3}(?:(?:\* *){3,}|(?:- *){3,}|(?:_ *){3,})""")
private val SETEXT_UNDERLINE = Regex(""" {0,3}(=+|-+) *""")
private val LIST_MARKER = Regex("""( {0,3})(?:([-+*])|(\d{1,9})([.)]))( *)(.*)""")
private val DELIMITER_CELL = Regex(""":?-+:?""")
private val TASK_MARKER = Regex("""\[([ xX])] +""")
private val WHITESPACE_RUN = Regex("""\s+""")

private val LINK_DEFINITION = Regex(
    """ {0,3}\[((?:[^\[\]\\]|\\.)+)]: *(<[^<>]*>|[^ ]+)(?: +("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|\((?:[^)\\]|\\.)*\)))? *""",
)

private fun isAtxHeading(line: String): Boolean = ATX_HEADING.matchEntire(line) != null

private fun isThematicBreak(line: String): Boolean = THEMATIC_BREAK.matchEntire(line) != null

private fun isBlockQuote(line: String): Boolean =
    indentWidth(line) < 4 && line.trimStart().startsWith(">")

private fun setextLevel(line: String): Int? {
    val match = SETEXT_UNDERLINE.matchEntire(line) ?: return null
    return if (match.groupValues[1][0] == '=') 1 else 2
}

internal data class ListMarkerInfo(
    val indent: Int,
    val ordered: Boolean,
    val start: Int,
    val delimiter: Char,
    val contentIndent: Int,
    val hasContent: Boolean,
)

private fun listMarker(line: String): ListMarkerInfo? {
    val match = LIST_MARKER.matchEntire(line) ?: return null
    val indent = match.groupValues[1].length
    val bullet = match.groupValues[2]
    val number = match.groupValues[3]
    val spaces = match.groupValues[5]
    val rest = match.groupValues[6]
    // "-foo" and "1.foo" are paragraphs, not list items.
    if (spaces.isEmpty() && rest.isNotEmpty()) return null
    val markerWidth = if (bullet.isNotEmpty()) 1 else number.length + 1
    val gap = if (rest.isEmpty() || spaces.length > 4) 1 else spaces.length
    return ListMarkerInfo(
        indent = indent,
        ordered = bullet.isEmpty(),
        start = number.toIntOrNull() ?: 0,
        delimiter = if (bullet.isNotEmpty()) bullet[0] else match.groupValues[4][0],
        contentIndent = indent + markerWidth + gap,
        hasContent = rest.isNotBlank(),
    )
}

private fun sameListType(a: ListMarkerInfo, b: ListMarkerInfo): Boolean =
    a.ordered == b.ordered && a.delimiter == b.delimiter

// ---------------------------------------------------------------------------
// GFM tables
// ---------------------------------------------------------------------------

internal fun splitTableRow(line: String): List<String> {
    var body = line.trim()
    if (body.startsWith("|")) body = body.substring(1)
    if (body.endsWith("|") && !body.endsWith("\\|")) body = body.dropLast(1)
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var i = 0
    while (i < body.length) {
        val c = body[i]
        when {
            c == '\\' && i + 1 < body.length && body[i + 1] == '|' -> {
                cell.append('|')
                i += 2
            }
            c == '\\' && i + 1 < body.length -> {
                cell.append(c).append(body[i + 1])
                i += 2
            }
            c == '|' -> {
                cells.add(cell.toString().trim())
                cell.clear()
                i++
            }
            else -> {
                cell.append(c)
                i++
            }
        }
    }
    cells.add(cell.toString().trim())
    return cells
}

private fun isDelimiterRow(line: String): Boolean {
    if (indentWidth(line) >= 4) return false
    val trimmed = line.trim()
    if (!trimmed.contains('-')) return false
    if (trimmed.any { it != '|' && it != '-' && it != ':' && it != ' ' }) return false
    val cells = splitTableRow(line)
    return cells.isNotEmpty() && cells.all { DELIMITER_CELL.matchEntire(it) != null }
}

private fun isTableStart(lines: List<String>, index: Int): Boolean {
    val header = lines[index]
    if (indentWidth(header) >= 4 || !header.contains('|')) return false
    if (index + 1 >= lines.size || !isDelimiterRow(lines[index + 1])) return false
    return splitTableRow(header).size == splitTableRow(lines[index + 1]).size
}

private fun columnAlignments(line: String): List<ColumnAlignment> = splitTableRow(line).map { cell ->
    val left = cell.startsWith(":")
    val right = cell.endsWith(":")
    when {
        left && right -> ColumnAlignment.CENTER
        left -> ColumnAlignment.LEFT
        right -> ColumnAlignment.RIGHT
        else -> ColumnAlignment.DEFAULT
    }
}

// ---------------------------------------------------------------------------
// Link reference definitions
// ---------------------------------------------------------------------------

internal fun normalizeLabel(label: String): String =
    label.trim().replace(WHITESPACE_RUN, " ").lowercase()

private fun unwrapDestination(raw: String): String {
    val trimmed = raw.trim()
    val inner = if (trimmed.startsWith("<") && trimmed.endsWith(">")) {
        trimmed.substring(1, trimmed.length - 1)
    } else {
        trimmed
    }
    return unescapePunctuation(inner)
}

private fun unquote(raw: String): String = unescapePunctuation(raw.substring(1, raw.length - 1))

internal fun unescapePunctuation(value: String): String {
    if (!value.contains('\\')) return value
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '\\' && i + 1 < value.length && isMarkdownPunctuation(value[i + 1])) {
            out.append(value[i + 1])
            i += 2
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

/**
 * Removes top level link reference definitions and records them, so that
 * `[text][label]` resolves regardless of where the definition appears.
 * Definitions are only recognised at a block boundary and never inside a fence.
 */
private fun stripLinkDefinitions(
    lines: List<String>,
    into: MutableMap<String, LinkDefinition>,
): List<String> {
    val kept = ArrayList<String>(lines.size)
    var fence: String? = null
    var atBlockStart = true
    for (line in lines) {
        val marker = fenceMarker(line)
        if (fence != null) {
            if (marker != null && closesFence(fence, line)) fence = null
            kept.add(line)
            atBlockStart = false
            continue
        }
        if (marker != null) {
            fence = marker
            kept.add(line)
            atBlockStart = false
            continue
        }
        if (atBlockStart) {
            val match = LINK_DEFINITION.matchEntire(line)
            if (match != null) {
                val label = normalizeLabel(match.groupValues[1])
                if (label.isNotEmpty() && label !in into) {
                    into[label] = LinkDefinition(
                        destination = unwrapDestination(match.groupValues[2]),
                        title = match.groupValues[3].takeIf { it.isNotEmpty() }?.let(::unquote),
                    )
                }
                continue
            }
        }
        kept.add(line)
        atBlockStart = line.isBlank()
    }
    return kept
}

// ---------------------------------------------------------------------------
// Block parsing
// ---------------------------------------------------------------------------

private class BlockParser(private val inlines: InlineParser) {

    fun parse(lines: List<String>): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            i = when {
                line.isBlank() -> i + 1
                fenceMarker(line) != null -> readFencedCode(lines, i, blocks)
                isAtxHeading(line) -> readAtxHeading(lines, i, blocks)
                isThematicBreak(line) -> {
                    blocks.add(MarkdownBlock.ThematicBreak)
                    i + 1
                }
                isBlockQuote(line) -> readBlockQuote(lines, i, blocks)
                isTableStart(lines, i) -> readTable(lines, i, blocks)
                listMarker(line) != null -> readList(lines, i, blocks)
                indentWidth(line) >= 4 -> readIndentedCode(lines, i, blocks)
                else -> readParagraph(lines, i, blocks)
            }
        }
        return blocks
    }

    private fun readAtxHeading(lines: List<String>, start: Int, blocks: MutableList<MarkdownBlock>): Int {
        val match = ATX_HEADING.matchEntire(lines[start])!!
        val level = match.groupValues[1].length
        var text = match.groupValues[2].trim()
        val closing = Regex("""(.*?) +#+""").matchEntire(text)
        text = when {
            closing != null -> closing.groupValues[1].trim()
            text.isNotEmpty() && text.all { it == '#' } -> ""
            else -> text
        }
        blocks.add(MarkdownBlock.Heading(level, inlines.parse(text)))
        return start + 1
    }

    private fun readFencedCode(lines: List<String>, start: Int, blocks: MutableList<MarkdownBlock>): Int {
        val fence = fenceMarker(lines[start])!!
        val indent = indentWidth(lines[start])
        val info = lines[start].trimStart().drop(fence.length).trim()
        val body = mutableListOf<String>()
        var i = start + 1
        while (i < lines.size && !closesFence(fence, lines[i])) {
            body.add(stripIndent(lines[i], indent))
            i++
        }
        if (i < lines.size) i++
        blocks.add(
            MarkdownBlock.CodeBlock(
                language = info.takeWhile { !it.isWhitespace() }.takeIf { it.isNotEmpty() },
                code = body.joinToString("\n"),
            ),
        )
        return i
    }

    private fun readIndentedCode(lines: List<String>, start: Int, blocks: MutableList<MarkdownBlock>): Int {
        val body = mutableListOf<String>()
        var i = start
        var lastContent = start
        while (i < lines.size && (lines[i].isBlank() || indentWidth(lines[i]) >= 4)) {
            body.add(if (lines[i].isBlank()) "" else stripIndent(lines[i], 4))
            if (lines[i].isNotBlank()) lastContent = i
            i++
        }
        val code = body.subList(0, lastContent - start + 1).joinToString("\n")
        blocks.add(MarkdownBlock.CodeBlock(null, code))
        return lastContent + 1
    }

    private fun readBlockQuote(lines: List<String>, start: Int, blocks: MutableList<MarkdownBlock>): Int {
        val inner = mutableListOf<String>()
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            when {
                isBlockQuote(line) -> {
                    val stripped = line.trimStart().drop(1)
                    inner.add(if (stripped.startsWith(" ")) stripped.drop(1) else stripped)
                    i++
                }
                // Lazy continuation of a paragraph inside the quote.
                line.isNotBlank() && inner.lastOrNull()?.isNotBlank() == true && !startsNewBlock(lines, i) -> {
                    inner.add(line.trimStart())
                    i++
                }
                else -> return finishQuote(inner, blocks, i)
            }
        }
        return finishQuote(inner, blocks, i)
    }

    private fun finishQuote(inner: List<String>, blocks: MutableList<MarkdownBlock>, next: Int): Int {
        blocks.add(MarkdownBlock.BlockQuote(parse(inner)))
        return next
    }

    private fun readTable(lines: List<String>, start: Int, blocks: MutableList<MarkdownBlock>): Int {
        val headerCells = splitTableRow(lines[start])
        val alignments = columnAlignments(lines[start + 1])
        val columns = headerCells.size
        val rows = mutableListOf<List<List<MarkdownInline>>>()
        var i = start + 2
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank() || !line.contains('|') || indentWidth(line) >= 4) break
            if (fenceMarker(line) != null || isAtxHeading(line) || isThematicBreak(line)) break
            val cells = splitTableRow(line)
            rows.add(List(columns) { column -> inlines.parse(cells.getOrElse(column) { "" }) })
            i++
        }
        blocks.add(
            MarkdownBlock.Table(
                header = headerCells.map(inlines::parse),
                rows = rows,
                alignments = List(columns) { alignments.getOrElse(it) { ColumnAlignment.DEFAULT } },
            ),
        )
        return i
    }

    private fun readList(lines: List<String>, start: Int, blocks: MutableList<MarkdownBlock>): Int {
        val first = listMarker(lines[start])!!
        val chunks = mutableListOf<MutableList<String>>()
        var contentIndent = first.contentIndent
        var sawBlank = false
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                val nextIndex = (i + 1 until lines.size).firstOrNull { lines[it].isNotBlank() }
                if (nextIndex == null) {
                    i++
                    break
                }
                val next = lines[nextIndex]
                val nextMarker = listMarker(next)
                val continues = (nextMarker != null && nextMarker.indent < contentIndent && sameListType(first, nextMarker)) ||
                    indentWidth(next) >= contentIndent
                if (!continues) {
                    i++
                    break
                }
                sawBlank = true
                chunks.lastOrNull()?.add("")
                i++
                continue
            }
            val marker = listMarker(line)
            if (marker != null && (chunks.isEmpty() || marker.indent < contentIndent)) {
                if (chunks.isNotEmpty() && !sameListType(first, marker)) break
                contentIndent = marker.contentIndent
                chunks.add(mutableListOf(line.substring(minOf(marker.contentIndent, line.length))))
                i++
                continue
            }
            if (chunks.isNotEmpty() && indentWidth(line) >= contentIndent) {
                chunks.last().add(stripIndent(line, contentIndent))
                i++
                continue
            }
            if (chunks.isNotEmpty() && chunks.last().lastOrNull()?.isNotBlank() == true && !startsNewBlock(lines, i)) {
                chunks.last().add(line.trimStart())
                i++
                continue
            }
            break
        }

        val items = chunks.map { chunk ->
            while (chunk.isNotEmpty() && chunk.last().isBlank()) chunk.removeAt(chunk.size - 1)
            var checked: Boolean? = null
            if (chunk.isNotEmpty()) {
                val task = TASK_MARKER.matchAt(chunk[0], 0)
                if (task != null) {
                    checked = task.groupValues[1].lowercase() == "x"
                    chunk[0] = chunk[0].substring(task.value.length)
                }
            }
            MarkdownListItem(checked, parse(chunk))
        }
        blocks.add(
            MarkdownBlock.ListBlock(
                ordered = first.ordered,
                start = if (first.ordered) first.start else 0,
                tight = !sawBlank && items.all { it.children.size <= 1 },
                items = items,
            ),
        )
        return i
    }

    private fun readParagraph(lines: List<String>, start: Int, blocks: MutableList<MarkdownBlock>): Int {
        val buffer = mutableListOf(lines[start].trimStart())
        var i = start + 1
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) break
            if (isTableStart(lines, i)) break
            val setext = setextLevel(line)
            if (setext != null) {
                blocks.add(MarkdownBlock.Heading(setext, inlines.parse(buffer.joinToString("\n"))))
                return i + 1
            }
            if (startsNewBlock(lines, i)) break
            buffer.add(line.trimStart())
            i++
        }
        blocks.add(MarkdownBlock.Paragraph(inlines.parse(buffer.joinToString("\n"))))
        return i
    }

    /** Whether the line at [index] starts a block that can interrupt a paragraph. */
    private fun startsNewBlock(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        if (line.isBlank()) return true
        if (fenceMarker(line) != null) return true
        if (isAtxHeading(line)) return true
        if (isThematicBreak(line)) return true
        if (isBlockQuote(line)) return true
        if (isTableStart(lines, index)) return true
        val marker = listMarker(line)
        return marker != null && marker.hasContent && (!marker.ordered || marker.start == 1)
    }
}
