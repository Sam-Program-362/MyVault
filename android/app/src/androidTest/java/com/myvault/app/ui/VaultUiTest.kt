package com.myvault.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myvault.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun createWritePreviewCloseAndReopen() {
        val space = "Test space ${System.currentTimeMillis()}"
        compose.onNodeWithText("New space").performClick()
        compose.onNodeWithText("Space name").performTextInput(space)
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("New entry").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("New entry").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("Entry title").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Entry title").performTextInput("A saved note")
        compose.onNodeWithContentDescription("Entry content").performTextInput("# Hello\nA little Markdown")
        compose.onNodeWithContentDescription("Pin entry").performClick()
        compose.onNodeWithContentDescription("Preview").performClick()
        compose.onNodeWithContentDescription("Close preview").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Close editor").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("A saved note").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("A saved note").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("Entry content").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Entry title").assertTextContains("A saved note")
        compose.onNodeWithContentDescription("Entry content").assertTextContains("A little Markdown")
        compose.onNodeWithContentDescription("Unpin entry").assertIsDisplayed()
    }
}
