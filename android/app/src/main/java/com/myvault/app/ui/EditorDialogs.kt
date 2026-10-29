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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    onCreate: (String, String, Boolean, String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf(paletteColors.first()) }
    var reusable by rememberSaveable { mutableStateOf(true) }
    var scope by rememberSaveable { mutableStateOf("") }
    val available =
        tags
            .filter { tag -> selected.none { it.id == tag.id } }
            .filter {
                if (query.isBlank()) it.reusable else it.name.contains(query.trim(), ignoreCase = true)
            }
            .take(8)
    val exact = scope.isNotBlank() && tags.any { it.scope == scope && it.name.equals(query.trim(), true) }
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
                        Surface(
                            color = parseColor(tag.color).copy(alpha = 0.08f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                            border = androidx.compose.foundation.BorderStroke(1.dp, parseColor(tag.color)),
                        ) {
                            Text(
                                tag.name,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                fontSize = 13.sp,
                            )
                        }
                        Text("  ${if (tag.scope == "local") "Local" else "Global"}", fontSize = 12.sp)
                        if (!tag.reusable)
                            Text(
                                "  One-off",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                    }
                }
                if (query.isNotBlank()) {
                    HorizontalDivider()
                    Text("Create “${query.trim()}”", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    TagScopeChoices(scope, true) { scope = it }
                    if (exact) Text("A tag with this name already exists in this scope.", fontSize = 12.sp)
                    ColorChoices(color) { color = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = reusable,
                            onCheckedChange = { reusable = it },
                            modifier =
                                Modifier.semantics { contentDescription = "Save tag for future entries" },
                        )
                        Text("Save for future entries", fontSize = 13.sp)
                    }
                    TextButton(onClick = { onCreate(query, color, reusable, scope) }, enabled = scope.isNotBlank() && !exact) { Text("Create & add tag") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
fun TagScopeChoices(scope: String, allowLocal: Boolean, onChange: (String) -> Unit) {
    Text("Choose tag scope", fontSize = 13.sp)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = allowLocal) { onChange("local") }) {
        RadioButton(selected = scope == "local", onClick = { onChange("local") }, enabled = allowLocal)
        Text(if (allowLocal) "Local tag" else "Local tag (choose a Space first)", fontSize = 13.sp)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onChange("global") }) {
        RadioButton(selected = scope == "global", onClick = { onChange("global") })
        Text("Global tag", fontSize = 13.sp)
    }
}
