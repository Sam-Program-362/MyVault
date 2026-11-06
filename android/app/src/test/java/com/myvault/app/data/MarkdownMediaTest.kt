package com.myvault.app.data

import androidx.compose.ui.text.TextRange
import com.myvault.app.ui.editor.EditorField
import com.myvault.app.ui.editor.EditorUndoHistory
import com.myvault.app.ui.editor.EditorUndoSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownMediaTest {
    @Test
    fun insertsAtSelectionWithFilenameAltAndOneUndoStep() {
        val before = EditorUndoSnapshot(
            title = androidx.compose.ui.text.input.TextFieldValue("Title"),
            content = androidx.compose.ui.text.input.TextFieldValue("left selected right", TextRange(5, 13)),
            activeField = EditorField.Content,
        )
        val history = EditorUndoHistory(before)
        val insertion = insertMarkdownImage(
            before.content.text,
            before.content.selection,
            markdownImageAltFromFilename("trip_photo-01.png"),
            "file:///data/user/0/app/files/images/a1.png",
        )
        val expected = "left ![trip photo 01](file:///data/user/0/app/files/images/a1.png) right"
        assertEquals(expected, insertion.markdown)
        assertEquals(expected.indexOf(" right"), insertion.cursor)
        val after = before.copy(
            content = androidx.compose.ui.text.input.TextFieldValue(expected, TextRange(insertion.cursor)),
        )
        history.record(after, EditorField.Content, forceNewOperation = true)
        assertEquals(before, history.undo())
        assertEquals(after, history.redo())
    }

    @Test
    fun markdownReplacementSkipsCodeAndKeepsExternalLinksAndAdjacentText() {
        val local = "file:///data/user/0/app/files/images/photo.png"
        val external = "https://example.org/image.png"
        val data = "data:image/png;base64,AA=="
        val original = "Before ![local]($local) after ![external]($external)\n`![code]($local)`\n```\n![fenced]($local)\n```\n![portable]($data)"
        val refs = findManagedMarkdownImages(original)
        assertEquals(listOf(local, data), refs.map { it.destination })
        val rewritten = replaceManagedMarkdownImages(original) { if (it == local) "portable-local" else "stored-copy" }
        assertTrue(rewritten.contains("Before ![local](portable-local) after ![external]($external)"))
        assertTrue(rewritten.contains("`![code]($local)`"))
        assertTrue(rewritten.contains("![fenced]($local)"))
        assertTrue(rewritten.endsWith("![portable](stored-copy)"))

        val fallback = fallbackInvalidMarkdownImages("before ![missing file]($local) after") { false }
        assertEquals("before [Image unavailable: missing file] after", fallback)
    }
}
