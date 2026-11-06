package com.myvault.app.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** JSON stays compatible with the web app, with local images embedded as data URIs. */
class BackupManager(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val media: MediaFiles,
) {
    data class Result(val spaces: Int, val entries: Int, val tags: Int, val externalImages: Int)

    private val codec = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        explicitNulls = true
        encodeDefaults = true
    }
    private val importer = ExportImportRepository(db.spaceDao(), db.tagDao(), db.entryDao(), settings)

    suspend fun exportTo(uri: Uri): Result =
        withContext(Dispatchers.IO) {
            val original = codec.decodeFromString<ExportPayload>(importer.export())
            val budget = MediaBudget()
            val entries = original.entries.map { entry ->
                entry.copy(
                    content = portableMarkdown(entry.content, budget),
                    coverImage = portableReference(entry.coverImage, budget),
                )
            }
            val settingsValue = original.settings?.let { it.copy(wallpaperUrl = portableReference(it.wallpaperUrl, budget)) }
            val payload = original.copy(entries = entries, settings = settingsValue)
            val text = codec.encodeToString(payload)
            require(text.length <= MAX_BACKUP_CHARS) { "The backup is larger than the 128 MB import limit." }
            val output =
                context.contentResolver.openOutputStream(uri)
                    ?: error("Could not write the selected backup file.")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
            Result(
                payload.spaces.size,
                payload.entries.size,
                payload.tags.size,
                externalImages(payload, true),
            )
        }

    suspend fun importFrom(uri: Uri, applySettings: Boolean): Result =
        withContext(Dispatchers.IO) {
            val input =
                context.contentResolver.openInputStream(uri)
                    ?: error("Could not open the selected backup file.")
            val text =
                input.bufferedReader(Charsets.UTF_8).use { reader ->
                    val result = StringBuilder()
                    val buffer = CharArray(8192)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count == -1) break
                        require(result.length.toLong() + count <= MAX_BACKUP_CHARS) {
                            "The backup is larger than 128 MB."
                        }
                        result.append(buffer, 0, count)
                    }
                    result.toString()
                }
            val original = codec.decodeFromString<ExportPayload>(text)
            validateTagScopes(original)
            val createdFiles = mutableListOf<File>()
            val budget = MediaBudget()
            val restoredReferences = mutableMapOf<String, String>()
            var transactionStarted = false
            try {
                val entries = original.entries.map { entry ->
                    entry.copy(
                        content = restoreMarkdown(entry.content, createdFiles, restoredReferences, budget),
                        coverImage = restoreReference(entry.coverImage, createdFiles, restoredReferences, budget),
                    )
                }
                val settingsValue =
                    if (applySettings)
                        original.settings?.let {
                            it.copy(wallpaperUrl = restoreReference(it.wallpaperUrl, createdFiles, restoredReferences, budget))
                        }
                    else original.settings
                val restored = original.copy(entries = entries, settings = settingsValue)
                // After the transaction starts, keep files on failure: cancellation can race a commit.
                // An orphan is preferable to deleting media that a committed entry may reference.
                transactionStarted = true
                val summary = db.withTransaction {
                    importer.import(codec.encodeToString(restored), applySettings)
                }
                Result(
                    summary.spacesAdded,
                    summary.entriesAdded,
                    summary.tagsAdded,
                    externalImages(original, applySettings),
                )
            } catch (error: Exception) {
                if (!transactionStarted) createdFiles.forEach { it.delete() }
                throw error
            }
        }

    private class MediaBudget {
        private val seen = mutableSetOf<String>()
        private var total = 0
        fun include(reference: String?) {
            if (reference.isNullOrBlank() || !reference.startsWith("data:image/", ignoreCase = true) || !seen.add(reference)) return
            total += MediaFiles.dataImageByteSize(reference)
            require(total <= MediaFiles.MAX_BACKUP_MEDIA_BYTES) { "Backup images exceed the 16 MB total limit." }
        }
    }

    private suspend fun portableReference(reference: String?, budget: MediaBudget): String? {
        val portable = media.portableReference(reference)
        budget.include(portable)
        return portable
    }

    private suspend fun portableMarkdown(markdown: String, budget: MediaBudget): String {
        var result = markdown
        for (ref in findManagedMarkdownImages(markdown).asReversed()) {
            val portable = portableReference(ref.destination, budget) ?: ref.destination
            result = result.replaceRange(ref.start, ref.end, portable)
        }
        return result
    }

    private suspend fun restoreReference(
        reference: String?,
        created: MutableList<File>,
        cache: MutableMap<String, String>,
        budget: MediaBudget,
    ): String? {
        if (reference.isNullOrBlank() || !reference.startsWith("data:image/", ignoreCase = true)) return reference
        cache[reference]?.let { return it }
        budget.include(reference)
        val restored = media.restoreReference(reference, created)
        if (restored != null) cache[reference] = restored
        return restored
    }

    private suspend fun restoreMarkdown(
        markdown: String,
        created: MutableList<File>,
        cache: MutableMap<String, String>,
        budget: MediaBudget,
    ): String {
        var result = markdown
        for (ref in findManagedMarkdownImages(markdown).asReversed()) {
            val restored = restoreReference(ref.destination, created, cache, budget) ?: ref.destination
            result = result.replaceRange(ref.start, ref.end, restored)
        }
        return result
    }

    private fun externalImages(payload: ExportPayload, includeSettings: Boolean): Int {
        val refs =
            payload.entries.map { it.coverImage } +
                if (includeSettings) {
                    listOf(payload.settings?.wallpaperUrl)
                } else emptyList()
        return refs.count { !it.isNullOrBlank() && !it.startsWith("data:", ignoreCase = true) }
    }

    companion object {
        private const val MAX_BACKUP_CHARS = 128L * 1024 * 1024
    }
}
