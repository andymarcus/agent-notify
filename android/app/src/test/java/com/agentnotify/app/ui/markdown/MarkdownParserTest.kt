package com.agentnotify.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    private fun parse(markdown: String): List<MarkdownBlock> = MarkdownParser.parse(markdown.trimIndent())

    private fun single(markdown: String): MarkdownBlock = parse(markdown).single()

    private fun paragraphText(markdown: String): String =
        (single(markdown) as MarkdownBlock.Paragraph).content.plainText()

    // -----------------------------------------------------------------------
    // Tables
    // -----------------------------------------------------------------------

    @Test
    fun `table parses header rows and alignments`() {
        val table = single(
            """
            | Name | Count | Owner |
            | :--- | ----: | :---: |
            | api  | 12    | ana   |
            | web  | 3     | ben   |
            """,
        ) as MarkdownBlock.Table

        assertEquals(listOf("Name", "Count", "Owner"), table.header.map { it.plainText() })
        assertEquals(
            listOf(ColumnAlignment.LEFT, ColumnAlignment.RIGHT, ColumnAlignment.CENTER),
            table.alignments,
        )
        assertEquals(2, table.rows.size)
        assertEquals(listOf("api", "12", "ana"), table.rows[0].map { it.plainText() })
        assertEquals(listOf("web", "3", "ben"), table.rows[1].map { it.plainText() })
    }

    @Test
    fun `table without outer pipes and with default alignment`() {
        val table = single(
            """
            Key | Value
            --- | ---
            a   | 1
            """,
        ) as MarkdownBlock.Table

        assertEquals(listOf(ColumnAlignment.DEFAULT, ColumnAlignment.DEFAULT), table.alignments)
        assertEquals(listOf("Key", "Value"), table.header.map { it.plainText() })
        assertEquals(listOf("a", "1"), table.rows.single().map { it.plainText() })
    }

    @Test
    fun `table rows are padded and truncated to the header width`() {
        val table = single(
            """
            | a | b | c |
            | - | - | - |
            | 1 |
            | 1 | 2 | 3 | 4 |
            """,
        ) as MarkdownBlock.Table

        assertEquals(3, table.rows[0].size)
        assertEquals(listOf("1", "", ""), table.rows[0].map { it.plainText() })
        assertEquals(listOf("1", "2", "3"), table.rows[1].map { it.plainText() })
    }

    @Test
    fun `escaped pipe stays inside a table cell`() {
        val table = single(
            """
            | pattern | note |
            | --- | --- |
            | a \| b | or |
            """,
        ) as MarkdownBlock.Table

        assertEquals(listOf("a | b", "or"), table.rows.single().map { it.plainText() })
    }

    @Test
    fun `table cells keep inline formatting including links and code`() {
        val table = single(
            """
            | item | link |
            | --- | --- |
            | `code` **bold** | [docs](https://example.com/docs) |
            """,
        ) as MarkdownBlock.Table

        val itemCell = table.rows.single()[0]
        assertEquals(MarkdownInline.CodeSpan("code"), itemCell[0])
        assertTrue(itemCell.any { it is MarkdownInline.Strong })

        val link = table.rows.single()[1].single() as MarkdownInline.Link
        assertEquals("https://example.com/docs", link.destination)
        assertEquals("docs", link.children.plainText())
    }

    @Test
    fun `delimiter row with a different column count is not a table`() {
        val blocks = parse(
            """
            | a | b |
            | --- |
            """,
        )

        assertTrue(blocks.none { it is MarkdownBlock.Table })
    }

    @Test
    fun `single column setext heading is not treated as a table`() {
        val heading = single(
            """
            Release notes
            ---
            """,
        ) as MarkdownBlock.Heading

        assertEquals(2, heading.level)
        assertEquals("Release notes", heading.content.plainText())
    }

    @Test
    fun `table interrupts a paragraph and a blank line ends the table`() {
        val blocks = parse(
            """
            Intro line
            | a | b |
            | - | - |
            | 1 | 2 |

            Outro line
            """,
        )

        assertEquals(3, blocks.size)
        assertEquals("Intro line", (blocks[0] as MarkdownBlock.Paragraph).content.plainText())
        assertEquals(1, (blocks[1] as MarkdownBlock.Table).rows.size)
        assertEquals("Outro line", (blocks[2] as MarkdownBlock.Paragraph).content.plainText())
    }

    @Test
    fun `pipes inside fenced code are not parsed as a table`() {
        val code = single(
            """
            ```
            | a | b |
            | - | - |
            ```
            """,
        ) as MarkdownBlock.CodeBlock

        assertEquals("| a | b |\n| - | - |", code.code)
    }

    // -----------------------------------------------------------------------
    // Links, autolinks and images
    // -----------------------------------------------------------------------

    @Test
    fun `inline link with title and nested emphasis`() {
        val link = (single("""[see *this*](https://example.com "Title")""") as MarkdownBlock.Paragraph)
            .content.single() as MarkdownInline.Link

        assertEquals("https://example.com", link.destination)
        assertEquals("Title", link.title)
        assertEquals("see this", link.children.plainText())
        assertTrue(link.children.any { it is MarkdownInline.Emphasis })
    }

    @Test
    fun `angle bracket destination allows spaces`() {
        val link = (single("[a](<https://example.com/a b>)") as MarkdownBlock.Paragraph)
            .content.single() as MarkdownInline.Link

        assertEquals("https://example.com/a b", link.destination)
    }

    @Test
    fun `destination keeps balanced parentheses`() {
        val link = (single("[wiki](https://example.com/a_(b))") as MarkdownBlock.Paragraph)
            .content.single() as MarkdownInline.Link

        assertEquals("https://example.com/a_(b)", link.destination)
    }

    @Test
    fun `reference links resolve full collapsed and shortcut forms`() {
        val blocks = parse(
            """
            [full][ref] [collapsed][] [shortcut]

            [ref]: https://example.com/ref "Ref"
            [collapsed]: https://example.com/collapsed
            [shortcut]: https://example.com/shortcut
            """,
        )

        val paragraph = blocks.single() as MarkdownBlock.Paragraph
        val links = paragraph.content.filterIsInstance<MarkdownInline.Link>()
        assertEquals(
            listOf(
                "https://example.com/ref",
                "https://example.com/collapsed",
                "https://example.com/shortcut",
            ),
            links.map { it.destination },
        )
        assertEquals("Ref", links[0].title)
        assertEquals(listOf("full", "collapsed", "shortcut"), links.map { it.children.plainText() })
    }

    @Test
    fun `reference definitions are matched case insensitively and may appear before use`() {
        val blocks = parse(
            """
            [Docs]: https://example.com/docs

            Read the [docs].
            """,
        )

        val link = (blocks.single() as MarkdownBlock.Paragraph)
            .content.filterIsInstance<MarkdownInline.Link>().single()
        assertEquals("https://example.com/docs", link.destination)
    }

    @Test
    fun `unresolved reference stays literal text`() {
        assertEquals("[missing][nope]", paragraphText("[missing][nope]"))
    }

    @Test
    fun `angle autolinks cover uris and email`() {
        val nodes = (single("<https://example.com> and <someone@example.com>") as MarkdownBlock.Paragraph).content
        val links = nodes.filterIsInstance<MarkdownInline.Link>()

        assertEquals("https://example.com", links[0].destination)
        assertEquals("mailto:someone@example.com", links[1].destination)
        assertEquals("someone@example.com", links[1].children.plainText())
    }

    @Test
    fun `bare autolinks drop trailing sentence punctuation`() {
        val nodes = (single("See https://example.com/page. Also www.example.org!") as MarkdownBlock.Paragraph).content
        val links = nodes.filterIsInstance<MarkdownInline.Link>()

        assertEquals("https://example.com/page", links[0].destination)
        assertEquals("http://www.example.org", links[1].destination)
        assertEquals("www.example.org", links[1].children.plainText())
        assertEquals("See https://example.com/page. Also www.example.org!", nodes.plainText())
    }

    @Test
    fun `autolink inside a longer word is not detected`() {
        assertTrue(
            (single("nohttps://example.com") as MarkdownBlock.Paragraph)
                .content.none { it is MarkdownInline.Link },
        )
    }

    @Test
    fun `only web schemes are openable`() {
        assertTrue(isOpenableUri("https://example.com"))
        assertTrue(isOpenableUri("HTTP://example.com"))
        assertTrue(isOpenableUri("mailto:a@b.com"))
        assertTrue(isOpenableUri("tel:+61400000000"))
        assertFalse(isOpenableUri("javascript:alert(1)"))
        assertFalse(isOpenableUri("intent://scan/#Intent;end"))
        assertFalse(isOpenableUri("/relative/path"))
        assertFalse(isOpenableUri(""))
    }

    @Test
    fun `images expose alt text and destination`() {
        val image = (single("""![Build chart](https://example.com/c.png "Chart")""") as MarkdownBlock.Paragraph)
            .content.single() as MarkdownInline.Image

        assertEquals("https://example.com/c.png", image.destination)
        assertEquals("Build chart", image.alt)
        assertEquals("Chart", image.title)
    }

    @Test
    fun `image inside a link keeps both nodes`() {
        val link = (single("[![logo](https://example.com/l.png)](https://example.com)") as MarkdownBlock.Paragraph)
            .content.single() as MarkdownInline.Link

        assertEquals("https://example.com", link.destination)
        assertEquals("logo", (link.children.single() as MarkdownInline.Image).alt)
    }

    @Test
    fun `unclosed link syntax stays literal`() {
        assertEquals("[not a link", paragraphText("[not a link"))
        assertEquals("[label] plain", paragraphText("[label] plain"))
    }

    // -----------------------------------------------------------------------
    // Headings, emphasis and escapes
    // -----------------------------------------------------------------------

    @Test
    fun `atx headings support all levels and closing hashes`() {
        val blocks = parse(
            """
            # One
            ###### Six
            ## Closed ##
            #NotAHeading
            """,
        )

        assertEquals(1, (blocks[0] as MarkdownBlock.Heading).level)
        assertEquals(6, (blocks[1] as MarkdownBlock.Heading).level)
        val closed = blocks[2] as MarkdownBlock.Heading
        assertEquals(2, closed.level)
        assertEquals("Closed", closed.content.plainText())
        assertEquals("#NotAHeading", (blocks[3] as MarkdownBlock.Paragraph).content.plainText())
    }

    @Test
    fun `setext heading level one`() {
        val heading = single(
            """
            Title
            ===
            """,
        ) as MarkdownBlock.Heading

        assertEquals(1, heading.level)
    }

    @Test
    fun `emphasis strong and combined delimiters`() {
        val nodes = (single("*a* **b** ***c*** _d_ __e__") as MarkdownBlock.Paragraph).content

        assertEquals("a", (nodes[0] as MarkdownInline.Emphasis).children.plainText())
        assertEquals("b", (nodes[2] as MarkdownInline.Strong).children.plainText())
        // CommonMark nests *** as emphasis wrapping strong emphasis.
        val combined = nodes[4] as MarkdownInline.Emphasis
        assertEquals("c", (combined.children.single() as MarkdownInline.Strong).children.plainText())
        assertEquals("d", (nodes[6] as MarkdownInline.Emphasis).children.plainText())
        assertEquals("e", (nodes[8] as MarkdownInline.Strong).children.plainText())
    }

    @Test
    fun `emphasis can nest and wrap other inline content`() {
        val strong = (single("**bold with *inner* and `code`**") as MarkdownBlock.Paragraph)
            .content.single() as MarkdownInline.Strong

        assertTrue(strong.children.any { it is MarkdownInline.Emphasis })
        assertTrue(strong.children.any { it is MarkdownInline.CodeSpan })
    }

    @Test
    fun `intraword underscores are literal but asterisks are not`() {
        assertEquals("snake_case_name", paragraphText("snake_case_name"))
        assertTrue(
            (single("mid*dle*word") as MarkdownBlock.Paragraph)
                .content.any { it is MarkdownInline.Emphasis },
        )
    }

    @Test
    fun `unmatched delimiters stay literal`() {
        assertEquals("2 * 3 * 4 = 24", paragraphText("2 * 3 * 4 = 24"))
        assertEquals("a ** b", paragraphText("a ** b"))
    }

    @Test
    fun `strikethrough uses double tildes only`() {
        val nodes = (single("~~gone~~ and ~kept~") as MarkdownBlock.Paragraph).content

        assertEquals("gone", (nodes[0] as MarkdownInline.Strikethrough).children.plainText())
        assertEquals("and ~kept~", (nodes[1] as MarkdownInline.Text).text.trimStart())
    }

    @Test
    fun `backslash escapes suppress markup`() {
        assertEquals("*not emphasis*", paragraphText("""\*not emphasis\*"""))
        assertEquals("[not a link](x)", paragraphText("""\[not a link](x)"""))
        assertEquals("a|b", paragraphText("""a\|b"""))
    }

    @Test
    fun `hard breaks from trailing spaces and backslash`() {
        val spaces = (single("line one  \nline two") as MarkdownBlock.Paragraph).content
        assertTrue(spaces.contains(MarkdownInline.HardBreak))
        assertEquals("line one\nline two", spaces.plainText())

        val backslash = (single("line one\\\nline two") as MarkdownBlock.Paragraph).content
        assertTrue(backslash.contains(MarkdownInline.HardBreak))
    }

    @Test
    fun `single newline inside a paragraph is a soft break`() {
        val nodes = (single("line one\nline two") as MarkdownBlock.Paragraph).content

        assertTrue(nodes.contains(MarkdownInline.SoftBreak))
        assertEquals("line one line two", nodes.plainText())
    }

    // -----------------------------------------------------------------------
    // Code
    // -----------------------------------------------------------------------

    @Test
    fun `fenced code keeps language and raw content`() {
        val code = single(
            """
            ```kotlin
            val a = 1
              val b = 2
            ```
            """,
        ) as MarkdownBlock.CodeBlock

        assertEquals("kotlin", code.language)
        assertEquals("val a = 1\n  val b = 2", code.code)
    }

    @Test
    fun `tilde fence allows backticks inside`() {
        val code = single(
            """
            ~~~
            ```
            ~~~
            """,
        ) as MarkdownBlock.CodeBlock

        assertNull(code.language)
        assertEquals("```", code.code)
    }

    @Test
    fun `unclosed fence runs to the end of the document`() {
        val code = single(
            """
            ```
            still code
            """,
        ) as MarkdownBlock.CodeBlock

        assertEquals("still code", code.code)
    }

    @Test
    fun `four space indent is a code block`() {
        val blocks = parse(
            """
            Intro

                indented code
                second line

            After
            """,
        )

        assertEquals("indented code\nsecond line", (blocks[1] as MarkdownBlock.CodeBlock).code)
        assertEquals("After", (blocks[2] as MarkdownBlock.Paragraph).content.plainText())
    }

    @Test
    fun `code spans support backtick runs and padding removal`() {
        assertEquals(
            MarkdownInline.CodeSpan("a `b` c"),
            (single("``a `b` c``") as MarkdownBlock.Paragraph).content.single(),
        )
        assertEquals(
            MarkdownInline.CodeSpan("``"),
            (single("` `` `") as MarkdownBlock.Paragraph).content.single(),
        )
    }

    @Test
    fun `markup inside a code span is literal`() {
        assertEquals(
            MarkdownInline.CodeSpan("**not bold** [not](a link)"),
            (single("`**not bold** [not](a link)`") as MarkdownBlock.Paragraph).content.single(),
        )
    }

    // -----------------------------------------------------------------------
    // Lists
    // -----------------------------------------------------------------------

    @Test
    fun `bullet list items accept all markers`() {
        val list = single(
            """
            - one
            - two
            """,
        ) as MarkdownBlock.ListBlock

        assertFalse(list.ordered)
        assertTrue(list.tight)
        assertEquals(listOf("one", "two"), list.items.map { it.paragraphText() })
    }

    @Test
    fun `changing the bullet marker starts a new list`() {
        val blocks = parse(
            """
            - one
            * two
            """,
        )

        assertEquals(2, blocks.filterIsInstance<MarkdownBlock.ListBlock>().size)
    }

    @Test
    fun `ordered list keeps its start number`() {
        val list = single(
            """
            3. three
            4. four
            """,
        ) as MarkdownBlock.ListBlock

        assertTrue(list.ordered)
        assertEquals(3, list.start)
        assertEquals(2, list.items.size)
    }

    @Test
    fun `nested lists become child blocks`() {
        val list = single(
            """
            - outer
              - inner one
              - inner two
            - sibling
            """,
        ) as MarkdownBlock.ListBlock

        assertEquals(2, list.items.size)
        val nested = list.items[0].children[1] as MarkdownBlock.ListBlock
        assertEquals(listOf("inner one", "inner two"), nested.items.map { it.paragraphText() })
        assertEquals("sibling", list.items[1].paragraphText())
    }

    @Test
    fun `task list items record their checkbox state`() {
        val list = single(
            """
            - [x] done
            - [X] also done
            - [ ] pending
            - plain
            """,
        ) as MarkdownBlock.ListBlock

        assertEquals(listOf(true, true, false, null), list.items.map { it.checked })
        assertEquals("done", list.items[0].paragraphText())
        assertEquals("plain", list.items[3].paragraphText())
    }

    @Test
    fun `blank lines between items make the list loose`() {
        val list = single(
            """
            - one

            - two
            """,
        ) as MarkdownBlock.ListBlock

        assertFalse(list.tight)
        assertEquals(2, list.items.size)
    }

    @Test
    fun `list item can hold multiple blocks`() {
        val list = single(
            """
            1. first paragraph

               second paragraph
            """,
        ) as MarkdownBlock.ListBlock

        val children = list.items.single().children
        assertEquals(2, children.size)
        assertEquals("second paragraph", (children[1] as MarkdownBlock.Paragraph).content.plainText())
    }

    @Test
    fun `list item can hold a fenced code block`() {
        val list = single(
            """
            - run this:

              ```
              make build
              ```
            """,
        ) as MarkdownBlock.ListBlock

        val code = list.items.single().children.filterIsInstance<MarkdownBlock.CodeBlock>().single()
        assertEquals("make build", code.code)
    }

    @Test
    fun `a paragraph after a list is not swallowed`() {
        val blocks = parse(
            """
            - one

            Not a list item
            """,
        )

        assertEquals(2, blocks.size)
        assertEquals("Not a list item", (blocks[1] as MarkdownBlock.Paragraph).content.plainText())
    }

    // -----------------------------------------------------------------------
    // Quotes and breaks
    // -----------------------------------------------------------------------

    @Test
    fun `block quote content is parsed and can nest`() {
        val quote = single(
            """
            > ## Heading
            > text
            > > nested
            """,
        ) as MarkdownBlock.BlockQuote

        assertEquals(2, (quote.children[0] as MarkdownBlock.Heading).level)
        assertEquals("text", (quote.children[1] as MarkdownBlock.Paragraph).content.plainText())
        val nested = quote.children[2] as MarkdownBlock.BlockQuote
        assertEquals("nested", (nested.children.single() as MarkdownBlock.Paragraph).content.plainText())
    }

    @Test
    fun `block quote takes lazy continuation lines`() {
        val blocks = parse(
            """
            > first
            second

            after
            """,
        )

        val quote = blocks[0] as MarkdownBlock.BlockQuote
        assertEquals("first second", (quote.children.single() as MarkdownBlock.Paragraph).content.plainText())
        assertEquals("after", (blocks[1] as MarkdownBlock.Paragraph).content.plainText())
    }

    @Test
    fun `thematic breaks in every spelling`() {
        val blocks = parse(
            """
            ***

            - - -

            ___
            """,
        )

        assertEquals(3, blocks.size)
        assertTrue(blocks.all { it === MarkdownBlock.ThematicBreak })
    }

    @Test
    fun `crlf and tabs are normalised`() {
        val blocks = MarkdownParser.parse("# Title\r\n\r\n-\tone\r\n")

        assertEquals("Title", (blocks[0] as MarkdownBlock.Heading).content.plainText())
        assertEquals("one", (blocks[1] as MarkdownBlock.ListBlock).items.single().paragraphText())
    }

    @Test
    fun `empty and blank input produce no blocks`() {
        assertTrue(MarkdownParser.parse("").isEmpty())
        assertTrue(MarkdownParser.parse("   \n\n  ").isEmpty())
    }

    @Test
    fun `plain multi paragraph text still renders as paragraphs`() {
        val blocks = parse(
            """
            First paragraph.

            Second paragraph.
            """,
        )

        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it is MarkdownBlock.Paragraph })
    }

    // -----------------------------------------------------------------------
    // Regressions
    // -----------------------------------------------------------------------

    @Test
    fun `nested list without blank lines keeps the parent tight`() {
        val list = single(
            """
            - a
              - b
            - c
            """,
        ) as MarkdownBlock.ListBlock

        assertTrue(list.tight)
        assertTrue((list.items[0].children[1] as MarkdownBlock.ListBlock).tight)
    }

    @Test
    fun `blank line inside a nested list only loosens the nested list`() {
        val list = single(
            """
            - a
              - b

              - c
            - d
            """,
        ) as MarkdownBlock.ListBlock

        assertTrue(list.tight)
        val nested = list.items[0].children[1] as MarkdownBlock.ListBlock
        assertFalse(nested.tight)
        assertEquals(listOf("b", "c"), nested.items.map { it.paragraphText() })
    }

    @Test
    fun `blank line between a nested list and a following paragraph loosens the parent`() {
        val list = single(
            """
            - a
              - b

              after
            - c
            """,
        ) as MarkdownBlock.ListBlock

        assertFalse(list.tight)
        val children = list.items[0].children
        assertEquals(3, children.size)
        assertTrue((children[1] as MarkdownBlock.ListBlock).tight)
        assertEquals("after", (children[2] as MarkdownBlock.Paragraph).content.plainText())
        assertEquals("c", list.items[1].paragraphText())
    }

    @Test
    fun `lazy continuation does not extend a quoted heading`() {
        val blocks = parse(
            """
            > # Title
            outside
            """,
        )

        assertEquals(2, blocks.size)
        val quote = blocks[0] as MarkdownBlock.BlockQuote
        assertEquals("Title", (quote.children.single() as MarkdownBlock.Heading).content.plainText())
        assertEquals("outside", (blocks[1] as MarkdownBlock.Paragraph).content.plainText())
    }

    @Test
    fun `lazy continuation does not extend a quoted fence`() {
        val blocks = parse(
            """
            > ```
            outside
            """,
        )

        assertEquals(2, blocks.size)
        val quote = blocks[0] as MarkdownBlock.BlockQuote
        assertTrue(quote.children.single() is MarkdownBlock.CodeBlock)
        assertEquals("outside", (blocks[1] as MarkdownBlock.Paragraph).content.plainText())
    }

    @Test
    fun `lazy continuation still reaches a paragraph nested in a quoted list`() {
        val quote = single(
            """
            > - item
            continued
            """,
        ) as MarkdownBlock.BlockQuote

        val list = quote.children.single() as MarkdownBlock.ListBlock
        assertEquals("item continued", list.items.single().paragraphText())
    }

    @Test
    fun `lazy continuation does not extend a fence inside a list item`() {
        val blocks = parse(
            """
            - ```
            outside
            """,
        )

        assertEquals(2, blocks.size)
        val list = blocks[0] as MarkdownBlock.ListBlock
        assertTrue(list.items.single().children.single() is MarkdownBlock.CodeBlock)
        assertEquals("outside", (blocks[1] as MarkdownBlock.Paragraph).content.plainText())
    }

    @Test
    fun `link definition inside a fence in a list item is kept as code`() {
        val blocks = parse(
            """
            - ```
              code

              [x]: not-a-definition
              ```

            [x]: https://example.com

            [x]
            """,
        )

        val list = blocks[0] as MarkdownBlock.ListBlock
        val code = list.items.single().children.single() as MarkdownBlock.CodeBlock
        assertEquals("code\n\n[x]: not-a-definition", code.code)
        val link = (blocks[1] as MarkdownBlock.Paragraph).content.single() as MarkdownInline.Link
        assertEquals("https://example.com", link.destination)
    }

    @Test
    fun `link definition inside a fence in a block quote is kept as code`() {
        val blocks = parse(
            """
            > ```
            >
            > [x]: not-a-definition
            > ```

            [x]: https://example.com

            [x]
            """,
        )

        val quote = blocks[0] as MarkdownBlock.BlockQuote
        val code = quote.children.single() as MarkdownBlock.CodeBlock
        assertEquals("\n[x]: not-a-definition", code.code)
        val link = (blocks[1] as MarkdownBlock.Paragraph).content.single() as MarkdownInline.Link
        assertEquals("https://example.com", link.destination)
    }

    private fun MarkdownListItem.paragraphText(): String =
        (children.first() as MarkdownBlock.Paragraph).content.plainText()
}
