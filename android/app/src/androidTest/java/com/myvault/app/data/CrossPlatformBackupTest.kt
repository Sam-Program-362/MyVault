package com.myvault.app.data

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myvault.app.MyVaultApplication
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise v2 serialization on disposable fixture rows; no production DB is opened. */
@RunWith(AndroidJUnit4::class)
class CrossPlatformBackupTest {
    @Test fun exporterPreservesScopeIdentityAndLinks() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MyVaultApplication>()
        // Deliberately omit write guards only in this in-memory fixture so the
        // exporter can be tested with a future Local/Global-shaped dataset.
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            db.spaceDao().upsert(SpaceEntity(id = "source-a", name = "Cross A"))
            db.spaceDao().upsert(SpaceEntity(id = "source-b", name = "Cross B"))
            db.tagDao().upsert(TagEntity(id = "local-a", name = "Ideas", scope = "local", ownerSpaceId = "source-a", reusable = false))
            db.tagDao().upsert(TagEntity(id = "local-b", name = "Ideas", scope = "local", ownerSpaceId = "source-b", reusable = true))
            db.tagDao().upsert(TagEntity(id = "global", name = "Shared", scope = "global", reusable = false))
            for ((suffix, local) in listOf("a" to "local-a", "b" to "local-b")) {
                db.entryDao().upsert(EntryEntity(id = "entry-$suffix", spaceId = "source-$suffix", title = "Entry $suffix", content = "Body $suffix"))
                db.entryDao().addTagRef(EntryTagCrossRef("entry-$suffix", local))
                db.entryDao().addTagRef(EntryTagCrossRef("entry-$suffix", "global"))
            }
            val file = File(app.filesDir, "cross-android.json")
            BackupManager(app, db, app.settings, app.media).exportTo(Uri.fromFile(file))
            val payload = Json { ignoreUnknownKeys = true }.decodeFromString(ExportPayload.serializer(), file.readText())
            assertEquals(2, payload.version)
            assertEquals(setOf("local-a", "local-b", "global"), payload.tags.map { it.id }.toSet())
            for (entry in payload.entries) {
                val local = entry.tags.single { it.scope == "local" }
                assertEquals(entry.spaceId, local.ownerSpaceId)
                assertTrue(local.id == "local-${entry.spaceId.last()}")
                assertEquals(2, entry.tags.size)
            }
            assertEquals(false, payload.tags.single { it.id == "local-a" }.reusable)
            assertEquals(true, payload.tags.single { it.id == "local-b" }.reusable)

            val destination = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
                .addCallback(AppDatabase.SCOPE_GUARD_CALLBACK).allowMainThreadQueries().build()
            try {
                val rejected = runCatching {
                    BackupManager(app, destination, app.settings, app.media).importFrom(Uri.fromFile(file), false)
                }
                assertTrue("Unknown tag IDs must not be mapped by name", rejected.isFailure)
                assertTrue(destination.spaceDao().getAll().isEmpty())
                assertTrue(destination.tagDao().getAll().isEmpty())
                assertTrue(destination.entryDao().getAll().isEmpty())
            } finally { destination.close() }
        } finally { db.close() }
    }
}
