package com.myvault.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myvault.app.MainActivity
import com.myvault.app.MyVaultApplication
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun createWritePreviewCloseAndReopen() {
        val space = "Test space ${System.currentTimeMillis()}"
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("New space").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("New space").performClick()
        compose.onNodeWithText("Space name").performTextInput(space)
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("New entry").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("New entry").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("Entry title").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Entry title").performTextInput("A saved note")
        compose.onNodeWithContentDescription("Entry content").performTextInput("# Hello\nA little Markdown")
        compose.onNodeWithContentDescription("Pin entry").performClick()
        compose.onNodeWithContentDescription("Preview").performClick()
        try {
            compose.waitUntil(10_000) { compose.onNodeWithContentDescription("Close preview").isDisplayed() }
            compose.onNodeWithText("Markdown").assertIsDisplayed()
            capturePreviewForDiagnostics()
        } catch (failure: Throwable) {
            capturePreviewForDiagnostics()
            val header = compose.onAllNodesWithText("Markdown", useUnmergedTree = true).printToString()
            val close =
                compose
                    .onAllNodesWithContentDescription("Close preview", useUnmergedTree = true)
                    .printToString()
            throw AssertionError("Preview was not visible.\n$header\n$close", failure)
        }
        compose.onNodeWithContentDescription("Close preview").performClick()
        compose.onNodeWithContentDescription("Find and replace").performClick()
        compose.onNodeWithText("Find").performTextInput("little")
        compose.onNodeWithText("Replace with").performTextInput("small")
        compose.onNodeWithText("Replace all").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Entry content").assertTextContains("A small Markdown")
        compose.onNodeWithContentDescription("Find and replace").performClick()
        compose.onNodeWithText("Add tag…", substring = true).performClick()
        compose.onNodeWithText("Find or create a tag").performTextInput("Journal")
        compose.onNodeWithText("Create & add tag").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("Remove Journal tag").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Close editor").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("A saved note").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("A saved note").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("Entry content").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Entry title").assertTextContains("A saved note")
        compose.onNodeWithContentDescription("Entry content").assertTextContains("A small Markdown")
        compose.onNodeWithContentDescription("Unpin entry").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove Journal tag").assertIsDisplayed()
    }

    @Test
    fun appearanceSettingsPersistAcrossNavigation() {
        val app = ApplicationProvider.getApplicationContext<MyVaultApplication>()
        val original = runBlocking { app.settings.settingsFlow.first() }
        try {
            compose.waitUntil(15_000) {
                compose.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Dark").performClick()
            compose.waitUntil(15_000) { runBlocking { app.settings.settingsFlow.first().mode == "dark" } }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Dark").assertIsSelected()
        } finally {
            runBlocking { app.settings.update { original } }
        }
    }

    private fun capturePreviewForDiagnostics() {
        runCatching {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return
            val thumbnail =
                Bitmap.createScaledBitmap(screenshot, 360, screenshot.height * 360 / screenshot.width, true)
            File(instrumentation.targetContext.cacheDir, "myvault-preview-test.jpg").outputStream().use {
                thumbnail.compress(Bitmap.CompressFormat.JPEG, 45, it)
            }
        }
    }
}
