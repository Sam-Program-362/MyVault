package com.myvault.app.ui

import android.os.SystemClock
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
        var diagnostic = "No synthetic document row was found"
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            // If a successful action already opened the app's confirmation, do not click again.
            if (root?.findAccessibilityNodeInfosByText("Import backup?")?.isNotEmpty() == true)
                return
            val file =
                root?.findAccessibilityNodeInfosByText("myvault-ci-migration.json")?.firstOrNull()
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
                // The RecyclerView's item delegate routes a row ACTION_CLICK to
                // openItem when nothing is selected. Clicking a child instead can
                // merely toggle selection and report success without opening it.
                val row = chain.lastOrNull()
                if (!rowActionSent && row != null && row.isEnabled) {
                    rowActionSent = row.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            SystemClock.sleep(250)
        }
        fail(
            "The system picker did not open the synthetic document (rowActionSent=$rowActionSent). Accessibility chain: $diagnostic"
        )
    }
}
