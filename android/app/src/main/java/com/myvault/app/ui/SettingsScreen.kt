package com.myvault.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.myvault.app.data.AppSettings
import com.myvault.app.data.MediaFiles
import com.myvault.app.data.TagEntity
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: AppSettings,
    tags: List<TagEntity>,
    onBack: () -> Unit,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onChooseWallpaper: () -> Unit,
    onCreateTag: (String, String, Boolean) -> Unit,
    onSetReusable: (TagEntity, Boolean) -> Unit,
    onDeleteTag: (TagEntity) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    visible: Boolean = true,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var deleting by remember { mutableStateOf<TagEntity?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = visible
    LaunchedEffect(visible) {
        if (visible) tab = 0
        else {
            creating = false
            deleting = null
        }
    }
    if (visibility.currentState || visibility.targetState) {
        Dialog(
            onDismissRequest = onBack,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            AnimatedVisibility(
                visibleState = visibility,
                enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.97f),
                exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.98f),
                label = "Settings dialog",
            ) {
                BoxWithConstraints(
                    Modifier.fillMaxSize().imePadding().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier =
                            Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(0.88f),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 24.dp,
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Settings",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                                    Icon(
                                        Icons.Default.Close,
                                        "Back",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                            )
                            Row(
                                Modifier.fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val tabs =
                                    listOf(
                                        "Appearance" to Icons.Default.Palette,
                                        "Tags" to Icons.Default.Sell,
                                        "Import / Export" to Icons.Default.Download,
                                    )
                                tabs.forEachIndexed { index, (title, icon) ->
                                    Column(
                                        Modifier.selectable(
                                                selected = tab == index,
                                                role = Role.Tab,
                                                onClick = { tab = index },
                                            )
                                            .padding(horizontal = 10.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Row(
                                            Modifier.heightIn(min = 48.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                icon,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint =
                                                    if (tab == index)
                                                        MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                title,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                color =
                                                    if (tab == index)
                                                        MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                            )
                                        }
                                        Box(
                                            Modifier.fillMaxWidth()
                                                .height(2.dp)
                                                .background(
                                                    if (tab == index)
                                                        MaterialTheme.colorScheme.primary
                                                    else Color.Transparent
                                                )
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                            )
                            Column(
                                Modifier.weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                when (tab) {
                                    0 -> AppearanceSettings(settings, onUpdate, onChooseWallpaper)
                                    1 -> {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    "Your tags",
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 18.sp,
                                                )
                                                Text(
                                                    "Reusable tags appear as suggestions in the editor.",
                                                    color =
                                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontSize = 13.sp,
                                                )
                                            }
                                            IconButton(
                                                onClick = { creating = true },
                                                modifier = Modifier.size(48.dp),
                                            ) {
                                                Icon(
                                                    Icons.Default.Add,
                                                    "Create tag",
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        }
                                        if (tags.isEmpty())
                                            Text(
                                                "No tags yet.",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        tags.forEach { tag ->
                                            Surface(
                                                shape = MaterialTheme.shapes.medium,
                                                color = MaterialTheme.colorScheme.surface,
                                                border =
                                                    BorderStroke(
                                                        1.dp,
                                                        MaterialTheme.colorScheme.outline.copy(
                                                            alpha = 0.2f
                                                        ),
                                                    ),
                                            ) {
                                                Row(
                                                    Modifier.fillMaxWidth()
                                                        .padding(
                                                            start = 12.dp,
                                                            top = 4.dp,
                                                            bottom = 4.dp,
                                                            end = 4.dp,
                                                        ),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Surface(
                                                        color = parseColor(tag.color),
                                                        shape = MaterialTheme.shapes.small,
                                                    ) {
                                                        Text(
                                                            tag.name,
                                                            color =
                                                                parseColor(
                                                                    tag.textColor,
                                                                    Color.White,
                                                                ),
                                                            fontSize = 13.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier =
                                                                Modifier.padding(
                                                                    horizontal = 10.dp,
                                                                    vertical = 6.dp,
                                                                ),
                                                        )
                                                    }
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(
                                                        if (tag.reusable) "Reusable" else "One-off",
                                                        modifier = Modifier.weight(1f),
                                                        color =
                                                            MaterialTheme.colorScheme
                                                                .onSurfaceVariant,
                                                        fontSize = 12.sp,
                                                    )
                                                    if (!tag.reusable)
                                                        TextButton(
                                                            onClick = { onSetReusable(tag, true) }
                                                        ) {
                                                            Text("Save")
                                                        }
                                                    IconButton(
                                                        onClick = { deleting = tag },
                                                        modifier = Modifier.size(48.dp),
                                                    ) {
                                                        Icon(
                                                            Icons.Default.DeleteOutline,
                                                            "Delete ${tag.name} tag",
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    else -> {
                                        SettingsCard(
                                            "Export everything",
                                            "Save a JSON backup of spaces, entries, tags and settings. Local covers and wallpaper are embedded in the file.",
                                        ) {
                                            Button(onClick = onExport) {
                                                Icon(Icons.Default.Download, null)
                                                Spacer(Modifier.width(8.dp))
                                                Text("Export JSON")
                                            }
                                        }
                                        SettingsCard(
                                            "Import from JSON",
                                            "Import merges spaces and tags by name and adds entries. Existing entries are not deleted. You can choose whether to restore appearance settings.",
                                        ) {
                                            OutlinedButton(onClick = onImport) {
                                                Icon(Icons.Default.Upload, null)
                                                Spacer(Modifier.width(8.dp))
                                                Text("Choose JSON file")
                                            }
                                        }
                                        Text(
                                            "Backups can contain personal notes and images. Store them somewhere you trust. External image links from older or web backups may need to be re-selected.",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 13.sp,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    if (creating && visible) {
        TagCreateDialog(onDismiss = { creating = false }) { name, color, reusable ->
            creating = false
            onCreateTag(name, color, reusable)
        }
    }
    deleting?.let { tag ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${tag.name}?") },
            text = {
                Text("This removes the tag from every entry. The entries themselves will remain.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        onDeleteTag(tag)
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
private fun AppearanceSettings(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onChooseWallpaper: () -> Unit,
) {
    var name by rememberSaveable(settings.appName) { mutableStateOf(settings.appName) }
    var customAccent by rememberSaveable(settings.accent) { mutableStateOf(settings.accent) }
    var opacity by
        remember(settings.wallpaperOpacity) {
            mutableFloatStateOf(settings.wallpaperOpacity.coerceIn(0, 100).toFloat())
        }
    var blur by
        remember(settings.wallpaperBlur) {
            mutableFloatStateOf(settings.wallpaperBlur.coerceIn(0, 20).toFloat())
        }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("App name")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("App name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = { onUpdate { it.copy(appName = name.trim()) } },
                enabled = name.isNotBlank(),
            ) {
                Text("Save")
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Accent color")
        ColorChoices(settings.accent) { color -> onUpdate { it.copy(accent = color) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val valid = customAccent.matches(Regex("#[0-9a-fA-F]{6}"))
            OutlinedTextField(
                value = customAccent,
                onValueChange = { customAccent = it },
                label = { Text("Custom color (#RRGGBB)") },
                singleLine = true,
                isError = customAccent.isNotEmpty() && !valid,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { onUpdate { it.copy(accent = customAccent) } }, enabled = valid) {
                Text("Apply")
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Theme")
        ChoiceRow(
            listOf("light" to "Light", "dark" to "Dark", "system" to "System"),
            settings.mode,
        ) { mode ->
            onUpdate { it.copy(mode = mode) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Font size")
        ChoiceRow(
            listOf("sm" to "Small", "md" to "Medium", "lg" to "Large", "xl" to "Extra large"),
            settings.fontSize,
        ) { size ->
            onUpdate { it.copy(fontSize = size) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Roundness")
        ChoiceRow(
            listOf(
                "none" to "Square",
                "sm" to "Subtle",
                "md" to "Soft",
                "lg" to "Rounded",
                "xl" to "Very round",
            ),
            settings.radius,
        ) { radius ->
            onUpdate { it.copy(radius = radius) }
        }
    }
    HorizontalDivider()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Background wallpaper")
        if (settings.wallpaperUri != null) {
            AsyncImage(
                model =
                    remember(settings.wallpaperUri) {
                        MediaFiles.displayModel(settings.wallpaperUri)
                    },
                contentDescription = "Wallpaper preview",
                modifier = Modifier.fillMaxWidth().height(120.dp),
                contentScale = ContentScale.Crop,
            )
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onChooseWallpaper) {
                Icon(Icons.Default.Image, null)
                Spacer(Modifier.width(8.dp))
                Text(if (settings.wallpaperUri == null) "Choose image" else "Change image")
            }
            if (settings.wallpaperUri != null)
                TextButton(
                    onClick = {
                        onUpdate { it.copy(wallpaperUri = null, wallpaperEnabled = false) }
                    }
                ) {
                    Text("Remove")
                }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Show wallpaper", modifier = Modifier.weight(1f))
            Switch(
                checked = settings.wallpaperEnabled,
                modifier = Modifier.semantics { contentDescription = "Show wallpaper" },
                onCheckedChange = { enabled -> onUpdate { it.copy(wallpaperEnabled = enabled) } },
            )
        }
        Text("Opacity ${opacity.roundToInt()}%", fontSize = 13.sp)
        Slider(
            value = opacity,
            modifier = Modifier.semantics { contentDescription = "Wallpaper opacity" },
            onValueChange = { opacity = it },
            valueRange = 0f..100f,
            onValueChangeFinished = {
                val value = opacity.roundToInt()
                onUpdate { it.copy(wallpaperOpacity = value) }
            },
        )
        Text("Blur ${blur.roundToInt()} px", fontSize = 13.sp)
        Slider(
            value = blur,
            modifier = Modifier.semantics { contentDescription = "Wallpaper blur" },
            onValueChange = { blur = it },
            valueRange = 0f..20f,
            onValueChangeFinished = {
                val value = blur.roundToInt()
                onUpdate { it.copy(wallpaperBlur = value) }
            },
        )
        Text(
            "Wallpaper blur is available on Android 12 and later.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun SectionLabel(label: String) {
    Text(
        label.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ChoiceRow(
    choices: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
                shape = MaterialTheme.shapes.medium,
                colors =
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
            )
        }
    }
}

@Composable
private fun SettingsCard(title: String, description: String, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            content()
        }
    }
}

@Composable
fun TagCreateDialog(onDismiss: () -> Unit, onCreate: (String, String, Boolean) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf(paletteColors.first()) }
    var reusable by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New tag") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Tag name") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                ColorChoices(color) { color = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = reusable,
                        onCheckedChange = { reusable = it },
                        modifier =
                            Modifier.semantics {
                                contentDescription = "Save tag for future entries"
                            },
                    )
                    Text("Save for future entries", fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name, color, reusable) }, enabled = name.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
