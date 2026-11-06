package com.myvault.app.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorUndoHistoryTest {
    private fun snapshot(
        title: String = "",
        content: String = "",
        field: EditorField = EditorField.Content,
        start: Int = 0,
        end: Int = start,
    ) = EditorUndoSnapshot(
        TextFieldValue(title, TextRange(start.takeIf { field == EditorField.Title } ?: title.length, end.takeIf { field == EditorField.Title } ?: title.length)),
        TextFieldValue(content, TextRange(start.takeIf { field == EditorField.Content } ?: content.length, end.takeIf { field == EditorField.Content } ?: content.length)),
        field,
    )

    @Test
    fun titleAndBodyShareHistoryAndRestoreSelections() {
        val history = EditorUndoHistory(snapshot())
        history.record(snapshot(title = "Title", field = EditorField.Title, start = 3, end = 5), EditorField.Title, atMs = 1, forceNewOperation = true)
        history.record(snapshot(title = "Title", content = "Body", field = EditorField.Content, start = 4), EditorField.Content, atMs = 2, forceNewOperation = true)

        assertEquals("Title", history.undo()?.title?.text)
        assertEquals("", history.undo()?.title?.text)
        assertFalse(history.canUndo)
        assertEquals("Title", history.redo()?.title?.text)
        val body = history.redo()
        assertEquals("Body", body?.content?.text)
        assertEquals(TextRange(4), body?.content?.selection)
        assertFalse(history.canRedo)
    }

    @Test
    fun typingCoalescesWithinWindowAndBulkInsertionStaysAtomic() {
        val history = EditorUndoHistory(snapshot())
        history.record(snapshot(content = "h", start = 1), EditorField.Content, atMs = 10)
        history.record(snapshot(content = "he", start = 2), EditorField.Content, atMs = 20)
        history.record(snapshot(content = "hello", start = 5), EditorField.Content, atMs = 30, forceNewOperation = true)
        assertEquals("he", history.undo()?.content?.text)
        assertEquals("hello", history.redo()?.content?.text)
    }

    @Test
    fun selectionOnlyChangesUpdateCaretAndEndTypingCoalescence() {
        val history = EditorUndoHistory(snapshot())
        history.record(snapshot(content = "h", start = 1), EditorField.Content, atMs = 10)
        history.updateSelection(snapshot(content = "h", start = 0, end = 1))
        history.record(snapshot(content = "ha", start = 2), EditorField.Content, atMs = 20)
        val undone = history.undo()
        assertEquals("h", undone?.content?.text)
        assertEquals(TextRange(0, 1), undone?.content?.selection)
    }

    @Test
    fun boundedHistoryEvictsOldestAndImportIsOneOperation() {
        val history = EditorUndoHistory(snapshot(), limit = 3)
        history.record(snapshot(content = "a", start = 1), EditorField.Content, atMs = 1, forceNewOperation = true)
        history.record(snapshot(content = "ab", start = 2), EditorField.Content, atMs = 2, forceNewOperation = true)
        history.record(snapshot(content = "imported\nmarkdown", start = 16), EditorField.Content, atMs = 3, forceNewOperation = true)
        assertEquals("ab", history.undo()?.content?.text)
        assertEquals("a", history.undo()?.content?.text)
        assertNull(history.undo())
    }

    @Test
    fun separateEntryHistoryStartsAtItsOwnInitialStateAndBranchesDiscardRedo() {
        val first = EditorUndoHistory(snapshot())
        first.record(snapshot(title = "first", field = EditorField.Title, start = 5), EditorField.Title, forceNewOperation = true)
        val second = EditorUndoHistory(snapshot(title = "second", field = EditorField.Title, start = 6))
        assertFalse(second.canUndo)
        assertNull(second.undo())

        first.undo()
        first.record(snapshot(title = "replacement", field = EditorField.Title, start = 11), EditorField.Title, forceNewOperation = true)
        assertFalse(first.canRedo)
    }
}
