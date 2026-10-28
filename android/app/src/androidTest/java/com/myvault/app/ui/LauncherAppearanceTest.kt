package com.myvault.app.ui

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Only run against a disposable emulator: opens its launcher and captures synthetic CI state. */
@RunWith(AndroidJUnit4::class)
class LauncherAppearanceTest {
    @Test
    fun launcherAppDrawerShowsTheMyVaultIcon() {
        if (InstrumentationRegistry.getArguments().getString("myvault.capture") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        fun shell(command: String) {
            automation.executeShellCommand(command).close()
        }
        shell("input keyevent KEYCODE_HOME")
        Thread.sleep(800)
        val dimensions = requireNotNull(automation.takeScreenshot())
        val width = dimensions.width
        val height = dimensions.height
        dimensions.recycle()
        // Swipe up into the system launcher's app drawer, not an app-provided icon preview.
        shell("input swipe ${width / 2} ${height * 9 / 10} ${width / 2} ${height / 4} 450")
        Thread.sleep(1200)
        var icons =
            automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("MyVault").orEmpty()
        repeat(3) {
            if (icons.isEmpty()) {
                shell("input swipe ${width / 2} ${height * 3 / 4} ${width / 2} ${height / 3} 350")
                Thread.sleep(500)
                icons =
                    automation.rootInActiveWindow
                        ?.findAccessibilityNodeInfosByText("MyVault")
                        .orEmpty()
            }
        }
        assertTrue("The system launcher should show MyVault in its app drawer", icons.isNotEmpty())
        val screenshot = automation.takeScreenshot()
        assertNotNull("Could not capture the system launcher", screenshot)
        val image = requireNotNull(screenshot)
        val directory = File(instrumentation.targetContext.filesDir, "synthetic-captures")
        directory.mkdirs()
        File(directory, "launcher.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val bounds = Rect().also { icons.first().getBoundsInScreen(it) }
        val left = (bounds.left - 8).coerceIn(0, image.width - 1)
        val top = (bounds.top - 8).coerceIn(0, image.height - 1)
        val right = (bounds.right + 8).coerceIn(left + 1, image.width)
        val bottom = (bounds.bottom + 8).coerceIn(top + 1, image.height)
        val crop = Bitmap.createBitmap(image, left, top, right - left, bottom - top)
        val preview = Bitmap.createScaledBitmap(crop, 140, crop.height * 140 / crop.width, true)
        File(directory, "launcher.jpg").outputStream().use {
            preview.compress(Bitmap.CompressFormat.JPEG, 35, it)
        }
        preview.recycle()
        crop.recycle()
        image.recycle()
    }
}
