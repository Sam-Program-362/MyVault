package com.myvault.driverproof

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myvault.app.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable Room v1->v3 migration plus the SQL prototype in a mirror table only. */
@RunWith(AndroidJUnit4::class)
class RoomSqlMigrationProofTest {
    @Test(timeout = 300_000)
    fun roomMigrationPreservesLegacyIdsAndLinksBeforeSqlPrototypeProof() {
        runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "room-sql-migration-proof.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                old.execSQL("CREATE TABLE spaces (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, description TEXT, icon TEXT NOT NULL, color TEXT NOT NULL, sortOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                old.execSQL("CREATE TABLE tags (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, color TEXT NOT NULL, textColor TEXT NOT NULL, reusable INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                old.execSQL("CREATE TABLE entries (id TEXT NOT NULL PRIMARY KEY, spaceId TEXT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, coverImagePath TEXT, pinned INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(spaceId) REFERENCES spaces(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                old.execSQL("CREATE INDEX index_entries_spaceId ON entries(spaceId)")
                old.execSQL("CREATE TABLE entry_tags (entryId TEXT NOT NULL, tagId TEXT NOT NULL, PRIMARY KEY(entryId, tagId), FOREIGN KEY(entryId) REFERENCES entries(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(tagId) REFERENCES tags(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                old.execSQL("CREATE INDEX index_entry_tags_entryId ON entry_tags(entryId)")
                old.execSQL("CREATE INDEX index_entry_tags_tagId ON entry_tags(tagId)")
                old.execSQL("INSERT INTO spaces VALUES ('space-a','Space A',NULL,'icon','#000000',0,1)")
                old.execSQL("INSERT INTO tags VALUES ('legacy-cafe-a','Café','#000000','#ffffff',0,1)")
                old.execSQL("INSERT INTO tags VALUES ('legacy-cafe-b','Café','#ffffff','#000000',1,2)")
                old.execSQL("INSERT INTO entries VALUES ('entry-a','space-a','Title','Body',NULL,0,1,1)")
                old.execSQL("INSERT INTO entry_tags VALUES ('entry-a','legacy-cafe-a'),('entry-a','legacy-cafe-b')")
                old.version = 1
            }

            val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
                .build()
            try {
                val oldTags = room.tagDao().getAll()
                assertEquals(setOf("legacy-cafe-a", "legacy-cafe-b"), oldTags.map { it.id }.toSet())
                assertEquals(2, room.entryDao().allTagRefs().size)
                assertEquals(setOf("legacy-cafe-a", "legacy-cafe-b"), room.entryDao().tagRefsForEntry("entry-a").map { it.tagId }.toSet())

                val db = room.openHelper.writableDatabase
                fun read(asset: String) = InstrumentationRegistry.getInstrumentation().context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
                fun execScript(script: String) {
                    val statement = StringBuilder()
                    var inTrigger = false
                    for (line in script.lines()) {
                        statement.appendLine(line)
                        if (!inTrigger && line.trim().startsWith("CREATE TRIGGER", ignoreCase = true)) inTrigger = true
                        val trimmed = line.trim()
                        val complete = if (inTrigger) trimmed.equals("END;", ignoreCase = true) else trimmed.endsWith(";")
                        if (complete) {
                            db.execSQL(statement.toString())
                            statement.clear()
                            inTrigger = false
                        }
                    }
                    check(statement.isBlank()) { "Unterminated SQL script: $statement" }
                }

                db.execSQL("CREATE TABLE ucd(cp INTEGER PRIMARY KEY,nfd TEXT,fold TEXT,cc INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE compositions(a INTEGER,b INTEGER,value TEXT NOT NULL,PRIMARY KEY(a,b))")
                db.execSQL("CREATE TABLE n(id TEXT NOT NULL PRIMARY KEY,name TEXT NOT NULL,scope TEXT NOT NULL,owner TEXT,reusable INTEGER NOT NULL)")
                val ucd = JSONObject(read("ucd-15.1.json")).getJSONArray("rows")
                val compositions = JSONObject(read("ucd-15.1.json")).getJSONArray("compositions")
                db.beginTransaction()
                try {
                    val insert = db.compileStatement("INSERT INTO ucd VALUES(?,?,?,?)")
                    for (i in 0 until ucd.length()) {
                        val row = ucd.getJSONArray(i)
                        insert.bindLong(1, row.getLong(0))
                        if (row.isNull(1)) insert.bindNull(2) else insert.bindString(2, row.getString(1))
                        if (row.isNull(2)) insert.bindNull(3) else insert.bindString(3, row.getString(2))
                        insert.bindLong(4, row.getLong(3))
                        insert.executeInsert()
                    }
                    val compose = db.compileStatement("INSERT INTO compositions VALUES(?,?,?)")
                    for (i in 0 until compositions.length()) {
                        val row = compositions.getJSONArray(i)
                        compose.bindLong(1, row.getLong(0)); compose.bindLong(2, row.getLong(1)); compose.bindString(3, row.getString(2))
                        compose.executeInsert()
                    }
                    db.execSQL("INSERT INTO n SELECT id,name,scope,ownerSpaceId,reusable FROM tags")
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                db.execSQL(read("sqlite-proof-view.sql"))
                execScript(read("sqlite-proof-guard.sql"))
                db.query("SELECT count(*) FROM identity_cache WHERE scope='global' AND owner IS NULL AND identity=(SELECT identity FROM identity_cache WHERE id='legacy-cafe-a')").use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals(2, c.getInt(0))
                }
                var collisionRejected = false
                try { db.execSQL("INSERT INTO n(id,name,scope,owner,reusable) VALUES('new-tag','CAFÉ','global',NULL,1)") }
                catch (_: android.database.SQLException) { collisionRejected = true }
                assertTrue("Expected the SQL prototype to reject a new global duplicate", collisionRejected)
                db.execSQL("UPDATE n SET name='café' WHERE id='legacy-cafe-a'")
                db.execSQL("UPDATE n SET name='resolved tag' WHERE id='legacy-cafe-b'")

                // The production Room rows and associations remain untouched by the mirror-table proof.
                assertEquals(setOf("legacy-cafe-a", "legacy-cafe-b"), room.tagDao().getAll().map { it.id }.toSet())
                assertEquals(setOf("legacy-cafe-a", "legacy-cafe-b"), room.entryDao().tagRefsForEntry("entry-a").map { it.tagId }.toSet())
                assertEquals("Body", room.entryDao().getById("entry-a")?.content)
                android.util.Log.i("MVRoomProof", "PASS disposable Room v1->v3 migration retained tag IDs and entry-tag links; mirror-table SQL guard preserved legacy duplicates")
            } finally { room.close() }
        } finally { context.deleteDatabase(name) }
        }
    }
}
