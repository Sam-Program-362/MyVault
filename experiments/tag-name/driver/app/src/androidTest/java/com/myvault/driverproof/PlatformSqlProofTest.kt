package com.myvault.driverproof

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
            db.execSQL("CREATE TABLE n(id INTEGER PRIMARY KEY,name TEXT NOT NULL)")
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
                val names = db.compileStatement("INSERT INTO n VALUES(?,?)")
                inputs.forEachIndexed { id, value -> names.bindLong(1,id.toLong()); names.bindString(2,value); names.executeInsert() }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            db.execSQL(read("sqlite-proof-view.sql"))
            db.rawQuery("SELECT identity FROM keys ORDER BY id",null).use { cursor ->
                assertEquals(expected.length(),cursor.count)
                var i=0
                while (cursor.moveToNext()) { assertEquals("input $i: ${inputs[i]}",expected.getString(i),cursor.getString(0)); i++ }
            }
        }
    }
}
