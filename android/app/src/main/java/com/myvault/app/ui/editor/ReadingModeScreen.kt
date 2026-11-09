package com.myvault.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import com.myvault.app.data.EntryDto
import com.myvault.app.data.MediaFiles
import dev.jeziellago.compose.markdowntext.MarkdownText

private fun findReaderMatches(text: String, query: String): List<Int> {
    if (query.isBlank()) return emptyList()
    val matches = mutableListOf<Int>()
    var from = 0
    while (from <= text.length - query.length) {
        val next = text.indexOf(query, startIndex = from, ignoreCase = true)
        if (next < 0) break
        matches += next
        from = next + query.length
    }
    return matches
}

@Composable
fun ReadingModeScreen(
    entry: EntryDto,
    darkTheme: Boolean,
    accent: Color,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    onExport: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (darkTheme) Color(0xFF191918) else Color(0xFFFBFAF8)
    val foreground = if (darkTheme) Color(0xFFEAE9E4) else Color(0xFF292825)
    val muted = if (darkTheme) Color(0xFFAAA9A1) else Color(0xFF74736D)
    val line = if (darkTheme) Color(0xFF373734) else Color(0xFFE8E6E1)
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var findOpen by remember(entry.id) { mutableStateOf(false) }
    var query by remember(entry.id) { mutableStateOf("") }
    var currentMatch by remember(entry.id) { mutableIntStateOf(-1) }
    var status by remember(entry.id) { mutableStateOf("") }
    val matches = remember(entry.content, query) { findReaderMatches(entry.content, query) }
    val selectedMatch = matches.getOrNull(currentMatch)
    val previewText = remember(entry.content, selectedMatch, query) {
        if (selectedMatch == null || query.isEmpty()) null
        else {
            val start = (selectedMatch - 48).coerceAtLeast(0)
            val end = (selectedMatch + query.length + 72).coerceAtMost(entry.content.length)
            val snippet = entry.content.substring(start, end)
            val matchStart = selectedMatch - start
            AnnotatedString.Builder().apply {
                append(if (start > 0) "…" else "")
                append(snippet.substring(0, matchStart))
                pushStyle(SpanStyle(background = Color(0xFFFFD54F), color = Color(0xFF241F12), fontWeight = FontWeight.Bold))
                append(snippet.substring(matchStart, matchStart + query.length))
                pop()
                append(snippet.substring(matchStart + query.length))
                if (end < entry.content.length) append("…")
            }.toAnnotatedString()
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = background) {
        Column(Modifier.fillMaxSize().background(background)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Return to editor", tint = muted)
                }
                Icon(Icons.Default.Book, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Text(
                    "Reading mode",
                    color = foreground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(entry.content))
                    status = "Original Markdown copied."
                }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy original Markdown", tint = muted)
                }
                IconButton(onClick = { findOpen = !findOpen; status = "" }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Search, contentDescription = "Find in entry", tint = muted)
                }
                IconButton(onClick = { onExport(entry.title) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Download, contentDescription = "Export this entry", tint = muted)
                }
            }
            HorizontalDivider(color = line)
            if (status.isNotBlank()) {
                Text(status, color = muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
            }

            if (findOpen) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it; currentMatch = -1 },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Find in entry") },
                        )
                        IconButton(onClick = { findOpen = false; query = "" }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close find", tint = muted)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (matches.isEmpty()) "0 matches" else "${if (currentMatch < 0) 0 else currentMatch + 1} of ${matches.size} matches",
                            color = muted,
                            fontSize = 12.sp,
                            modifier = Modifier.semantics { contentDescription = "${matches.size} matches" },
                        )
                        Row {
                            TextButton(
                                onClick = {
                                    if (matches.isNotEmpty()) currentMatch = if (currentMatch <= 0) matches.lastIndex else currentMatch - 1
                                },
                                enabled = matches.isNotEmpty(),
                            ) {
                                Icon(Icons.Default.SkipPrevious, contentDescription = null)
                                Text("Previous")
                            }
                            TextButton(
                                onClick = {
                                    if (matches.isNotEmpty()) currentMatch = if (currentMatch >= matches.lastIndex) 0 else currentMatch + 1
                                },
                                enabled = matches.isNotEmpty(),
                            ) {
                                Text("Next")
                                Icon(Icons.Default.SkipNext, contentDescription = null)
                            }
                        }
                    }
                    if (matches.isEmpty() && query.isNotBlank()) {
                        Text("No matches found.", color = muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                    } else if (previewText != null) {
                        Text(
                            previewText,
                            color = foreground,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        )
                    }
                }
                HorizontalDivider(color = line)
            }

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 22.dp),
            ) {
                if (!entry.coverImage.isNullOrBlank()) {
                    AsyncImage(
                        model = remember(entry.coverImage) { MediaFiles.displayModel(entry.coverImage) },
                        contentDescription = "Entry cover",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(bottom = 14.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (entry.pinned) ReaderMetadataChip("Pinned", foreground, line)
                    entry.tags.forEach { tag ->
                        ReaderMetadataChip("${tag.name}${if (tag.scope == "local") " · Local" else ""}", foreground, line)
                    }
                }
                Text(
                    entry.title.ifBlank { "Untitled entry" },
                    color = foreground,
                    fontSize = 28.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
                Text(
                    "Updated ${entry.updatedAt}",
                    color = muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 20.dp),
                )
                MarkdownText(
                    markdown = remember(entry.content, context) {
                        fallbackInvalidMarkdownImages(entry.content) {
                            MediaFiles.isRenderableMarkdownImage(context, it)
                        }
                    }.ifEmpty { "*Nothing here yet.*" },
                    style = TextStyle(color = foreground, fontSize = 15.sp, lineHeight = 25.sp),
                    linkColor = accent,
                    imageLoader = imageLoader,
                    isTextSelectable = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ReaderMetadataChip(text: String, foreground: Color, line: Color) {
    Surface(shape = RoundedCornerShape(50), color = Color.Transparent, border = androidx.compose.foundation.BorderStroke(1.dp, line)) {
        Text(text, color = foreground, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
    }
}
