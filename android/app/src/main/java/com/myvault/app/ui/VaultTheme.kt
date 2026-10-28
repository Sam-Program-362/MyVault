package com.myvault.app.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.myvault.app.data.AppSettings

fun parseColor(value: String, fallback: Color = Color(0xFF6366F1)): Color = runCatching {
    Color(android.graphics.Color.parseColor(value))
}.getOrDefault(fallback)

@Composable
fun VaultTheme(settings: AppSettings, content: @Composable (dark: Boolean) -> Unit) {
    val dark =
        when (settings.mode) {
            "light" -> false
            "dark" -> true
            else -> isSystemInDarkTheme()
        }
    val accent = parseColor(settings.accent)
    val onAccent = if (accent.luminance() > 0.55f) Color(0xFF171717) else Color.White
    val scheme =
        if (dark)
            darkColorScheme(
                primary = accent,
                onPrimary = onAccent,
                background = Color(0xFF171717),
                onBackground = Color(0xFFF5F5F5),
                surface = Color(0xFF202020),
                onSurface = Color(0xFFF5F5F5),
                surfaceVariant = Color(0xFF2C2C2C),
                onSurfaceVariant = Color(0xFFD0D0D0),
                outline = Color(0xFF767676),
            )
        else
            lightColorScheme(
                primary = accent,
                onPrimary = onAccent,
                background = Color(0xFFFAFAFA),
                onBackground = Color(0xFF171717),
                surface = Color.White,
                onSurface = Color(0xFF171717),
                surfaceVariant = Color(0xFFF1F1F1),
                onSurfaceVariant = Color(0xFF525252),
                outline = Color(0xFF737373),
            )
    val radius =
        when (settings.radius) {
            "none" -> 0
            "sm" -> 4
            "md" -> 8
            "xl" -> 20
            else -> 12
        }.dp
    val shapes =
        Shapes(
            extraSmall = RoundedCornerShape(radius / 2),
            small = RoundedCornerShape(radius),
            medium = RoundedCornerShape(radius),
            large = RoundedCornerShape(radius),
            extraLarge = RoundedCornerShape(radius * 1.5f),
        )
    val density = LocalDensity.current
    val fontMultiplier =
        when (settings.fontSize) {
            "sm" -> 0.9f
            "lg" -> 1.12f
            "xl" -> 1.25f
            else -> 1f
        }
    val activity = LocalContext.current as? Activity
    SideEffect {
        activity?.window?.let { window ->
            window.statusBarColor = scheme.background.toArgb()
            window.navigationBarColor = scheme.background.toArgb()
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * fontMultiplier)
    ) {
        MaterialTheme(colorScheme = scheme, shapes = shapes) { content(dark) }
    }
}
