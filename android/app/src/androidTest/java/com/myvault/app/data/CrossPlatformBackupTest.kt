package com.myvault.app.data

import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
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
    @Test fun webStylePortableBackupImportsToDurableLocalMediaAndExportsAgain() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MyVaultApplication>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val sourceImage = File(app.cacheDir, "portable-${System.nanoTime()}.png")
        sourceImage.outputStream().use { output ->
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        var restoredFile: File? = null
        val dataUri = "data:image/png;base64,${Base64.encodeToString(sourceImage.readBytes(), Base64.NO_WRAP)}"
        val backup = File(app.cacheDir, "web-portable-${System.nanoTime()}.json")
        val exported = File(app.cacheDir, "android-portable-${System.nanoTime()}.json")
        try {
            backup.writeText(
                """{"version":2,"spaces":[{"id":"web-space","name":"From Web"}],"entries":[{"id":"web-entry","spaceId":"web-space","title":"Portable","content":"before ![photo]($dataUri) after\\n![same]($dataUri) and ![external](https://example.org/image.png)","coverImage":"$dataUri"}]}""",
            )
            val manager = BackupManager(app, db, app.settings, app.media)
            manager.importFrom(Uri.fromFile(backup), false)
            val imported = db.entryDao().getAll().single()
            assertTrue(imported.content.contains("before ![photo](file://"))
            assertTrue(imported.content.contains("after\\n![same]("))
            assertTrue(imported.content.contains("![external](https://example.org/image.png)"))
            val firstUri = Regex("!\\[photo]\\((file:[^)]*)\\)").find(imported.content)!!.groupValues[1]
            val secondUri = Regex("!\\[same]\\((file:[^)]*)\\)").find(imported.content)!!.groupValues[1]
            assertEquals(firstUri, secondUri)
            assertEquals(firstUri, imported.coverImagePath)
            val imageFile = File(requireNotNull(Uri.parse(firstUri).path))
            restoredFile = imageFile
            assertTrue(imageFile.isFile)
            assertEquals(sourceImage.readBytes().toList(), imageFile.readBytes().toList())

            manager.exportTo(Uri.fromFile(exported))
            val roundTrip = Json { ignoreUnknownKeys = true }.decodeFromString(ExportPayload.serializer(), exported.readText())
            assertTrue(roundTrip.entries.single().content.contains("![photo](data:image/png;base64,"))
            assertTrue(roundTrip.entries.single().content.contains("![external](https://example.org/image.png)"))
        } finally {
            db.close()
            sourceImage.delete()
            restoredFile?.delete()
            backup.delete()
            exported.delete()
        }
    }

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
            val inlineImage = File(app.filesDir, "cross-inline-${System.nanoTime()}.png")
            inlineImage.outputStream().use { output ->
                Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            for ((suffix, local) in listOf("a" to "local-a", "b" to "local-b")) {
                val content = if (suffix == "a") {
                    "Before ![diagram](${Uri.fromFile(inlineImage)}) after.\\n![external](https://example.org/image.png)"
                } else "Body $suffix"
                db.entryDao().upsert(EntryEntity(id = "entry-$suffix", spaceId = "source-$suffix", title = "Entry $suffix", content = content))
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
            val portableContent = payload.entries.single { it.id == "entry-a" }.content
            assertTrue(portableContent.contains("![diagram](data:image/png;base64,"))
            assertTrue(portableContent.contains("![external](https://example.org/image.png)"))

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
