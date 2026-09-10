package com.agentnotify.app.ui.markdown

/**
 * Inline (span level) parser: code spans, links, images, autolinks, emphasis,
 * strong emphasis, strikethrough, backslash escapes and line breaks.
 *
 * Emphasis uses the CommonMark delimiter run algorithm: left/right flanking
 * classification, the extra `_` intraword restriction, and the "rule of three"
 * for adjacent runs.
 */
internal class InlineParser(private val definitions: Map<String, LinkDefinition>) {

    fun parse(source: String): List<MarkdownInline> {
        if (source.isEmpty()) return emptyList()
        val tokens = tokenize(source)
        processEmphasis(tokens)
        return build(tokens)
    }

    // -----------------------------------------------------------------------
    // Tokenizer
    // -----------------------------------------------------------------------

    private enum class WrapKind { EMPHASIS, STRONG, STRIKETHROUGH }

    private sealed class Token {
        class Leaf(val node: MarkdownInline) : Token()

        class Delimiter(
            val char: Char,
            var count: Int,
            val original: Int,
            val canOpen: Boolean,
            val canClose: Boolean,
        ) : Token()

        class Wrap(val kind: WrapKind, val children: MutableList<Token>) : Token()
    }

    private fun tokenize(source: String): MutableList<Token> {
        val tokens = mutableListOf<Token>()
        val text = StringBuilder()

        fun flush() {
            if (text.isNotEmpty()) {
                tokens.add(Token.Leaf(MarkdownInline.Text(text.toString())))
                text.clear()
            }
        }

        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c == '\\' && i + 1 < source.length && source[i + 1] == '\n' -> {
                    flush()
                    tokens.add(Token.Leaf(MarkdownInline.HardBreak))
                    i += 2
                }
                c == '\\' && i + 1 < source.length && isMarkdownPunctuation(source[i + 1]) -> {
                    text.append(source[i + 1])
                    i += 2
                }
                c == '\n' -> {
                    var spaces = 0
                    while (spaces < text.length && text[text.length - 1 - spaces] == ' ') spaces++
                    if (spaces > 0) text.setLength(text.length - spaces)
                    val hard = spaces >= 2
                    flush()
                    tokens.add(Token.Leaf(if (hard) MarkdownInline.HardBreak else MarkdownInline.SoftBreak))
                    i++
                }
                c == '`' -> {
                    val run = runLength(source, i, '`')
                    val close = findBacktickRun(source, i + run, run)
                    if (close < 0) {
                        text.append(source, i, i + run)
                        i += run
                    } else {
                        flush()
                        tokens.add(Token.Leaf(MarkdownInline.CodeSpan(normalizeCodeSpan(source.substring(i + run, close)))))
                        i = close + run
                    }
                }
                c == '<' -> {
                    val autolink = parseAngleAutolink(source, i)
                    if (autolink == null) {
                        text.append(c)
                        i++
                    } else {
                        flush()
                        tokens.add(Token.Leaf(autolink.first))
                        i = autolink.second
                    }
                }
                c == '!' && i + 1 < source.length && source[i + 1] == '[' -> {
                    val image = parseLinkOrImage(source, i, image = true)
                    if (image == null) {
                        text.append(c)
                        i++
                    } else {
                        flush()
                        tokens.add(Token.Leaf(image.first))
                        i = image.second
                    }
                }
                c == '[' -> {
                    val link = parseLinkOrImage(source, i, image = false)
                    if (link == null) {
                        text.append(c)
                        i++
                    } else {
                        flush()
                        tokens.add(Token.Leaf(link.first))
                        i = link.second
                    }
                }
                isBareAutolinkStart(source, i) -> {
                    val autolink = parseBareAutolink(source, i)
                    flush()
                    tokens.add(Token.Leaf(autolink.first))
                    i = autolink.second
                }
                c == '*' || c == '_' || c == '~' -> {
                    val run = runLength(source, i, c)
                    val before = if (i == 0) '\n' else source[i - 1]
                    val after = if (i + run >= source.length) '\n' else source[i + run]
                    flush()
                    tokens.add(delimiter(c, run, before, after))
                    i += run
                }
                else -> {
                    text.append(c)
                    i++
                }
            }
        }
        flush()
        return tokens
    }

    private fun delimiter(char: Char, run: Int, before: Char, after: Char): Token.Delimiter {
        val beforeWhitespace = before.isWhitespace()
        val afterWhitespace = after.isWhitespace()
        val beforePunctuation = isMarkdownPunctuation(before)
        val afterPunctuation = isMarkdownPunctuation(after)
        val leftFlanking = !afterWhitespace && (!afterPunctuation || beforeWhitespace || beforePunctuation)
        val rightFlanking = !beforeWhitespace && (!beforePunctuation || afterWhitespace || afterPunctuation)
        val canOpen: Boolean
        val canClose: Boolean
        if (char == '_') {
            canOpen = leftFlanking && (!rightFlanking || beforePunctuation)
            canClose = rightFlanking && (!leftFlanking || afterPunctuation)
        } else {
            canOpen = leftFlanking
            canClose = rightFlanking
        }
        return Token.Delimiter(char, run, run, canOpen, canClose)
    }

    // -----------------------------------------------------------------------
    // Emphasis
    // -----------------------------------------------------------------------

    private fun processEmphasis(tokens: MutableList<Token>) {
        var i = 0
        while (i < tokens.size) {
            val closer = tokens[i] as? Token.Delimiter
            if (closer == null || !closer.canClose || closer.count == 0) {
                i++
                continue
            }
            var openerIndex = -1
            var opener: Token.Delimiter? = null
            var j = i - 1
            while (j >= 0) {
                val candidate = tokens[j] as? Token.Delimiter
                if (candidate != null &&
                    candidate.count > 0 &&
                    candidate.canOpen &&
                    candidate.char == closer.char &&
                    ruleOfThree(candidate, closer)
                ) {
                    opener = candidate
                    openerIndex = j
                    break
                }
                j--
            }
            if (opener == null) {
                i++
                continue
            }
            val strikethrough = closer.char == '~'
            if (strikethrough && (opener.count < 2 || closer.count < 2)) {
                i++
                continue
            }
            val used = if (strikethrough || (opener.count >= 2 && closer.count >= 2)) 2 else 1
            val kind = when {
                strikethrough -> WrapKind.STRIKETHROUGH
                used == 2 -> WrapKind.STRONG
                else -> WrapKind.EMPHASIS
            }
            val children = mutableListOf<Token>()
            for (k in openerIndex + 1 until i) children.add(tokens[k])
            repeat(i - openerIndex - 1) { tokens.removeAt(openerIndex + 1) }
            opener.count -= used
            closer.count -= used
            var insertAt = openerIndex + 1
            if (opener.count == 0) {
                tokens.removeAt(openerIndex)
                insertAt = openerIndex
            }
            tokens.add(insertAt, Token.Wrap(kind, children))
            if (closer.count == 0) tokens.removeAt(insertAt + 1)
            i = insertAt + 1
        }
    }

    private fun ruleOfThree(opener: Token.Delimiter, closer: Token.Delimiter): Boolean {
        if (!closer.canOpen && !opener.canClose) return true
        if ((opener.original + closer.original) % 3 != 0) return true
        return opener.original % 3 == 0 && closer.original % 3 == 0
    }

    private fun build(tokens: List<Token>): List<MarkdownInline> {
        val nodes = mutableListOf<MarkdownInline>()
        for (token in tokens) {
            when (token) {
                is Token.Leaf -> nodes.add(token.node)
                is Token.Delimiter ->
                    if (token.count > 0) nodes.add(MarkdownInline.Text(token.char.toString().repeat(token.count)))
                is Token.Wrap -> {
                    val children = build(token.children)
                    nodes.add(
                        when (token.kind) {
                            WrapKind.EMPHASIS -> MarkdownInline.Emphasis(children)
                            WrapKind.STRONG -> MarkdownInline.Strong(children)
                            WrapKind.STRIKETHROUGH -> MarkdownInline.Strikethrough(children)
                        },
                    )
                }
            }
        }
        return mergeAdjacentText(nodes)
    }

    private fun mergeAdjacentText(nodes: List<MarkdownInline>): List<MarkdownInline> {
        val merged = mutableListOf<MarkdownInline>()
        for (node in nodes) {
            val previous = merged.lastOrNull()
            if (node is MarkdownInline.Text && previous is MarkdownInline.Text) {
                merged[merged.size - 1] = MarkdownInline.Text(previous.text + node.text)
            } else {
                merged.add(node)
            }
        }
        return merged
    }

    // -----------------------------------------------------------------------
    // Links, images and autolinks
    // -----------------------------------------------------------------------

    private class Destination(val destination: String, val title: String?, val end: Int)

    private fun parseLinkOrImage(source: String, start: Int, image: Boolean): Pair<MarkdownInline, Int>? {
        val openBracket = start + if (image) 1 else 0
        val labelEnd = matchingBracket(source, openBracket)
        if (labelEnd < 0) return null
        val label = source.substring(openBracket + 1, labelEnd)
        var i = labelEnd + 1
        val destination: String
        val title: String?
        if (i < source.length && source[i] == '(') {
            val inline = parseInlineDestination(source, i) ?: return null
            destination = inline.destination
            title = inline.title
            i = inline.end
        } else {
            var referenceLabel = label
            if (i < source.length && source[i] == '[') {
                val referenceEnd = matchingBracket(source, i)
                if (referenceEnd < 0) return null
                val explicit = source.substring(i + 1, referenceEnd)
                if (explicit.isNotBlank()) referenceLabel = explicit
                i = referenceEnd + 1
            }
            val definition = definitions[normalizeLabel(referenceLabel)] ?: return null
            destination = definition.destination
            title = definition.title
        }
        val node = if (image) {
            MarkdownInline.Image(destination, parse(label).plainText(), title)
        } else {
            MarkdownInline.Link(destination, title, parse(label))
        }
        return node to i
    }

    /** Index of the `]` matching the `[` at [open], or -1. Handles nesting, escapes and code spans. */
    private fun matchingBracket(source: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < source.length) {
            val c = source[i]
            when {
                c == '\\' && i + 1 < source.length -> i += 2
                c == '`' -> {
                    val run = runLength(source, i, '`')
                    val close = findBacktickRun(source, i + run, run)
                    i = if (close < 0) i + run else close + run
                }
                c == '[' -> {
                    depth++
                    i++
                }
                c == ']' -> {
                    depth--
                    if (depth == 0) return i
                    i++
                }
                else -> i++
            }
        }
        return -1
    }

    private fun parseInlineDestination(source: String, open: Int): Destination? {
        var i = open + 1
        while (i < source.length && (source[i] == ' ' || source[i] == '\n')) i++
        val destination = StringBuilder()
        if (i < source.length && source[i] == '<') {
            i++
            while (i < source.length && source[i] != '>') {
                if (source[i] == '\\' && i + 1 < source.length) {
                    destination.append(source[i + 1])
                    i += 2
                } else {
                    destination.append(source[i])
                    i++
                }
            }
            if (i >= source.length) return null
            i++
        } else {
            var depth = 0
            while (i < source.length) {
                val c = source[i]
                if (c == '\\' && i + 1 < source.length) {
                    destination.append(source[i + 1])
                    i += 2
                    continue
                }
                if (c.isWhitespace()) break
                if (c == '(') depth++
                if (c == ')') {
                    if (depth == 0) break
                    depth--
                }
                destination.append(c)
                i++
            }
        }
        while (i < source.length && (source[i] == ' ' || source[i] == '\n')) i++
        var title: String? = null
        if (i < source.length && (source[i] == '"' || source[i] == '\'' || source[i] == '(')) {
            val closing = if (source[i] == '(') ')' else source[i]
            val builder = StringBuilder()
            var j = i + 1
            while (j < source.length && source[j] != closing) {
                if (source[j] == '\\' && j + 1 < source.length) {
                    builder.append(source[j + 1])
                    j += 2
                } else {
                    builder.append(source[j])
                    j++
                }
            }
            if (j >= source.length) return null
            title = builder.toString()
            i = j + 1
        }
        while (i < source.length && (source[i] == ' ' || source[i] == '\n')) i++
        if (i >= source.length || source[i] != ')') return null
        return Destination(destination.toString(), title, i + 1)
    }

    private fun parseAngleAutolink(source: String, start: Int): Pair<MarkdownInline, Int>? {
        val end = source.indexOf('>', start + 1)
        if (end < 0) return null
        val content = source.substring(start + 1, end)
        if (content.isEmpty() || content.any { it.isWhitespace() || it == '<' }) return null
        val destination = when {
            ABSOLUTE_URI.matchEntire(content) != null -> content
            EMAIL.matchEntire(content) != null -> "mailto:" + content
            else -> return null
        }
        return MarkdownInline.Link(destination, null, listOf(MarkdownInline.Text(content))) to end + 1
    }

    private fun isBareAutolinkStart(source: String, i: Int): Boolean {
        if (i > 0) {
            val previous = source[i - 1]
            if (previous.isLetterOrDigit() || previous == '/' || previous == ':' || previous == '@') return false
        }
        val rest = source.substring(i)
        val prefix = BARE_AUTOLINK_PREFIXES.firstOrNull { rest.startsWith(it, ignoreCase = true) } ?: return false
        return rest.length > prefix.length && !rest[prefix.length].isWhitespace()
    }

    private fun parseBareAutolink(source: String, start: Int): Pair<MarkdownInline, Int> {
        var end = start
        while (end < source.length && !source[end].isWhitespace() && source[end] != '<') end++
        var text = source.substring(start, end)
        // Trailing punctuation is far more likely to be prose than part of the link.
        while (text.isNotEmpty()) {
            val last = text.last()
            if (last in ".,:;!?'\"") {
                text = text.dropLast(1)
                continue
            }
            if (last == ')' && text.count { it == ')' } > text.count { it == '(' }) {
                text = text.dropLast(1)
                continue
            }
            break
        }
        val destination = if (text.startsWith("www.", ignoreCase = true)) "http://" + text else text
        return MarkdownInline.Link(destination, null, listOf(MarkdownInline.Text(text))) to start + text.length
    }

    private companion object {
        val ABSOLUTE_URI = Regex("""[A-Za-z][A-Za-z0-9+.\-]*:[^ ]+""")
        val EMAIL = Regex("""[^ @]+@[^ @]+\.[^ @]+""")
        val BARE_AUTOLINK_PREFIXES = listOf("https://", "http://", "www.")
    }
}

// ---------------------------------------------------------------------------
// Inline helpers shared with the block parser
// ---------------------------------------------------------------------------

internal fun isMarkdownPunctuation(c: Char): Boolean = !c.isLetterOrDigit() && !c.isWhitespace()

internal fun runLength(source: String, start: Int, char: Char): Int {
    var i = start
    while (i < source.length && source[i] == char) i++
    return i - start
}

/** First index at or after [from] holding a backtick run of exactly [length] backticks. */
internal fun findBacktickRun(source: String, from: Int, length: Int): Int {
    var i = from
    while (i < source.length) {
        if (source[i] != '`') {
            i++
            continue
        }
        val run = runLength(source, i, '`')
        if (run == length) return i
        i += run
    }
    return -1
}

/** CommonMark code span normalisation: newlines become spaces and one padding space is removed. */
internal fun normalizeCodeSpan(raw: String): String {
    val singleLine = raw.replace('\n', ' ')
    return if (singleLine.length > 2 &&
        singleLine.startsWith(" ") &&
        singleLine.endsWith(" ") &&
        singleLine.isNotBlank()
    ) {
        singleLine.substring(1, singleLine.length - 1)
    } else {
        singleLine
    }
}
