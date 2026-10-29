package com.myvault.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SpaceDao {
    @Query("SELECT * FROM spaces ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<SpaceEntity>>

    @Query("SELECT * FROM spaces")
    suspend fun getAll(): List<SpaceEntity>

    @Upsert
    suspend fun upsert(space: SpaceEntity)

    @Update
    suspend fun update(space: SpaceEntity)

    @Query("DELETE FROM spaces WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM entries WHERE spaceId = :spaceId")
    suspend fun entryCount(spaceId: String): Int
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY name ASC")
    suspend fun getAll(): List<TagEntity>

    @Query("SELECT * FROM tags WHERE scope = 'global' AND lower(name) = lower(:name) LIMIT 1")
    suspend fun findByName(name: String): TagEntity?

    @Upsert
    suspend fun upsert(tag: TagEntity)

    @Update
    suspend fun update(tag: TagEntity)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM tags WHERE scope = 'local' AND ownerSpaceId = :spaceId")
    suspend fun deleteForSpace(spaceId: String)
}

@Dao
interface EntryDao {
    @Query(
        "SELECT * FROM entries WHERE (:spaceId IS NULL OR spaceId = :spaceId) " +
            "AND (:query IS NULL OR title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') " +
            "ORDER BY pinned DESC, updatedAt DESC",
    )
    fun observeFiltered(spaceId: String?, query: String?): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun getById(id: String): EntryEntity?

    @Upsert
    suspend fun upsert(entry: EntryEntity)

    @Update
    suspend fun update(entry: EntryEntity): Int

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM entries")
    suspend fun getAll(): List<EntryEntity>

    @Query("SELECT * FROM entry_tags WHERE entryId = :entryId")
    suspend fun tagRefsForEntry(entryId: String): List<EntryTagCrossRef>

    @Query("SELECT * FROM entry_tags")
    suspend fun allTagRefs(): List<EntryTagCrossRef>

    @Query("SELECT * FROM entry_tags")
    fun observeAllTagRefs(): Flow<List<EntryTagCrossRef>>

    @Query("DELETE FROM entry_tags WHERE entryId = :entryId AND tagId = :tagId")
    suspend fun removeTagRef(entryId: String, tagId: String)

    @Query(
        "SELECT tags.* FROM tags INNER JOIN entry_tags ON tags.id = entry_tags.tagId " +
            "WHERE entry_tags.entryId = :entryId",
    )
    suspend fun tagsForEntry(entryId: String): List<TagEntity>

    @Query("DELETE FROM entry_tags WHERE entryId = :entryId")
    suspend fun clearTags(entryId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTagRef(ref: EntryTagCrossRef)

    @Transaction
    suspend fun setTags(entryId: String, tagIds: List<String>) {
        clearTags(entryId)
        tagIds.forEach { addTagRef(EntryTagCrossRef(entryId, it)) }
    }
}
