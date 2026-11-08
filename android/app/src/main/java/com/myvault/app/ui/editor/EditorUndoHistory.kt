package com.myvault.app.ui.editor

import androidx.compose.ui.text.input.TextFieldValue

/** The cursor-bearing text state for one editor entry. */
data class EditorUndoSnapshot(
    val title: TextFieldValue,
    val content: TextFieldValue,
    val activeField: EditorField,
)

enum class EditorField { Title, Content }

/** A bounded, entry-local history shared by the title and Markdown fields. */
class EditorUndoHistory(
    initial: EditorUndoSnapshot,
    private val limit: Int = 100,
    private val coalesceWindowMs: Long = 750,
) {
    private val snapshots = mutableListOf(initial)
    private var index = 0
    private var lastEdit: Pair<EditorField, Long>? = null

    val canUndo: Boolean get() = index > 0
    val canRedo: Boolean get() = index < snapshots.lastIndex

    /** Record caret-only changes without making a separate undo step. */
    fun updateSelection(snapshot: EditorUndoSnapshot) {
        val current = snapshots[index]
        if (sameText(current, snapshot)) {
            if (current.activeField != snapshot.activeField ||
                current.title.selection != snapshot.title.selection ||
                current.content.selection != snapshot.content.selection
            ) lastEdit = null
            snapshots[index] = snapshot
        }
    }

    /** Multi-character replacements/imports are automatically kept as a single atomic step. */
    fun record(
        snapshot: EditorUndoSnapshot,
        field: EditorField,
        atMs: Long = System.currentTimeMillis(),
        forceNewOperation: Boolean = false,
    ) {
        val current = snapshots[index]
        if (sameText(current, snapshot)) {
            updateSelection(snapshot)
            return
        }
        if (index < snapshots.lastIndex) {
            while (snapshots.lastIndex > index) snapshots.removeAt(snapshots.lastIndex)
            lastEdit = null
        }

        val previousText = if (field == EditorField.Title) current.title.text else current.content.text
        val nextText = if (field == EditorField.Title) snapshot.title.text else snapshot.content.text
        val coalescible = !forceNewOperation && isSingleCharacterEdit(previousText, nextText)
        val last = lastEdit
        val merge = coalescible && last != null && last.first == field &&
            atMs >= last.second && atMs - last.second <= coalesceWindowMs

        if (merge) {
            snapshots[index] = snapshot
        } else {
            snapshots += snapshot
            index++
            if (snapshots.size > limit) {
                val excess = snapshots.size - limit
                repeat(excess) { snapshots.removeAt(0) }
                index -= excess
            }
        }
        lastEdit = if (coalescible) field to atMs else null
    }

    fun undo(): EditorUndoSnapshot? {
        if (!canUndo) return null
        index--
        lastEdit = null
        return snapshots[index]
    }

    fun redo(): EditorUndoSnapshot? {
        if (!canRedo) return null
        index++
        lastEdit = null
        return snapshots[index]
    }

    private fun sameText(left: EditorUndoSnapshot, right: EditorUndoSnapshot) =
        left.title.text == right.title.text && left.content.text == right.content.text

    private fun isSingleCharacterEdit(before: String, after: String): Boolean {
        var prefix = 0
        val shared = minOf(before.length, after.length)
        while (prefix < shared && before[prefix] == after[prefix]) prefix++
        var suffix = 0
        while (
            suffix < before.length - prefix && suffix < after.length - prefix &&
                before[before.length - 1 - suffix] == after[after.length - 1 - suffix]
        ) suffix++
        val removed = before.length - prefix - suffix
        val inserted = after.length - prefix - suffix
        return maxOf(removed, inserted) <= 1
    }
}
