package com.myvault.app.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myvault.app.MainActivity
import com.myvault.app.MyVaultApplication
import com.myvault.app.data.AppDatabase
import com.myvault.app.data.BackupManager
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Executed in separate instrumentation processes with actual force-stops between phases. */
@RunWith(AndroidJUnit4::class)
class OfflineRestartTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun airplaneModeForceStopAndRestart() {
        // The normal connected suite also discovers this test. Its real phases are launched
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
            "verifyLayout" -> verifyLayoutAfterForceStop(app)
            "exportForPreview" -> exportForPreview(app)
            else -> error("Unknown offline restart phase: $phase")
        }
    }

    private fun seedOfflineVault(app: MyVaultApplication) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("New space").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("New space").performClick()
        compose.onNodeWithText("Space name").performTextInput("Offline space")
        val newWelcome =
            compose.onAllNodesWithText("Create & enter").fetchSemanticsNodes().isNotEmpty()
        if (newWelcome) compose.onNodeWithText("Create & enter").performScrollTo().performClick()
        else compose.onNodeWithText("Create").performClick()
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
        val body = compose.onNodeWithContentDescription("Entry content")
        if (newWelcome) body.performScrollTo()
        body.performTextInput("# Still here\nSaved without a network.")
        compose.onNodeWithContentDescription("Pin entry").performClick()
        compose.onNodeWithContentDescription("Close editor").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Offline note").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Settings").performClick()
        val dark = compose.onNodeWithText("Dark")
        if (newWelcome) dark.performScrollTo()
        dark.performClick()
        compose.waitUntil(15_000) {
            runBlocking { app.settings.settingsFlow.first().mode == "dark" }
        }
        compose.onNodeWithContentDescription("Back").performClick()

        // A private image in the old install exercises both cover and wallpaper paths across
        // update.
        val image = File(app.filesDir, "offline-fixture.png")
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.MAGENTA)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val imageUri = Uri.fromFile(image).toString()
        runBlocking {
            val entry = app.database.entryDao().getAll().first { it.title == "Offline note" }
            app.database.entryDao().update(entry.copy(coverImagePath = imageUri))
            app.settings.update { it.copy(wallpaperUri = imageUri, wallpaperEnabled = true) }
        }
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
        compose.onNodeWithContentDescription("Entry cover").assertIsDisplayed()
        val preferences = runBlocking { app.settings.settingsFlow.first() }
        assertEquals("dark", preferences.mode)
        assertTrue(preferences.wallpaperEnabled)
        val entry = runBlocking {
            app.database.entryDao().getAll().first { it.title == "Offline note" }
        }
        assertEquals(entry.coverImagePath, preferences.wallpaperUri)
        val image = File(requireNotNull(Uri.parse(requireNotNull(entry.coverImagePath)).path))
        assertTrue(image.isFile)
        assertNotNull(BitmapFactory.decodeFile(image.path))

        // Export and merge-import the updated vault while the emulator remains offline.
        val backup = File(app.cacheDir, "offline-upgrade-export.json")
        val restored = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            runBlocking {
                app.backups.exportTo(Uri.fromFile(backup))
                assertTrue(backup.readText().contains("data:image/png;base64,"))
                val result =
                    BackupManager(app, restored, app.settings, app.media)
                        .importFrom(Uri.fromFile(backup), applySettings = false)
                assertTrue(result.entries >= 1)
                val imported = restored.entryDao().getAll().first { it.title == "Offline note" }
                assertEquals("# Still here\nSaved without a network.", imported.content)
                assertTrue(imported.pinned)
                assertTrue(restored.entryDao().tagsForEntry(imported.id).isEmpty())
                assertNotNull(imported.coverImagePath)
            }
        } finally {
            restored.close()
            backup.delete()
        }

        // The preference is Android-only and is set after updating the previous APK.
        compose.onNodeWithContentDescription("Close editor").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("List view").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("List view").performClick()
        compose.waitUntil(15_000) { runBlocking { app.settings.entryLayoutFlow.first() == "list" } }
    }

    private fun exportForPreview(app: MyVaultApplication) {
        // This phase runs only on the guarded disposable emulator. A real MediaStore document
        // lets the other package import the backup through Android's document picker.
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "myvault-ci-migration.json")
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        val uri =
            requireNotNull(
                app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            )
        runBlocking { app.backups.exportTo(uri) }
        val contents =
            requireNotNull(app.contentResolver.openInputStream(uri)).bufferedReader().use {
                it.readText()
            }
        assertTrue(contents.contains("Offline note"))
        assertTrue(contents.contains("data:image/png;base64,"))
    }

    private fun verifyLayoutAfterForceStop(app: MyVaultApplication) {
        assertEquals("list", runBlocking { app.settings.entryLayoutFlow.first() })
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Offline space").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Offline space").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Offline note").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("List view").assertIsSelected()
        compose.onNodeWithText("2 lines").assertIsDisplayed()
        compose.onNodeWithContentDescription("Expand Offline note").performClick()
        compose.onNodeWithContentDescription("Collapse Offline note").assertIsDisplayed()
        compose.onNodeWithText("Offline note").performClick()
        compose.waitUntil(15_000) {
            compose
                .onAllNodesWithContentDescription("Entry content")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose
            .onNodeWithContentDescription("Entry content")
            .assertTextContains("Saved without a network.", substring = true)
    }
}
