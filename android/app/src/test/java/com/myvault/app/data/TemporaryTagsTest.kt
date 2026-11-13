package com.myvault.app.data

import com.myvault.app.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporaryTagsTest {
    @Test
    fun settingsRepositoryReportsBuildConfigDefaultOff() {
        assertFalse(BuildConfig.TAG_STAGING_ENABLED)
        assertFalse(SettingsRepository.tagStagingEnabled)
    }

    @Test
    fun missingOrFalseConfigurationStaysOffAndOnlyTrueEnables() {
        assertFalse(SettingsRepository.isTagStagingEnabled(null))
        assertFalse(SettingsRepository.isTagStagingEnabled(false))
        assertTrue(SettingsRepository.isTagStagingEnabled(true))
    }
}
