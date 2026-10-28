package com.myvault.app.ui.editor

/** Match the literal search text, rather than interpreting it as a regular expression. */
internal fun editorSearchRegex(query: String, wholeWord: Boolean): Regex? {
    if (query.isEmpty()) return null
    val escaped = Regex.escape(query)
    val pattern = if (wholeWord) "\\b$escaped\\b" else escaped
    return Regex(pattern, RegexOption.IGNORE_CASE)
}

internal fun editorMatches(content: String, query: String, wholeWord: Boolean): List<IntRange> =
    editorSearchRegex(query, wholeWord)?.findAll(content)?.map { it.range }?.toList().orEmpty()

internal fun replaceEditorMatches(
    content: String,
    query: String,
    replacement: String,
    wholeWord: Boolean,
): String = editorSearchRegex(query, wholeWord)?.replace(content) { replacement } ?: content
