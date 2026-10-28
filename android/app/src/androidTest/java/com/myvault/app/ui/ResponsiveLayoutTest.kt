package com.myvault.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myvault.app.data.AppSettings
import com.myvault.app.data.SpaceEntity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Constraint tests complement the full-activity, real-window emulator captures. */
@RunWith(AndroidJUnit4::class)
class ResponsiveLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun shortAndTallWelcomeKeepCardsNearTheDescription() {
        val height = mutableStateOf(560.dp)
        compose.setContent {
            VaultTheme(AppSettings(mode = "dark")) {
                Box(Modifier.size(width = 320.dp, height = height.value)) {
                    SpacesScreen(
                        "MyVault",
                        emptyList(),
                        emptyList(),
                        onOpen = {},
                        onSettings = {},
                        onCreate = { _, _, _, _ -> },
                        onUpdate = { _, _, _, _, _ -> },
                        onDelete = {},
                    )
                }
            }
        }
        fun checkGap() {
            val description =
                compose
                    .onNodeWithText("Pick a space to jump into", substring = true)
                    .getUnclippedBoundsInRoot()
            val card = compose.onNodeWithContentDescription("New space").getUnclippedBoundsInRoot()
            val settings =
                compose.onNodeWithContentDescription("Settings").getUnclippedBoundsInRoot()
            val badge = compose.onNodeWithText("YOUR ORGANIZED VAULT").getUnclippedBoundsInRoot()
            val gap = (card.top - description.bottom).value
            assertTrue("Welcome-to-card gap should stay modest, was $gap dp", gap in 0f..60f)
            assertTrue("Settings should sit alongside the badge", settings.top <= badge.bottom)
            assertTrue("Settings touch target must remain 48dp", settings.width.value >= 48f)
        }
        checkGap()
        compose.runOnIdle { height.value = 760.dp }
        checkGap()
    }

    @Test
    fun narrowDashboardKeepsFullWidthChromeAndAUsableSearchRow() {
        val space = SpaceEntity(id = "responsive", name = "Work")
        compose.setContent {
            VaultTheme(AppSettings(mode = "dark")) {
                Box(Modifier.size(width = 300.dp, height = 600.dp)) {
                    EntriesScreen(
                        appName = "MyVault",
                        spaces = listOf(space),
                        selectedSpaceId = space.id,
                        entries = emptyList(),
                        tags = emptyList(),
                        tagRefs = emptyList(),
                        onSelectSpace = {},
                        onBack = {},
                        onSettings = {},
                        onNewSpace = { _, _, _, _ -> },
                        onNewEntry = {},
                        onOpenEntry = {},
                    )
                }
            }
        }
        val toolbar = compose.onNodeWithTag("dashboard-toolbar").getUnclippedBoundsInRoot()
        val navigation = compose.onNodeWithTag("space-navigation").getUnclippedBoundsInRoot()
        val grid = compose.onNodeWithTag("entries-grid").getUnclippedBoundsInRoot()
        val search =
            compose.onNodeWithContentDescription("Search entries").getUnclippedBoundsInRoot()
        val back = compose.onNodeWithContentDescription("Back to spaces").getUnclippedBoundsInRoot()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        assertTrue("Toolbar should span the full available width", toolbar.width.value >= 299f)
        assertTrue(
            "Navigation should align with the toolbar",
            navigation.left == toolbar.left && navigation.right == toolbar.right,
        )
        assertTrue(
            "Entry grid should align with the toolbar",
            grid.left == toolbar.left && grid.right == toolbar.right,
        )
        assertTrue(
            "Search gets its own usable row on narrow phones",
            search.width.value >= 250f && search.top >= back.bottom,
        )
    }
}
