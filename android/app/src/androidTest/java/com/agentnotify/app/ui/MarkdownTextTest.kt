package com.agentnotify.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Rendering level coverage for [MarkdownText]: link annotations, tables and block styling. */
@RunWith(AndroidJUnit4::class)
class MarkdownTextTest {

    @get:Rule
    val compose = createComposeRule()

    private fun render(markdown: String, uriHandler: UriHandler? = null) {
        compose.setContent {
            AgentNotifyTheme {
                Surface {
                    CompositionLocalProvider(LocalUriHandler provides (uriHandler ?: LocalUriHandler.current)) {
                        Column(Modifier.fillMaxSize().padding(12.dp)) { MarkdownText(markdown) }
                    }
                }
            }
        }
    }

    private fun textOf(substring: String): AnnotatedString =
        compose.onNodeWithText(substring, substring = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .first()

    private fun linksIn(substring: String): List<LinkAnnotation.Url> =
        textOf(substring).let { text ->
            text.getLinkAnnotations(0, text.length).mapNotNull { it.item as? LinkAnnotation.Url }
        }

    @Test
    fun inlineLinkCarriesItsDestinationAndStyling() {
        render("Read the [docs](https://example.com/docs) now.")

        compose.onNodeWithText("Read the docs now.").assertExists()
        val link = linksIn("Read the docs").single()
        assertEquals("https://example.com/docs", link.url)
        assertNotNull("link should be styled", link.styles?.style?.color)
        assertNotNull("link should be underlined", link.styles?.style?.textDecoration)
    }

    @Test
    fun referenceAndAutolinkDestinationsResolve() {
        render(
            """
            See [the guide][g] or https://example.com/bare

            [g]: https://example.com/guide
            """.trimIndent(),
        )

        val urls = linksIn("See the guide").map { it.url }
        assertEquals(listOf("https://example.com/guide", "https://example.com/bare"), urls)
    }

    @Test
    fun tappingALinkOpensItsDestination() {
        val opened = mutableListOf<String>()
        render(
            "[open me](https://example.com/target)",
            object : UriHandler {
                override fun openUri(uri: String) {
                    opened.add(uri)
                }
            },
        )

        compose.onNodeWithText("open me").performClick()
        compose.waitForIdle()
        assertEquals(listOf("https://example.com/target"), opened)
    }

    @Test
    fun unsupportedSchemesAreNotClickable() {
        render("[danger](javascript:alert(1)) and [relative](/settings)")

        compose.onNodeWithText("danger and relative").assertExists()
        assertTrue(linksIn("danger and relative").isEmpty())
    }

    @Test
    fun tableRendersEveryCellWithoutPipeSyntax() {
        render(
            """
            | Service | Status | Owner |
            | :------ | -----: | :---: |
            | api     | ok     | ana   |
            | web     | failed | ben   |
            """.trimIndent(),
        )

        listOf("Service", "Status", "Owner", "api", "ok", "ana", "web", "failed", "ben").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        assertTrue(
            "table should not render pipe syntax",
            compose.onAllNodesWithText("|", substring = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun tableCellKeepsItsInlineLink() {
        render(
            """
            | doc | link |
            | --- | ---- |
            | api | [spec](https://example.com/spec) |
            """.trimIndent(),
        )

        assertEquals("https://example.com/spec", linksIn("spec").single().url)
    }

    @Test
    fun wideTableIsHorizontallyScrollable() {
        render(
            """
            | one | two | three | four | five | six | seven | eight |
            | --- | --- | ----- | ---- | ---- | --- | ----- | ----- |
            | aaaaaaaa | bbbbbbbb | cccccccc | dddddddd | eeeeeeee | ffffffff | gggggggg | hhhhhhhh |
            """.trimIndent(),
        )

        compose.onNode(hasScrollAction()).assertExists()
    }

    @Test
    fun taskListItemsExposeCheckboxState() {
        render(
            """
            - [x] shipped
            - [ ] pending
            """.trimIndent(),
        )

        compose.onNodeWithContentDescription("Completed task").assertExists()
        compose.onNodeWithContentDescription("Incomplete task").assertExists()
        compose.onNodeWithText("shipped").assertExists()
        compose.onNodeWithText("pending").assertExists()
    }

    @Test
    fun blockSyntaxIsConsumedButCodeBlocksStayRaw() {
        render(
            """
            # Heading

            **bold** and *italic* and ~~gone~~

            > quoted line

            1. first
            2. second

            ```
            | raw | table |
            ```
            """.trimIndent(),
        )

        compose.onNodeWithText("Heading").assertExists()
        compose.onNodeWithText("bold and italic and gone").assertExists()
        compose.onNodeWithText("quoted line").assertExists()
        compose.onNodeWithText("first").assertExists()
        compose.onNodeWithText("second").assertExists()
        // Fenced content is preserved verbatim, pipes included.
        compose.onNodeWithText("| raw | table |").assertExists()
    }

    @Test
    fun captureWideTableScreenshot() {
        render(
            """
            Wide table overflow check:

            | Service | Region | Latency | Error rate | Deploy | Owner |
            | :------ | :----- | ------: | ---------: | :----: | :---- |
            | checkout-api | ap-southeast-2 | 82ms | 0.02% | ok | payments team |
            | web-frontend | us-east-1 | 140ms | 1.40% | slow | web team |
            """.trimIndent(),
        )
        compose.waitForIdle()

        assertTrue(saveScreenshot("markdown-wide-table.png").length() > 0)
    }

    @Test
    fun captureKitchenSinkScreenshot() {
        render(
            """
            # Deploy report

            Build **12** finished with a [full log](https://example.com/log).

            | Service | Latency | State |
            | :------ | ------: | :---: |
            | api     |    82ms |  ok   |
            | web     |   140ms | slow  |

            - [x] migrations applied
            - [ ] cache warmed
              - nested note with `inline code`

            > Rollback is available for 24 hours.

            ```kotlin
            val status = deploy(target = "prod")
            ```

            ---

            ~~Old plan~~ replaced. See https://example.com/plan
            """.trimIndent(),
        )
        compose.waitForIdle()

        assertTrue(saveScreenshot("markdown-kitchen-sink.png").length() > 0)
    }

    /** Writes the current root render to the app's external files dir for manual inspection. */
    private fun saveScreenshot(name: String): File {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        val file = File(directory, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }
}
