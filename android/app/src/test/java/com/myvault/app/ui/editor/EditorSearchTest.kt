package com.myvault.app.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorSearchTest {
    @Test
    fun wholeWordMatchesWithoutMatchingInsideOtherWords() {
        assertEquals(listOf(0..2, 12..14), editorMatches("cat catalog CAT", "cat", true))
    }

    @Test
    fun searchAndReplacementTreatSpecialCharactersLiterally() {
        assertEquals(2, editorMatches("a+b a+b", "a+b", false).size)
        assertEquals("[$&] [$&]", replaceEditorMatches("a+b a+b", "a+b", "[$&]", false))
    }

    @Test
    fun emptySearchLeavesContentUnchanged() {
        assertEquals(emptyList<IntRange>(), editorMatches("notes", "", false))
        assertEquals("notes", replaceEditorMatches("notes", "", "x", false))
    }
}
