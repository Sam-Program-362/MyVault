package com.myvault.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class EntryTextTest {
    @Test
    fun countsLogicalLinesWithoutDependingOnScreenWidth() {
        assertEquals(0, entryLineCount(""))
        assertEquals(
            1,
            entryLineCount("a very long single line that could wrap on a narrow screen"),
        )
        assertEquals(1, entryLineCount("  "))
        assertEquals(2, entryLineCount("first\nsecond"))
        assertEquals(2, entryLineCount("first\r\nsecond"))
        assertEquals(3, entryLineCount("one\rtwo\r\nthree"))
        assertEquals(3, entryLineCount("# Heading\n\n- Markdown item"))
        assertEquals(2, entryLineCount("trailing newline\n"))
        assertEquals(3, entryLineCount("\n\n"))
    }

    @Test
    fun listPreviewKeepsContentLinesButRemovesCommonMarkdownAndImages() {
        assertEquals(
            "Heading\nRead link\nfinish",
            listTextPreview(
                "# Heading\r\nRead [link](https://example.test)\r![image](local)finish"
            ),
        )
        assertEquals("", listTextPreview(""))
    }
}
