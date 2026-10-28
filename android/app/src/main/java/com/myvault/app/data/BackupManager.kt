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
            val entries =
                original.entries.map { it.copy(coverImage = media.portableReference(it.coverImage)) }
            val settingsValue =
                original.settings?.let {
                    it.copy(wallpaperUrl = media.portableReference(it.wallpaperUrl))
                }
            val payload = original.copy(entries = entries, settings = settingsValue)
            val output =
                context.contentResolver.openOutputStream(uri)
                    ?: error("Could not write the selected backup file.")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(codec.encodeToString(payload)) }
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
            val createdFiles = mutableListOf<File>()
            var transactionStarted = false
            try {
                val entries =
                    original.entries.map {
                        it.copy(coverImage = media.restoreReference(it.coverImage, createdFiles))
                    }
                val settingsValue =
                    if (applySettings)
                        original.settings?.let {
                            it.copy(wallpaperUrl = media.restoreReference(it.wallpaperUrl, createdFiles))
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
