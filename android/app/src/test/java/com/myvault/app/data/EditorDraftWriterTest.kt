package com.myvault.app.data

import com.myvault.app.ui.editor.EditorSaveState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorDraftWriterTest {
    @Test
    fun changesWhileSavingAreWrittenInOrder() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        val writer =
            EditorDraftWriter("") { value ->
                if (writes.isEmpty()) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                writes.add(value)
            }
        writer.update { "first" }
        val save = async { writer.flush() }
        firstStarted.await()
        writer.update { "latest" }
        releaseFirst.complete(Unit)
        assertTrue(save.await())
        assertEquals(listOf("first", "latest"), writes)
        assertEquals(EditorSaveState.Saved, writer.state.value.saveState)
        assertEquals("latest", writer.state.value.value)
    }

    @Test
    fun undoDuringDelayedAutosavePersistsTheLatestRevisionAfterTheOldAcknowledgement() = runBlocking {
        data class Draft(val title: String, val content: String)
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        val writes = mutableListOf<Draft>()
        val writer = EditorDraftWriter(Draft("", "")) { value ->
            if (value.content == "edited") {
                saveStarted.complete(Unit)
                releaseSave.await()
            }
            writes += value
        }
        writer.update { it.copy(content = "edited") }
        val autosave = async { writer.flush() }
        saveStarted.await()
        // This is the editor's undo snapshot while the older request is still in flight.
        writer.update { it.copy(content = "") }
        releaseSave.complete(Unit)

        assertTrue(autosave.await())
        assertEquals(listOf("edited", ""), writes.map { it.content })
        assertEquals("", writer.state.value.value.content)
        assertEquals(writer.state.value.revision, writer.state.value.savedRevision)
    }

    @Test
    fun undoAfterSaveFailureKeepsHistoryDraftAvailableForRetry() = runBlocking {
        var attempts = 0
        val writes = mutableListOf<String>()
        val writer = EditorDraftWriter("original") { value ->
            attempts++
            if (attempts == 1) error("temporary failure")
            writes += value
        }
        writer.update { "edited" }
        assertFalse(writer.flush())
        writer.update { "original" }
        assertTrue(writer.flush())
        assertEquals(listOf("original"), writes)
        assertEquals("original", writer.state.value.value)
    }

    @Test
    fun failedSaveKeepsTheLatestDraftForRetry() = runBlocking {
        var attempts = 0
        val writes = mutableListOf<String>()
        val writer =
            EditorDraftWriter("") { value ->
                attempts++
                if (attempts == 1) error("temporary failure")
                writes.add(value)
            }
        writer.update { "keep me" }
        assertFalse(writer.flush())
        assertEquals(EditorSaveState.Error, writer.state.value.saveState)
        assertEquals("keep me", writer.state.value.value)
        assertTrue(writer.flush())
        assertEquals(listOf("keep me"), writes)
        assertEquals(EditorSaveState.Saved, writer.state.value.saveState)
    }
}
