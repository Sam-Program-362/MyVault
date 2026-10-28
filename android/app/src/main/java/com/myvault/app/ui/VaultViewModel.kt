package com.myvault.app.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.myvault.app.MyVaultApplication
import com.myvault.app.data.AppSettings
import com.myvault.app.data.EditorDraftWriter
import com.myvault.app.data.EntryEntity
import com.myvault.app.data.EntryTagCrossRef
import com.myvault.app.data.SpaceEntity
import com.myvault.app.data.TagEntity
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VaultViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MyVaultApplication
    private val db = app.database
    private val spacesDao = db.spaceDao()
    private val tagsDao = db.tagDao()
    private val entriesDao = db.entryDao()

    val spaces = spacesDao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val tags = tagsDao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val entries =
        entriesDao.observeFiltered(null, null).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val tagRefs = entriesDao.observeAllTagRefs().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings = app.settings.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val currentWriter = MutableStateFlow<EditorDraftWriter<EntryEntity>?>(null)
    @OptIn(ExperimentalCoroutinesApi::class)
    val editor =
        currentWriter
            .flatMapLatest { it?.state ?: flowOf(null) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = mutableMessages.asSharedFlow()
    private var debounce: Job? = null

    private fun message(text: String) {
        mutableMessages.tryEmit(text)
    }

    private fun action(failure: String, block: suspend () -> Unit) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message("$failure ${error.message?.take(160) ?: "Please try again."}")
            } finally {
                mutableBusy.value = false
            }
        }
    }

    fun createSpace(
        name: String,
        description: String,
        color: String,
        icon: String,
        onCreated: (String) -> Unit,
    ) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            message("Enter a space name.")
            return
        }
        action("Couldn’t create space.") {
            val existing = spacesDao.getAll()
            val space =
                SpaceEntity(
                    id = UUID.randomUUID().toString(),
                    name = cleanName,
                    description = description.trim().ifEmpty { null },
                    color = color,
                    icon = icon,
                    sortOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                )
            spacesDao.upsert(space)
            onCreated(space.id)
        }
    }

    fun updateSpace(space: SpaceEntity, name: String, description: String, color: String, icon: String) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            message("Enter a space name.")
            return
        }
        action("Couldn’t update space.") {
            spacesDao.update(
                space.copy(
                    name = cleanName,
                    description = description.trim().ifEmpty { null },
                    color = color,
                    icon = icon,
                )
            )
        }
    }

    fun deleteSpace(space: SpaceEntity, onDeleted: () -> Unit) =
        action("Couldn’t delete space.") {
            spacesDao.delete(space.id)
            onDeleted()
            message("Space deleted.")
        }

    fun createEntry(spaceId: String) =
        action("Couldn’t create entry.") {
            if (currentWriter.value != null) return@action
            val entry = EntryEntity(id = UUID.randomUUID().toString(), spaceId = spaceId, title = "")
            entriesDao.upsert(entry)
            startEditing(entry)
        }

    fun openEntry(id: String) =
        action("Couldn’t open entry.") {
            if (currentWriter.value != null) return@action
            val entry = entriesDao.getById(id) ?: error("This entry no longer exists.")
            startEditing(entry)
        }

    private fun startEditing(entry: EntryEntity) {
        debounce?.cancel()
        currentWriter.value =
            EditorDraftWriter(entry) { draft ->
                check(entriesDao.update(draft) == 1) { "This entry no longer exists." }
            }
    }

    fun currentDraft(): EntryEntity? = currentWriter.value?.state?.value?.value

    fun updateDraft(transform: (EntryEntity) -> EntryEntity) {
        val writer = currentWriter.value ?: return
        writer.update { current ->
            val next = transform(current)
            if (next == current) current else next.copy(updatedAt = System.currentTimeMillis())
        }
        debounce?.cancel()
        // Only cancel the delay, never an in-flight Room write.
        debounce = viewModelScope.launch {
            delay(350)
            viewModelScope.launch {
                if (!writer.flush())
                    message("Couldn’t save the entry. Your draft is still open; try closing it again.")
            }
        }
    }

    fun savePendingDraft() {
        val writer = currentWriter.value ?: return
        debounce?.cancel()
        viewModelScope.launch {
            if (!writer.flush()) message("Couldn’t save the entry. Your draft is still open; try again.")
        }
    }

    fun closeEditor() =
        action("Couldn’t close entry.") {
            val writer = currentWriter.value ?: return@action
            debounce?.cancel()
            if (writer.flush()) currentWriter.value = null
            else message("Couldn’t save the entry. Your draft is still open; try again.")
        }

    fun deleteEditor() =
        action("Couldn’t delete entry.") {
            val writer = currentWriter.value ?: return@action
            debounce?.cancel()
            // Wait for any earlier write before deleting; @Update can never recreate a deleted row.
            writer.flush()
            entriesDao.delete(writer.state.value.value.id)
            currentWriter.value = null
            message("Entry deleted.")
        }

    fun setCover(uri: Uri) =
        action("Couldn’t add cover image.") {
            val writer = currentWriter.value ?: return@action
            val path = app.media.copySelected(uri)
            if (currentWriter.value === writer) updateDraft { it.copy(coverImagePath = path) }
        }

    fun addTag(tag: TagEntity) =
        action("Couldn’t add tag.") {
            val entryId = currentWriter.value?.state?.value?.value?.id ?: return@action
            entriesDao.addTagRef(EntryTagCrossRef(entryId, tag.id))
        }

    fun createTag(name: String, color: String, reusable: Boolean, attachToEditor: Boolean) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            message("Enter a tag name.")
            return
        }
        action("Couldn’t create tag.") {
            db.withTransaction {
                val tag =
                    tagsDao.findByName(cleanName)
                        ?: TagEntity(
                                id = UUID.randomUUID().toString(),
                                name = cleanName,
                                color = color,
                                textColor = "#FFFFFF",
                                reusable = reusable,
                            )
                            .also { tagsDao.upsert(it) }
                if (attachToEditor) {
                    val id = currentWriter.value?.state?.value?.value?.id ?: error("Entry is no longer open.")
                    entriesDao.addTagRef(EntryTagCrossRef(id, tag.id))
                }
            }
        }
    }

    fun removeTag(tag: TagEntity) =
        action("Couldn’t remove tag.") {
            val entryId = currentWriter.value?.state?.value?.value?.id ?: return@action
            entriesDao.removeTagRef(entryId, tag.id)
        }

    fun setTagReusable(tag: TagEntity, reusable: Boolean) =
        action("Couldn’t update tag.") {
            tagsDao.update(tag.copy(reusable = reusable))
        }

    fun deleteTag(tag: TagEntity) =
        action("Couldn’t delete tag.") {
            tagsDao.delete(tag.id)
        }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            try {
                app.settings.update(transform)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message("Couldn’t save settings. Please try again.")
            }
        }
    }

    fun setWallpaper(uri: Uri) =
        action("Couldn’t add wallpaper.") {
            val path = app.media.copySelected(uri)
            app.settings.update { it.copy(wallpaperUri = path, wallpaperEnabled = true) }
        }

    fun exportBackup(uri: Uri) =
        action("Couldn’t export backup.") {
            val result = app.backups.exportTo(uri)
            message("Backup saved.${imageWarning(result.externalImages)}")
        }

    fun importBackup(uri: Uri, applySettings: Boolean) =
        action("Couldn’t import backup.") {
            val result = app.backups.importFrom(uri, applySettings)
            message(
                "Imported ${result.entries} entries, ${result.spaces} spaces and ${result.tags} tags.${imageWarning(result.externalImages)}"
            )
        }

    private fun imageWarning(count: Int): String =
        if (count == 0) ""
        else " $count external image reference(s) may need to be re-selected on this device."

    data class ImportedText(val name: String, val content: String)

    fun readText(uri: Uri, onRead: (ImportedText) -> Unit) =
        action("Couldn’t read text file.") {
            val imported =
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val name =
                        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        } ?: "Imported text"
                    val mime = resolver.getType(uri)
                    require(
                        mime?.startsWith("text/") == true ||
                            name.matches(Regex(".*\\.(txt|md|markdown)$", RegexOption.IGNORE_CASE))
                    ) {
                        "Choose a .txt or .md file."
                    }
                    val input = resolver.openInputStream(uri) ?: error("The file is unavailable.")
                    val content =
                        input.bufferedReader(Charsets.UTF_8).use { reader ->
                            val result = StringBuilder()
                            val buffer = CharArray(8192)
                            while (true) {
                                val count = reader.read(buffer)
                                if (count == -1) break
                                require(result.length + count <= 4 * 1024 * 1024) {
                                    "Text files must be 4 MB or smaller."
                                }
                                result.append(buffer, 0, count)
                            }
                            result.toString()
                        }
                    ImportedText(name, content)
                }
            onRead(imported)
        }

    fun notify(text: String) = message(text)
}
