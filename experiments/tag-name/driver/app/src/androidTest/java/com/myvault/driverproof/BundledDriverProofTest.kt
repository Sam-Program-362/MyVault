package com.myvault.driverproof

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myvault.app.data.AppDatabase
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Driver/lifecycle proof only. The function is NOT a Unicode canonicalizer. */
@RunWith(AndroidJUnit4::class)
class BundledDriverProofTest {
    @Test fun scalarRegisteredBeforeRealRoomMigrationAndAvailableToQueriesAndTriggers() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "driver-proof.db"
        context.deleteDatabase(name)
        context.getDatabasePath(name).parentFile!!.mkdirs()
        try {
            // Seed the actual legacy v1 schema with two colliding tags and links.
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { old ->
                old.execSQL("CREATE TABLE spaces (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, description TEXT, icon TEXT NOT NULL, color TEXT NOT NULL, sortOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                old.execSQL("CREATE TABLE tags (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, color TEXT NOT NULL, textColor TEXT NOT NULL, reusable INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                old.execSQL("CREATE TABLE entries (id TEXT NOT NULL PRIMARY KEY, spaceId TEXT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, coverImagePath TEXT, pinned INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(spaceId) REFERENCES spaces(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                old.execSQL("CREATE INDEX index_entries_spaceId ON entries(spaceId)")
                old.execSQL("CREATE TABLE entry_tags (entryId TEXT NOT NULL, tagId TEXT NOT NULL, PRIMARY KEY(entryId, tagId), FOREIGN KEY(entryId) REFERENCES entries(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(tagId) REFERENCES tags(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                old.execSQL("CREATE INDEX index_entry_tags_entryId ON entry_tags(entryId)")
                old.execSQL("CREATE INDEX index_entry_tags_tagId ON entry_tags(tagId)")
                old.execSQL("INSERT INTO spaces VALUES ('s','Space',NULL,'icon','#000',0,1)")
                old.execSQL("INSERT INTO tags VALUES ('a','Ideas','#000','#fff',0,1),('b','ideas','#000','#fff',1,1)")
                old.execSQL("INSERT INTO entries VALUES ('e','s','Title','Body',NULL,0,1,1)")
                old.execSQL("INSERT INTO entry_tags VALUES ('e','a'),('e','b')")
                old.version = 1
            }
            val configured = AtomicInteger()
            val base = RequerySQLiteOpenHelperFactory()
            val factory = SupportSQLiteOpenHelper.Factory { config ->
                val delegate = config.callback
                val callback = object : SupportSQLiteOpenHelper.Callback(delegate.version) {
                    override fun onConfigure(db: SupportSQLiteDatabase) {
                        (db as io.requery.android.database.sqlite.SQLiteDatabase).addFunction("proof_scalar", 1, { args, result ->
                            result.set("proof:" + args.getString(0))
                        })
                        configured.incrementAndGet()
                        delegate.onConfigure(db)
                    }
                    override fun onCreate(db: SupportSQLiteDatabase) = delegate.onCreate(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = delegate.onUpgrade(db, oldVersion, newVersion)
                    override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = delegate.onDowngrade(db, oldVersion, newVersion)
                    override fun onOpen(db: SupportSQLiteDatabase) = delegate.onOpen(db)
                    override fun onCorruption(db: SupportSQLiteDatabase) = delegate.onCorruption(db)
                }
                base.create(SupportSQLiteOpenHelper.Configuration.builder(config.context).name(config.name).callback(callback).build())
            }
            val migration = object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.query("SELECT proof_scalar('migration')").use { c -> assertTrue(c.moveToFirst()); assertEquals("proof:migration", c.getString(0)) }
                    AppDatabase.MIGRATION_2_3.migrate(db)
                }
            }
            val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .openHelperFactory(factory).setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(AppDatabase.MIGRATION_1_2, migration).addCallback(AppDatabase.SCOPE_GUARD_CALLBACK).build()
            try {
                val db = room.openHelper.writableDatabase
                assertTrue(configured.get() > 0)
                db.query("SELECT proof_scalar('query')").use { c -> assertTrue(c.moveToFirst()); assertEquals("proof:query", c.getString(0)) }
                db.execSQL("CREATE TABLE proof(v TEXT)")
                db.execSQL("CREATE TRIGGER proof_guard BEFORE INSERT ON proof BEGIN SELECT CASE WHEN proof_scalar(NEW.v)='proof:bad' THEN RAISE(ABORT,'proof rejected') END; END")
                db.execSQL("INSERT INTO proof VALUES ('good')")
                try { db.execSQL("INSERT INTO proof VALUES ('bad')"); fail("Trigger did not reject") } catch (_: android.database.SQLException) { }
                db.query("SELECT id FROM tags ORDER BY id").use { c -> assertEquals(2,c.count); assertTrue(c.moveToFirst()); assertEquals("a",c.getString(0)); assertTrue(c.moveToNext()); assertEquals("b",c.getString(0)) }
                db.query("SELECT count(*) FROM entry_tags").use { c -> assertTrue(c.moveToFirst()); assertEquals(2,c.getInt(0)) }
                // Force concurrent queries through the connection pool; each result must be intact.
                val pool = Executors.newFixedThreadPool(6)
                try {
                    val futures = (0 until 6).map { worker -> pool.submit<Boolean> {
                        (0 until 40).all { n ->
                            val value = "$worker-$n"
                            room.query(SimpleSQLiteQuery("SELECT proof_scalar(?)", arrayOf(value))).use { c -> c.moveToFirst() && c.getString(0) == "proof:$value" }
                        }
                    } }
                    futures.forEach { assertTrue(it.get(60, TimeUnit.SECONDS)) }
                } finally { pool.shutdownNow() }
            } finally { room.close() }
        } finally { context.deleteDatabase(name) }
    }
}
