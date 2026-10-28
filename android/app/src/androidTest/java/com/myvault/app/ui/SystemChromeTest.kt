package com.myvault.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myvault.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemChromeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun drawsBehindSystemBarsWhileKeepingWelcomeActionsInSafeInsets() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty()
        }
        val bounds = compose.onNodeWithContentDescription("Settings").getUnclippedBoundsInRoot()
        val rootSize = compose.onRoot().fetchSemanticsNode().size
        compose.runOnIdle {
            val activity = compose.activity
            val decor = activity.window.decorView
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(decor))
            val safe =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.systemGestures()
                )
            val density = activity.resources.displayMetrics.density
            assertEquals(
                "Compose content should fill the window behind the bars",
                decor.height,
                rootSize.height,
            )
            assertEquals(Color.TRANSPARENT, activity.window.statusBarColor)
            if (Build.VERSION.SDK_INT >= 26) {
                assertEquals(Color.TRANSPARENT, activity.window.navigationBarColor)
            }
            assertFalse(
                WindowCompat.getInsetsController(activity.window, decor).isAppearanceLightStatusBars
            )
            assertTrue(
                "Settings must clear the top inset",
                bounds.top.value * density + 1 >= safe.top,
            )
            assertTrue(
                "Settings must clear the right inset",
                bounds.right.value * density <= rootSize.width - safe.right + 1,
            )
            if (
                InstrumentationRegistry.getArguments().getString("myvault.expectCutout") == "true"
            ) {
                assertNotNull(
                    "The emulated cutout must be present for this test",
                    insets.displayCutout,
                )
            }
        }
    }

    @Test
    fun launcherUsesAdaptiveLayersWithTheMarkInsideTheSafeArea() {
        if (Build.VERSION.SDK_INT < 26) return
        compose.runOnIdle {
            val activity = compose.activity
            val icon = activity.packageManager.getApplicationIcon(activity.packageName)
            assertTrue(
                "Launcher should expose adaptive foreground and background",
                icon is AdaptiveIconDrawable,
            )
            icon as AdaptiveIconDrawable
            assertNotNull(icon.background)
            if (Build.VERSION.SDK_INT >= 33)
                assertNotNull("Themed launcher icon is missing", icon.monochrome)
            val foreground = requireNotNull(icon.foreground)
            val bitmap = Bitmap.createBitmap(216, 216, Bitmap.Config.ARGB_8888)
            foreground.setBounds(0, 0, bitmap.width, bitmap.height)
            foreground.draw(Canvas(bitmap))
            var left = bitmap.width
            var right = -1
            var top = bitmap.height
            var bottom = -1
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 8) {
                    left = minOf(left, x)
                    right = maxOf(right, x)
                    top = minOf(top, y)
                    bottom = maxOf(bottom, y)
                }
            }
            assertTrue("Foreground should contain a visible mark", right > left && bottom > top)
            assertTrue(
                "Foreground must remain within adaptive mask safe bounds",
                left >= 42 && right < 174 && top >= 42 && bottom < 174,
            )
            bitmap.recycle()
        }
    }
}
