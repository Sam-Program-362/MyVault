package com.myvault.app.data

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myvault.app.MyVaultApplication
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in exchange with an isolated web server and PostgreSQL; never touches the user's vault. */
@RunWith(AndroidJUnit4::class)
class CrossPlatformBackupTest {
    @Test fun exchangeRealFiles() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("myvault.crossPhase") ?: return@runBlocking
        val app = ApplicationProvider.getApplicationContext<MyVaultApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .addCallback(AppDatabase.SCOPE_GUARD_CALLBACK).allowMainThreadQueries().build()
        try {
            val backup = BackupManager(app, db, app.settings, app.media)
            if (phase == "export") {
                for ((id, name) in listOf("source-a" to "Cross A", "source-b" to "Cross B"))
                    db.spaceDao().upsert(SpaceEntity(id = id, name = name))
                db.tagDao().upsert(TagEntity(id = "source-local-a", name = "Ideas", scope = "local", ownerSpaceId = "source-a", reusable = false))
                db.tagDao().upsert(TagEntity(id = "source-local-b", name = "Ideas", scope = "local", ownerSpaceId = "source-b", reusable = true))
                db.tagDao().upsert(TagEntity(id = "source-global", name = "Shared", scope = "global", reusable = false))
                for ((suffix, local) in listOf("a" to "source-local-a", "b" to "source-local-b")) {
                    db.entryDao().upsert(EntryEntity(id = "source-entry-$suffix", spaceId = "source-$suffix", title = "Entry $suffix", content = "Body $suffix"))
                    db.entryDao().addTagRef(EntryTagCrossRef("source-entry-$suffix", local))
                    db.entryDao().addTagRef(EntryTagCrossRef("source-entry-$suffix", "source-global"))
                }
                backup.exportTo(Uri.fromFile(File(app.filesDir, "cross-android.json")))
            } else {
                require(phase == "import")
                db.spaceDao().upsert(SpaceEntity(id = "android-destination-a", name = "Cross A"))
                db.spaceDao().upsert(SpaceEntity(id = "android-destination-b", name = "Cross B"))
                backup.importFrom(Uri.fromFile(File(app.filesDir, "cross-web.json")), false)
                assertEquals(2, db.entryDao().getAll().size)
                assertEquals(3, db.tagDao().getAll().size)
                for ((suffix, reusable) in listOf("a" to false, "b" to true)) {
                    val entry = db.entryDao().getAll().single { it.title == "Entry $suffix" }
                    assertEquals("android-destination-$suffix", entry.spaceId)
                    val refs = db.entryDao().tagsForEntry(entry.id)
                    assertEquals(2, refs.size)
                    val local = refs.single { it.name == "Ideas" }
                    assertEquals("local", local.scope)
                    assertEquals(entry.spaceId, local.ownerSpaceId)
                    assertEquals(reusable, local.reusable)
                    val global = refs.single { it.name == "Shared" }
                    assertEquals("global", global.scope)
                    assertEquals(null, global.ownerSpaceId)
                    assertFalse(global.reusable)
                    assertTrue(entry.id != "source-entry-$suffix")
                }
            }
        } finally { db.close() }
    }
}
