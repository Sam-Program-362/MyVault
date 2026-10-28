package com.myvault.app.data

import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * DTOs mirror the exact JSON shape produced/consumed by the web app's
 * `/api/export` and `/api/import` routes, so a backup made on the web can be
 * merged on Android or the web. Local image portability is handled separately.
 */
@Serializable
data class SettingsDto(
    val appName: String = "MyVault",
    val accent: String = "#6366F1",
    val mode: String = "system",
    val fontSize: String = "md",
    val radius: String = "lg",
    val wallpaperUrl: String? = null,
    val wallpaperEnabled: Boolean = false,
    val wallpaperOpacity: Int = 35,
    val wallpaperBlur: Int = 0,
)

@Serializable
data class SpaceDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val icon: String = "\uD83D\uDCC1",
    val color: String = "#6366F1",
    val sortOrder: Int = 0,
    val createdAt: String = Instant.now().toString(),
)

@Serializable
data class TagDto(
    val id: String,
    val name: String,
    val color: String = "#6366F1",
    val textColor: String = "#FFFFFF",
    val reusable: Boolean = true,
    val createdAt: String = Instant.now().toString(),
)

@Serializable
data class EntryDto(
    val id: String,
    val spaceId: String,
    val title: String = "Untitled entry",
    val content: String = "",
    val coverImage: String? = null,
    val pinned: Boolean = false,
    val createdAt: String = Instant.now().toString(),
    val updatedAt: String = Instant.now().toString(),
    val tags: List<TagDto> = emptyList(),
)

@Serializable
data class ExportPayload(
    val version: Int = 1,
    val exportedAt: String = Instant.now().toString(),
    val settings: SettingsDto? = null,
    val spaces: List<SpaceDto> = emptyList(),
    val tags: List<TagDto> = emptyList(),
    val entries: List<EntryDto> = emptyList(),
)

data class ImportSummary(val spacesAdded: Int, val tagsAdded: Int, val entriesAdded: Int, val settingsApplied: Boolean)

class ExportImportRepository(
    private val spaceDao: SpaceDao,
    private val tagDao: TagDao,
    private val entryDao: EntryDao,
    private val settingsRepository: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    suspend fun export(): String {
        val spaces = spaceDao.getAll()
        val currentTags = tagDao.getAll()
        val entries = entryDao.getAll()
        val settings = settingsSnapshot()

        val payload = ExportPayload(
            exportedAt = Instant.now().toString(),
            settings = settings,
            spaces = spaces.map { it.toDto() },
            tags = currentTags.map { it.toDto() },
            entries = entries.map { entry ->
                val entryTags = entryDao.tagsForEntry(entry.id)
                entry.toDto(entryTags.map { it.toDto() })
            },
        )
        return json.encodeToString(ExportPayload.serializer(), payload)
    }

    private suspend fun settingsSnapshot(): SettingsDto {
        val current = settingsRepository.settingsFlow.first()
        return SettingsDto(
            appName = current.appName,
            accent = current.accent,
            mode = current.mode,
            fontSize = current.fontSize,
            radius = current.radius,
            wallpaperUrl = current.wallpaperUri,
            wallpaperEnabled = current.wallpaperEnabled,
            wallpaperOpacity = current.wallpaperOpacity,
            wallpaperBlur = current.wallpaperBlur,
        )
    }

    /**
     * Merge-import: existing spaces/tags are matched by name and reused,
     * entries are always added as new records. Nothing already stored is
     * ever deleted or overwritten, guaranteeing no data loss.
     */
    suspend fun import(rawJson: String, applySettings: Boolean): ImportSummary {
        val payload = json.decodeFromString(ExportPayload.serializer(), rawJson)

        var tagsAdded = 0
        var spacesAdded = 0
        var entriesAdded = 0

        val tagIdMap = HashMap<String, String>()
        val allImportedTags = payload.tags + payload.entries.flatMap { it.tags }
        for (tag in allImportedTags) {
            if (tagIdMap.containsKey(tag.id)) continue
            val existing = tagDao.findByName(tag.name)
            val targetId = if (existing != null) {
                existing.id
            } else {
                val newId = java.util.UUID.randomUUID().toString()
                tagDao.upsert(
                    TagEntity(
                        id = newId,
                        name = tag.name,
                        color = tag.color,
                        textColor = tag.textColor,
                        reusable = tag.reusable,
                        createdAt = tag.createdAt.toEpochMillis(),
                    ),
                )
                tagsAdded++
                newId
            }
            tagIdMap[tag.id] = targetId
        }

        val spaceIdMap = HashMap<String, String>()
        val existingSpaces = spaceDao.getAll()
        val spaceByName = existingSpaces.associateBy { it.name.lowercase() }.toMutableMap()
        var nextOrder = (existingSpaces.maxOfOrNull { it.sortOrder } ?: -1) + 1
        for (space in payload.spaces) {
            val key = space.name.lowercase()
            val existing = spaceByName[key]
            val targetId = if (existing != null) {
                existing.id
            } else {
                val newId = java.util.UUID.randomUUID().toString()
                val entity = SpaceEntity(
                    id = newId,
                    name = space.name,
                    description = space.description,
                    icon = space.icon,
                    color = space.color,
                    sortOrder = nextOrder++,
                    createdAt = space.createdAt.toEpochMillis(),
                )
                spaceDao.upsert(entity)
                spaceByName[key] = entity
                spacesAdded++
                newId
            }
            spaceIdMap[space.id] = targetId
        }

        var fallbackSpaceId: String? = null
        for (entry in payload.entries) {
            var targetSpaceId = spaceIdMap[entry.spaceId]
            if (targetSpaceId == null) {
                if (fallbackSpaceId == null) {
                    fallbackSpaceId = spaceDao.getAll().firstOrNull()?.id ?: run {
                        val id = java.util.UUID.randomUUID().toString()
                        spaceDao.upsert(SpaceEntity(id = id, name = "Imported", icon = "\uD83D\uDCE5", sortOrder = nextOrder++))
                        spacesAdded++
                        id
                    }
                }
                targetSpaceId = fallbackSpaceId
            }

            val newEntryId = java.util.UUID.randomUUID().toString()
            entryDao.upsert(
                EntryEntity(
                    id = newEntryId,
                    spaceId = targetSpaceId!!,
                    title = entry.title,
                    content = entry.content,
                    coverImagePath = entry.coverImage,
                    pinned = entry.pinned,
                    createdAt = entry.createdAt.toEpochMillis(),
                    updatedAt = entry.updatedAt.toEpochMillis(),
                ),
            )
            entriesAdded++

            val mappedTagIds = entry.tags.mapNotNull { tagIdMap[it.id] }.distinct()
            if (mappedTagIds.isNotEmpty()) entryDao.setTags(newEntryId, mappedTagIds)
        }

        var settingsApplied = false
        if (applySettings && payload.settings != null) {
            val s = payload.settings
            settingsRepository.update {
                settingsApplied = true
                it.copy(
                    appName = s.appName,
                    accent = s.accent,
                    mode = s.mode,
                    fontSize = s.fontSize,
                    radius = s.radius,
                    wallpaperUri = s.wallpaperUrl,
                    wallpaperEnabled = s.wallpaperEnabled,
                    wallpaperOpacity = s.wallpaperOpacity,
                    wallpaperBlur = s.wallpaperBlur,
                )
            }
        }

        return ImportSummary(spacesAdded, tagsAdded, entriesAdded, settingsApplied)
    }
}

private fun SpaceEntity.toDto() = SpaceDto(
    id = id,
    name = name,
    description = description,
    icon = icon,
    color = color,
    sortOrder = sortOrder,
    createdAt = Instant.ofEpochMilli(createdAt).toString(),
)

private fun TagEntity.toDto() = TagDto(
    id = id,
    name = name,
    color = color,
    textColor = textColor,
    reusable = reusable,
    createdAt = Instant.ofEpochMilli(createdAt).toString(),
)

private fun EntryEntity.toDto(tags: List<TagDto>) = EntryDto(
    id = id,
    spaceId = spaceId,
    title = title,
    content = content,
    coverImage = coverImagePath,
    pinned = pinned,
    createdAt = Instant.ofEpochMilli(createdAt).toString(),
    updatedAt = Instant.ofEpochMilli(updatedAt).toString(),
    tags = tags,
)

private fun String.toEpochMillis(): Long = runCatching { Instant.parse(this).toEpochMilli() }.getOrElse { System.currentTimeMillis() }
