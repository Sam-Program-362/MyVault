package com.myvault.app.data

import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myvault.app.MyVaultApplication
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        source =
            Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        target =
            Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
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
            .upsert(
                EntryEntity(id = "existing-entry", spaceId = "existing-space", content = "existing")
            )

        val backup = File(tempDir, "backup.json")
        val exported =
            BackupManager(app, source, app.settings, app.media).exportTo(Uri.fromFile(backup))
        assertEquals(0, exported.externalImages)
        assertTrue(backup.readText().contains("data:image/png;base64,"))
        assertFalse(
            backup.readText().contains("entryLayout")
        ) // Android-only UI state is not a web setting.
        image.delete() // the exported image must not depend on the original file
        val result =
            BackupManager(app, target, app.settings, app.media)
                .importFrom(Uri.fromFile(backup), false)
        assertEquals(1, result.entries)
        assertEquals(0, result.spaces)
        assertEquals(0, result.tags)
        val imported = target.entryDao().getAll().first { it.id != "existing-entry" }
        assertEquals("kept", imported.content)
        assertEquals(3000L, imported.createdAt)
        assertEquals(4000L, imported.updatedAt)
        assertEquals("existing-space", imported.spaceId)
        assertEquals(
            listOf("existing-tag"),
            target.entryDao().tagRefsForEntry(imported.id).map { it.tagId },
        )
        assertEquals("existing", target.entryDao().getById("existing-entry")?.content)
        assertTrue(File(requireNotNull(Uri.parse(imported.coverImagePath).path)).isFile)
        assertNotNull(MediaFiles.displayModel(imported.coverImagePath))
    }

    @Test
    fun localTagOwnershipSurvivesBackupAndInvalidOwnershipRollsBack() = runBlocking {
        source.spaceDao().upsert(SpaceEntity(id = "s", name = "Work"))
        source.tagDao().upsert(TagEntity(id = "local", name = "Ideas", scope = "local", ownerSpaceId = "s", reusable = false))
        source.entryDao().upsert(EntryEntity(id = "entry", spaceId = "s", content = "body"))
        source.entryDao().addTagRef(EntryTagCrossRef("entry", "local"))
        target.spaceDao().upsert(SpaceEntity(id = "existing", name = "Work"))
        val file = File(tempDir, "scoped.json")
        BackupManager(app, source, app.settings, app.media).exportTo(Uri.fromFile(file))
        BackupManager(app, target, app.settings, app.media).importFrom(Uri.fromFile(file), false)
        val entry = target.entryDao().getAll().single()
        val tag = target.entryDao().tagsForEntry(entry.id).single()
        assertEquals("existing", entry.spaceId)
        assertEquals("local", tag.scope)
        assertEquals("existing", tag.ownerSpaceId)
        assertFalse(tag.reusable)
        val before = target.entryDao().getAll().size
        file.writeText(file.readText().replace("\"ownerSpaceId\": \"s\"", "\"ownerSpaceId\": \"missing\""))
        var failed = false
        try { BackupManager(app, target, app.settings, app.media).importFrom(Uri.fromFile(file), false) }
        catch (_: Exception) { failed = true }
        assertTrue(failed)
        assertEquals(before, target.entryDao().getAll().size)
    }

    @Test
    fun failedDatabaseImportRollsBackSpacesAndTags() = runBlocking {
        target.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_entry BEFORE INSERT ON entries BEGIN SELECT RAISE(ABORT, 'test failure'); END"
        )
        val backup = File(tempDir, "invalid-transaction.json")
        backup.writeText(
            """{"spaces":[{"id":"s","name":"New space"}],"tags":[{"id":"t","name":"New tag"}],"entries":[{"id":"e","spaceId":"s","content":"note"}]}"""
        )
        var failed = false
        try {
            BackupManager(app, target, app.settings, app.media)
                .importFrom(Uri.fromFile(backup), false)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
        assertTrue(target.spaceDao().getAll().isEmpty())
        assertTrue(target.tagDao().getAll().isEmpty())
        assertTrue(target.entryDao().getAll().isEmpty())
    }

    @Test
    fun legacyAmbiguousFixtureFailsPreciselyWithoutChangingExistingIdentitiesOrLinks() = runBlocking {
        target.spaceDao().upsert(SpaceEntity(id = "existing-space", name = "Existing"))
        target.tagDao().upsert(TagEntity(id = "existing-tag", name = "Saved", reusable = false))
        target.entryDao().upsert(EntryEntity(id = "existing-entry", spaceId = "existing-space"))
        target.entryDao().addTagRef(EntryTagCrossRef("existing-entry", "existing-tag"))
        val file = File(tempDir, "legacy-v1.json")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            .open("legacy-v1-ambiguous.json").use { input -> file.outputStream().use { input.copyTo(it) } }
        val error = try {
            BackupManager(app, target, app.settings, app.media).importFrom(Uri.fromFile(file), false)
            null
        } catch (e: IllegalArgumentException) { e }
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("Ambiguous imported tags named", ignoreCase = true))
        assertEquals(listOf("existing-space"), target.spaceDao().getAll().map { it.id })
        assertEquals(listOf("existing-tag"), target.tagDao().getAll().map { it.id })
        assertFalse(target.tagDao().getAll().single().reusable)
        assertEquals(listOf("existing-tag"), target.entryDao().tagRefsForEntry("existing-entry").map { it.tagId })
        assertEquals(listOf("existing-entry"), target.entryDao().getAll().map { it.id })
    }

    @Test
    fun legacyMissingOptionalTagFieldsUseDefaults() = runBlocking {
        val file = File(tempDir, "legacy-defaults.json")
        file.writeText("""{"version":1,"tags":[{"id":"old","name":"Defaults"}]}""")
        BackupManager(app, target, app.settings, app.media).importFrom(Uri.fromFile(file), false)
        val tag = target.tagDao().getAll().single()
        assertEquals("global", tag.scope)
        assertEquals(null, tag.ownerSpaceId)
        assertTrue(tag.reusable)
        assertEquals("#6366F1", tag.color)
        assertEquals("#FFFFFF", tag.textColor)
    }

    @Test
    fun externalImagePathsArePreservedAndReported() = runBlocking {
        source.spaceDao().upsert(SpaceEntity(id = "s", name = "Web"))
        source
            .entryDao()
            .upsert(EntryEntity(id = "e", spaceId = "s", coverImagePath = "/uploads/cover.png"))
        val file = File(tempDir, "external.json")
        val exported =
            BackupManager(app, source, app.settings, app.media).exportTo(Uri.fromFile(file))
        assertEquals(1, exported.externalImages)
        val imported =
            BackupManager(app, target, app.settings, app.media)
                .importFrom(Uri.fromFile(file), false)
        assertEquals(1, imported.externalImages)
        assertEquals("/uploads/cover.png", target.entryDao().getAll().single().coverImagePath)
    }

    @Test
    fun settingsAreOnlyRestoredWhenRequested() = runBlocking {
        val original = app.settings.settingsFlow.first()
        val originalLayout = app.settings.entryLayoutFlow.first()
        val file = File(tempDir, "settings.json")
        file.writeText("""{"settings":{"appName":"Imported vault","mode":"dark"}}""")
        try {
            app.settings.updateEntryLayout("list")
            BackupManager(app, target, app.settings, app.media)
                .importFrom(Uri.fromFile(file), false)
            assertEquals(original.appName, app.settings.settingsFlow.first().appName)
            BackupManager(app, target, app.settings, app.media).importFrom(Uri.fromFile(file), true)
            assertEquals("Imported vault", app.settings.settingsFlow.first().appName)
            assertEquals("dark", app.settings.settingsFlow.first().mode)
            assertEquals("list", app.settings.entryLayoutFlow.first())
        } finally {
            app.settings.update { original }
            app.settings.updateEntryLayout(originalLayout)
        }
    }

    @Test
    fun selectedImageIsCopiedBeforeTheOriginalDisappears() = runBlocking {
        val image = File(tempDir, "selected.png")
        image.outputStream().use { output ->
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        val saved = app.media.copySelected(Uri.fromFile(image))
        val privateFile = File(requireNotNull(Uri.parse(saved).path))
        try {
            assertTrue(privateFile.isFile)
            assertTrue(
                privateFile.canonicalPath.startsWith(app.filesDir.canonicalPath + File.separator)
            )
            image.delete()
            assertTrue(privateFile.isFile)
        } finally {
            privateFile.delete()
        }
    }
}
