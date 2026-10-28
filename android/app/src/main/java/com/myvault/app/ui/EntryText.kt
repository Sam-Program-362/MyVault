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

/** A lightweight plain-text preview; it never decodes or displays an entry's cover image. */
internal fun listTextPreview(markdown: String): String =
    markdown
        .replace(Regex("!\\[.*?]\\(.*?\\)"), "")
        .replace(Regex("\\[(.*?)]\\(.*?\\)"), "\$1")
        .replace(Regex("[#>*_`~-]"), "")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()
