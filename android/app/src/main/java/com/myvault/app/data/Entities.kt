package com.myvault.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A tab / collection that groups related entries together, e.g. "Work", "Recipes". */
@Entity(tableName = "spaces")
data class SpaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String? = null,
    val icon: String = "\uD83D\uDCC1",
    val color: String = "#6366F1",
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A reusable, color-coded tag that can be attached to any entry. */
@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: String = "#6366F1",
    val textColor: String = "#FFFFFF",
    /** Whether this tag shows up as a suggestion for future entries. */
    val reusable: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A single manual/entry: a title, markdown body, and an optional cover image. */
@Entity(
    tableName = "entries",
    foreignKeys = [
        ForeignKey(
            entity = SpaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["spaceId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("spaceId")],
)
data class EntryEntity(
    @PrimaryKey val id: String,
    val spaceId: String,
    val title: String = "Untitled entry",
    val content: String = "",
    val coverImagePath: String? = null,
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Many-to-many join between entries and tags. */
@Entity(
    tableName = "entry_tags",
    primaryKeys = ["entryId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = EntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("entryId"), Index("tagId")],
)
data class EntryTagCrossRef(
    @ColumnInfo(name = "entryId") val entryId: String,
    @ColumnInfo(name = "tagId") val tagId: String,
)

data class EntryWithTags(
    val entry: EntryEntity,
    val tags: List<TagEntity>,
)
