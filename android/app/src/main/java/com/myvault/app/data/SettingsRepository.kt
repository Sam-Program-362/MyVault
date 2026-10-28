package com.myvault.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

private val Context.dataStore by preferencesDataStore(name = "settings")

@Serializable
data class AppSettings(
    val appName: String = "MyVault",
    val accent: String = "#6366F1",
    val mode: String = "system", // light | dark | system
    val fontSize: String = "md", // sm | md | lg | xl
    val radius: String = "lg", // none | sm | md | lg | xl
    val wallpaperUri: String? = null,
    val wallpaperEnabled: Boolean = false,
    val wallpaperOpacity: Int = 35,
    val wallpaperBlur: Int = 0,
)

/**
 * Settings (theme, wallpaper, app name) are stored locally via Jetpack DataStore, so they persist
 * across launches without ever touching the network — mirroring the web app's "settings save on
 * your device" behavior.
 */
class SettingsRepository(private val context: Context) {
    private object Keys {
        val APP_NAME = stringPreferencesKey("app_name")
        val ACCENT = stringPreferencesKey("accent")
        val MODE = stringPreferencesKey("mode")
        val FONT_SIZE = stringPreferencesKey("font_size")
        val RADIUS = stringPreferencesKey("radius")
        val WALLPAPER_URI = stringPreferencesKey("wallpaper_uri")
        val WALLPAPER_ENABLED = booleanPreferencesKey("wallpaper_enabled")
        val WALLPAPER_OPACITY = intPreferencesKey("wallpaper_opacity")
        val WALLPAPER_BLUR = intPreferencesKey("wallpaper_blur")
        val ENTRY_LAYOUT = stringPreferencesKey("entry_layout")
    }

    val settingsFlow: Flow<AppSettings> =
        context.dataStore.data.map { prefs ->
            AppSettings(
                appName = prefs[Keys.APP_NAME] ?: "MyVault",
                accent = prefs[Keys.ACCENT] ?: "#6366F1",
                mode = prefs[Keys.MODE] ?: "system",
                fontSize = prefs[Keys.FONT_SIZE] ?: "md",
                radius = prefs[Keys.RADIUS] ?: "lg",
                wallpaperUri = prefs[Keys.WALLPAPER_URI],
                wallpaperEnabled = prefs[Keys.WALLPAPER_ENABLED] ?: false,
                wallpaperOpacity = prefs[Keys.WALLPAPER_OPACITY] ?: 35,
                wallpaperBlur = prefs[Keys.WALLPAPER_BLUR] ?: 0,
            )
        }

    // This Android-only preference lives in the same DataStore, but is not part of
    // AppSettings or the web-compatible JSON backup format.
    val entryLayoutFlow: Flow<String> =
        context.dataStore.data.map { prefs ->
            if (prefs[Keys.ENTRY_LAYOUT] == "list") "list" else "cards"
        }

    suspend fun updateEntryLayout(layout: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ENTRY_LAYOUT] = if (layout == "list") "list" else "cards"
        }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val current =
                AppSettings(
                    appName = prefs[Keys.APP_NAME] ?: "MyVault",
                    accent = prefs[Keys.ACCENT] ?: "#6366F1",
                    mode = prefs[Keys.MODE] ?: "system",
                    fontSize = prefs[Keys.FONT_SIZE] ?: "md",
                    radius = prefs[Keys.RADIUS] ?: "lg",
                    wallpaperUri = prefs[Keys.WALLPAPER_URI],
                    wallpaperEnabled = prefs[Keys.WALLPAPER_ENABLED] ?: false,
                    wallpaperOpacity = prefs[Keys.WALLPAPER_OPACITY] ?: 35,
                    wallpaperBlur = prefs[Keys.WALLPAPER_BLUR] ?: 0,
                )
            val next = transform(current)
            prefs[Keys.APP_NAME] = next.appName
            prefs[Keys.ACCENT] = next.accent
            prefs[Keys.MODE] = next.mode
            prefs[Keys.FONT_SIZE] = next.fontSize
            prefs[Keys.RADIUS] = next.radius
            next.wallpaperUri?.let { prefs[Keys.WALLPAPER_URI] = it }
                ?: prefs.remove(Keys.WALLPAPER_URI)
            prefs[Keys.WALLPAPER_ENABLED] = next.wallpaperEnabled
            prefs[Keys.WALLPAPER_OPACITY] = next.wallpaperOpacity
            prefs[Keys.WALLPAPER_BLUR] = next.wallpaperBlur
        }
    }
}
