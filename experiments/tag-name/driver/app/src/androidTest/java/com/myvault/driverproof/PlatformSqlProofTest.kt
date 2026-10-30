package com.myvault.driverproof

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the same proof view on Android's platform SQLite, never on a user DB. */
@RunWith(AndroidJUnit4::class)
class PlatformSqlProofTest {
    @Test fun platformSqlComputesSharedUnicodeKeys() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun read(name: String) = assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val rows = JSONObject(read("ucd-15.1.json")).getJSONArray("rows")
        val expected = JSONArray(read("expected-keys.json"))
        val fixture = JSONObject(read("tag-name-policy-vectors.json"))
        val inputs = mutableListOf<String>()
        val pairs = fixture.getJSONArray("pairs")
        for (i in 0 until pairs.length()) {
            val p = pairs.getJSONObject(i)
            inputs.add(p.getString("left")); inputs.add(p.getString("right"))
        }
        val scoped = fixture.getJSONArray("scopedCases")
        for (i in 0 until scoped.length()) {
            val tags = scoped.getJSONObject(i).getJSONArray("tags")
            for (j in 0 until tags.length()) inputs.add(tags.getJSONObject(j).getString("name"))
        }
        inputs.addAll(listOf("", " \u00a0\t", "Café"))
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE ucd(cp INTEGER PRIMARY KEY,nfd TEXT,fold TEXT,cc INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE compositions(a INTEGER,b INTEGER,value TEXT NOT NULL,PRIMARY KEY(a,b))")
            db.execSQL("CREATE TABLE n(id INTEGER PRIMARY KEY,name TEXT NOT NULL,scope TEXT NOT NULL DEFAULT 'global',owner TEXT,reusable INTEGER NOT NULL DEFAULT 1)")
            db.beginTransaction()
            try {
                val insert = db.compileStatement("INSERT INTO ucd VALUES(?,?,?,?)")
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONArray(i)
                    insert.bindLong(1,row.getLong(0))
                    if (row.isNull(1)) insert.bindNull(2) else insert.bindString(2,row.getString(1))
                    if (row.isNull(2)) insert.bindNull(3) else insert.bindString(3,row.getString(2))
                    insert.bindLong(4,row.getLong(3)); insert.executeInsert()
                }
                val compositions = JSONObject(read("ucd-15.1.json")).getJSONArray("compositions")
                val compose = db.compileStatement("INSERT INTO compositions VALUES(?,?,?)")
                for (i in 0 until compositions.length()) {
                    val row = compositions.getJSONArray(i)
                    compose.bindLong(1,row.getLong(0)); compose.bindLong(2,row.getLong(1)); compose.bindString(3,row.getString(2)); compose.executeInsert()
                }
                val names = db.compileStatement("INSERT INTO n(id,name) VALUES(?,?)")
                inputs.forEachIndexed { id, value -> names.bindLong(1,id.toLong()); names.bindString(2,value); names.executeInsert() }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            db.execSQL(read("sqlite-proof-view.sql"))
            db.rawQuery("SELECT identity FROM keys ORDER BY id",null).use { cursor ->
                assertEquals(expected.length(),cursor.count)
                var i=0
                while (cursor.moveToNext()) { assertEquals("input $i: ${inputs[i]}",expected.getString(i),cursor.getString(0)); i++ }
            }
            // Seed legacy collisions before installing the experimental guard.
            db.execSQL("DELETE FROM n")
            db.execSQL("INSERT INTO n(id,name) VALUES(1,'Café'),(2,'Café')")
            db.execSQL("CREATE TABLE links(entry_id INTEGER,tag_id INTEGER)")
            db.execSQL("INSERT INTO links VALUES(11,1),(12,2)")
            db.execSQL("""CREATE TRIGGER proof_ins AFTER INSERT ON n BEGIN
                SELECT CASE WHEN (SELECT identity FROM keys WHERE id=NEW.id) = '' THEN RAISE(ABORT,'empty') END;
                SELECT CASE WHEN EXISTS (SELECT 1 FROM n x WHERE x.id<>NEW.id AND x.scope=NEW.scope
                  AND x.owner IS NEW.owner AND (SELECT identity FROM keys WHERE id=x.id)=(SELECT identity FROM keys WHERE id=NEW.id))
                  THEN RAISE(ABORT,'collision') END;
            END""")
            try { db.execSQL("INSERT INTO n(id,name) VALUES(3,'CAFÉ')"); fail("Expected collision") }
            catch (_: android.database.sqlite.SQLiteException) { }
            try { db.execSQL("INSERT INTO n(id,name) VALUES(4,'   ')"); fail("Expected empty key rejection") }
            catch (_: android.database.sqlite.SQLiteException) { }
            db.execSQL("INSERT INTO n(id,name,scope,owner,reusable) VALUES(5,'CAFÉ','local','space-a',0)")
            db.execSQL("INSERT INTO n(id,name,scope,owner,reusable) VALUES(6,'CAFÉ','local','space-b',1)")
            try { db.execSQL("INSERT INTO n(id,name,scope,owner,reusable) VALUES(7,'CAFÉ','local','space-a',1)"); fail("Expected local collision") }
            catch (_: android.database.sqlite.SQLiteException) { }
            db.rawQuery("SELECT count(*) FROM links",null).use { c -> assertTrue(c.moveToFirst()); assertEquals(2,c.getInt(0)) }
            db.rawQuery("SELECT count(*) FROM n",null).use { c -> assertTrue(c.moveToFirst()); assertEquals(4,c.getInt(0)) }
        }
    }
}
