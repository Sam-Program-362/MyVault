package com.myvault.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myvault.app.data.TagEntity

@Composable
fun TagPickerDialog(
    tags: List<TagEntity>,
    selected: List<TagEntity>,
    onDismiss: () -> Unit,
    onSelect: (TagEntity) -> Unit,
    onCreate: (String, String, Boolean) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf(paletteColors.first()) }
    var reusable by rememberSaveable { mutableStateOf(true) }
    val available =
        tags
            .filter { tag -> selected.none { it.id == tag.id } }
            .filter {
                if (query.isBlank()) it.reusable else it.name.contains(query.trim(), ignoreCase = true)
            }
            .take(8)
    val exact = tags.any { it.name.equals(query.trim(), true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add tag") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Find or create a tag") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (available.isEmpty())
                    Text(
                        "No matching tags.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                available.forEach { tag ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(tag) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(color = parseColor(tag.color), shape = MaterialTheme.shapes.small) {
                            Text(
                                tag.name,
                                color = parseColor(tag.textColor, Color.White),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                fontSize = 13.sp,
                            )
                        }
                        if (!tag.reusable)
                            Text(
                                "  One-off",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                    }
                }
                if (query.isNotBlank() && !exact) {
                    HorizontalDivider()
                    Text("Create “${query.trim()}”", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    ColorChoices(color) { color = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = reusable, onCheckedChange = { reusable = it })
                        Text("Save for future entries", fontSize = 13.sp)
                    }
                    TextButton(onClick = { onCreate(query, color, reusable) }) { Text("Create & add tag") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
