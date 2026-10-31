package com.myvault.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myvault.app.MainActivity
import com.myvault.app.MyVaultApplication
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
        compose.onNodeWithText("Create & enter").performScrollTo().performClick()
        try {
            compose.waitUntil(15_000) {
                compose.onAllNodesWithContentDescription("New entry").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            val app = ApplicationProvider.getApplicationContext<MyVaultApplication>()
            val spaces = runBlocking { app.database.spaceDao().getAll().map { it.name } }
            val busy = ViewModelProvider(compose.activity)[VaultViewModel::class.java].busy.value
            val tree = compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 12)
            throw AssertionError("New entry did not appear after creating $space; persisted=$spaces busy=$busy\n$tree", failure)
        }
        compose.onNodeWithContentDescription("New entry").performClick()
        compose.waitUntil(15_000) {
            compose
                .onAllNodesWithContentDescription("Entry title")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithContentDescription("Entry title").performTextInput("A saved note")
        compose
            .onNodeWithContentDescription("Entry content")
            .performTextInput("# Hello\nA little Markdown")
        compose.onNodeWithContentDescription("Pin entry").performClick()
        compose.onNodeWithContentDescription("Preview").performClick()
        try {
            compose.waitUntil(10_000) {
                compose.onNodeWithContentDescription("Close preview").isDisplayed()
            }
            compose.onNodeWithText("Markdown").assertIsDisplayed()
        } catch (failure: Throwable) {
            val header =
                compose.onAllNodesWithText("Markdown", useUnmergedTree = true).printToString()
            val close =
                compose
                    .onAllNodesWithContentDescription("Close preview", useUnmergedTree = true)
                    .printToString()
            throw AssertionError("Preview was not visible.\n$header\n$close", failure)
        }
        compose.onNodeWithContentDescription("Close preview").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) {
            !compose.onNodeWithContentDescription("Close preview").isDisplayed()
        }
        compose.onNodeWithContentDescription("Entry title").assertIsDisplayed()
        compose.onNodeWithContentDescription("Find and replace").performClick()
        compose.onNodeWithText("Find").performTextInput("little")
        compose.onNodeWithText("Replace with").performTextInput("small")
        compose.onNodeWithText("Replace all").performScrollTo().performClick()
        compose
            .onNodeWithContentDescription("Entry content")
            .assertTextContains("A small Markdown", substring = true)
        compose.onNodeWithContentDescription("Find and replace").performClick()
        compose.onNodeWithText("Add tag…", substring = true).performClick()
        compose.onNodeWithText("Find or create a tag").performTextInput("Journal")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Choose tag scope").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Choose tag scope").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Local tag").assertIsDisplayed()
        compose.onNodeWithText("Global tag").performScrollTo().assertIsDisplayed()
        val createTagButton = compose.onNodeWithText("Create & add tag (paused)").performScrollTo()
        createTagButton.assertIsNotEnabled()
        compose.onNodeWithText("Global tag").performScrollTo().performClick()
        createTagButton.assertIsNotEnabled()
        compose.onNodeWithText("Creation is paused", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Close editor").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("A saved note").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("A saved note").performClick()
        compose.waitUntil(15_000) {
            compose
                .onAllNodesWithContentDescription("Entry content")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithContentDescription("Entry title").assertTextContains("A saved note")
        compose
            .onNodeWithContentDescription("Entry content")
            .assertTextContains("A small Markdown", substring = true)
        compose.onNodeWithContentDescription("Unpin entry").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun undoRedoControlsAndKeyboardShortcutsCoverTitleAndBody() {
        val space = "Undo space ${System.currentTimeMillis()}"
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("New space").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("New space").performClick()
        compose.onNodeWithText("Space name").performTextInput(space)
        compose.onNodeWithText("Create & enter").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("New entry").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("New entry").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("Entry title").fetchSemanticsNodes().isNotEmpty()
        }
        val title = compose.onNodeWithContentDescription("Entry title")
        val body = compose.onNodeWithContentDescription("Entry content")
        title.performTextInput("Title draft")
        body.performTextInput("Body draft")
        compose.onNodeWithContentDescription("Undo").assertIsDisplayed().performClick()
        body.assertTextEquals("")
        compose.onNodeWithContentDescription("Undo").performClick()
        title.assertTextEquals("")
        compose.onNodeWithContentDescription("Redo").performClick()
        title.assertTextEquals("Title draft")
        title.performKeyInput {
            keyDown(Key.CtrlLeft)
            keyDown(Key.ShiftLeft)
            pressKey(Key.Z)
            keyUp(Key.ShiftLeft)
            keyUp(Key.CtrlLeft)
        }
        body.assertTextEquals("Body draft")
        body.performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.Z)
            keyUp(Key.CtrlLeft)
        }
        body.assertTextEquals("")
        title.performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.Y)
            keyUp(Key.CtrlLeft)
        }
        body.assertTextEquals("Body draft")
        compose.onNodeWithContentDescription("Close editor").performClick()
    }

    @Test
    fun appearanceSettingsPersistAcrossNavigation() {
        val app = ApplicationProvider.getApplicationContext<MyVaultApplication>()
        val original = runBlocking { app.settings.settingsFlow.first() }
        try {
            compose.waitUntil(15_000) {
                compose
                    .onAllNodesWithContentDescription("Settings")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Dark").performClick()
            compose.waitUntil(15_000) {
                runBlocking { app.settings.settingsFlow.first().mode == "dark" }
            }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Dark").assertIsSelected()
        } finally {
            runBlocking { app.settings.update { original } }
        }
    }
}
