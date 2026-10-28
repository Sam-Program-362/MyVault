package com.myvault.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomPersistenceTest {
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        db =
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After fun cleanup() = db.close()

    @Test
    fun updatingParentsPreservesEntriesAndTagLinks() = runBlocking {
        val space = SpaceEntity(id = "space", name = "Notes")
        val entry = EntryEntity(id = "entry", spaceId = space.id, content = "Original")
        val tag = TagEntity(id = "tag", name = "Ideas")
        db.spaceDao().upsert(space)
        db.entryDao().upsert(entry)
        db.tagDao().upsert(tag)
        db.entryDao().addTagRef(EntryTagCrossRef(entry.id, tag.id))

        db.spaceDao().upsert(space.copy(name = "Renamed"))
        db.entryDao().upsert(entry.copy(content = "Updated"))
        db.tagDao().upsert(tag.copy(color = "#10B981"))

        assertEquals("Updated", db.entryDao().getById(entry.id)?.content)
        assertEquals(listOf(tag.id), db.entryDao().tagRefsForEntry(entry.id).map { it.tagId })
    }
}
