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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myvault.app.data.EntryEntity
import com.myvault.app.data.SpaceEntity

val paletteColors =
    listOf("#6366F1", "#EC4899", "#F59E0B", "#10B981", "#06B6D4", "#8B5CF6", "#EF4444", "#0EA5E9")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpacesScreen(
    appName: String,
    spaces: List<SpaceEntity>,
    entries: List<EntryEntity>,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onCreate: (String, String, String, String) -> Unit,
    onUpdate: (SpaceEntity, String, String, String, String) -> Unit,
    onDelete: (SpaceEntity) -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SpaceEntity?>(null) }
    var deleting by remember { mutableStateOf<SpaceEntity?>(null) }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        appName.ifBlank { "MyVault" },
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, "New space") },
                text = { Text("New space") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding =
                androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 12.dp,
                    bottom = 100.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Your spaces", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "A quiet place for everything worth keeping.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(12.dp))
            }
            if (spaces.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                    ) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.Start) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("Start with a space", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Create a space to organize your entries.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }
            items(spaces, key = { it.id }) { space ->
                SpaceCard(
                    space,
                    entries.count { it.spaceId == space.id },
                    onOpen = { onOpen(space.id) },
                    onEdit = { editing = space },
                    onDelete = { deleting = space },
                )
            }
        }
    }
    if (creating)
        SpaceDialog(title = "New space", onDismiss = { creating = false }) { name, description, color, icon ->
            onCreate(name, description, color, icon)
            creating = false
        }
    editing?.let { space ->
        SpaceDialog(title = "Edit space", space = space, onDismiss = { editing = null }) {
            name,
            description,
            color,
            icon ->
            onUpdate(space, name, description, color, icon)
            editing = null
        }
    }
    deleting?.let { space ->
        val count = entries.count { it.spaceId == space.id }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${space.name}?") },
            text = {
                Text(
                    "This permanently deletes the space and its $count ${if (count == 1) "entry" else "entries"}. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        onDelete(space)
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SpaceCard(
    space: SpaceEntity,
    count: Int,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = parseColor(space.color).copy(alpha = 0.13f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.size(50.dp),
            ) {
                Box(contentAlignment = Alignment.Center) { Text(space.icon, fontSize = 24.sp) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    space.name,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    space.description?.takeIf { it.isNotBlank() }
                        ?: "$count ${if (count == 1) "entry" else "entries"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, "Options for ${space.name}")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit space") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = {
                            menu = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete space") },
                        leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun SpaceDialog(
    title: String,
    space: SpaceEntity? = null,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit,
) {
    var name by rememberSaveable(space?.id) { mutableStateOf(space?.name ?: "") }
    var description by rememberSaveable(space?.id) { mutableStateOf(space?.description ?: "") }
    var color by rememberSaveable(space?.id) { mutableStateOf(space?.color ?: paletteColors.first()) }
    var icon by rememberSaveable(space?.id) { mutableStateOf(space?.icon ?: "📁") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Space name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                )
                Text("Icon", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("📁", "📚", "💡", "📝", "🏠", "🌱", "⭐", "🎨").forEach { choice ->
                        IconButton(
                            onClick = { icon = choice },
                            modifier =
                                Modifier.semantics {
                                    contentDescription = "Space icon $choice"
                                    stateDescription = if (icon == choice) "Selected" else "Not selected"
                                },
                        ) {
                            Surface(
                                shape = CircleShape,
                                color =
                                    if (icon == choice) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                    else Color.Transparent,
                            ) {
                                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                    Text(choice, fontSize = 21.sp)
                                }
                            }
                        }
                    }
                }
                Text("Color", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                ColorChoices(color, onChoose = { color = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, description, color, icon) }, enabled = name.isNotBlank()) {
                Text(if (space == null) "Create" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ColorChoices(selected: String, onChoose: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        paletteColors.forEach { color ->
            IconButton(
                onClick = { onChoose(color) },
                modifier =
                    Modifier.semantics {
                        contentDescription = "Color $color"
                        stateDescription = if (selected.equals(color, true)) "Selected" else "Not selected"
                    },
            ) {
                Surface(shape = CircleShape, color = parseColor(color), modifier = Modifier.size(30.dp)) {
                    if (selected.equals(color, true))
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint =
                                    if (parseColor(color).luminance() > 0.179f) Color(0xFF171717)
                                    else Color.White,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                }
            }
        }
    }
}
