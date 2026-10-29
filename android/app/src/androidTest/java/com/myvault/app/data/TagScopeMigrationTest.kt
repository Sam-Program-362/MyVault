package com.myvault.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Open an actual version-1 database through Room's registered migration. */
@RunWith(AndroidJUnit4::class)
class TagScopeMigrationTest {
    @Test
    fun legacyTagsAndRelationshipsSurvive() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "tag-scope-migration-test.db"
        context.deleteDatabase(name)
        try {
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { old ->
                old.execSQL("CREATE TABLE spaces (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, description TEXT, icon TEXT NOT NULL, color TEXT NOT NULL, sortOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                old.execSQL("CREATE TABLE tags (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, color TEXT NOT NULL, textColor TEXT NOT NULL, reusable INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                old.execSQL("CREATE TABLE entries (id TEXT NOT NULL PRIMARY KEY, spaceId TEXT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, coverImagePath TEXT, pinned INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(spaceId) REFERENCES spaces(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                old.execSQL("CREATE INDEX index_entries_spaceId ON entries(spaceId)")
                old.execSQL("CREATE TABLE entry_tags (entryId TEXT NOT NULL, tagId TEXT NOT NULL, PRIMARY KEY(entryId, tagId), FOREIGN KEY(entryId) REFERENCES entries(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(tagId) REFERENCES tags(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                old.execSQL("CREATE INDEX index_entry_tags_entryId ON entry_tags(entryId)")
                old.execSQL("CREATE INDEX index_entry_tags_tagId ON entry_tags(tagId)")
                old.execSQL("INSERT INTO spaces VALUES ('s', 'Space', NULL, 'icon', '#000000', 0, 1)")
                old.execSQL("INSERT INTO tags VALUES ('t', 'Ideas', '#000000', '#ffffff', 0, 1)")
                old.execSQL("INSERT INTO tags VALUES ('t2', 'ideas', '#000000', '#ffffff', 1, 1)")
                old.execSQL("INSERT INTO entries VALUES ('e', 's', 'Title', 'Body', NULL, 0, 1, 1)")
                old.execSQL("INSERT INTO entry_tags VALUES ('e', 't')")
                old.version = 1
            }
            val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()
            try {
                val tags = db.tagDao().getAll()
                assertEquals(2, tags.size)
                val tag = tags.single { it.id == "t" }
                assertEquals("global", tag.scope)
                assertNull(tag.ownerSpaceId)
                assertFalse(tag.reusable)
                // Legacy duplicates can still be edited without collapsing their identities.
                db.tagDao().update(tag.copy(color = "#123456"))
                var duplicateRejected = false
                try { db.tagDao().upsert(TagEntity(id = "new", name = "IDEAS")) }
                catch (_: Exception) { duplicateRejected = true }
                org.junit.Assert.assertTrue(duplicateRejected)
                assertEquals(2, db.tagDao().getAll().size)
                db.spaceDao().upsert(SpaceEntity(id = "other", name = "Other"))
                db.tagDao().upsert(TagEntity(id = "local1", name = "Ideas", scope = "local", ownerSpaceId = "s"))
                db.tagDao().upsert(TagEntity(id = "local2", name = "Ideas", scope = "local", ownerSpaceId = "other"))
                db.entryDao().addTagRef(EntryTagCrossRef("e", "local1"))
                var invalidEditRejected = false
                try { db.tagDao().update(TagEntity(id = "local1", name = "Ideas", scope = "local", ownerSpaceId = "other")) }
                catch (_: Exception) { invalidEditRejected = true }
                org.junit.Assert.assertTrue(invalidEditRejected)
                assertEquals("Body", db.entryDao().getById("e")?.content)
                assertEquals(setOf("t", "local1"), db.entryDao().tagRefsForEntry("e").map { it.tagId }.toSet())
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
