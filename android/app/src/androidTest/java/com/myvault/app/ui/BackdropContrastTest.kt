package com.myvault.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myvault.app.data.AppSettings
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the worst-case wallpaper colors without using a real user's images. */
@RunWith(AndroidJUnit4::class)
class BackdropContrastTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun extremeWallpapersKeepTheSystemBarEdgesReadable() {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        fun wallpaper(name: String, color: Int): File {
            val file = File(directory, name)
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            return file
        }
        val bright = wallpaper("synthetic-white-wallpaper.png", android.graphics.Color.WHITE)
        val dark = wallpaper("synthetic-black-wallpaper.png", android.graphics.Color.BLACK)
        val current =
            mutableStateOf(
                AppSettings(
                    mode = "dark",
                    wallpaperEnabled = true,
                    wallpaperUri = Uri.fromFile(bright).toString(),
                    wallpaperOpacity = 100,
                )
            )
        try {
            compose.setContent {
                Box(Modifier.fillMaxSize()) {
                    VaultBackdrop(
                        current.value,
                        dark = current.value.mode == "dark",
                        splash = false,
                    )
                }
            }
            fun luminanceAt(fraction: Float): Float {
                val image = compose.onRoot().captureToImage().asAndroidBitmap()
                val result =
                    Color(image.getPixel(image.width / 2, (image.height * fraction).toInt()))
                        .luminance()
                image.recycle()
                return result
            }
            // Verify the synthetic image is loaded, rather than measuring the fallback color.
            compose.waitUntil(10_000) { luminanceAt(0.5f) > 0.25f }
            for (position in listOf(0.03f, 0.97f)) {
                val ratio = 1.05f / (luminanceAt(position) + 0.05f)
                assertTrue(
                    "White system icons need a 4.5:1 contrast on a bright wallpaper: $ratio",
                    ratio >= 4.5f,
                )
            }
            compose.runOnIdle {
                current.value =
                    current.value.copy(mode = "light", wallpaperUri = Uri.fromFile(dark).toString())
            }
            compose.waitUntil(10_000) { luminanceAt(0.5f) < 0.1f }
            for (position in listOf(0.03f, 0.97f)) {
                val ratio = (luminanceAt(position) + 0.05f) / 0.05f
                assertTrue(
                    "Dark system icons need a 4.5:1 contrast on a dark wallpaper: $ratio",
                    ratio >= 4.5f,
                )
            }
        } finally {
            bright.delete()
            dark.delete()
        }
    }
}
