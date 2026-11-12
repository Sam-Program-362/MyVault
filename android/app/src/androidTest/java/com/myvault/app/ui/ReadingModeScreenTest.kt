package com.myvault.app.ui

import android.content.ClipboardManager
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.containsString
import coil.ImageLoader
import com.myvault.app.data.EntryDto
import com.myvault.app.ui.editor.ReadingModeScreen
import dev.jeziellago.compose.markdowntext.MarkdownText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingModeScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun assertRenderedMarkdownContains(text: String) {
        val matcher = withText(containsString(text))
        onView(matcher).check(matches(matcher))
    }

    private fun sharedFixture(): String =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("markdown-compatibility.md").bufferedReader().use { it.readText() }

    private fun entry(content: String) = EntryDto(
        id = "reader-entry",
        spaceId = "reader-space",
        title = "Saved reader title",
        content = content,
        pinned = true,
        createdAt = "2026-10-01T00:00:00Z",
        updatedAt = "2026-10-02T00:00:00Z",
    )

    @Test
    fun sharedFixtureRendersGfmAndMissingImageDoesNotHideSurroundingText() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val content = sharedFixture()
        val imageLoader = ImageLoader(context)
        try {
            compose.setContent {
                MaterialTheme {
                    ReadingModeScreen(
                        entry = entry(content),
                        darkTheme = true,
                        accent = Color(0xFF818CF8),
                        imageLoader = imageLoader,
                        onBack = {},
                        onExport = {},
                        modifier = Modifier.size(width = 390.dp, height = 760.dp),
                    )
                }
            }
            compose.onNodeWithText("Saved reader title").assertIsDisplayed()
            assertRenderedMarkdownContains("Shared Markdown compatibility fixture")
            assertRenderedMarkdownContains("strong text")
            assertRenderedMarkdownContains("emphasis")
            assertRenderedMarkdownContains("inline code")
            assertRenderedMarkdownContains("ordered item one")
            assertRenderedMarkdownContains("nested unordered child")
            assertRenderedMarkdownContains("completed task")
            assertRenderedMarkdownContains("strikethrough")
            assertRenderedMarkdownContains("https://example.org/plain-url")
            assertRenderedMarkdownContains("A blockquote stays visible on both renderers.")
            assertRenderedMarkdownContains("fenced code")
            assertRenderedMarkdownContains("Text before a missing image")
            assertRenderedMarkdownContains("Image unavailable: missing test image")
            assertRenderedMarkdownContains("text after it must remain visible.")
        } finally {
            imageLoader.shutdown()
        }
    }

    @Test
    fun gfmTableRowsRenderAllCellsInNativeMarkdownSpans() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val imageLoader = ImageLoader(context)
        try {
            compose.setContent {
                MaterialTheme {
                    MarkdownText(
                        markdown = sharedFixture(),
                        style = TextStyle(color = Color.White),
                        imageLoader = imageLoader,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            // Markwon's table extension paints rows with a native ReplacementSpan, so
            // cell text is not part of TextView.text.toString() or Compose semantics.
            // Inspect the actual rendered Spanned value and the extension's row cells.
            val expectedCells = listOf("Syntax", "Expected", "table", "cells", "GFM", "visible")
            onView(isAssignableFrom(TextView::class.java)).check { view, _ ->
                val rendered = (view as TextView).text as? Spanned
                    ?: throw AssertionError("Markwon output was not a Spanned native TextView value")
                val tableRows = rendered.getSpans(0, rendered.length, ReplacementSpan::class.java)
                    .filter { it.javaClass.name == "io.noties.markwon.ext.tables.TableRowSpan" }
                assertTrue("Expected three native Markwon table row spans, got ${tableRows.size}", tableRows.size >= 3)
                val cellsField = tableRows.first().javaClass.getDeclaredField("cells").apply {
                    isAccessible = true
                }
                val cellText = tableRows.flatMap { row ->
                    @Suppress("UNCHECKED_CAST")
                    val cells = cellsField.get(row) as List<Any>
                    cells.map { cell -> cell.javaClass.getMethod("text").invoke(cell).toString() }
                }
                expectedCells.forEach { cell ->
                    assertTrue("GFM table cell '$cell' missing from Markwon row spans: $cellText", cellText.contains(cell))
                }
            }
        } finally {
            imageLoader.shutdown()
        }
    }

    @Test
    fun readerFindCopyExportAndReturnAreReadOnlyAndWrapMatches() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = "One needle and another **needle**."
        val imageLoader = ImageLoader(context)
        val returned = mutableStateOf(false)
        val exportedTitle = mutableStateOf<String?>(null)
        try {
            compose.setContent {
                MaterialTheme {
                    ReadingModeScreen(
                        entry = entry(source),
                        darkTheme = false,
                        accent = Color(0xFF4F46E5),
                        imageLoader = imageLoader,
                        onBack = { returned.value = true },
                        onExport = { exportedTitle.value = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            compose.onNodeWithContentDescription("Find in entry").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("needle")
            compose.onNodeWithText("0 of 2 matches").assertExists()
            compose.onNodeWithText("Next").performClick()
            compose.onNodeWithText("1 of 2 matches").assertExists()
            compose.onNodeWithText("Next").performClick()
            compose.onNodeWithText("2 of 2 matches").assertExists()
            compose.onNodeWithText("Next").performClick()
            compose.onNodeWithText("1 of 2 matches").assertExists()
            compose.onNode(hasSetTextAction()).performTextInput("not-found")
            compose.onNodeWithText("No matches found.").assertExists()

            compose.onNodeWithContentDescription("Copy original Markdown").performClick()
            compose.runOnIdle {
                val manager = context.getSystemService(ClipboardManager::class.java)
                assertEquals(source, manager.primaryClip?.getItemAt(0)?.text?.toString())
            }
            compose.onNodeWithContentDescription("Export this entry").performClick()
            compose.runOnIdle { assertEquals("Saved reader title", exportedTitle.value) }
            compose.onNodeWithContentDescription("Return to editor").performClick()
            compose.runOnIdle { assertTrue(returned.value) }
            assertEquals("Read operations must not replace the original Markdown", source, entry(source).content)
        } finally {
            imageLoader.shutdown()
        }
    }

    @Test
    fun longReaderContentRemainsReachableByScrolling() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val content = sharedFixture() + ("\n\nScrollable paragraph with enough text to continue down the reading page.".repeat(70)) + "\n\nLast scroll marker."
        val imageLoader = ImageLoader(context)
        try {
            compose.setContent {
                MaterialTheme {
                    Box(Modifier.size(width = 360.dp, height = 640.dp)) {
                        ReadingModeScreen(
                            entry = entry(content),
                            darkTheme = true,
                            accent = Color(0xFF818CF8),
                            imageLoader = imageLoader,
                            onBack = {},
                            onExport = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            val scrollNode = compose.onNodeWithTag("reader-scroll")
            val before = scrollNode.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            scrollNode.performTouchInput { repeat(12) { swipeUp() } }
            val after = scrollNode.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("long reading content must scroll", after > before)
            assertRenderedMarkdownContains("Last scroll marker.")
        } finally {
            imageLoader.shutdown()
        }
    }
}
