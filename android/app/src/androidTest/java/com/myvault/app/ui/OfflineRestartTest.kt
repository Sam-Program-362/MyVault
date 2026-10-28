package com.myvault.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myvault.app.MainActivity
import com.myvault.app.MyVaultApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Executed in two separate instrumentation processes with an actual force-stop between them. */
@RunWith(AndroidJUnit4::class)
class OfflineRestartTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun airplaneModeForceStopAndRestart() {
        // The normal connected suite also discovers this test. Its two real phases are launched
        // by ci/run-offline-restart-test.sh, which controls the emulator and keeps app data
        // installed.
        val phase =
            InstrumentationRegistry.getArguments().getString("myvault.restartPhase") ?: return
        val app = ApplicationProvider.getApplicationContext<MyVaultApplication>()
        assertEquals(
            1,
            Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0),
        )
        @Suppress("DEPRECATION")
        val permissions =
            app.packageManager
                .getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                .orEmpty()
                .toSet()
        assertFalse(
            "The standalone vault must not request internet access",
            Manifest.permission.INTERNET in permissions,
        )

        when (phase) {
            "seed" -> seedOfflineVault(app)
            "verify" -> verifyAfterForceStop(app)
            else -> error("Unknown offline restart phase: $phase")
        }
    }

    private fun seedOfflineVault(app: MyVaultApplication) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("New space").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("New space").performClick()
        compose.onNodeWithText("Space name").performTextInput("Offline space")
        compose.onNodeWithText("Create & enter").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("New entry").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("New entry").performClick()
        compose.waitUntil(15_000) {
            compose
                .onAllNodesWithContentDescription("Entry title")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithContentDescription("Entry title").performTextInput("Offline note")
        compose
            .onNodeWithContentDescription("Entry content")
            .performScrollTo()
            .performTextInput("# Still here\nSaved without a network.")
        compose.onNodeWithContentDescription("Pin entry").performClick()
        compose.onNodeWithText("Add tag…", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Find or create a tag").performTextInput("Offline tag")
        compose.onNodeWithText("Create & add tag").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            compose
                .onAllNodesWithContentDescription("Remove Offline tag tag")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithContentDescription("Close editor").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Offline note").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Dark").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            runBlocking { app.settings.settingsFlow.first().mode == "dark" }
        }
        compose.onNodeWithContentDescription("Back").performClick()
    }

    private fun verifyAfterForceStop(app: MyVaultApplication) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Offline space").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Offline space").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Offline note").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Offline note").performClick()
        compose.waitUntil(15_000) {
            compose
                .onAllNodesWithContentDescription("Entry content")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithContentDescription("Entry title").assertTextContains("Offline note")
        compose
            .onNodeWithContentDescription("Entry content")
            .assertTextContains("Saved without a network.", substring = true)
        compose.onNodeWithContentDescription("Unpin entry").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove Offline tag tag").assertIsDisplayed()
        assertEquals("dark", runBlocking { app.settings.settingsFlow.first().mode })
    }
}
