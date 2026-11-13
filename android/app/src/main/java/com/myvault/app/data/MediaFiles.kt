package com.myvault.app.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Selected images are validated and copied immediately; picker grants need not survive a reboot. */
class MediaFiles(private val context: Context) {
    companion object {
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        const val MAX_BACKUP_MEDIA_BYTES = 16 * 1024 * 1024
        private val allowedMime = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

        /** Coil 2 does not decode data URIs itself. Convert validated portable data to an in-memory byte array. */
        fun displayModel(reference: String?): Any? {
            if (reference == null || !reference.startsWith("data:image/", ignoreCase = true)) return reference
            return runCatching { decodeDataImage(reference).second }.getOrNull()
        }

        fun isRenderableMarkdownImage(context: Context, reference: String): Boolean = runCatching {
            // Compose-Markdown's Coil plugin passes URL strings directly; Coil 2 does not
            // decode data: URLs. Portable backup imports rewrite them to validated file:// URIs.
            if (reference.startsWith("data:image/", ignoreCase = true)) return@runCatching false
            val uri = Uri.parse(reference)
            if (uri.scheme != "file") return@runCatching false
            val file = File(requireNotNull(uri.path)).canonicalFile
            val root = File(context.filesDir, "images").canonicalFile
            if (!file.path.startsWith(root.path + File.separator) || !file.isFile) return@runCatching false
            validateImage(file.readBytes())
            true
        }.getOrDefault(false)

        fun dataImageByteSize(reference: String): Int = decodeDataImage(reference).second.size

        private fun decodeDataImage(reference: String): Pair<String, ByteArray> {
            val comma = reference.indexOf(',')
            require(comma > 5) { "The backup contains an invalid image." }
            val metadata = reference.substring(5, comma).lowercase()
            val mime = metadata.substringBefore(';')
            require(mime in allowedMime && metadata.substringAfter(';', "").split(';').any { it == "base64" }) {
                "Only PNG, JPEG, GIF, or WebP base64 image data can be restored."
            }
            val encoded = reference.substring(comma + 1)
            require(encoded.length <= (MAX_IMAGE_BYTES.toLong() * 4 / 3 + 16)) {
                "Images must be 2 MB or smaller."
            }
            val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }
                .getOrElse { throw IllegalArgumentException("The backup contains malformed base64 image data.") }
            require(bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES) { "Images must be 2 MB or smaller." }
            val actualMime = validateImage(bytes, mime)
            return actualMime to bytes
        }

        /** Sniff actual bytes, enforce the declared MIME if present, and cap decoded dimensions. */
        private fun validateImage(bytes: ByteArray, declaredMime: String? = null): String {
            require(bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES) { "Images must be 2 MB or smaller." }
            val mime = when {
                bytes.size >= 45 && bytes.take(8).map { it.toInt() and 0xff } == listOf(137, 80, 78, 71, 13, 10, 26, 10) &&
                    readU32(bytes, 8) == 13L && String(bytes, 12, 4, Charsets.US_ASCII) == "IHDR" -> {
                    val bitDepth = bytes[24].toInt() and 0xff
                    val colorType = bytes[25].toInt() and 0xff
                    val allowedDepths = mapOf(0 to setOf(1, 2, 4, 8, 16), 2 to setOf(8, 16), 3 to setOf(1, 2, 4, 8), 4 to setOf(8, 16), 6 to setOf(8, 16))
                    require(bitDepth in (allowedDepths[colorType] ?: emptySet()) && bytes[26].toInt() == 0 && bytes[27].toInt() == 0 && bytes[28].toInt() in 0..1) {
                        "Malformed PNG image."
                    }
                    var offset = 8
                    var hasData = false
                    var hasEnd = false
                    while (offset + 12 <= bytes.size) {
                        val size = readU32(bytes, offset).toInt()
                        require(size >= 0 && size <= bytes.size - offset - 12) { "Malformed PNG image." }
                        val kind = String(bytes, offset + 4, 4, Charsets.US_ASCII)
                        if (offset == 8) require(kind == "IHDR" && size == 13) { "Malformed PNG image." }
                        val crcOffset = offset + size + 8
                        require(crc32(bytes, offset + 4, crcOffset) == readU32(bytes, crcOffset)) { "Malformed PNG image checksum." }
                        if (kind == "IDAT" && size > 0) hasData = true
                        offset += size + 12
                        if (kind == "IEND") {
                            require(size == 0 && offset == bytes.size) { "Malformed PNG image." }
                            hasEnd = true
                            break
                        }
                    }
                    require(hasData && hasEnd) { "Malformed PNG image." }
                    "image/png"
                }
                bytes.size >= 4 && (bytes[0].toInt() and 0xff) == 0xff && (bytes[1].toInt() and 0xff) == 0xd8 &&
                    (bytes[bytes.lastIndex - 1].toInt() and 0xff) == 0xff && (bytes.last().toInt() and 0xff) == 0xd9 -> "image/jpeg"
                bytes.size >= 14 && (String(bytes, 0, 6, Charsets.US_ASCII) == "GIF87a" || String(bytes, 0, 6, Charsets.US_ASCII) == "GIF89a") &&
                    (bytes.last().toInt() and 0xff) == 0x3b -> "image/gif"
                bytes.size >= 30 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" &&
                    readU32(bytes, 4) + 8L == bytes.size.toLong() &&
                    String(bytes, 12, 4, Charsets.US_ASCII) in setOf("VP8 ", "VP8L", "VP8X") -> "image/webp"
                else -> throw IllegalArgumentException("Unsupported or malformed image. Use PNG, JPEG, GIF, or WebP; SVG is not supported.")
            }
            val hint = declaredMime?.lowercase()?.let { if (it == "image/jpg") "image/jpeg" else it }
            require(hint == null || hint == "application/octet-stream" || hint == mime) {
                "The image bytes do not match the selected file's declared type."
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            require(width in 1..12_000 && height in 1..12_000 && width.toLong() * height <= 40_000_000) {
                "Image dimensions are unsupported (maximum 40 megapixels)."
            }
            return mime
        }

        private fun readU32(bytes: ByteArray, offset: Int): Long =
            ((bytes[offset].toLong() and 0xff) shl 24) or
                ((bytes[offset + 1].toLong() and 0xff) shl 16) or
                ((bytes[offset + 2].toLong() and 0xff) shl 8) or
                (bytes[offset + 3].toLong() and 0xff)

        private fun crc32(bytes: ByteArray, start: Int, end: Int): Long {
            var crc = 0xffffffffL
            for (index in start until end) {
                crc = crc xor (bytes[index].toLong() and 0xff)
                repeat(8) { crc = if ((crc and 1L) != 0L) 0xedb88320L xor (crc ushr 1) else crc ushr 1 }
            }
            return crc xor 0xffffffffL
        }
    }

    private val imageDir: File
        get() = File(context.filesDir, "images").also { it.mkdirs() }

    suspend fun copySelected(uri: Uri): String =
        withContext(Dispatchers.IO) {
            val declaredMime = context.contentResolver.getType(uri)
            require(declaredMime == null || declaredMime.lowercase() in allowedMime || declaredMime.equals("image/jpg", ignoreCase = true) || declaredMime.equals("application/octet-stream", ignoreCase = true)) {
                "Choose a PNG, JPEG, GIF, or WebP image."
            }
            val file = newFile(declaredMime)
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: error("Could not open the selected image.")
                input.use { source -> file.outputStream().use { target -> copyLimited(source, target) } }
                val bytes = file.readBytes()
                val actualMime = validateImage(bytes, declaredMime)
                // Correct a misleading extension from the document provider using the sniffed MIME.
                val validatedFile = File(imageDir, "${file.nameWithoutExtension}.${extensionFor(actualMime)}")
                if (file != validatedFile) {
                    require(file.renameTo(validatedFile)) { "Could not save the selected image." }
                }
                Uri.fromFile(validatedFile).toString()
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }

    /** Embed local app-managed images in portable backup references; external URLs stay unchanged. */
    suspend fun portableReference(reference: String?): String? =
        withContext(Dispatchers.IO) {
            if (reference.isNullOrBlank()) return@withContext reference
            if (reference.startsWith("data:", ignoreCase = true)) {
                val (mime, bytes) = decodeDataImage(reference)
                return@withContext "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
            }
            val uri = Uri.parse(reference)
            val localUri = when (uri.scheme?.lowercase()) {
                "content", "file" -> uri
                null -> if (reference.startsWith("/") && File(reference).isFile) Uri.fromFile(File(reference)) else null
                else -> null
            } ?: return@withContext reference
            val input = context.contentResolver.openInputStream(localUri)
                ?: error("A saved image is no longer readable. Re-select it before exporting.")
            val bytes = input.use { readLimited(it) }
            val mime = validateImage(bytes, context.contentResolver.getType(localUri))
            "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        }

    /** Convert embedded data to app-private files for Coil and offline use. */
    suspend fun restoreReference(reference: String?, created: MutableList<File>): String? =
        withContext(Dispatchers.IO) {
            if (reference.isNullOrBlank() || !reference.startsWith("data:", ignoreCase = true)) return@withContext reference
            val (mime, bytes) = decodeDataImage(reference)
            val file = newFile(mime)
            try {
                file.writeBytes(bytes)
                validateImage(file.readBytes(), mime)
                created.add(file)
                Uri.fromFile(file).toString()
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }

    private fun newFile(mime: String?): File {
        val normalized = mime?.lowercase()?.let { if (it == "image/jpg") "image/jpeg" else it }
        val extension = normalized?.let(::extensionFor) ?: "img"
        return File(imageDir, "${UUID.randomUUID()}.$extension")
    }

    private fun extensionFor(mime: String): String = when (mime) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "img"
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
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
            require(total <= MAX_IMAGE_BYTES) { "Images must be 2 MB or smaller." }
            output.write(buffer, 0, count)
        }
        require(total > 0) { "The image is empty." }
    }
}
