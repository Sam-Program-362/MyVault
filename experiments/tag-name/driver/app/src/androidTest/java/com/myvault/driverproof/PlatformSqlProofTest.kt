package com.myvault.driverproof

import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.Log
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
            fun exec(sql: String) {
                Log.i("MVSqlProof", "BEGIN SQL: $sql")
                db.execSQL(sql)
                Log.i("MVSqlProof", "END SQL: $sql")
            }
            Log.i("MVSqlProof", "Device API=${Build.VERSION.SDK_INT} ABI=${Build.SUPPORTED_ABIS.joinToString()} fingerprint=${Build.FINGERPRINT}")
            db.rawQuery("SELECT sqlite_version()", null).use { c ->
                check(c.moveToFirst())
                Log.i("MVSqlProof", "SELECT sqlite_version(): ${c.getString(0)}")
            }
            exec("CREATE TABLE ucd(cp INTEGER PRIMARY KEY,nfd TEXT,fold TEXT,cc INTEGER NOT NULL)")
            exec("CREATE TABLE compositions(a INTEGER,b INTEGER,value TEXT NOT NULL,PRIMARY KEY(a,b))")
            exec("CREATE TABLE n(id INTEGER PRIMARY KEY,name TEXT NOT NULL,scope TEXT NOT NULL DEFAULT 'global',owner TEXT,reusable INTEGER NOT NULL DEFAULT 1)")
            Log.i("MVSqlProof", "BEGIN load Unicode data and ${inputs.size} inputs")
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
            Log.i("MVSqlProof", "END load Unicode data")
            exec(read("sqlite-proof-view.sql"))
            Log.i("MVSqlProof", "BEGIN SQL: SELECT identity FROM keys ORDER BY id")
            db.rawQuery("SELECT identity FROM keys ORDER BY id",null).use { cursor ->
                assertEquals(expected.length(),cursor.count)
                var i=0
                while (cursor.moveToNext()) { assertEquals("input $i: ${inputs[i]}",expected.getString(i),cursor.getString(0)); i++ }
            }
            Log.i("MVSqlProof", "END SQL: SELECT identity FROM keys ORDER BY id")
            fun benchmark(label: String, values: List<String>) {
                val samples = mutableListOf<Long>()
                repeat(1) {
                    exec("DELETE FROM n")
                    db.beginTransaction()
                    try {
                        val statement = db.compileStatement("INSERT INTO n(id,name) VALUES(?,?)")
                        values.forEachIndexed { i, name -> statement.bindLong(1, i.toLong()); statement.bindString(2, name); statement.executeInsert() }
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                    val started = System.nanoTime()
                    db.rawQuery("SELECT identity FROM keys ORDER BY id", null).use { c -> while (c.moveToNext()) c.getString(0) }
                    samples += (System.nanoTime() - started) / 1_000_000
                }
                Log.i("MVSqlProof", "BENCH $label elapsedMs=${samples.single()} sqlite-rows=${values.size}")
            }
            benchmark("one-short-3cp", listOf("tag"))
            benchmark("one-typical-18cp", listOf("Project café 2026"))
            benchmark("one-long-24cp", listOf("x".repeat(24)))
            benchmark("ten-typical", (0 until 10).map { "Project café $it" })
            // Seed legacy collisions before installing the experimental guard.
            exec("DELETE FROM n")
            exec("INSERT INTO n(id,name) VALUES(1,'Café'),(2,'Café')")
            exec("CREATE TABLE links(entry_id INTEGER,tag_id INTEGER)")
            exec("INSERT INTO links VALUES(11,1),(12,2)")
            fun execScript(script: String) {
                val statement = StringBuilder()
                var inTrigger = false
                for (line in script.lines()) {
                    statement.appendLine(line)
                    if (!inTrigger && line.trim().startsWith("CREATE TRIGGER", ignoreCase = true)) inTrigger = true
                    val trimmed = line.trim()
                    val complete = if (inTrigger) trimmed.equals("END;", ignoreCase = true) else trimmed.endsWith(";")
                    if (complete) {
                        exec(statement.toString())
                        statement.clear()
                        inTrigger = false
                    }
                }
                check(statement.isBlank()) { "Unterminated SQL script: $statement" }
            }
            execScript(read("sqlite-proof-guard.sql"))
            db.rawQuery("SELECT count(*) FROM n", null).use { c -> check(c.moveToFirst()); Log.i("MVSqlProof", "legacy n rows=${c.getInt(0)}") }
            db.rawQuery("SELECT id,identity FROM keys ORDER BY id", null).use { c -> while (c.moveToNext()) Log.i("MVSqlProof", "recomputed key id=${c.getInt(0)} hex=${c.getString(1).toByteArray().joinToString("") { "%02x".format(it) }}") }
            db.rawQuery("SELECT id,scope,hex(identity),quote(identity) FROM identity_cache ORDER BY id", null).use { c ->
                while (c.moveToNext()) Log.i("MVSqlProof", "identity_cache id=${c.getInt(0)} scope=${c.getString(1)} hex=${c.getString(2)} identity=${c.getString(3)}")
            }
            db.rawQuery("SELECT group_concat(id,',') FROM identity_cache GROUP BY scope,owner,identity HAVING count(*)>1", null).use { c ->
                assertTrue("Expected the pre-existing canonical duplicate report", c.moveToFirst())
                assertEquals("1,2", c.getString(0))
            }
            try { exec("INSERT INTO n(id,name) VALUES(3,'CAFÉ')"); fail("Expected collision") }
            catch (_: android.database.sqlite.SQLiteException) { }
            try { exec("INSERT INTO n(id,name) VALUES(4,'   ')"); fail("Expected empty key rejection") }
            catch (_: android.database.sqlite.SQLiteException) { }
            exec("INSERT INTO n(id,name) VALUES(43,'${"x".repeat(24)}')")
            try { exec("INSERT INTO n(id,name) VALUES(42,'${"x".repeat(25)}')"); fail("Expected the prototype length limit") }
            catch (e: android.database.sqlite.SQLiteException) { assertTrue(e.message.orEmpty().contains("24")) }
            val nulInsert = db.compileStatement("INSERT INTO n(id,name) VALUES(?,?)")
            nulInsert.bindLong(1, 41); nulInsert.bindString(2, "a\u0000b")
            try { nulInsert.executeInsert(); fail("Expected embedded NUL rejection") }
            catch (_: android.database.sqlite.SQLiteException) { }
            exec("INSERT INTO n(id,name,scope,owner,reusable) VALUES(5,'CAFÉ','local','space-a',0)")
            exec("INSERT INTO n(id,name,scope,owner,reusable) VALUES(6,'CAFÉ','local','space-b',1)")
            try { exec("INSERT INTO n(id,name,scope,owner,reusable) VALUES(7,'CAFÉ','local','space-a',1)"); fail("Expected local collision") }
            catch (_: android.database.sqlite.SQLiteException) { }
            try { exec("INSERT INTO n(id,name) VALUES(8,'CAFÉ')"); fail("Expected global collision") }
            catch (_: android.database.sqlite.SQLiteException) { }
            exec("UPDATE n SET reusable=0 WHERE id=1")
            exec("UPDATE n SET name='café' WHERE id=1") // identity-preserving edit in old duplicate group
            db.rawQuery("SELECT count(*) FROM identity_cache WHERE scope='global' AND owner IS NULL AND identity=(SELECT identity FROM identity_cache WHERE id=1)",null).use { c -> assertTrue(c.moveToFirst()); assertEquals(2,c.getInt(0)) }
            exec("UPDATE n SET name='resolved tag' WHERE id=2") // explicit resolution; no winner chosen
            db.rawQuery("SELECT count(*) FROM identity_cache WHERE scope='global' AND owner IS NULL AND identity=(SELECT identity FROM identity_cache WHERE id=1)",null).use { c -> assertTrue(c.moveToFirst()); assertEquals(1,c.getInt(0)) }
            exec("INSERT INTO n(id,name,scope,owner) VALUES(10,'new local','local','space-a')")
            try { exec("UPDATE n SET name='CAFÉ' WHERE id=10"); fail("Expected new conflict on update") }
            catch (_: android.database.sqlite.SQLiteException) { }
            db.rawQuery("SELECT count(*) FROM pending_identity",null).use { c -> assertTrue(c.moveToFirst()); assertEquals(0,c.getInt(0)) }
            db.rawQuery("SELECT count(*) FROM links",null).use { c -> assertTrue(c.moveToFirst()); assertEquals(2,c.getInt(0)) }
            db.rawQuery("SELECT count(*) FROM n",null).use { c -> assertTrue(c.moveToFirst()); assertEquals(6,c.getInt(0)) }
        }
    }
}
