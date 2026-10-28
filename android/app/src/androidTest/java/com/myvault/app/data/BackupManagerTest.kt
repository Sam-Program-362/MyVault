package com.myvault.app.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myvault.app.MyVaultApplication
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupManagerTest {
    private lateinit var app: MyVaultApplication
    private lateinit var source: AppDatabase
    private lateinit var target: AppDatabase
    private lateinit var tempDir: File

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        source = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        target = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        tempDir = File(app.cacheDir, "backup-test-${System.nanoTime()}").also { it.mkdirs() }
    }

    @After
    fun cleanup() {
        source.close()
        target.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun localImagesAndRelationsSurviveJsonExportAndMergeImport() = runBlocking {
        val image = File(tempDir, "cover.png")
        image.outputStream().use { output ->
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        val space = SpaceEntity(id = "space", name = "Work", createdAt = 1000)
        val tag = TagEntity(id = "tag", name = "Ideas", createdAt = 2000)
        val entry =
            EntryEntity(
                id = "entry",
                spaceId = space.id,
                title = "My note",
                content = "kept",
                coverImagePath = Uri.fromFile(image).toString(),
                createdAt = 3000,
                updatedAt = 4000,
            )
        source.spaceDao().upsert(space)
        source.tagDao().upsert(tag)
        source.entryDao().upsert(entry)
        source.entryDao().addTagRef(EntryTagCrossRef(entry.id, tag.id))
        // Matching spaces and tags are reused; existing entries must not be replaced.
        target.spaceDao().upsert(SpaceEntity(id = "existing-space", name = "work"))
        target.tagDao().upsert(TagEntity(id = "existing-tag", name = "ideas"))
        target
            .entryDao()
            .upsert(EntryEntity(id = "existing-entry", spaceId = "existing-space", content = "existing"))

        val backup = File(tempDir, "backup.json")
        val exported = BackupManager(app, source, app.settings, app.media).exportTo(Uri.fromFile(backup))
        assertEquals(0, exported.externalImages)
        assertTrue(backup.readText().contains("data:image/png;base64,"))
        image.delete() // the exported image must not depend on the original file
        val result =
            BackupManager(app, target, app.settings, app.media).importFrom(Uri.fromFile(backup), false)
        assertEquals(1, result.entries)
        assertEquals(0, result.spaces)
        assertEquals(0, result.tags)
        val imported = target.entryDao().getAll().first { it.id != "existing-entry" }
        assertEquals("kept", imported.content)
        assertEquals(3000L, imported.createdAt)
        assertEquals(4000L, imported.updatedAt)
        assertEquals("existing-space", imported.spaceId)
        assertEquals(listOf("existing-tag"), target.entryDao().tagRefsForEntry(imported.id).map { it.tagId })
        assertEquals("existing", target.entryDao().getById("existing-entry")?.content)
        assertTrue(File(requireNotNull(Uri.parse(imported.coverImagePath).path)).isFile)
        assertNotNull(MediaFiles.displayModel(imported.coverImagePath))
    }
}
