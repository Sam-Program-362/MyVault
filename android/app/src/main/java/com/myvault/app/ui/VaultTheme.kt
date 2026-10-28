package com.myvault.app.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
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
}
    .getOrDefault(fallback)

@Composable
fun VaultTheme(
    settings: AppSettings,
    forceDarkSystemBars: Boolean = false,
    content: @Composable (dark: Boolean) -> Unit,
) {
    val dark =
        when (settings.mode) {
            "light" -> false
            "dark" -> true
            else -> isSystemInDarkTheme()
        }
    val accent = parseColor(settings.accent)
    val onAccent = if (accent.luminance() > 0.179f) Color(0xFF171717) else Color.White
    val scheme =
        if (dark)
            darkColorScheme(
                primary = accent,
                onPrimary = onAccent,
                background = Color(0xFF191918),
                onBackground = Color(0xFFEAE9E4),
                surface = Color(0xFF202020),
                onSurface = Color(0xFFEAE9E4),
                surfaceVariant = Color(0xFF2C2C2B),
                onSurfaceVariant = Color(0xFFB8B7B1),
                outline = Color(0xFF767676),
            )
        else
            lightColorScheme(
                primary = accent,
                onPrimary = onAccent,
                background = Color(0xFFF6F5F2),
                onBackground = Color(0xFF292825),
                surface = Color.White,
                onSurface = Color(0xFF292825),
                surfaceVariant = Color(0xFFF2F1EE),
                onSurfaceVariant = Color(0xFF64635E),
                outline = Color(0xFF737373),
            )
    val radius =
        when (settings.radius) {
            "none" -> 0
            "sm" -> 6
            "md" -> 11
            "xl" -> 24
            else -> 16
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
            val darkBars = dark || forceDarkSystemBars
            window.statusBarColor = Color.Transparent.toArgb()
            // Android 7 cannot draw dark navigation icons. A translucent scrim
            // keeps its white icons readable over a light writing surface.
            window.navigationBarColor =
                if (Build.VERSION.SDK_INT < 26 && !darkBars)
                    Color.Black.copy(alpha = 0.32f).toArgb()
                else Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !darkBars
                isAppearanceLightNavigationBars = !darkBars
            }
        }
    }
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * fontMultiplier)
    ) {
        MaterialTheme(colorScheme = scheme, shapes = shapes) {
            // MaterialTheme does not set LocalContentColor itself. The app draws its
            // backdrop in a Box rather than a Surface, so provide readable defaults
            // for text and icons that do not declare their own color.
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
                content(dark)
            }
        }
    }
}
