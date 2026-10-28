package com.myvault.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.myvault.app.data.EntryEntity
import com.myvault.app.data.EntryTagCrossRef
import com.myvault.app.data.MediaFiles
import com.myvault.app.data.SpaceEntity
import com.myvault.app.data.TagEntity
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntriesScreen(
    spaces: List<SpaceEntity>,
    selectedSpaceId: String?,
    entries: List<EntryEntity>,
    tags: List<TagEntity>,
    tagRefs: List<EntryTagCrossRef>,
    onSelectSpace: (String?) -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onNewEntry: (String) -> Unit,
    onOpenEntry: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var chooseSpace by rememberSaveable { mutableStateOf(false) }
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
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        space?.name ?: "All spaces",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to spaces")
                    }
                },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (selectedSpaceId != null) onNewEntry(selectedSpaceId) else chooseSpace = true
                },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New entry") },
                expanded = true,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = {
                    Text(if (selectedSpaceId == null) "Search all spaces…" else "Search entries…")
                },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty())
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear search") }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedSpaceId == null,
                    onClick = { onSelectSpace(null) },
                    label = { Text("All spaces") },
                )
                spaces.forEach { item ->
                    FilterChip(
                        selected = selectedSpaceId == item.id,
                        onClick = { onSelectSpace(item.id) },
                        label = { Text("${item.icon} ${item.name}") },
                    )
                }
            }
            if (tags.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = tagFilter == null,
                        onClick = { tagFilter = null },
                        label = { Text("All tags") },
                    )
                    tags.forEach { tag ->
                        FilterChip(
                            selected = tagFilter == tag.id,
                            onClick = { tagFilter = if (tagFilter == tag.id) null else tag.id },
                            label = { Text(tag.name) },
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Entries",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text("${filtered.size}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding =
                    androidx.compose.foundation.layout.PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = 8.dp,
                        bottom = 100.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (filtered.isEmpty())
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                            shape = MaterialTheme.shapes.large,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                        ) {
                            Column(Modifier.padding(24.dp)) {
                                Icon(
                                    Icons.Default.Description,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    if (query.isNotBlank() || tagFilter != null) "No matching entries"
                                    else "No entries yet",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    if (query.isNotBlank() || tagFilter != null)
                                        "Try another search or filter."
                                    else "Create an entry to start writing.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    }
                items(filtered, key = { it.id }) { entry ->
                    val entryTags = refsByEntry[entry.id].orEmpty().mapNotNull { tagsById[it.tagId] }
                    EntryCard(
                        entry,
                        entryTags,
                        if (selectedSpaceId == null) spaces.find { it.id == entry.spaceId }?.name else null,
                    ) {
                        onOpenEntry(entry.id)
                    }
                }
            }
        }
    }
    if (chooseSpace)
        AlertDialog(
            onDismissRequest = { chooseSpace = false },
            title = { Text("Choose a space") },
            text = {
                if (spaces.isEmpty()) Text("Create a space first.")
                else
                    Column {
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

@Composable
private fun EntryCard(entry: EntryEntity, tags: List<TagEntity>, spaceName: String?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.title.ifBlank { "Untitled entry" },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (entry.pinned)
                        Icon(
                            Icons.Default.PushPin,
                            "Pinned",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.content.ifBlank { "No content yet" }.replace('\n', ' '),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        listOfNotNull(
                                spaceName,
                                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.updatedAt)),
                            )
                            .joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (tags.isNotEmpty()) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        tags.forEach { tag ->
                            Surface(color = parseColor(tag.color), shape = MaterialTheme.shapes.small) {
                                Text(
                                    tag.name,
                                    color = parseColor(tag.textColor, Color.White),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (entry.coverImagePath != null) {
                Spacer(Modifier.width(12.dp))
                AsyncImage(
                    model = remember(entry.coverImagePath) { MediaFiles.displayModel(entry.coverImagePath) },
                    contentDescription = null,
                    modifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.medium),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}
