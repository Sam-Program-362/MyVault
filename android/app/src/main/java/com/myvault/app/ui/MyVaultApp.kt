package com.myvault.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.myvault.app.data.MediaFiles
import com.myvault.app.ui.editor.EditorContentUpdate
import com.myvault.app.ui.editor.EntryEditorScreen

@Composable
fun MyVaultApp(vault: VaultViewModel) {
    val settings by vault.settings.collectAsState()
    val spaces by vault.spaces.collectAsState()
    val entries by vault.entries.collectAsState()
    val tags by vault.tags.collectAsState()
    val refs by vault.tagRefs.collectAsState()
    val editor by vault.editor.collectAsState()
    val busy by vault.busy.collectAsState()
    var screen by rememberSaveable { mutableStateOf("spaces") }
    var settingsReturn by rememberSaveable { mutableStateOf("spaces") }
    var selectedSpaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var showTags by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var pendingBackup by rememberSaveable { mutableStateOf<String?>(null) }
    var applySettings by rememberSaveable { mutableStateOf(false) }
    var importTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var selectionStart by rememberSaveable { mutableIntStateOf(0) }
    var selectionEnd by rememberSaveable { mutableIntStateOf(0) }
    var importedText by remember { mutableStateOf<VaultViewModel.ImportedText?>(null) }
    var externalUpdate by remember { mutableStateOf<EditorContentUpdate?>(null) }
    var updateSequence by remember { mutableStateOf(0L) }
    val snackbar = remember { SnackbarHostState() }

    fun applyImportedText(content: String, title: String?, cursor: Int) {
        val current = vault.currentDraft() ?: return
        if (current.id != importTarget) return
        val newTitle = title ?: current.title
        vault.updateDraft { it.copy(title = newTitle, content = content) }
        updateSequence += 1
        externalUpdate = EditorContentUpdate(current.id, updateSequence, newTitle, content, cursor)
    }

    fun closeEditor() {
        externalUpdate = null
        importTarget = null
        importedText = null
        showTags = false
        confirmDelete = false
        vault.closeEditor()
    }

    LaunchedEffect(vault) {
        vault.messages.collect { snackbar.showSnackbar(it, duration = SnackbarDuration.Long) }
    }
    val coverPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(vault::setCover)
        }
    val wallpaperPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(vault::setWallpaper)
        }
    val exportPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let(vault::exportBackup)
        }
    val importPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                pendingBackup = uri.toString()
                applySettings = false
            }
        }
    val textPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                vault.readText(uri) { text ->
                    val current = vault.currentDraft()
                    if (current != null && current.id == importTarget) {
                        if (current.content.isBlank()) {
                            val title =
                                if (current.title.isBlank() || current.title == "Untitled entry")
                                    text.name.substringBeforeLast('.', text.name)
                                else current.title
                            applyImportedText(text.content, title, text.content.length)
                        } else importedText = text
                    }
                }
        }

    VaultTheme(settings) { dark ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (
                settings.wallpaperEnabled &&
                    settings.wallpaperUri != null &&
                    editor == null &&
                    screen != "settings"
            ) {
                AsyncImage(
                    model =
                        remember(settings.wallpaperUri) { MediaFiles.displayModel(settings.wallpaperUri) },
                    contentDescription = null,
                    modifier =
                        Modifier.fillMaxSize()
                            .alpha(settings.wallpaperOpacity.coerceIn(0, 100) / 100f)
                            .blur(settings.wallpaperBlur.coerceIn(0, 20).dp),
                    contentScale = ContentScale.Crop,
                )
            }
            val draftState = editor
            if (draftState != null) {
                val entry = draftState.value
                val selectedTags = tags.filter { tag ->
                    refs.any { it.entryId == entry.id && it.tagId == tag.id }
                }
                BackHandler(enabled = !busy) { closeEditor() }
                EntryEditorScreen(
                    entryId = entry.id,
                    title = entry.title,
                    content = entry.content,
                    coverImage = entry.coverImagePath,
                    pinned = entry.pinned,
                    tags = selectedTags,
                    darkTheme = dark,
                    saveState = draftState.saveState,
                    externalUpdate = externalUpdate,
                    onTitleChange = { value -> vault.updateDraft { it.copy(title = value) } },
                    onContentChange = { value -> vault.updateDraft { it.copy(content = value) } },
                    onClose = { closeEditor() },
                    onTogglePin = { vault.updateDraft { it.copy(pinned = !it.pinned) } },
                    onChooseCover = { coverPicker.launch(arrayOf("image/*")) },
                    onRemoveCover = { vault.updateDraft { it.copy(coverImagePath = null) } },
                    onAddTag = { showTags = true },
                    onRemoveTag = vault::removeTag,
                    onImport = { selection: TextRange ->
                        importTarget = entry.id
                        selectionStart = selection.min
                        selectionEnd = selection.max
                        textPicker.launch(arrayOf("text/*", "application/octet-stream"))
                    },
                    onDelete = { confirmDelete = true },
                )
                if (showTags)
                    TagPickerDialog(
                        tags,
                        selectedTags,
                        onDismiss = { showTags = false },
                        onSelect = { tag ->
                            showTags = false
                            vault.addTag(tag)
                        },
                        onCreate = { name, color, reusable ->
                            showTags = false
                            vault.createTag(name, color, reusable, true)
                        },
                    )
                if (confirmDelete)
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        title = { Text("Delete entry?") },
                        text = {
                            Text(
                                "This permanently deletes “${entry.title.ifBlank { "Untitled entry" }}”. This cannot be undone."
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    confirmDelete = false
                                    externalUpdate = null
                                    importedText = null
                                    importTarget = null
                                    vault.deleteEditor()
                                }
                            ) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                        },
                    )
                importedText?.let { text ->
                    AlertDialog(
                        onDismissRequest = { importedText = null },
                        title = { Text("Import ${text.name}?") },
                        text = {
                            Text(
                                "Choose whether to replace the current text or insert the file at the selected position."
                            )
                        },
                        confirmButton = {
                            Row {
                                TextButton(
                                    onClick = {
                                        applyImportedText(text.content, null, text.content.length)
                                        importedText = null
                                    }
                                ) {
                                    Text("Replace")
                                }
                                TextButton(
                                    onClick = {
                                        val current = vault.currentDraft()
                                        if (current != null && current.id == importTarget) {
                                            val start = selectionStart.coerceIn(0, current.content.length)
                                            val end = selectionEnd.coerceIn(start, current.content.length)
                                            val next = current.content.replaceRange(start, end, text.content)
                                            applyImportedText(next, null, start + text.content.length)
                                        }
                                        importedText = null
                                    }
                                ) {
                                    Text("Insert")
                                }
                            }
                        },
                        dismissButton = { TextButton(onClick = { importedText = null }) { Text("Cancel") } },
                    )
                }
            } else
                when (screen) {
                    "entries" -> {
                        BackHandler(enabled = !busy) { screen = "spaces" }
                        EntriesScreen(
                            spaces,
                            selectedSpaceId,
                            entries,
                            tags,
                            refs,
                            onSelectSpace = { selectedSpaceId = it },
                            onBack = { screen = "spaces" },
                            onSettings = {
                                settingsReturn = "entries"
                                screen = "settings"
                            },
                            onNewEntry = vault::createEntry,
                            onOpenEntry = vault::openEntry,
                        )
                    }
                    "settings" -> {
                        BackHandler(enabled = !busy) { screen = settingsReturn }
                        SettingsScreen(
                            settings,
                            tags,
                            onBack = { screen = settingsReturn },
                            onUpdate = vault::updateSettings,
                            onChooseWallpaper = { wallpaperPicker.launch(arrayOf("image/*")) },
                            onCreateTag = { name, color, reusable ->
                                vault.createTag(name, color, reusable, false)
                            },
                            onSetReusable = vault::setTagReusable,
                            onDeleteTag = vault::deleteTag,
                            onExport = {
                                val fileName =
                                    settings.appName
                                        .lowercase()
                                        .replace(Regex("[^a-z0-9_-]+"), "-")
                                        .trim('-')
                                        .ifBlank { "myvault" }
                                exportPicker.launch("$fileName-export.json")
                            },
                            onImport = {
                                importPicker.launch(
                                    arrayOf(
                                        "application/json",
                                        "text/json",
                                        "text/plain",
                                        "application/octet-stream",
                                    )
                                )
                            },
                        )
                    }
                    else ->
                        SpacesScreen(
                            settings.appName,
                            spaces,
                            entries,
                            onOpen = {
                                selectedSpaceId = it
                                screen = "entries"
                            },
                            onSettings = {
                                settingsReturn = "spaces"
                                screen = "settings"
                            },
                            onCreate = { name, description, color, icon ->
                                vault.createSpace(name, description, color, icon) { id ->
                                    selectedSpaceId = id
                                    screen = "entries"
                                }
                            },
                            onUpdate = vault::updateSpace,
                            onDelete = { space ->
                                vault.deleteSpace(space) {
                                    if (selectedSpaceId == space.id) selectedSpaceId = null
                                }
                            },
                        )
                }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
            if (busy) {
                // Don't let system Back finish the activity while a save or import is in progress.
                BackHandler {}
                Box(
                    Modifier.fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.15f))
                        .clickable(enabled = true, onClick = {}),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                }
            }
        }
        pendingBackup?.let { rawUri ->
            AlertDialog(
                onDismissRequest = { pendingBackup = null },
                title = { Text("Import backup?") },
                text = {
                    Column {
                        Text(
                            "Existing entries will not be deleted. Entries from the file will be added; matching spaces and tags will be reused."
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = applySettings,
                                onCheckedChange = { applySettings = it },
                                modifier =
                                    Modifier.semantics {
                                        contentDescription = "Also restore appearance settings"
                                    },
                            )
                            Text("Also restore appearance settings")
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingBackup = null
                            vault.importBackup(Uri.parse(rawUri), applySettings)
                        }
                    ) {
                        Text("Import")
                    }
                },
                dismissButton = { TextButton(onClick = { pendingBackup = null }) { Text("Cancel") } },
            )
        }
    }
}
