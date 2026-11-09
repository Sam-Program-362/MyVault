package com.myvault.app.ui

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import com.myvault.app.data.EntryDto
import dev.jeziellago.compose.markdowntext.MarkdownText
import com.myvault.app.data.fallbackInvalidMarkdownImages
import com.myvault.app.ui.editor.ReadingModeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingModeScreenTest {
    @get:Rule val compose = createComposeRule()

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
            compose.onNodeWithText("Shared Markdown compatibility fixture", substring = true).assertExists()
            compose.onNodeWithText("strong text", substring = true).assertExists()
            compose.onNodeWithText("emphasis", substring = true).assertExists()
            compose.onNodeWithText("inline code", substring = true).assertExists()
            compose.onNodeWithText("ordered item one", substring = true).assertExists()
            compose.onNodeWithText("nested unordered child", substring = true).assertExists()
            compose.onNodeWithText("completed task", substring = true).assertExists()
            compose.onNodeWithText("strikethrough", substring = true).assertExists()
            compose.onNodeWithText("https://example.org/plain-url", substring = true).assertExists()
            compose.onNodeWithText("A blockquote stays visible on both renderers.", substring = true).assertExists()
            compose.onNodeWithText("fenced code", substring = true).assertExists()
            compose.onNodeWithText("table", substring = true).assertExists()
            compose.onNodeWithText("Text before a missing image", substring = true).assertExists()
            compose.onNodeWithText("Image unavailable: missing test image", substring = true).assertExists()
            compose.onNodeWithText("text after it must remain visible.", substring = true).assertExists()
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
            compose.onNodeWithText("Last scroll marker.").performScrollTo().assertIsDisplayed()
        } finally {
            imageLoader.shutdown()
        }
    }
}
