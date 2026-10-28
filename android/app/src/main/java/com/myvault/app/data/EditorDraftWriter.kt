package com.myvault.app.data

import com.myvault.app.ui.editor.EditorSaveState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes snapshots so an older write cannot overwrite a newer draft. */
class EditorDraftWriter<T>(initial: T, private val persist: suspend (T) -> Unit) {
    data class State<T>(
        val value: T,
        val revision: Long = 0,
        val savedRevision: Long = 0,
        val saveState: EditorSaveState = EditorSaveState.Saved,
    )

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(State(initial))
    val state: StateFlow<State<T>> = mutableState.asStateFlow()

    fun update(transform: (T) -> T) {
        mutableState.update { current ->
            val next = transform(current.value)
            if (next == current.value) current
            else
                current.copy(
                    value = next,
                    revision = current.revision + 1,
                    saveState = EditorSaveState.Saving,
                )
        }
    }

    /** Wait for all pending changes. Failure leaves the draft available for retry. */
    suspend fun flush(): Boolean = mutex.withLock {
        while (true) {
            val snapshot = mutableState.value
            if (snapshot.savedRevision == snapshot.revision) return@withLock true
            try {
                persist(snapshot.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(saveState = EditorSaveState.Error) }
                return@withLock false
            }
            mutableState.update { current ->
                current.copy(
                    savedRevision = snapshot.revision,
                    saveState =
                        if (current.revision == snapshot.revision) EditorSaveState.Saved
                        else EditorSaveState.Saving,
                )
            }
        }
        @Suppress("UNREACHABLE_CODE") false
    }
}
