package com.myvault.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.myvault.app.data.AppSettings
import com.myvault.app.data.MediaFiles

/** The web's subtle accent glows, or a user's locally stored wallpaper, behind native screens. */
@Composable
fun VaultBackdrop(settings: AppSettings, dark: Boolean, splash: Boolean) {
    val isDark = dark || splash
    val base = if (isDark) Color(0xFF191918) else Color(0xFFF6F5F2)
    val accent = parseColor(settings.accent)
    val wallpaper =
        settings.wallpaperUri.takeIf { settings.wallpaperEnabled && !it.isNullOrBlank() }
    Box(Modifier.fillMaxSize().background(base)) {
        if (wallpaper == null) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = maxOf(size.width, size.height) * 0.65f
                val glowAlpha = if (isDark) 0.16f else 0.08f
                drawRect(
                    brush =
                        Brush.radialGradient(
                            colors = listOf(accent.copy(alpha = glowAlpha), Color.Transparent),
                            center = Offset(size.width * 0.2f, size.height * 0.2f),
                            radius = radius,
                        )
                )
                drawRect(
                    brush =
                        Brush.radialGradient(
                            colors =
                                listOf(accent.copy(alpha = glowAlpha * 0.65f), Color.Transparent),
                            center = Offset(size.width * 0.8f, 0f),
                            radius = radius * 0.9f,
                        )
                )
            }
        } else {
            AsyncImage(
                model = remember(wallpaper) { MediaFiles.displayModel(wallpaper) },
                contentDescription = null,
                modifier =
                    Modifier.fillMaxSize()
                        .alpha(settings.wallpaperOpacity.coerceIn(0, 100) / 100f)
                        .blur(settings.wallpaperBlur.coerceIn(0, 20).dp),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.fillMaxSize()
                    .background(
                        if (isDark) Color.Black.copy(alpha = 0.4f)
                        else Color.White.copy(alpha = 0.3f)
                    )
            )
        }
    }
}
