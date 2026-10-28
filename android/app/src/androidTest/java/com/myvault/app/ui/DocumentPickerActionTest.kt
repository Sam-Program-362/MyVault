package com.myvault.app.ui

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
        val root = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        val file = root.findAccessibilityNodeInfosByText("myvault-ci-migration.json").firstOrNull()
        assertNotNull("The synthetic backup must be visible in the system picker", file)
        var row = file?.parent
        while (
            row != null &&
                row.parent?.className?.toString() != "androidx.recyclerview.widget.RecyclerView"
        ) {
            row = row.parent
        }
        assertNotNull("The document picker must expose its file row", row)
        assertTrue(
            "The document picker must activate the synthetic backup through accessibility",
            requireNotNull(row).performAction(AccessibilityNodeInfo.ACTION_CLICK),
        )
    }
}
