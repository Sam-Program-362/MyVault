package com.myvault.app.ui

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myvault.app.MainActivity
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
        val context = instrumentation.targetContext
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
        // Inspect the system launcher's app drawer, not an app-provided icon preview.
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
        val directory = File(context.filesDir, "synthetic-captures")
        directory.mkdirs()
        File(directory, "launcher.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val bounds = Rect().also { icons.first().getBoundsInScreen(it) }
        image.recycle()

        val homeCapture = File(directory, "launcher-home.png")
        val homeMethod = File(directory, "launcher-home-method.txt")
        homeCapture.delete()
        homeMethod.delete()
        fun captureHome(method: String): Boolean {
            val root = automation.rootInActiveWindow ?: return false
            if (root.packageName?.toString() == context.packageName) return false
            if (root.findAccessibilityNodeInfosByText("MyVault").isEmpty()) return false
            val home = requireNotNull(automation.takeScreenshot())
            homeCapture.outputStream().use { home.compress(Bitmap.CompressFormat.PNG, 100, it) }
            home.recycle()
            homeMethod.writeText(method)
            return true
        }

        // First try dragging the actual app icon from the drawer to the emulator home screen.
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
            captureHome("app icon")
        }
            .onFailure {
                if (pressed) runCatching { pointer(MotionEvent.ACTION_CANCEL, startX, startY) }
            }

        // If that launcher does not support dragging, try its standard pinned-shortcut prompt,
        // using the same launcher resource. This is only attempted on the disposable emulator.
        if (!homeCapture.exists() && Build.VERSION.SDK_INT >= 26)
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                Thread.sleep(800)
                val manager = context.getSystemService(ShortcutManager::class.java)
                if (manager != null && manager.isRequestPinShortcutSupported) {
                    val shortcut =
                        ShortcutInfo.Builder(context, "myvault-ci-home")
                            .setShortLabel("MyVault")
                            .setIcon(Icon.createWithResource(context, context.applicationInfo.icon))
                            .setIntent(
                                Intent(context, MainActivity::class.java)
                                    .setAction(Intent.ACTION_VIEW)
                            )
                            .build()
                    if (manager.requestPinShortcut(shortcut, null)) {
                        Thread.sleep(1000)
                        val root = automation.rootInActiveWindow
                        val labels = listOf("Add automatically", "Add to Home screen", "Add")
                        val button =
                            labels
                                .asSequence()
                                .flatMap { label ->
                                    root
                                        ?.findAccessibilityNodeInfosByText(label)
                                        .orEmpty()
                                        .asSequence()
                                }
                                .firstOrNull { it.isClickable || it.parent?.isClickable == true }
                        val clickable = if (button?.isClickable == true) button else button?.parent
                        if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                            Thread.sleep(1200)
                            shell("input keyevent KEYCODE_HOME")
                            Thread.sleep(800)
                            captureHome("pinned shortcut using the launcher icon")
                        }
                    }
                }
            }
    }
}
