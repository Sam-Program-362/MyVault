package com.myvault.app.ui

import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myvault.app.MyVaultApplication
import com.myvault.app.data.AppSettings
import com.myvault.app.data.EntryEntity
import com.myvault.app.data.SpaceEntity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The CI motion phase explicitly enables Android animations and launches these tests. */
@RunWith(AndroidJUnit4::class)
class MotionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun switchingCardAndListKeepsBothLayoutsDuringTheTransition() {
        if (InstrumentationRegistry.getArguments().getString("myvault.motion") != "true") return
        compose.mainClock.autoAdvance = false
        val mode = mutableStateOf("cards")
        val space = SpaceEntity(id = "s", name = "Work")
        val entry =
            EntryEntity(
                id = "motion",
                spaceId = "s",
                title = "Animated note",
                content = "First\nSecond",
            )
        compose.setContent {
            VaultTheme(AppSettings(mode = "dark")) {
                EntriesScreen(
                    appName = "MyVault",
                    spaces = listOf(space),
                    selectedSpaceId = space.id,
                    entries = listOf(entry),
                    tags = emptyList(),
                    tagRefs = emptyList(),
                    onSelectSpace = {},
                    onBack = {},
                    onSettings = {},
                    onNewSpace = { _, _, _, _ -> },
                    onNewEntry = {},
                    onOpenEntry = {},
                    entryLayout = mode.value,
                    onEntryLayoutChange = { mode.value = it },
                )
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("entry-card-motion").assertExists()
        compose.onNodeWithContentDescription("List view").performClick()
        compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithTag("entry-card-motion").assertExists()
        compose.onNodeWithTag("entry-list-motion").assertExists()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("entry-card-motion").assertDoesNotExist()
        compose.onNodeWithTag("entry-list-motion").assertExists()
    }

    @Test
    fun settingsDialogStaysMountedDuringExitAnimation() {
        if (InstrumentationRegistry.getArguments().getString("myvault.motion") != "true") return
        compose.mainClock.autoAdvance = false
        val visible = mutableStateOf(true)
        compose.setContent {
            VaultTheme(AppSettings(mode = "dark")) {
                SettingsScreen(
                    settings = AppSettings(mode = "dark"),
                    tags = emptyList(),
                    onBack = { visible.value = false },
                    onUpdate = {},
                    onChooseWallpaper = {},
                    onCreateTag = { _, _, _ -> },
                    onSetReusable = { _, _ -> },
                    onDeleteTag = {},
                    onExport = {},
                    onImport = {},
                    visible = visible.value,
                )
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithText("Settings").assertExists()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("Settings").assertDoesNotExist()
    }

    @Test
    fun expandingAndCollapsingListRowAnimatesItsHeight() {
        if (InstrumentationRegistry.getArguments().getString("myvault.motion") != "true") return
        val app = ApplicationProvider.getApplicationContext<MyVaultApplication>()
        assertTrue(
            Settings.Global.getFloat(
                app.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        )
        compose.mainClock.autoAdvance = false
        val space = SpaceEntity(id = "s", name = "Work")
        val entry =
            EntryEntity(
                id = "motion",
                spaceId = "s",
                title = "Animated note",
                content = "First line\nSecond line\nThird line\nFourth line",
            )
        compose.setContent {
            VaultTheme(AppSettings(mode = "dark")) {
                EntriesScreen(
                    appName = "MyVault",
                    spaces = listOf(space),
                    selectedSpaceId = space.id,
                    entries = listOf(entry),
                    tags = emptyList(),
                    tagRefs = emptyList(),
                    onSelectSpace = {},
                    onBack = {},
                    onSettings = {},
                    onNewSpace = { _, _, _, _ -> },
                    onNewEntry = {},
                    onOpenEntry = {},
                    entryLayout = "list",
                )
            }
        }
        compose.mainClock.advanceTimeBy(500)
        val collapsed =
            compose.onNodeWithTag("entry-list-motion").getUnclippedBoundsInRoot().height.value
        compose.onNodeWithContentDescription("Expand Animated note").performClick()
        compose.mainClock.advanceTimeBy(80)
        val midway =
            compose.onNodeWithTag("entry-list-motion").getUnclippedBoundsInRoot().height.value
        compose.mainClock.advanceTimeBy(400)
        val expanded =
            compose.onNodeWithTag("entry-list-motion").getUnclippedBoundsInRoot().height.value
        assertTrue("Expanded row must grow", expanded > collapsed)
        assertTrue(
            "Expansion should pass through an intermediate height",
            midway > collapsed && midway < expanded,
        )
        compose
            .onNodeWithContentDescription("Collapse Animated note")
            .assertIsDisplayed()
            .performClick()
        compose.mainClock.advanceTimeBy(80)
        val collapsing =
            compose.onNodeWithTag("entry-list-motion").getUnclippedBoundsInRoot().height.value
        compose.mainClock.advanceTimeBy(400)
        val closed =
            compose.onNodeWithTag("entry-list-motion").getUnclippedBoundsInRoot().height.value
        assertTrue(
            "Collapse should pass through an intermediate height",
            collapsing > closed && collapsing < expanded,
        )
        assertTrue(
            "Collapsed row should return to its original height",
            kotlin.math.abs(closed - collapsed) < 1f,
        )
    }
}
