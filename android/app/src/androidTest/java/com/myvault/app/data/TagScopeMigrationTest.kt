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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Room upgrade/preservation and fail-closed scope guards on disposable databases. */
@RunWith(AndroidJUnit4::class)
class TagScopeMigrationTest {
    @Test
    fun versionOneLegacyTagsAndRelationshipsSurviveWithoutIdentityMutation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "tag-scope-migration-v1.db"
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
                db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'trigger' AND name = 'tag_scope_insert'").use {
                    assertTrue("v1-to-v3 migration must install the scope guards", it.moveToFirst())
                }
                val tags = db.tagDao().getAll()
                assertEquals(setOf("t", "t2"), tags.map { it.id }.toSet())
                assertTrue(tags.all { it.scope == "global" && it.ownerSpaceId == null })
                assertFalse(tags.single { it.id == "t" }.reusable)
                assertEquals(setOf("t"), db.entryDao().tagRefsForEntry("e").map { it.tagId }.toSet())
                db.tagDao().update(tags.single { it.id == "t" }.copy(color = "#123456", reusable = true))
                assertTrue(db.tagDao().getAll().single { it.id == "t" }.reusable)

                assertRejected("v1-to-v3 insert must be blocked") { db.tagDao().upsert(TagEntity(id = "new", name = "New")) }
                assertRejected("v1-to-v3 name edit must be blocked") { db.tagDao().update(tags.single { it.id == "t" }.copy(name = "Renamed")) }
                assertEquals(setOf("t", "t2"), db.tagDao().getAll().map { it.id }.toSet())
                assertEquals("Body", db.entryDao().getById("e")?.content)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun versionTwoScopeMetadataAndLinksSurviveAndCrossSpaceUseIsRejected() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "tag-scope-migration-v2.db"
        context.deleteDatabase(name)
        try {
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { old ->
                old.execSQL("CREATE TABLE spaces (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, description TEXT, icon TEXT NOT NULL, color TEXT NOT NULL, sortOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                old.execSQL("CREATE TABLE tags (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, color TEXT NOT NULL, textColor TEXT NOT NULL, reusable INTEGER NOT NULL, scope TEXT NOT NULL DEFAULT 'global', ownerSpaceId TEXT, createdAt INTEGER NOT NULL)")
                old.execSQL("CREATE TABLE entries (id TEXT NOT NULL PRIMARY KEY, spaceId TEXT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, coverImagePath TEXT, pinned INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(spaceId) REFERENCES spaces(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                old.execSQL("CREATE INDEX index_entries_spaceId ON entries(spaceId)")
                old.execSQL("CREATE TABLE entry_tags (entryId TEXT NOT NULL, tagId TEXT NOT NULL, PRIMARY KEY(entryId, tagId), FOREIGN KEY(entryId) REFERENCES entries(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(tagId) REFERENCES tags(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                old.execSQL("CREATE INDEX index_entry_tags_entryId ON entry_tags(entryId)")
                old.execSQL("CREATE INDEX index_entry_tags_tagId ON entry_tags(tagId)")
                old.execSQL("INSERT INTO spaces VALUES ('a', 'A', NULL, 'icon', '#000000', 0, 1), ('b', 'B', NULL, 'icon', '#000000', 1, 1)")
                old.execSQL("INSERT INTO tags VALUES ('local-a', 'Ideas', '#000000', '#ffffff', 0, 'local', 'a', 1), ('local-b', 'Ideas', '#000000', '#ffffff', 1, 'local', 'b', 1), ('global', 'Shared', '#000000', '#ffffff', 0, 'global', NULL, 1)")
                old.execSQL("INSERT INTO entries VALUES ('ea', 'a', 'A entry', 'A', NULL, 0, 1, 1), ('eb', 'b', 'B entry', 'B', NULL, 0, 1, 1)")
                old.execSQL("INSERT INTO entry_tags VALUES ('ea', 'local-a'), ('ea', 'global'), ('eb', 'local-b')")
                old.version = 2
            }
            val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_2_3).build()
            try {
                val tags = db.tagDao().getAll()
                assertEquals(setOf("local-a", "local-b", "global"), tags.map { it.id }.toSet())
                assertEquals("a", tags.single { it.id == "local-a" }.ownerSpaceId)
                assertEquals("b", tags.single { it.id == "local-b" }.ownerSpaceId)
                assertFalse(tags.single { it.id == "local-a" }.reusable)
                assertTrue(tags.single { it.id == "local-b" }.reusable)
                assertEquals(setOf("local-a", "global"), db.entryDao().tagRefsForEntry("ea").map { it.tagId }.toSet())

                assertRejected("cross-Space local tag association must be blocked") { db.entryDao().setTags("ea", listOf("local-b")) }
                assertEquals(setOf("local-a", "global"), db.entryDao().tagRefsForEntry("ea").map { it.tagId }.toSet())
                assertRejected("Local owner change must be blocked") { db.tagDao().update(tags.single { it.id == "local-a" }.copy(ownerSpaceId = "b")) }
                db.tagDao().update(tags.single { it.id == "local-a" }.copy(reusable = true))
                assertTrue(db.tagDao().getAll().single { it.id == "local-a" }.reusable)
                assertNull(db.tagDao().getAll().single { it.id == "global" }.ownerSpaceId)
                db.tagDao().delete("global")
                assertFalse(db.entryDao().tagRefsForEntry("ea").any { it.tagId == "global" })
                db.spaceDao().delete("b")
                assertTrue(db.tagDao().getAll().none { it.id == "local-b" })
                assertTrue(db.tagDao().getAll().any { it.id == "local-a" })
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    private suspend fun assertRejected(reason: String, block: suspend () -> Unit) {
        var rejected = false
        try { block() } catch (_: Exception) { rejected = true }
        assertTrue(reason, rejected)
    }
}
