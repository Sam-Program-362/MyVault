package com.myvault.app.ui

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.KeyEvent
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
        var focusAttempted = false
        var focusSucceeded = false
        var keyDownSent = false
        var keyUpSent = false
        var diagnostic = "No synthetic document row was found"
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            // If a successful action already opened the app's confirmation, do not click again.
            if (root?.findAccessibilityNodeInfosByText("Import backup?")?.isNotEmpty() == true) {
                println("MYVAULT_PICKER rowActionSent=$rowActionSent focusAttempted=$focusAttempted focusSucceeded=$focusSucceeded")
                return
            }
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
                    } ?: chain.lastOrNull()
                if (!rowActionSent && target != null && target.isEnabled &&
                    target.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                    rowActionSent = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                // Some Android 11 DocumentsUI rows advertise keyboard focus but no
                // accessibility click. Focus the actual row and send Enter, then
                // still require the app's confirmation (and later import/restart).
                if (!rowActionSent && !focusAttempted) {
                    focusAttempted = true
                    val focusTarget = chain.asReversed().firstOrNull { candidate ->
                        candidate.isEnabled && candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_FOCUS }
                    }
                    focusSucceeded = focusTarget?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) == true
                    if (focusSucceeded) {
                        val now = SystemClock.uptimeMillis()
                        keyDownSent = automation.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0), true)
                        keyUpSent = automation.injectInputEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0), true)
                    }
                }
            }
            SystemClock.sleep(250)
        }
        fail(
            "The system picker did not open the synthetic document (rowActionSent=$rowActionSent, focusAttempted=$focusAttempted, focusSucceeded=$focusSucceeded, keyDownSent=$keyDownSent, keyUpSent=$keyUpSent). Accessibility chain: $diagnostic"
        )
    }
}
