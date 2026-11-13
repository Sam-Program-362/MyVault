package com.myvault.app.data

import androidx.compose.ui.text.TextRange

/** Character offsets for a managed image destination in Markdown. */
data class MarkdownImageDestination(
    val destination: String,
    val start: Int,
    val end: Int,
    val imageStart: Int,
    val imageEnd: Int,
    val altText: String,
)

data class MarkdownImageInsertion(val markdown: String, val cursor: Int)

private val managedImageDestination = Regex(
    "^(?:data:image/(?:png|jpeg|jpg|gif|webp);base64,[A-Za-z0-9+/]+={0,2}|file:/+.*|/?api/media/[0-9a-f-]{36})$",
    RegexOption.IGNORE_CASE,
)

/** Finds inline image targets outside fenced and inline code; external URLs and other Markdown are untouched. */
fun findManagedMarkdownImages(markdown: String): List<MarkdownImageDestination> {
    val found = mutableListOf<MarkdownImageDestination>()
    var fencedChar: Char? = null
    var fencedLength = 0
    var lineStart = 0
    while (lineStart < markdown.length) {
        val newline = markdown.indexOf('\n', lineStart).let { if (it < 0) markdown.length else it }
        val line = markdown.substring(lineStart, newline)
        val fence = Regex("^ {0,3}(`{3,}|~{3,})").find(line)
        if (fencedChar != null) {
            if (fence != null && fence.groupValues[1][0] == fencedChar &&
                fence.groupValues[1].length >= fencedLength && line.substring(fence.range.last + 1).isBlank()
            ) fencedChar = null
        } else if (fence != null) {
            fencedChar = fence.groupValues[1][0]
            fencedLength = fence.groupValues[1].length
        } else {
            var index = lineStart
            while (index < newline) {
                if (markdown[index] == '`') {
                    var runEnd = index + 1
                    while (runEnd < newline && markdown[runEnd] == '`') runEnd++
                    val delimiter = markdown.substring(index, runEnd)
                    val closing = markdown.indexOf(delimiter, runEnd)
                    if (closing in runEnd until newline) index = closing + delimiter.length else index = runEnd
                    continue
                }
                if (markdown[index] == '!' && index + 1 < newline && markdown[index + 1] == '[') {
                    var boundary = index + 2
                    while (boundary < newline - 1) {
                        if (markdown[boundary] == '\\') { boundary += 2; continue }
                        if (markdown[boundary] == ']' && markdown[boundary + 1] == '(') break
                        boundary++
                    }
                    if (boundary < newline - 1 && markdown[boundary] == ']') {
                        val start = boundary + 2
                        var end = start
                        while (end < newline && markdown[end] != ')' && !markdown[end].isWhitespace()) {
                            if (markdown[end] == '\\' && end + 1 < newline) end++
                            end++
                        }
                        if (end < newline && markdown[end] == ')') {
                            val destination = markdown.substring(start, end)
                            if (managedImageDestination.matches(destination)) {
                                val rawAlt = markdown.substring(index + 2, boundary)
                                val alt = rawAlt.replace("\\]", "]").replace("\\[", "[").replace("\\\\", "\\")
                                found += MarkdownImageDestination(destination, start, end, index, end + 1, alt)
                            }
                            index = end + 1
                            continue
                        }
                    }
                }
                index++
            }
        }
        lineStart = newline + 1
    }
    return found
}

fun replaceManagedMarkdownImages(markdown: String, replace: (String) -> String): String {
    val refs = findManagedMarkdownImages(markdown).asReversed()
    var result = markdown
    for (ref in refs) result = result.replaceRange(ref.start, ref.end, replace(ref.destination))
    return result
}

fun fallbackInvalidMarkdownImages(markdown: String, isValid: (String) -> Boolean): String {
    var result = markdown
    for (ref in findManagedMarkdownImages(markdown).asReversed()) {
        if (!isValid(ref.destination)) result = result.replaceRange(ref.imageStart, ref.imageEnd, "[Image unavailable: ${ref.altText.ifBlank { "Image" }}]")
    }
    return result
}

fun markdownImageAltFromFilename(filename: String): String =
    filename.substringAfterLast('/').substringBeforeLast('.', filename)
        .replace(Regex("[._-]+"), " ").replace(Regex("\\s+"), " ").trim().ifEmpty { "Image" }

fun insertMarkdownImage(markdown: String, selection: TextRange, altText: String, reference: String): MarkdownImageInsertion {
    val alt = altText.trim().ifEmpty { "Image" }
        .replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace("\n", " ")
    val start = selection.min.coerceIn(0, markdown.length)
    val end = selection.max.coerceIn(start, markdown.length)
    val syntax = "![$alt]($reference)"
    return MarkdownImageInsertion(markdown.replaceRange(start, end, syntax), start + syntax.length)
}
