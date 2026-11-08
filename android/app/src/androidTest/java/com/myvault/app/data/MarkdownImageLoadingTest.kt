package com.myvault.app.data

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownImageLoadingTest {
    @Test
    fun coilUsedByComposeMarkdownLoadsAppPrivateFileAndPortableDataBytes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.filesDir, "markdown-image-${System.nanoTime()}.png")
        file.outputStream().use { output ->
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        try {
            val loader = ImageLoader(context)
            val fileResult = loader.execute(ImageRequest.Builder(context).data(Uri.fromFile(file).toString()).build())
            assertTrue("Coil's file:// mapper must load managed inline media", fileResult is SuccessResult)

            val bytes = file.readBytes()
            val dataUri = "data:image/png;base64,${android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)}"
            val byteArrayModel = MediaFiles.displayModel(dataUri)
            assertTrue("data URI is converted to Coil's supported ByteArray model", byteArrayModel is ByteArray)
            val dataResult = loader.execute(ImageRequest.Builder(context).data(byteArrayModel).build())
            assertTrue("Coil must decode the portable data after conversion", dataResult is SuccessResult)
        } finally {
            file.delete()
        }
    }

    @Test
    fun appPrivateInlineFileAndMarkdownReferenceSurviveDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseName = "inline-media-reopen-${System.nanoTime()}.db"
        val imageDirectory = File(context.filesDir, "images").also { it.mkdirs() }
        val image = File(imageDirectory, "reopen-${System.nanoTime()}.png")
        image.outputStream().use { output ->
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        val markdown = "Before ![persistent image](${Uri.fromFile(image)}) after."
        try {
            val first = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).allowMainThreadQueries().build()
            first.spaceDao().upsert(SpaceEntity(id = "space", name = "Notes"))
            first.entryDao().upsert(EntryEntity(id = "entry", spaceId = "space", content = markdown))
            first.close()

            val reopened = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).allowMainThreadQueries().build()
            try {
                assertTrue(image.isFile)
                assertTrue(reopened.entryDao().getById("entry")!!.content.contains("![persistent image](file://"))
                val result = ImageLoader(context).execute(ImageRequest.Builder(context).data(Uri.fromFile(image).toString()).build())
                assertTrue("Coil can read app-private image bytes after database reopen", result is SuccessResult)
            } finally { reopened.close() }
        } finally {
            image.delete()
            context.deleteDatabase(databaseName)
        }
    }
}
