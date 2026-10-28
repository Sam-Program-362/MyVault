package com.myvault.app.ui

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Test-only accessibility fallback for the system document picker on disposable emulators. */
@RunWith(AndroidJUnit4::class)
class DocumentPickerActionTest {
    @Test
    fun selectSyntheticBackup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (InstrumentationRegistry.getArguments().getString("myvault.pickerPhase") != "select")
            return
        val automation = instrumentation.uiAutomation
        val deadline = SystemClock.uptimeMillis() + 12_000
        var rowActionSent = false
        var keyboardAttempted = false
        var focusAccepted = false
        var focusedWithinRow = false
        var diagnostic = "No synthetic document row was found"
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            // If a successful action already opened the app's confirmation, do not click again.
            if (root?.findAccessibilityNodeInfosByText("Import backup?")?.isNotEmpty() == true)
                return
            // Ignore the grid's separate "Preview the file ..." thumbnail node.
            val file =
                root?.findAccessibilityNodeInfosByText("myvault-ci-migration.json")?.firstOrNull {
                    it.text?.toString() == "myvault-ci-migration.json"
                }
            if (file != null) {
                val chain = mutableListOf<AccessibilityNodeInfo>()
                var node: AccessibilityNodeInfo? = file
                while (
                    node != null &&
                        node.className?.toString() != "androidx.recyclerview.widget.RecyclerView"
                ) {
                    chain.add(node)
                    node = node.parent
                }
                diagnostic =
                    chain.joinToString(" -> ") { candidate ->
                        "${candidate.className}(clickable=${candidate.isClickable}, actions=${candidate.actionList.map { it.id }})"
                    }
                // Prefer the row's advertised open action. Some DocumentsUI
                // versions attach it to a text ancestor instead; the matched text
                // is the filename, never the separate preview-thumbnail node.
                val target =
                    chain.asReversed().firstOrNull { candidate ->
                        candidate.isEnabled &&
                            candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
                    }
                val row = chain.lastOrNull()
                if (!rowActionSent && target != null) {
                    rowActionSent = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else if (!rowActionSent && !keyboardAttempted && row != null && row.isEnabled) {
                    // Google's API 30 DocumentsUI exposes a focusable row without
                    // ACTION_CLICK. Its item key handler opens the focused file on
                    // Enter. Activate it only once, and only if focus is really on
                    // this row; then wait for the app's actual confirmation.
                    keyboardAttempted = true
                    focusAccepted = row.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    if (focusAccepted) {
                        SystemClock.sleep(200)
                        val rowBounds = Rect().also(row::getBoundsInScreen)
                        val focused =
                            automation.rootInActiveWindow?.findFocus(
                                AccessibilityNodeInfo.FOCUS_INPUT
                            )
                        val focusedBounds = Rect()
                        focused?.getBoundsInScreen(focusedBounds)
                        focusedWithinRow = focused != null && rowBounds.contains(focusedBounds)
                        if (focusedWithinRow) {
                            val downTime = SystemClock.uptimeMillis()
                            fun enterKey(action: Int) =
                                KeyEvent(
                                    downTime,
                                    SystemClock.uptimeMillis(),
                                    action,
                                    KeyEvent.KEYCODE_ENTER,
                                    0,
                                    0,
                                    KeyCharacterMap.VIRTUAL_KEYBOARD,
                                    0,
                                    KeyEvent.FLAG_FROM_SYSTEM,
                                    InputDevice.SOURCE_KEYBOARD,
                                )
                            val downSent =
                                automation.injectInputEvent(enterKey(KeyEvent.ACTION_DOWN), true)
                            val upSent =
                                automation.injectInputEvent(enterKey(KeyEvent.ACTION_UP), true)
                            rowActionSent = downSent && upSent
                        }
                    }
                }
            }
            SystemClock.sleep(250)
        }
        fail(
            "The system picker did not open the synthetic document (rowActionSent=$rowActionSent, keyboardAttempted=$keyboardAttempted, focusAccepted=$focusAccepted, focusedWithinRow=$focusedWithinRow). Accessibility chain: $diagnostic"
        )
    }
}
