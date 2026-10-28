package com.myvault.app.ui

/** Count stored logical lines, not device-dependent wrapped lines. An empty entry has no lines. */
internal fun entryLineCount(content: String): Int {
    if (content.isEmpty()) return 0
    var lines = 1
    var index = 0
    while (index < content.length) {
        when (content[index]) {
            '\r' -> {
                lines++
                if (index + 1 < content.length && content[index + 1] == '\n') index++
            }
            '\n' -> lines++
        }
        index++
    }
    return lines
}

private const val PREVIEW_CHARACTER_LIMIT = 16_384
private val markdownImage = Regex("!\\[.*?]\\(.*?\\)")
private val markdownLink = Regex("\\[(.*?)]\\(.*?\\)")
private val markdownHeading = Regex("(?m)^[ \\t]{0,3}#{1,6}[ \\t]+")
private val markdownQuote = Regex("(?m)^[ \\t]{0,3}(?:>[ \\t]?)+")
private val markdownBullet = Regex("(?m)^[ \\t]*[-+*][ \\t]+")
private val markdownEmphasis = Regex("(?<!\\w)([*_])([^\\n*_]+)\\1(?!\\w)")

/** A bounded text-only preview; entry images are never decoded or displayed in List mode. */
internal fun listTextPreview(markdown: String): String {
    // Text is displayed in at most eight lines. Limiting its input avoids processing and
    // measuring an entire large entry for every visible row; line counts use the raw content.
    var start = 0
    while (start < markdown.length && markdown[start].isWhitespace()) start++
    var end = minOf(markdown.length, start + PREVIEW_CHARACTER_LIMIT)
    if (end < markdown.length && end > start && Character.isHighSurrogate(markdown[end - 1])) end--
    return markdown
        .substring(start, end)
        .replace(markdownImage, "")
        .replace(markdownLink, "\$1")
        .replace(markdownHeading, "")
        .replace(markdownQuote, "")
        .replace(markdownBullet, "")
        .replace("**", "")
        .replace("__", "")
        .replace("~~", "")
        .replace(markdownEmphasis, "\$2")
        .replace("`", "")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()
}
