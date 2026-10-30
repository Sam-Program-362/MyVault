package com.myvault.app.ui

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.myvault.app.data.EntryEntity
import com.myvault.app.data.EntryTagCrossRef
import com.myvault.app.data.MediaFiles
import com.myvault.app.data.SpaceEntity
import com.myvault.app.data.TagEntity
import com.myvault.app.data.isVisibleInSpace

/** The web dashboard's compact header, horizontal spaces and cover-first entry cards. */
@Composable
fun EntriesScreen(
    appName: String,
    spaces: List<SpaceEntity>,
    selectedSpaceId: String?,
    entries: List<EntryEntity>,
    tags: List<TagEntity>,
    tagRefs: List<EntryTagCrossRef>,
    onSelectSpace: (String?) -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onNewSpace: (String, String, String, String) -> Unit,
    onNewEntry: (String) -> Unit,
    onOpenEntry: (String) -> Unit,
    entryLayout: String = "cards",
    onEntryLayoutChange: (String) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var chooseSpace by rememberSaveable { mutableStateOf(false) }
    var creatingSpace by rememberSaveable { mutableStateOf(false) }
    var lastSpaceId by rememberSaveable { mutableStateOf(selectedSpaceId) }
    LaunchedEffect(selectedSpaceId) {
        if (selectedSpaceId != null) lastSpaceId = selectedSpaceId
        tagFilter = null
    }
    val space = spaces.find { it.id == selectedSpaceId }
    val filtered =
        remember(entries, tagRefs, selectedSpaceId, query, tagFilter) {
            val matchingEntryIds =
                if (tagFilter == null) null
                else tagRefs.filter { it.tagId == tagFilter }.map { it.entryId }.toSet()
            entries.filter { entry ->
                (selectedSpaceId == null || entry.spaceId == selectedSpaceId) &&
                    (query.isBlank() ||
                        entry.title.contains(query.trim(), true) ||
                        entry.content.contains(query.trim(), true)) &&
                    (matchingEntryIds == null || entry.id in matchingEntryIds)
            }
        }
    val tagsById = remember(tags) { tags.associateBy { it.id } }
    val refsByEntry = remember(tagRefs) { tagRefs.groupBy { it.entryId } }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val listLayout = entryLayout == "list"

    Column(Modifier.fillMaxSize()) {
        DashboardHeader(
            appName = appName,
            query = query,
            onQueryChange = { query = it },
            allSpaces = selectedSpaceId == null,
            onToggleAllSpaces = {
                onSelectSpace(
                    if (selectedSpaceId == null) lastSpaceId ?: spaces.firstOrNull()?.id else null
                )
            },
            onBack = onBack,
            onSettings = onSettings,
            dark = dark,
        )
        Row(
            Modifier.fillMaxWidth()
                .testTag("space-navigation")
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = if (dark) 0.18f else 0.3f)
                )
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            spaces.forEach { item ->
                SpaceNavigationChip(
                    item,
                    count = entries.count { it.spaceId == item.id },
                    selected = selectedSpaceId == item.id,
                    onClick = { onSelectSpace(item.id) },
                )
            }
            Box(
                Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) {
                    creatingSpace = true
                },
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = Color.Transparent,
                    border =
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                ) {
                    Row(
                        Modifier.heightIn(min = 34.dp).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("New space", fontSize = 13.sp)
                    }
                }
            }
        }
        if (tags.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tags.filter { it.isVisibleInSpace(selectedSpaceId) }.forEach { tag ->
                    val selected = tagFilter == tag.id
                    val color = parseColor(tag.color)
                    Box(
                        Modifier.heightIn(min = 48.dp)
                            .selectable(
                                selected = selected,
                                role = Role.Checkbox,
                                onClick = { tagFilter = if (selected) null else tag.id },
                            )
                            .semantics { contentDescription = "Filter by ${tag.name}, ${if (tag.scope == "local") "Local" else "Global"}" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = color.copy(alpha = if (selected) 0.18f else 0.08f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, color),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(tag.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.width(5.dp))
                                Text(if (tag.scope == "local") "Local" else "Global", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
        LazyVerticalGrid(
            // Cards retain the existing adaptive dimensions. List mode uses the same keyed
            // collection and data, so switching layouts never reloads the vault.
            columns = if (listLayout) GridCells.Fixed(1) else GridCells.Adaptive(minSize = 300.dp),
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("entries-grid"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(if (listLayout) 6.dp else 16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "heading") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        space?.name ?: "All spaces",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    EntryLayoutToggle(listLayout, onEntryLayoutChange)
                    Spacer(Modifier.width(4.dp))
                    CompactNewEntryButton {
                        if (selectedSpaceId != null) onNewEntry(selectedSpaceId)
                        else chooseSpace = true
                    }
                }
            }
            if (filtered.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    EmptyEntries(hasFilter = query.isNotBlank() || tagFilter != null)
                }
            }
            items(filtered, key = { it.id }) { entry ->
                val entryTags = refsByEntry[entry.id].orEmpty().mapNotNull { tagsById[it.tagId] }
                var entryVisible by remember(entry.id) { mutableStateOf(false) }
                LaunchedEffect(entry.id) { entryVisible = true }
                AnimatedVisibility(
                    visible = entryVisible,
                    modifier = Modifier.animateItem(),
                    enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.98f),
                    label = "Entry entrance",
                ) {
                    AnimatedContent(
                        targetState = listLayout,
                        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                        label = "Entry layout",
                    ) { showList ->
                        if (showList) {
                            EntryListRow(entry = entry, onOpen = { onOpenEntry(entry.id) })
                        } else {
                            EntryCard(
                                entry = entry,
                                tags = entryTags,
                                space = spaces.find { it.id == entry.spaceId },
                                showSpace = selectedSpaceId == null,
                                onClick = { onOpenEntry(entry.id) },
                            )
                        }
                    }
                }
            }
        }
    }
    if (creatingSpace) {
        SpaceDialog(title = "New space", onDismiss = { creatingSpace = false }) {
            name,
            description,
            color,
            icon ->
            creatingSpace = false
            onNewSpace(name, description, color, icon)
        }
    }
    if (chooseSpace) {
        AlertDialog(
            onDismissRequest = { chooseSpace = false },
            title = { Text("Choose a space") },
            text = {
                if (spaces.isEmpty()) Text("Create a space first.")
                else
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        spaces.forEach { item ->
                            TextButton(
                                onClick = {
                                    chooseSpace = false
                                    onNewEntry(item.id)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("${item.icon}  ${item.name}")
                            }
                        }
                    }
            },
            confirmButton = { TextButton(onClick = { chooseSpace = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EntryLayoutToggle(listLayout: Boolean, onChange: (String) -> Unit) {
    Row(Modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
        listOf(false, true).forEach { isList ->
            val active = listLayout == isList
            val background by
                animateColorAsState(
                    if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    else Color.Transparent,
                    animationSpec = tween(160),
                    label = "Layout selection",
                )
            Box(
                Modifier.size(48.dp)
                    .selectable(
                        selected = active,
                        role = Role.RadioButton,
                        onClick = { if (!active) onChange(if (isList) "list" else "cards") },
                    )
                    .semantics { contentDescription = if (isList) "List view" else "Cards view" },
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.size(32.dp),
                    shape = MaterialTheme.shapes.small,
                    color = background,
                    contentColor =
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Icon(
                        if (isList) Icons.Default.ViewList else Icons.Default.GridView,
                        contentDescription = null,
                        modifier = Modifier.padding(7.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactNewEntryButton(onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).semantics {
            contentDescription = "New entry"
        },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Row(
                Modifier.heightIn(min = 34.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("New entry", fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun EntryListRow(entry: EntryEntity, onOpen: () -> Unit) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val title = entry.title.ifBlank { "Untitled entry" }
    val preview = remember(entry.content) { listTextPreview(entry.content) }
    val lineCount = remember(entry.content) { entryLineCount(entry.content) }
    val chevronRotation by
        animateFloatAsState(
            if (expanded) 180f else 0f,
            animationSpec = tween(180),
            label = "Expand entry",
        )
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("entry-list-${entry.id}"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f)
                        .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp)
                ) {
                    Text(
                        title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        preview.replace('\n', ' ').ifBlank { "No content yet." },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    if (lineCount == 1) "1 line" else "$lineCount lines",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier =
                        Modifier.size(48.dp).semantics {
                            stateDescription = if (expanded) "Expanded" else "Collapsed"
                        },
                ) {
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                        modifier = Modifier.size(20.dp).rotate(chevronRotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(150)) + expandVertically(tween(200)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(180)),
                label = "Entry preview",
            ) {
                Text(
                    preview.ifBlank { "No content yet." },
                    modifier =
                        Modifier.fillMaxWidth()
                            .testTag("entry-preview-${entry.id}")
                            .clickable(role = Role.Button, onClick = onOpen)
                            .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DashboardHeader(
    appName: String,
    query: String,
    onQueryChange: (String) -> Unit,
    allSpaces: Boolean,
    onToggleAllSpaces: () -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    dark: Boolean,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("dashboard-toolbar")) {
        val showAppName = maxWidth >= 600.dp
        val compact = maxWidth < 350.dp
        Column(
            Modifier.fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = if (dark) 0.18f else 0.3f)
                )
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Default.GridView,
                        "Back to spaces",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (showAppName) {
                    Text(
                        appName.ifBlank { "MyVault" },
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 180.dp).padding(end = 12.dp),
                    )
                }
                if (compact) Spacer(Modifier.weight(1f))
                else DashboardSearch(query, onQueryChange, allSpaces, Modifier.weight(1f))
                Spacer(Modifier.width(4.dp))
                AllSpacesToggle(allSpaces, onToggleAllSpaces)
                IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Settings, "Settings", modifier = Modifier.size(18.dp))
                }
            }
            // Give the search a full row on narrow phones rather than compressing
            // the text field and the adjacent 48dp touch targets.
            if (compact) {
                DashboardSearch(
                    query,
                    onQueryChange,
                    allSpaces,
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun AllSpacesToggle(allSpaces: Boolean, onToggle: () -> Unit) {
    val toggleColor by
        animateColorAsState(
            if (allSpaces) MaterialTheme.colorScheme.primary else Color.Transparent,
            animationSpec = tween(160),
            label = "All spaces selection",
        )
    Box(
        Modifier.heightIn(min = 48.dp)
            .selectable(selected = allSpaces, role = Role.Checkbox, onClick = onToggle)
            .semantics { contentDescription = "All spaces" },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = toggleColor,
            contentColor =
                if (allSpaces) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
            border =
                if (allSpaces) null
                else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        ) {
            Box(
                Modifier.heightIn(min = 34.dp).padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "All spaces",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DashboardSearch(
    query: String,
    onChange: (String) -> Unit,
    allSpaces: Boolean,
    modifier: Modifier,
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = textColor, fontSize = 14.sp),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier =
            modifier.heightIn(min = 48.dp).semantics { contentDescription = "Search entries" },
        decorationBox = { inner ->
            Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 36.dp)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                            MaterialTheme.shapes.medium,
                        )
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                            MaterialTheme.shapes.medium,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                if (allSpaces) "Search all spaces…" else "Search entries…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                    Spacer(Modifier.width(if (query.isNotEmpty()) 44.dp else 8.dp))
                }
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { onChange("") },
                        modifier = Modifier.align(Alignment.CenterEnd).size(48.dp),
                    ) {
                        Icon(Icons.Default.Clear, "Clear search", modifier = Modifier.size(18.dp))
                    }
                }
            }
        },
    )
}

@Composable
private fun SpaceNavigationChip(
    space: SpaceEntity,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = parseColor(space.color)
    val foreground = if (color.luminance() > 0.179f) Color(0xFF171717) else Color.White
    val background by
        animateColorAsState(
            if (selected) color else Color.Transparent,
            animationSpec = tween(160),
            label = "Space selection",
        )
    Box(
        Modifier.heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = background,
            contentColor = if (selected) foreground else MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                Modifier.heightIn(min = 34.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(space.icon, fontSize = 16.sp)
                Spacer(Modifier.width(6.dp))
                Text(space.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Text(
                    "$count",
                    fontSize = 11.sp,
                    color =
                        if (selected) foreground.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyEntries(hasFilter: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 52.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (hasFilter) "No matching entries. Try another search or filter."
                else "No entries yet. Create your first one.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun EntryCard(
    entry: EntryEntity,
    tags: List<TagEntity>,
    space: SpaceEntity?,
    showSpace: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("entry-card-${entry.id}"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
    ) {
        Column {
            if (entry.coverImagePath != null) {
                AsyncImage(
                    model =
                        remember(entry.coverImagePath) {
                            MediaFiles.displayModel(entry.coverImagePath)
                        },
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(128.dp),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    Modifier.fillMaxWidth()
                        .height(64.dp)
                        .background(
                            lerp(Color(0xFF1E293B), MaterialTheme.colorScheme.primary, 0.55f)
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(space?.icon ?: "📄", fontSize = 19.sp)
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        entry.title.ifBlank { "Untitled entry" },
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entry.pinned) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.PushPin,
                            "Pinned",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Text(
                    plainTextPreview(entry.content).ifBlank { "No content yet." },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (showSpace && space != null) {
                            Text(
                                space.name,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                        }
                        tags.take(3).forEach { tag ->
                            val color = parseColor(tag.color)
                            Surface(
                                color = color.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(50),
                                border = androidx.compose.foundation.BorderStroke(1.dp, color),
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(tag.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, maxLines = 1)
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (tag.scope == "local") "Local" else "Global", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        DateUtils.getRelativeTimeSpanString(
                                entry.updatedAt,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS,
                                DateUtils.FORMAT_ABBREV_RELATIVE,
                            )
                            .toString(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun plainTextPreview(markdown: String): String =
    markdown
        .replace(Regex("!\\[.*?]\\(.*?\\)"), "")
        .replace(Regex("\\[(.*?)]\\(.*?\\)"), "\$1")
        .replace(Regex("[#>*_`~-]"), "")
        .replace('\n', ' ')
        .trim()
