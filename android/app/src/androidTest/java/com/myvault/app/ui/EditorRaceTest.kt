package com.myvault.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myvault.app.ui.editor.EditorContentUpdate
import com.myvault.app.ui.editor.EditorSaveState
import com.myvault.app.ui.editor.EntryEditorScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorRaceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun staleHostEchoesCannotEraseTypingButExplicitImportsUpdateEditor() {
        var hostTitle by mutableStateOf("")
        var hostBody by mutableStateOf("")
        var imported by mutableStateOf<EditorContentUpdate?>(null)
        var lastTitle = ""
        var lastBody = ""
        compose.setContent {
            MaterialTheme {
                EntryEditorScreen(
                    entryId = "one",
                    title = hostTitle,
                    content = hostBody,
                    coverImage = null,
                    pinned = false,
                    tags = emptyList(),
                    darkTheme = false,
                    saveState = EditorSaveState.Saving,
                    onTitleChange = { lastTitle = it },
                    onContentChange = { lastBody = it },
                    onClose = {},
                    onTogglePin = {},
                    onChooseCover = {},
                    onRemoveCover = {},
                    onAddTag = {},
                    onRemoveTag = {},
                    onImport = {},
                    onDelete = {},
                    externalUpdate = imported,
                )
            }
        }
        compose.onNodeWithContentDescription("Entry title").performTextInput("Title")
        compose.onNodeWithContentDescription("Entry content").performTextInput("latest text")
        compose.runOnIdle {
            assertEquals("Title", lastTitle)
            assertEquals("latest text", lastBody)
            // Simulate an old value arriving from a slow persistence observer.
            hostTitle = "Ti"
            hostBody = "old"
        }
        compose.onNodeWithContentDescription("Entry title").assertTextContains("Title")
        compose.onNodeWithContentDescription("Entry content").assertTextContains("latest text")
        compose.runOnIdle {
            hostTitle = "Imported title"
            hostBody = "imported text"
            imported = EditorContentUpdate("one", 1L, hostTitle, hostBody, hostBody.length)
        }
        compose.onNodeWithContentDescription("Entry title").assertTextContains("Imported title")
        compose.onNodeWithContentDescription("Entry content").assertTextContains("imported text")
    }
}
