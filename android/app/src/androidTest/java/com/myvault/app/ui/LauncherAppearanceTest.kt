package com.myvault.app.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
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
        fun savePreview(bitmap: Bitmap, bounds: Rect) {
            val left = (bounds.left - 8).coerceIn(0, bitmap.width - 1)
            val top = (bounds.top - 8).coerceIn(0, bitmap.height - 1)
            val right = (bounds.right + 8).coerceIn(left + 1, bitmap.width)
            val bottom = (bounds.bottom + 8).coerceIn(top + 1, bitmap.height)
            val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            val preview = Bitmap.createScaledBitmap(crop, 140, crop.height * 140 / crop.width, true)
            File(directory, "launcher.jpg").outputStream().use {
                preview.compress(Bitmap.CompressFormat.JPEG, 35, it)
            }
            preview.recycle()
            crop.recycle()
        }
        val bounds = Rect().also { icons.first().getBoundsInScreen(it) }
        savePreview(image, bounds)
        image.recycle()

        // Try adding the real launcher icon to the disposable emulator's home screen.
        // Some launchers disable drag-to-home; the app-drawer check above remains mandatory.
        val homeCapture = File(directory, "launcher-home.png")
        homeCapture.delete()
        val startX = bounds.exactCenterX()
        val startY = bounds.exactCenterY()
        val down = SystemClock.uptimeMillis()
        var pressed = false
        fun pointer(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            try {
                check(automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        runCatching {
            pointer(MotionEvent.ACTION_DOWN, startX, startY)
            pressed = true
            Thread.sleep(900)
            pointer(MotionEvent.ACTION_MOVE, startX, startY - 50f)
            Thread.sleep(700)
            val targetX = width / 2f
            val targetY = height / 2f
            for (step in 1..20) {
                val fraction = step / 20f
                pointer(
                    MotionEvent.ACTION_MOVE,
                    startX + (targetX - startX) * fraction,
                    startY - 50f + (targetY - (startY - 50f)) * fraction,
                )
                Thread.sleep(20)
            }
            pointer(MotionEvent.ACTION_UP, targetX, targetY)
            pressed = false
            Thread.sleep(800)
            shell("input keyevent KEYCODE_HOME")
            Thread.sleep(800)
            val homeIcons =
                automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("MyVault").orEmpty()
            if (homeIcons.isNotEmpty()) {
                val home = requireNotNull(automation.takeScreenshot())
                homeCapture.outputStream().use { home.compress(Bitmap.CompressFormat.PNG, 100, it) }
                savePreview(home, Rect().also { homeIcons.first().getBoundsInScreen(it) })
                home.recycle()
            }
        }
            .onFailure {
                if (pressed) runCatching { pointer(MotionEvent.ACTION_CANCEL, startX, startY) }
            }
    }
}
