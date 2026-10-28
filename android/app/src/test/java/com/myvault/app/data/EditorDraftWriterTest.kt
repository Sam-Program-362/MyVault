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
