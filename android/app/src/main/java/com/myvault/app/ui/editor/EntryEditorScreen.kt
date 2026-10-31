package com.myvault.app.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.myvault.app.data.MediaFiles
import com.myvault.app.data.TagEntity
import dev.jeziellago.compose.markdowntext.MarkdownText

/** The host owns persistence, file pickers, tag management and deletion confirmation. */
enum class EditorSaveState {
    Saving,
    Saved,
    Error,
}

/** Explicit host-originated change (for example, a text file import). */
data class EditorContentUpdate(
    val entryId: String,
    val sequence: Long,
    val title: String,
    val content: String,
    val cursor: Int,
)

private data class EditorPalette(
    val background: Color,
    val foreground: Color,
    val muted: Color,
    val placeholder: Color,
    val line: Color,
    val subtle: Color,
    val danger: Color,
)

private val lightEditorPalette =
    EditorPalette(
        background = Color(0xFFFBFAF8),
        foreground = Color(0xFF292825),
        muted = Color(0xFF74736D),
        placeholder = Color(0xFF74736D),
        line = Color(0xFFE8E6E1),
        subtle = Color(0xFFF2F1EE),
        danger = Color(0xFFB04444),
    )
private val darkEditorPalette =
    EditorPalette(
        background = Color(0xFF191918),
        foreground = Color(0xFFEAE9E4),
        muted = Color(0xFFAAA9A1),
        placeholder = Color(0xFF888780),
        line = Color(0xFF373734),
        subtle = Color(0xFF30302E),
        danger = Color(0xFFE17B7B),
    )

/**
 * A full-page Markdown editor with a live preview that slides in from the right.
 *
 * Supply the current entry and update it immediately in the change callbacks. The host must persist
 * changes (including flushing a pending save before [onClose]), and provide the existing
 * cover/import/tag pickers and delete confirmation through the remaining callbacks. Host-originated
 * text changes must be supplied through [externalUpdate] so stale persistence echoes cannot erase
 * typing.
 */
@Composable
fun EntryEditorScreen(
    entryId: String,
    title: String,
    content: String,
    coverImage: String?,
    pinned: Boolean,
    tags: List<TagEntity>,
    darkTheme: Boolean,
    saveState: EditorSaveState,
    onTitleChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onClose: () -> Unit,
    onTogglePin: () -> Unit,
    onChooseCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onAddTag: () -> Unit,
    onRemoveTag: (TagEntity) -> Unit,
    onImport: (TextRange) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    externalUpdate: EditorContentUpdate? = null,
) {
    val palette = if (darkTheme) darkEditorPalette else lightEditorPalette
    var previewOpen by rememberSaveable(entryId) { mutableStateOf(false) }
    var findOpen by rememberSaveable(entryId) { mutableStateOf(false) }
    var findQuery by rememberSaveable(entryId) { mutableStateOf("") }
    var replaceQuery by rememberSaveable(entryId) { mutableStateOf("") }
    var wholeWord by rememberSaveable(entryId) { mutableStateOf(false) }
    val initialSnapshot = remember(entryId) {
        EditorUndoSnapshot(TextFieldValue(title), TextFieldValue(content), EditorField.Content)
    }
    val history = remember(entryId) { EditorUndoHistory(initialSnapshot) }
    var editorValue by remember(entryId) { mutableStateOf(initialSnapshot.content) }
    var editorTitle by remember(entryId) { mutableStateOf(initialSnapshot.title) }
    var activeField by remember(entryId) { mutableStateOf(EditorField.Content) }
    var restoreFocus by remember(entryId) { mutableStateOf<EditorField?>(null) }
    var restoreSequence by remember(entryId) { mutableIntStateOf(0) }
    val titleFocusRequester = remember(entryId) { FocusRequester() }
    val bodyFocusRequester = remember(entryId) { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val matches =
        remember(editorValue.text, findQuery, wholeWord) {
            editorMatches(editorValue.text, findQuery, wholeWord)
        }
    fun currentSnapshot() = EditorUndoSnapshot(editorTitle, editorValue, activeField)

    // Ordinary Room acknowledgements never feed back into the editor/history. Explicit host
    // updates are imports and therefore one deliberate undo step.
    LaunchedEffect(entryId, externalUpdate?.sequence) {
        if (externalUpdate != null && externalUpdate.entryId == entryId) {
            editorTitle = TextFieldValue(externalUpdate.title, TextRange(externalUpdate.title.length))
            editorValue = TextFieldValue(
                externalUpdate.content,
                TextRange(externalUpdate.cursor.coerceIn(0, externalUpdate.content.length)),
            )
            activeField = EditorField.Content
            history.record(currentSnapshot(), EditorField.Content, forceNewOperation = true)
        }
    }
    LaunchedEffect(restoreSequence) {
        val field = restoreFocus ?: return@LaunchedEffect
        if (field == EditorField.Title) titleFocusRequester.requestFocus()
        else bodyFocusRequester.requestFocus()
        restoreFocus = null
    }
    BackHandler(enabled = previewOpen) { previewOpen = false }
    BackHandler(enabled = findOpen && !previewOpen) { findOpen = false }

    fun applyHistorySnapshot(snapshot: EditorUndoSnapshot) {
        val previous = currentSnapshot()
        editorTitle = snapshot.title
        editorValue = snapshot.content
        activeField = snapshot.activeField
        if (previous.title.text != snapshot.title.text) onTitleChange(snapshot.title.text)
        if (previous.content.text != snapshot.content.text) onContentChange(snapshot.content.text)
        restoreFocus = snapshot.activeField
        restoreSequence++
    }

    fun undo() { history.undo()?.let(::applyHistorySnapshot) }
    fun redo() { history.redo()?.let(::applyHistorySnapshot) }

    fun handleHistoryKeyEvent(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed || event.isAltPressed) return false
        return when (event.key) {
            Key.Z -> { if (event.isShiftPressed) redo() else undo(); true }
            Key.Y -> { redo(); true }
            else -> false
        }
    }

    fun updateTitle(next: TextFieldValue) {
        val changed = next.text != editorTitle.text
        editorTitle = next
        activeField = EditorField.Title
        val snapshot = currentSnapshot()
        if (changed) {
            history.record(snapshot, EditorField.Title)
            onTitleChange(next.text)
        } else history.updateSelection(snapshot)
    }

    fun updateBody(next: TextFieldValue) {
        val changed = next.text != editorValue.text
        editorValue = next
        activeField = EditorField.Content
        val snapshot = currentSnapshot()
        if (changed) {
            history.record(snapshot, EditorField.Content)
            onContentChange(next.text)
        } else history.updateSelection(snapshot)
    }

    fun togglePreview() {
        if (!previewOpen) focusManager.clearFocus()
        previewOpen = !previewOpen
    }

    fun findNext() {
        val selectionEnd = editorValue.selection.max
        val next = matches.firstOrNull { it.first >= selectionEnd } ?: matches.firstOrNull()
        if (next != null) {
            editorValue = editorValue.copy(selection = TextRange(next.first, next.last + 1))
            activeField = EditorField.Content
            history.updateSelection(currentSnapshot())
            bodyFocusRequester.requestFocus()
        }
    }

    fun replaceCurrent() {
        val start = editorValue.selection.min
        val end = editorValue.selection.max
        if (matches.any { it.first == start && it.last + 1 == end }) {
            val next = editorValue.text.replaceRange(start, end, replaceQuery)
            updateBody(TextFieldValue(next, TextRange(start + replaceQuery.length)))
        } else {
            findNext()
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().imePadding().background(palette.background)) {
        val narrow = maxWidth < 380.dp
        val compact = maxWidth < 420.dp
        val findMaxHeight = maxHeight * 0.4f
        val bodyHeight = (maxHeight - if (narrow) 245.dp else 200.dp).coerceAtLeast(220.dp)
        Column(Modifier.fillMaxSize()) {
            if (narrow) {
                Row(
                    Modifier.fillMaxWidth().height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EditorIcon(Icons.Default.Close, "Close editor", palette, onClick = onClose)
                    SaveIndicator(saveState, palette, compact = true)
                    Spacer(Modifier.weight(1f))
                    EditorIcon(
                        Icons.Default.PushPin,
                        if (pinned) "Unpin entry" else "Pin entry",
                        palette,
                        active = pinned,
                        activeColor = accent,
                        toggle = true,
                        onClick = onTogglePin,
                    )
                    EditorIcon(
                        Icons.Default.Search,
                        "Find and replace",
                        palette,
                        active = findOpen,
                        toggle = true,
                        onClick = { findOpen = !findOpen },
                    )
                }
                Row(
                    Modifier.fillMaxWidth().height(48.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    EditorViewActions(
                        palette,
                        previewOpen,
                        onWrite = { previewOpen = false },
                        onPreview = ::togglePreview,
                        onImport = { onImport(editorValue.selection) },
                        onDelete = onDelete,
                    )
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EditorIcon(Icons.Default.Close, "Close editor", palette, onClick = onClose)
                    SaveIndicator(saveState, palette, compact)
                    Spacer(Modifier.weight(1f))
                    EditorIcon(
                        Icons.Default.PushPin,
                        if (pinned) "Unpin entry" else "Pin entry",
                        palette,
                        active = pinned,
                        activeColor = accent,
                        toggle = true,
                        onClick = onTogglePin,
                    )
                    EditorIcon(
                        Icons.Default.Search,
                        "Find and replace",
                        palette,
                        active = findOpen,
                        toggle = true,
                        onClick = { findOpen = !findOpen },
                    )
                    EditorViewActions(
                        palette,
                        previewOpen,
                        onWrite = { previewOpen = false },
                        onPreview = ::togglePreview,
                        onImport = { onImport(editorValue.selection) },
                        onDelete = onDelete,
                    )
                }
            }
            HorizontalDivider(color = palette.line)
            Row(
                Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorIcon(Icons.Default.Undo, "Undo", palette, enabled = history.canUndo, onClick = ::undo)
                EditorIcon(Icons.Default.Redo, "Redo", palette, enabled = history.canRedo, onClick = ::redo)
            }

            if (findOpen) {
                FindBar(
                    modifier = Modifier.heightIn(max = findMaxHeight),
                    query = findQuery,
                    onQueryChange = { findQuery = it },
                    replacement = replaceQuery,
                    onReplacementChange = { replaceQuery = it },
                    wholeWord = wholeWord,
                    onWholeWordChange = { wholeWord = it },
                    matchCount = matches.size,
                    palette = palette,
                    accent = accent,
                    onFindNext = ::findNext,
                    onReplace = ::replaceCurrent,
                    onReplaceAll = {
                        val next =
                            replaceEditorMatches(
                                editorValue.text,
                                findQuery,
                                replaceQuery,
                                wholeWord,
                            )
                        updateBody(TextFieldValue(next, TextRange(next.length)))
                    },
                )
            }

            // Keep the cover and metadata scrollable when the keyboard or a short window leaves
            // little room for writing; the editor itself retains its own text scrolling.
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (coverImage == null) {
                    TextButton(
                        onClick = onChooseCover,
                        modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                    ) {
                        Icon(
                            Icons.Default.AddPhotoAlternate,
                            contentDescription = null,
                            tint = palette.muted,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            "  Add cover image (optional)",
                            color = palette.muted,
                            fontSize = 12.sp,
                        )
                    }
                } else {
                    Box(Modifier.fillMaxWidth().height(132.dp)) {
                        AsyncImage(
                            model = remember(coverImage) { MediaFiles.displayModel(coverImage) },
                            contentDescription = "Entry cover",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                        IconButton(
                            onClick = onRemoveCover,
                            modifier =
                                Modifier.align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), CircleShape),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove cover image",
                                tint = Color.White,
                            )
                        }
                    }
                }

                Column(Modifier.fillMaxWidth()) {
                    BasicTextField(
                        value = editorTitle,
                        onValueChange = ::updateTitle,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrect = true),
                        textStyle =
                            TextStyle(
                                color = palette.foreground,
                                fontSize = 22.sp,
                                lineHeight = 29.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        cursorBrush = SolidColor(accent),
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 10.dp)
                                .onPreviewKeyEvent(::handleHistoryKeyEvent)
                                .focusRequester(titleFocusRequester)
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        activeField = EditorField.Title
                                        history.updateSelection(currentSnapshot())
                                    }
                                }
                                .semantics { contentDescription = "Entry title" },
                        decorationBox = { inner ->
                            Box {
                                if (editorTitle.text.isEmpty())
                                    Text(
                                        "Entry title",
                                        color = palette.placeholder,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                inner()
                            }
                        },
                    )
                    Row(
                        Modifier.fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        tags.forEach { tag ->
                            val tagColor = runCatching {
                                Color(android.graphics.Color.parseColor(tag.color))
                            }
                                .getOrDefault(palette.subtle)
                            val tagTextColor = palette.foreground
                            Surface(
                                color = tagColor.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(50),
                                border = androidx.compose.foundation.BorderStroke(1.dp, tagColor),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        tag.name,
                                        color = tagTextColor,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(start = 12.dp),
                                    )
                                    Text(
                                        if (tag.scope == "local") " · Local" else " · Global",
                                        color = tagTextColor.copy(alpha = 0.7f),
                                        fontSize = 10.sp,
                                    )
                                    IconButton(
                                        onClick = { onRemoveTag(tag) },
                                        modifier = Modifier.size(48.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove ${tag.name} tag",
                                            modifier = Modifier.size(14.dp),
                                            tint = tagTextColor,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        TextButton(onClick = onAddTag) {
                            Icon(
                                Icons.Default.Label,
                                contentDescription = null,
                                tint = palette.muted,
                                modifier = Modifier.size(16.dp),
                            )
                            Text("  Add tag…", color = palette.muted, fontSize = 12.sp)
                        }
                    }
                    BasicTextField(
                        value = editorValue,
                        onValueChange = ::updateBody,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrect = true),
                        textStyle =
                            TextStyle(
                                color = palette.foreground,
                                fontSize = 15.sp,
                                lineHeight = 26.sp,
                            ),
                        cursorBrush = SolidColor(accent),
                        modifier =
                            Modifier.fillMaxWidth()
                                .height(bodyHeight)
                                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp)
                                .onPreviewKeyEvent(::handleHistoryKeyEvent)
                                .focusRequester(bodyFocusRequester)
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        activeField = EditorField.Content
                                        history.updateSelection(currentSnapshot())
                                    }
                                }
                                .semantics { contentDescription = "Entry content" },
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxSize()) {
                                if (editorValue.text.isEmpty()) {
                                    Text(
                                        "Write in Markdown — # headings, **bold**, - lists, `code`, > quotes…",
                                        color = palette.placeholder,
                                        fontSize = 15.sp,
                                        lineHeight = 26.sp,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }
            }
        }

        if (previewOpen && maxWidth < 600.dp) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable {
                    previewOpen = false
                }
            )
        }
        val previewWidth = if (maxWidth < 600.dp) maxWidth * 0.9f else 480.dp
        AnimatedVisibility(
            visible = previewOpen,
            modifier =
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(previewWidth).semantics {
                    paneTitle = "Markdown preview"
                },
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
        ) {
            Surface(color = palette.background, shadowElevation = 12.dp) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Preview",
                                color = palette.foreground,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text("Markdown", color = palette.muted, fontSize = 11.sp)
                        }
                        EditorIcon(
                            Icons.Default.Close,
                            "Close preview",
                            palette,
                            onClick = { previewOpen = false },
                        )
                    }
                    HorizontalDivider(color = palette.line)
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
                    ) {
                        Text(
                            editorTitle.text.ifBlank { "Untitled entry" },
                            color = palette.foreground,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(20.dp))
                        MarkdownText(
                            markdown = editorValue.text.ifEmpty { "*Nothing here yet.*" },
                            style =
                                TextStyle(
                                    color = palette.foreground,
                                    fontSize = 15.sp,
                                    lineHeight = 25.sp,
                                ),
                            linkColor = accent,
                            isTextSelectable = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorViewActions(
    palette: EditorPalette,
    previewOpen: Boolean,
    onWrite: () -> Unit,
    onPreview: () -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(contentAlignment = Alignment.Center) {
        // The visible outline is compact; both buttons keep their full 48dp targets.
        Box(
            Modifier.matchParentSize()
                .padding(vertical = 7.dp)
                .border(1.dp, palette.line, RoundedCornerShape(8.dp))
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            EditorIcon(
                Icons.Default.Code,
                "Write",
                palette,
                active = !previewOpen,
                toggle = true,
                onClick = onWrite,
            )
            EditorIcon(
                Icons.Default.Visibility,
                "Preview",
                palette,
                active = previewOpen,
                toggle = true,
                onClick = onPreview,
            )
        }
    }
    EditorIcon(
        Icons.Default.UploadFile,
        "Import text or Markdown file",
        palette,
        onClick = onImport,
    )
    EditorIcon(
        Icons.Default.DeleteOutline,
        "Delete entry",
        palette,
        activeColor = palette.danger,
        active = true,
        onClick = onDelete,
    )
}

@Composable
private fun EditorIcon(
    icon: ImageVector,
    label: String,
    palette: EditorPalette,
    active: Boolean = false,
    activeColor: Color = palette.foreground,
    toggle: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val background by
        animateColorAsState(
            if (active && activeColor == palette.foreground) palette.subtle else Color.Transparent,
            animationSpec = tween(140),
            label = "Editor action selection",
        )
    val iconColor by
        animateColorAsState(
            if (active) activeColor else palette.muted,
            animationSpec = tween(140),
            label = "Editor action color",
        )
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier =
            Modifier.size(48.dp).semantics {
                if (toggle) stateDescription = if (active) "Selected" else "Not selected"
            },
    ) {
        Box(
            Modifier.size(32.dp).background(background, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = iconColor,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SaveIndicator(state: EditorSaveState, palette: EditorPalette, compact: Boolean) {
    val label =
        when (state) {
            EditorSaveState.Saving -> "Saving…"
            EditorSaveState.Saved -> "Saved"
            EditorSaveState.Error -> "Couldn’t save"
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        when (state) {
            EditorSaveState.Saved ->
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = palette.muted,
                    modifier = Modifier.size(14.dp),
                )
            EditorSaveState.Saving -> Text("•", color = palette.muted, fontSize = 16.sp)
            EditorSaveState.Error ->
                Text("!", color = palette.danger, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        if (!compact)
            Text(
                label,
                color = if (state == EditorSaveState.Error) palette.danger else palette.muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 3.dp),
            )
    }
}

@Composable
private fun FindBar(
    modifier: Modifier = Modifier,
    query: String,
    onQueryChange: (String) -> Unit,
    replacement: String,
    onReplacementChange: (String) -> Unit,
    wholeWord: Boolean,
    onWholeWordChange: (Boolean) -> Unit,
    matchCount: Int,
    palette: EditorPalette,
    accent: Color,
    onFindNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
) {
    val fieldColors =
        OutlinedTextFieldDefaults.colors(
            focusedTextColor = palette.foreground,
            unfocusedTextColor = palette.foreground,
            cursorColor = accent,
            focusedBorderColor = accent,
            unfocusedBorderColor = palette.line,
            focusedLabelColor = palette.muted,
            unfocusedLabelColor = palette.muted,
        )
    Column(
        modifier
            .fillMaxWidth()
            .background(palette.subtle)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text("Find") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = replacement,
                onValueChange = onReplacementChange,
                label = { Text("Replace with") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = wholeWord,
                onCheckedChange = onWholeWordChange,
                modifier = Modifier.semantics { contentDescription = "Whole word" },
                colors =
                    CheckboxDefaults.colors(checkedColor = accent, uncheckedColor = palette.muted),
            )
            Text(
                "Whole word · $matchCount ${if (matchCount == 1) "match" else "matches"}",
                color = palette.muted,
                fontSize = 12.sp,
            )
            TextButton(onClick = onFindNext) { Text("Find next", color = accent) }
            TextButton(onClick = onReplace) { Text("Replace", color = accent) }
            TextButton(onClick = onReplaceAll) { Text("Replace all", color = accent) }
        }
    }
}
