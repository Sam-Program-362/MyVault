package com.myvault.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myvault.app.data.EntryEntity
import com.myvault.app.data.SpaceEntity

val paletteColors =
    listOf("#6366F1", "#EC4899", "#F59E0B", "#10B981", "#06B6D4", "#8B5CF6", "#EF4444", "#0EA5E9")

private val spaceIcons =
    listOf("📁", "📝", "💡", "🧠", "🎨", "🛠️", "📚", "🎮", "🍳", "💼", "🌱", "🔒", "🏠", "⭐")

/** A native equivalent of the web's always-dark, branded space selector. */
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
    var introVisible by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    LaunchedEffect(Unit) { introVisible = true }
    LaunchedEffect(creating, spaces.size) {
        if (creating) gridState.animateScrollToItem(spaces.size + 1)
    }
    BackHandler(enabled = creating) { creating = false }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns =
            when {
                maxWidth >= 840.dp -> 4
                maxWidth >= 600.dp -> 3
                else -> 2
            }
        val compactHeading = maxWidth < 360.dp
        val compactHeight = maxHeight < 650.dp
        Column(Modifier.fillMaxSize()) {
            // Settings shares a real header with the vault badge, rather than
            // floating alone over an oversized, empty hero.
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.weight(1f).padding(end = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    WelcomeBadge()
                }
                IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding =
                    PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                // Center a short welcome + cards group together. When it grows beyond
                // the viewport, the same lazy grid scrolls without invented heights.
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "welcome") {
                    Column(Modifier.fillMaxWidth()) {
                        AnimatedVisibility(
                            visible = introVisible,
                            enter =
                                fadeIn(tween(320)) +
                                    scaleIn(tween(320), initialScale = 0.97f) +
                                    slideInVertically(tween(320)) { it / 12 },
                        ) {
                            WelcomeHeading(appName, compact = compactHeading)
                        }
                        Spacer(Modifier.height(if (compactHeight) 8.dp else 16.dp))
                    }
                }
                items(spaces, key = { "space-${it.id}" }) { space ->
                    var cardVisible by remember(space.id) { mutableStateOf(false) }
                    LaunchedEffect(space.id) { cardVisible = true }
                    AnimatedVisibility(
                        visible = cardVisible,
                        modifier = Modifier.animateItem(),
                        enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.97f),
                        label = "Space card",
                    ) {
                        SpaceCard(
                            space = space,
                            count = entries.count { it.spaceId == space.id },
                            onOpen = { onOpen(space.id) },
                            onEdit = { editing = space },
                            onDelete = { deleting = space },
                        )
                    }
                }
                if (!creating) {
                    item(key = "create-space") { NewSpaceCard { creating = true } }
                } else {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "new-space-form") {
                        NewSpaceForm(
                            onCancel = { creating = false },
                            onCreate = { name, description, color, icon ->
                                creating = false
                                onCreate(name, description, color, icon)
                            },
                        )
                    }
                }
            }
        }
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
private fun WelcomeBadge() {
    Surface(
        color = Color.White.copy(alpha = 0.09f),
        shape = CircleShape,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
    ) {
        Row(
            Modifier.padding(horizontal = 15.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "YOUR ORGANIZED VAULT",
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun WelcomeHeading(appName: String, compact: Boolean) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            appName.ifBlank { "MyVault" },
            color = Color.White,
            fontSize = if (compact) 36.sp else 42.sp,
            lineHeight = if (compact) 42.sp else 48.sp,
            letterSpacing = (-1).sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Pick a space to jump into, or create a new one. Everything you write stays neatly organized, searchable, and yours.",
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 14.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
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
    Box(Modifier.fillMaxWidth()) {
        Surface(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth().heightIn(min = 136.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color.White.copy(alpha = 0.06f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(
                    color = parseColor(space.color).copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) { Text(space.icon, fontSize = 20.sp) }
                }
                Spacer(Modifier.height(1.dp))
                Text(
                    space.name,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$count ${if (count == 1) "entry" else "entries"}",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                )
            }
        }
        Box(Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.MoreVert,
                    "Options for ${space.name}",
                    tint = Color.White.copy(alpha = 0.65f),
                )
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

@Composable
private fun NewSpaceCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        onClick = onClick,
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 136.dp)
                .semantics { contentDescription = "New space" }
                .drawWithContent {
                    drawContent()
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.28f),
                        cornerRadius = CornerRadius(16.dp.toPx()),
                        style =
                            Stroke(
                                width = 1.dp.toPx(),
                                pathEffect =
                                    PathEffect.dashPathEffect(
                                        floatArrayOf(6.dp.toPx(), 5.dp.toPx())
                                    ),
                            ),
                    )
                },
        shape = shape,
        color = Color.Transparent,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.65f),
            )
            Spacer(Modifier.height(8.dp))
            Text("New space", color = Color.White.copy(alpha = 0.65f), fontSize = 14.sp)
        }
    }
}

@Composable
private fun NewSpaceForm(
    onCancel: () -> Unit,
    onCreate: (String, String, String, String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf(paletteColors.first()) }
    var icon by rememberSaveable { mutableStateOf(spaceIcons.first()) }
    val fieldColors =
        OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedLabelColor = Color.White.copy(alpha = 0.75f),
            unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
            focusedBorderColor = Color.White.copy(alpha = 0.6f),
            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
            cursorColor = Color.White,
        )
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.09f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Space name") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description (optional)") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Icon", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            SpaceIconChoices(icon) { icon = it }
            Text("Color", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            ColorChoices(color) { color = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = { onCreate(name, description, color, icon) },
                    enabled = name.isNotBlank(),
                ) {
                    Text("Create & enter")
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
    var color by
        rememberSaveable(space?.id) { mutableStateOf(space?.color ?: paletteColors.first()) }
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
                SpaceIconChoices(icon) { icon = it }
                Text("Color", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                ColorChoices(color, onChoose = { color = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, description, color, icon) },
                enabled = name.isNotBlank(),
            ) {
                Text(if (space == null) "Create" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SpaceIconChoices(selected: String, onChoose: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        spaceIcons.forEach { choice ->
            IconButton(
                onClick = { onChoose(choice) },
                modifier =
                    Modifier.size(48.dp).semantics {
                        contentDescription = "Space icon $choice"
                        stateDescription = if (selected == choice) "Selected" else "Not selected"
                    },
            ) {
                Surface(
                    shape = CircleShape,
                    color =
                        if (selected == choice)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
                        else Color.White.copy(alpha = 0.06f),
                ) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        Text(choice, fontSize = 21.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun ColorChoices(selected: String, onChoose: (String) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        paletteColors.forEach { color ->
            IconButton(
                onClick = { onChoose(color) },
                modifier =
                    Modifier.size(48.dp).semantics {
                        contentDescription = "Color $color"
                        stateDescription =
                            if (selected.equals(color, true)) "Selected" else "Not selected"
                    },
            ) {
                Surface(
                    shape = CircleShape,
                    color = parseColor(color),
                    modifier = Modifier.size(30.dp),
                ) {
                    if (selected.equals(color, true)) {
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
}
