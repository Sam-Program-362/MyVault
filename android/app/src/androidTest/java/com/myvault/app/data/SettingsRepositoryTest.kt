package com.myvault.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myvault.app.MyVaultApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {
    @Test
    fun layoutPreferenceSurvivesNewRepositoryAndOtherSettingsUpdates() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MyVaultApplication>()
        val original = app.settings.settingsFlow.first()
        val originalLayout = app.settings.entryLayoutFlow.first()
        try {
            app.settings.updateEntryLayout("list")
            val reopened = SettingsRepository(app)
            assertEquals("list", reopened.entryLayoutFlow.first())
            reopened.update { it.copy(appName = "Test vault") }
            assertEquals("list", app.settings.entryLayoutFlow.first())
            reopened.updateEntryLayout("invalid")
            assertEquals("cards", app.settings.entryLayoutFlow.first())
        } finally {
            app.settings.update { original }
            app.settings.updateEntryLayout(originalLayout)
        }
    }
}
