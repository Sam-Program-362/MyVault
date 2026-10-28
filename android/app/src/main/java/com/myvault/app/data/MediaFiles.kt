package com.myvault.app.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.webkit.MimeTypeMap
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Selected images are copied immediately; document-picker grants need not survive a reboot. */
class MediaFiles(private val context: Context) {
    companion object {
        private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024

        /** Coil 2 does not decode data URIs itself. Older imported settings can still contain one. */
        fun displayModel(reference: String?): Any? {
            if (reference == null || !reference.startsWith("data:image/", ignoreCase = true)) return reference
            return runCatching { decodeDataImage(reference).second }.getOrNull()
        }

        private fun decodeDataImage(reference: String): Pair<String, ByteArray> {
            val comma = reference.indexOf(',')
            require(comma > 5) { "The backup contains an invalid image." }
            val metadata = reference.substring(5, comma)
            val mime = metadata.substringBefore(';').lowercase()
            require(mime.startsWith("image/") && metadata.split(';').any { it.equals("base64", true) }) {
                "Only base64 image data can be restored."
            }
            val encoded = reference.substring(comma + 1)
            require(encoded.length <= (MAX_IMAGE_BYTES.toLong() * 4 / 3 + 16)) {
                "An image exceeds the 12 MB limit."
            }
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            require(bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES) {
                "An image exceeds the 12 MB limit or is empty."
            }
            return mime to bytes
        }
    }

    private val imageDir: File
        get() = File(context.filesDir, "images").also { it.mkdirs() }

    suspend fun copySelected(uri: Uri): String =
        withContext(Dispatchers.IO) {
            val mime = context.contentResolver.getType(uri)
            require(mime == null || mime.startsWith("image/")) { "Choose an image file." }
            val file = newFile(mime)
            try {
                val input =
                    context.contentResolver.openInputStream(uri)
                        ?: error("Could not open the selected image.")
                input.use { source -> file.outputStream().use { target -> copyLimited(source, target) } }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This image format is not supported." }
                Uri.fromFile(file).toString()
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }

    /** Embed readable local files in the JSON, preserving nonlocal URLs and paths unchanged. */
    suspend fun portableReference(reference: String?): String? =
        withContext(Dispatchers.IO) {
            if (reference.isNullOrBlank() || reference.startsWith("data:", ignoreCase = true))
                return@withContext reference
            val uri = Uri.parse(reference)
            val localUri =
                when (uri.scheme?.lowercase()) {
                    "content",
                    "file" -> uri
                    null ->
                        if (reference.startsWith("/") && File(reference).isFile) Uri.fromFile(File(reference))
                        else null
                    else -> null
                } ?: return@withContext reference
            val input =
                context.contentResolver.openInputStream(localUri)
                    ?: error("A saved image is no longer readable. Re-select it before exporting.")
            val bytes = input.use { readLimited(it) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val mime =
                bounds.outMimeType
                    ?: context.contentResolver.getType(localUri)
                    ?: MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(
                            localUri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
                        )
                    ?: "image/jpeg"
            "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        }

    /** Convert embedded data to app-private files for Coil and for use without URI permissions. */
    suspend fun restoreReference(reference: String?, created: MutableList<File>): String? =
        withContext(Dispatchers.IO) {
            if (reference.isNullOrBlank() || !reference.startsWith("data:", ignoreCase = true))
                return@withContext reference
            val (mime, bytes) = decodeDataImage(reference)
            val file = newFile(mime)
            try {
                file.writeBytes(bytes)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                    "The backup contains an unsupported image."
                }
                created.add(file)
                Uri.fromFile(file).toString()
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }

    private fun newFile(mime: String?): File {
        val extension = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it.lowercase()) } ?: "img"
        return File(imageDir, "${UUID.randomUUID()}.$extension")
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        copyLimited(input, output)
        return output.toByteArray()
    }

    private fun copyLimited(input: InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            total += count
            require(total <= MAX_IMAGE_BYTES) { "Images must be 12 MB or smaller." }
            output.write(buffer, 0, count)
        }
        require(total > 0) { "The image is empty." }
    }
}
