package com.myvault.app.ui

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
) {
    var query by rememberSaveable { mutableStateOf("") }
    var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var chooseSpace by rememberSaveable { mutableStateOf(false) }
    var creatingSpace by rememberSaveable { mutableStateOf(false) }
    var lastSpaceId by rememberSaveable { mutableStateOf(selectedSpaceId) }
    LaunchedEffect(selectedSpaceId) { if (selectedSpaceId != null) lastSpaceId = selectedSpaceId }
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
                .background(
                    if (dark) Color(0xFF0A0A0A).copy(alpha = 0.4f)
                    else Color.White.copy(alpha = 0.5f)
                )
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            Surface(
                onClick = { creatingSpace = true },
                shape = MaterialTheme.shapes.medium,
                color = Color.Transparent,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
            ) {
                Row(
                    Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("New space", fontSize = 13.sp)
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
                tags.forEach { tag ->
                    val selected = tagFilter == tag.id
                    val color = parseColor(tag.color)
                    Box(
                        Modifier.heightIn(min = 48.dp)
                            .selectable(
                                selected = selected,
                                role = Role.Checkbox,
                                onClick = { tagFilter = if (selected) null else tag.id },
                            )
                            .semantics { contentDescription = "Filter by ${tag.name}" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (selected) color else color.copy(alpha = 0.14f),
                        ) {
                            Text(
                                tag.name,
                                color =
                                    if (selected) parseColor(tag.textColor, Color.White) else color,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 300.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
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
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (selectedSpaceId != null) onNewEntry(selectedSpaceId)
                            else chooseSpace = true
                        },
                        modifier =
                            Modifier.heightIn(min = 48.dp).semantics {
                                contentDescription = "New entry"
                            },
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("New entry", fontSize = 13.sp, maxLines = 1)
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
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val showAppName = maxWidth >= 600.dp
        Row(
            Modifier.fillMaxWidth()
                .background(
                    if (dark) Color(0xFF0A0A0A).copy(alpha = 0.65f)
                    else Color.White.copy(alpha = 0.7f)
                )
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.GridView,
                    "Back to spaces",
                    tint = MaterialTheme.colorScheme.primary,
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
            DashboardSearch(query, onQueryChange, allSpaces, Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            Surface(
                onClick = onToggleAllSpaces,
                shape = MaterialTheme.shapes.medium,
                color = if (allSpaces) MaterialTheme.colorScheme.primary else Color.Transparent,
                contentColor =
                    if (allSpaces) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                border =
                    if (allSpaces) null
                    else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                modifier =
                    Modifier.semantics {
                        contentDescription = "All spaces"
                        selected = allSpaces
                    },
            ) {
                Box(
                    Modifier.heightIn(min = 48.dp).padding(horizontal = 10.dp),
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
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Settings, "Settings")
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
            modifier
                .heightIn(min = 48.dp)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                    MaterialTheme.shapes.medium,
                )
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                    MaterialTheme.shapes.medium,
                )
                .semantics { contentDescription = "Search entries" },
        decorationBox = { inner ->
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(10.dp))
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(8.dp))
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
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onChange("") }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Clear, "Clear search", modifier = Modifier.size(18.dp))
                    }
                } else Spacer(Modifier.width(8.dp))
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
    val isSelected = selected
    Surface(
        onClick = onClick,
        modifier = Modifier.semantics { this.selected = isSelected },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) color else Color.Transparent,
        contentColor = if (selected) foreground else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(space.icon, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text(space.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.width(8.dp))
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
        modifier = Modifier.fillMaxWidth(),
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
                                color = color.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(50),
                            ) {
                                Text(
                                    tag.name,
                                    color = color,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
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
