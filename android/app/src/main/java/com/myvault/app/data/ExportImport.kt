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
    val scope: String = "global",
    val ownerSpaceId: String? = null,
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
            version = 2,
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
        validateTagScopes(payload)

        val existingSpaces = spaceDao.getAll()
        val spaceByName = existingSpaces.groupBy { it.name.lowercase() }.toMutableMap()
        val existingTags = tagDao.getAll().associateBy { it.id }
        val allImportedTags = payload.tags + payload.entries.flatMap { it.tags }
        val definitions = mutableMapOf<String, TagDto>()
        for (tag in allImportedTags) {
            val previous = definitions.putIfAbsent(tag.id, tag)
            require(previous == null || previous.sameDefinition(tag)) { "Conflicting definitions for tag ${tag.id}." }
        }
        // Do not resolve unknown tag IDs by lowercasing names or create tags
        // during import: neither path can guarantee the pinned Unicode identity.
        for (tag in definitions.values) {
            val existing = existingTags[tag.id]
                ?: error("Import of new tag identities is paused until Unicode 15.1 database enforcement is available.")
            require(existing.toDto().sameDefinition(tag)) {
                "Imported tag ${tag.name} conflicts with the existing ID ${tag.id}; no tag was changed."
            }
            if (tag.scope == "local") {
                val sourceOwner = payload.spaces.singleOrNull { it.id == tag.ownerSpaceId }
                    ?: error("Local tag ${tag.name} has no unambiguous owner Space.")
                val targetOwner = existingSpaces.singleOrNull { it.name.equals(sourceOwner.name, ignoreCase = true) }
                    ?: error("Local tag ${tag.name} requires its original Space ID for a safe import.")
                require(targetOwner.id == tag.ownerSpaceId) {
                    "Local tag ${tag.name} cannot be remapped to a different Space ID safely."
                }
            }
        }

        var tagsAdded = 0
        var spacesAdded = 0
        var entriesAdded = 0
        val spaceIdMap = HashMap<String, String>()
        var nextOrder = (existingSpaces.maxOfOrNull { it.sortOrder } ?: -1) + 1
        for (space in payload.spaces) {
            val key = space.name.lowercase()
            val candidates = spaceByName[key].orEmpty()
            require(candidates.size <= 1) { "Ambiguous existing Space ${space.name}." }
            val existing = candidates.singleOrNull()
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
                spaceByName[key] = listOf(entity)
                spacesAdded++
                newId
            }
            spaceIdMap[space.id] = targetId
        }

        val tagIdMap = definitions.keys.associateWith { it }
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
    scope = scope,
    ownerSpaceId = ownerSpaceId,
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

/** Validate ownership before any database writes or media restoration. */
fun validateTagScopes(payload: ExportPayload) {
    require(payload.version in 1..2) { "Unsupported backup version ${payload.version}." }
    val spaces = payload.spaces.map { it.id }.toSet()
    require(spaces.size == payload.spaces.size && payload.spaces.all { it.id.isNotBlank() && it.name.isNotBlank() }) { "Invalid or duplicate Space." }
    require(payload.spaces.map { it.name.lowercase() }.toSet().size == payload.spaces.size) { "Ambiguous Space names." }
    if (payload.version == 2) require(payload.entries.all { it.spaceId in spaces }) { "An entry refers to a missing Space." }
    val definitions = mutableMapOf<String, TagDto>()
    for (tag in payload.tags + payload.entries.flatMap { it.tags }) {
        require(tag.id.isNotBlank() && tag.name.isNotBlank()) { "A tag is missing its ID or name." }
        require(tag.scope == "global" || tag.scope == "local") { "Invalid tag scope." }
        require(if (tag.scope == "global") tag.ownerSpaceId == null else tag.ownerSpaceId != null && spaces.contains(tag.ownerSpaceId)) {
            "Tag ${tag.name} has invalid space ownership."
        }
        require(payload.version != 1 || tag.scope == "global") { "Version 1 does not support local tags." }
        val previous = definitions.putIfAbsent(tag.id, tag)
        require(previous == null || previous.sameDefinition(tag)) { "Conflicting definitions for tag ${tag.id}." }
    }
    for (entry in payload.entries) {
        for (tag in entry.tags) {
            require(definitions[tag.id]?.sameDefinition(tag) == true) { "Entry refers to an undeclared or conflicting tag ID ${tag.id}." }
            require(tag.scope != "local" || tag.ownerSpaceId == entry.spaceId) {
                "Local tag ${tag.name} belongs to a different space."
            }
        }
    }
}

private fun TagDto.sameDefinition(other: TagDto): Boolean =
    id == other.id && name == other.name && color == other.color && textColor == other.textColor &&
        reusable == other.reusable && scope == other.scope && ownerSpaceId == other.ownerSpaceId
