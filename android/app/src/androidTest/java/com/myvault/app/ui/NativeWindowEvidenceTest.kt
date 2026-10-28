package com.myvault.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myvault.app.MainActivity
import com.myvault.app.MyVaultApplication
import com.myvault.app.data.EntryEntity
import com.myvault.app.data.SpaceEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in only: real Activity/window and real Room data, on a disposable CI emulator. */
@RunWith(AndroidJUnit4::class)
class NativeWindowEvidenceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun captureActualActivityAndMeasureWindowInsets() {
        val phase = InstrumentationRegistry.getArguments().getString("myvault.evidence") ?: return
        require(phase == "before" || phase == "after")
        val app = ApplicationProvider.getApplicationContext<MyVaultApplication>()
        // Upsert only these uniquely named CI fixtures. Never clear Room or delete app data.
        runBlocking {
            app.settings.update { it.copy(mode = "dark", appName = "MyVault") }
            app.settings.updateEntryLayout("cards")
            app.database
                .spaceDao()
                .upsert(
                    SpaceEntity(id = "ci-evidence-populated", name = "Sample space", sortOrder = -2)
                )
            app.database
                .spaceDao()
                .upsert(SpaceEntity(id = "ci-evidence-empty", name = "Empty space", sortOrder = -1))
            app.database
                .entryDao()
                .upsert(
                    EntryEntity(
                        id = "ci-evidence-entry",
                        spaceId = "ci-evidence-populated",
                        title = "Evidence entry",
                        content = "# A sample note\nSecond line\nThird line",
                    )
                )
        }
        val dir = File(app.filesDir, "native-window-evidence/$phase").apply { mkdirs() }
        val log = File(dir, "window-metrics.txt")
        log.writeText("MyVault real MainActivity, phase=$phase; synthetic Room fixtures.\n")
        fun waitText(text: String) {
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
        }
        fun waitDescription(description: String) {
            compose.waitUntil(15_000) {
                compose
                    .onAllNodesWithContentDescription(description)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        }
        fun screenshot(name: String) {
            compose.waitForIdle()
            val bitmap =
                requireNotNull(
                    InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                )
            File(dir, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        fun recordInsets(screen: String) {
            val root = compose.onRoot().fetchSemanticsNode().size
            val activity = compose.activity
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(activity.window.decorView))
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val gestures = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val settings =
                compose.onNodeWithContentDescription("Settings").getUnclippedBoundsInRoot()
            val landmarks =
                if (screen == "welcome") {
                    val description =
                        compose.onNodeWithText("Pick a space to jump into", substring = true)
                            .getUnclippedBoundsInRoot()
                    val firstCardOptions =
                        compose.onNodeWithContentDescription("Options for Sample space")
                            .getUnclippedBoundsInRoot()
                    if (phase == "after") {
                        val gap = (firstCardOptions.top - description.bottom).value
                        assertTrue("Real welcome heading-to-card gap is $gap dp", gap in 0f..60f)
                    }
                    "descriptionBottomDp=${description.bottom} firstCardTopDp=${firstCardOptions.top}"
                } else {
                    val back =
                        compose.onNodeWithContentDescription("Back to spaces")
                            .getUnclippedBoundsInRoot()
                    "backDp=$back"
                }
            val line =
                "$screen: decor=${activity.window.decorView.width}x${activity.window.decorView.height}px " +
                    "compose=${root.width}x${root.height}px density=${activity.resources.displayMetrics.density} " +
                    "systemBars=$bars cutout=$cutout gestures=$gestures ime=$ime settingsDp=$settings $landmarks\n"
            log.appendText(line)
            println("MYVAULT_WINDOW $line")
        }

        waitText("Sample space")
        waitDescription("Settings")
        recordInsets("welcome")
        screenshot("welcome")
        compose.onNodeWithContentDescription("Options for Sample space").performClick()
        compose.onNodeWithText("Edit space").performClick()
        waitText("Space name")
        screenshot("space-dialog")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Empty space").performClick()
        waitText("No entries yet. Create your first one.")
        screenshot("dashboard-empty")
        compose.onNodeWithText("Sample space").performClick()
        waitText("Evidence entry")
        recordInsets("dashboard")
        screenshot("dashboard-cards")
        compose.onNodeWithContentDescription("List view").performClick()
        waitDescription("Expand Evidence entry")
        screenshot("dashboard-list")
        compose.onNodeWithContentDescription("Expand Evidence entry").performClick()
        screenshot("dashboard-list-expanded")
        compose.onNodeWithText("Evidence entry").performClick()
        waitDescription("Entry content")
        screenshot("editor")
        compose.onNodeWithContentDescription("Entry content").performClick()
        compose.waitUntil(15_000) {
            val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())
        }
        screenshot("editor-keyboard")
        assertTrue(
            "The real keyboard should produce an IME inset",
            requireNotNull(ViewCompat.getRootWindowInsets(compose.activity.window.decorView))
                .getInsets(WindowInsetsCompat.Type.ime())
                .bottom > 0,
        )
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            )
        compose.onNodeWithContentDescription("Close editor").performClick()
        waitDescription("Settings")
        compose.onNodeWithContentDescription("Settings").performClick()
        waitText("Settings")
        screenshot("settings")
    }
}
