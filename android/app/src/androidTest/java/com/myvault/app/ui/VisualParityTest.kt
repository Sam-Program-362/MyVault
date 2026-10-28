package com.myvault.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myvault.app.data.AppSettings
import com.myvault.app.data.EntryEntity
import com.myvault.app.data.EntryTagCrossRef
import com.myvault.app.data.SpaceEntity
import com.myvault.app.data.TagEntity
import com.myvault.app.ui.editor.EditorSaveState
import com.myvault.app.ui.editor.EntryEditorScreen
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI fixtures are synthetic; optional captures never contain a user's vault. */
@RunWith(AndroidJUnit4::class)
class VisualParityTest {
    @get:Rule val compose = createComposeRule()

    private val settings = AppSettings(mode = "dark")
    private val work = SpaceEntity(id = "work", name = "Work", icon = "📝")
    private val home = SpaceEntity(id = "home", name = "Home", icon = "🏠", color = "#10B981")
    private val ideas = TagEntity(id = "ideas", name = "Ideas", color = "#8B5CF6")
    private val note =
        EntryEntity(
            id = "note",
            spaceId = "work",
            title = "A thoughtful note",
            content = "# Ideas\nKeep this for later.",
            pinned = true,
        )
    private val other =
        EntryEntity(
            id = "other",
            spaceId = "home",
            title = "Home note",
            content = "A different space",
        )

    @Test
    fun themeProvidesReadableDefaultContentInDarkAndLightModes() {
        val mode = mutableStateOf("dark")
        var foreground = Color.Unspecified
        var background = Color.Unspecified
        var expectedForeground = Color.Unspecified
        compose.setContent {
            VaultTheme(settings.copy(mode = mode.value)) {
                val contentColor = LocalContentColor.current
                val backgroundColor = MaterialTheme.colorScheme.background
                val onBackground = MaterialTheme.colorScheme.onBackground
                SideEffect {
                    foreground = contentColor
                    background = backgroundColor
                    expectedForeground = onBackground
                }
            }
        }
        fun assertReadable() {
            assertEquals(expectedForeground, foreground)
            val first = foreground.luminance()
            val second = background.luminance()
            val contrast = (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
            assertTrue(
                "Default content contrast should be at least 4.5:1, was $contrast",
                contrast >= 4.5f,
            )
        }
        compose.runOnIdle {
            assertReadable()
            mode.value = "light"
        }
        compose.runOnIdle { assertReadable() }
    }

    @Test
    fun welcomeHasBrandedCardsAccessibleActionsAndSpaceDialog() {
        compose.setContent {
            VaultTheme(settings) { dark ->
                Box(Modifier.fillMaxSize()) {
                    VaultBackdrop(settings, dark, splash = true)
                    SpacesScreen(
                        "MyVault",
                        listOf(work, home),
                        listOf(note, other),
                        onOpen = {},
                        onSettings = {},
                        onCreate = { _, _, _, _ -> },
                        onUpdate = { _, _, _, _, _ -> },
                        onDelete = {},
                    )
                }
            }
        }
        compose.onNodeWithText("YOUR ORGANIZED VAULT").assertIsDisplayed()
        compose.onNodeWithText("MyVault").assertIsDisplayed()
        compose
            .onNodeWithContentDescription("Settings")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Options for Work").assertWidthIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Options for Work").performClick()
        compose.onNodeWithText("Edit space").performClick()
        compose.onNodeWithText("Space name").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test
    fun emptyWelcomeCanCreateOrCancelWithoutChangingExistingData() {
        var created = false
        compose.setContent {
            VaultTheme(settings) { dark ->
                Box(Modifier.fillMaxSize()) {
                    VaultBackdrop(settings, dark, splash = true)
                    SpacesScreen(
                        "MyVault",
                        emptyList(),
                        emptyList(),
                        onOpen = {},
                        onSettings = {},
                        onCreate = { _, _, _, _ -> created = true },
                        onUpdate = { _, _, _, _, _ -> },
                        onDelete = {},
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("New space").assertIsDisplayed().performClick()
        compose.onNodeWithText("Create & enter").assertIsNotEnabled()
        compose.onNodeWithText("Space name").performTextInput("A private space")
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.onNodeWithContentDescription("New space").assertIsDisplayed()
        assertEquals(false, created)
    }

    @Test
    fun dashboardSearchAndFiltersKeepSpacesAndEntriesDistinct() {
        val selected = mutableStateOf<String?>(work.id)
        compose.setContent {
            VaultTheme(settings) { dark ->
                Box(Modifier.fillMaxSize()) {
                    VaultBackdrop(settings, dark, splash = false)
                    EntriesScreen(
                        appName = "MyVault",
                        spaces = listOf(work, home),
                        selectedSpaceId = selected.value,
                        entries = listOf(note, other),
                        tags = listOf(ideas),
                        tagRefs = listOf(EntryTagCrossRef(note.id, ideas.id)),
                        onSelectSpace = { selected.value = it },
                        onBack = {},
                        onSettings = {},
                        onNewSpace = { _, _, _, _ -> },
                        onNewEntry = {},
                        onOpenEntry = {},
                    )
                }
            }
        }
        compose.onNodeWithText("A thoughtful note").assertIsDisplayed()
        compose
            .onNodeWithContentDescription("New entry")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        capture("dashboard")
        compose.onNodeWithContentDescription("Search entries").performTextInput("missing")
        compose
            .onNodeWithText("No matching entries. Try another search or filter.")
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithContentDescription("All spaces").performClick()
        compose.onNodeWithText("Home note").assertIsDisplayed()
        compose.onNodeWithContentDescription("Filter by Ideas").performClick()
        compose.onNodeWithText("A thoughtful note").assertIsDisplayed()
        compose.onNodeWithText("Home note").assertDoesNotExist()
    }

    @Test
    fun settingsUseAccessibleTabsAndKeepImportExportCallbacks() {
        val current = mutableStateOf(settings)
        val exported = mutableStateOf(false)
        compose.setContent {
            VaultTheme(current.value) {
                SettingsScreen(
                    settings = current.value,
                    tags = emptyList(),
                    onBack = {},
                    onUpdate = { current.value = it(current.value) },
                    onChooseWallpaper = {},
                    onCreateTag = { _, _, _ -> },
                    onSetReusable = { _, _ -> },
                    onDeleteTag = {},
                    onExport = { exported.value = true },
                    onImport = {},
                )
            }
        }
        compose.onNodeWithText("Settings").assertIsDisplayed()
        capture("settings")
        compose.onNodeWithText("Tags").performClick()
        compose.onNodeWithText("No tags yet.").assertIsDisplayed()
        compose.onNodeWithText("Import / Export").performScrollTo().performClick()
        compose.onNodeWithText("Export JSON").performScrollTo().performClick()
        assertEquals(true, exported.value)
    }

    @Test
    fun editorRemainsUsableInShortWindowsAndPreview() {
        val content = mutableStateOf("")
        compose.setContent {
            VaultTheme(settings) { dark ->
                Box(Modifier.size(width = 320.dp, height = 300.dp)) {
                    EntryEditorScreen(
                        entryId = "fixture",
                        title = "Untitled entry",
                        content = content.value,
                        coverImage = null,
                        pinned = false,
                        tags = emptyList(),
                        darkTheme = dark,
                        saveState = EditorSaveState.Saved,
                        onTitleChange = {},
                        onContentChange = { content.value = it },
                        onClose = {},
                        onTogglePin = {},
                        onChooseCover = {},
                        onRemoveCover = {},
                        onAddTag = {},
                        onRemoveTag = {},
                        onImport = {},
                        onDelete = {},
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Close editor").assertWidthIsAtLeast(48.dp)
        compose
            .onNodeWithContentDescription("Entry content")
            .performScrollTo()
            .performTextInput("# Offline ideas")
        compose
            .onNodeWithContentDescription("Entry content")
            .assertTextContains("Offline ideas", substring = true)
        compose.onNodeWithContentDescription("Preview").performClick()
        compose.onNodeWithContentDescription("Close preview").assertIsDisplayed()
    }

    @Test
    fun fullEditorShowsSavedEntry() {
        compose.setContent {
            VaultTheme(settings) { dark ->
                EntryEditorScreen(
                    entryId = note.id,
                    title = note.title,
                    content = note.content,
                    coverImage = null,
                    pinned = note.pinned,
                    tags = listOf(ideas),
                    darkTheme = dark,
                    saveState = EditorSaveState.Saved,
                    onTitleChange = {},
                    onContentChange = {},
                    onClose = {},
                    onTogglePin = {},
                    onChooseCover = {},
                    onRemoveCover = {},
                    onAddTag = {},
                    onRemoveTag = {},
                    onImport = {},
                    onDelete = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Entry title").assertTextContains("A thoughtful note")
        compose.onNodeWithContentDescription("Close editor").assertIsDisplayed()
        capture("editor")
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("myvault.capture") != "true") return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val node = if (name == "settings") compose.onNode(isDialog()) else compose.onRoot()
        val original = node.captureToImage().asAndroidBitmap()
        val width = 260
        val scaled =
            Bitmap.createScaledBitmap(
                original,
                width,
                original.height * width / original.width,
                true,
            )
        val dir = instrumentation.targetContext.filesDir
        File(dir, "myvault-$name.jpg").outputStream().use {
            scaled.compress(Bitmap.CompressFormat.JPEG, 30, it)
        }
        scaled.recycle()
        original.recycle()
    }
}
