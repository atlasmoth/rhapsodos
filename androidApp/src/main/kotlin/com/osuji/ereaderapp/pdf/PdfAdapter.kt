package com.osuji.ereaderapp.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import com.osuji.ereaderapp.data.syncDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

@Serializable data class SourceRegion(val start: Int, val end: Int, val x: Float, val y: Float, val width: Float, val height: Float)
@Serializable data class PdfSourcePage(
    val pageIndex: Int,
    val text: String,
    val provenance: String = "embedded",
    val languageTag: String = "en",
    val regions: List<SourceRegion> = emptyList(),
    val geometrySpace: String = "unspecified",
)

data class ImportedPdf(val id: String, val title: String, val path: String, val pageCount: Int)
@Serializable data class PdfOutlineDestination(val title: String, val pageIndex: Int)

class PdfAdapter(private val context: Context) {
    private val rasterSlots = Semaphore(2)
    private val coverRequests = Mutex()
    private val covers = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val ocr = LocalOcr(context)
    var beforeOcr: (suspend () -> Unit)? = null

    private val remote = RemotePdfClient(context)

    suspend fun import(uri: Uri, id: String = UUID.randomUUID().toString()): ImportedPdf = withContext(Dispatchers.IO) {
        require(UUID.fromString(id).toString() == id)
        val directory = File(context.filesDir, "documents").apply { check(mkdirs() || isDirectory) }
        val temporary = File(directory, "$id.importing")
        val destination = File(directory, "$id.pdf")
        try {
            if (!destination.isFile) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= 512L * 1024 * 1024) { "This PDF exceeds the 512 MB import limit." }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                } ?: error("The file provider could not open this PDF.")
            }
            val source = if (destination.isFile) destination else temporary
            val count = Json.parseToJsonElement(checkNotNull(remote.request(source.absolutePath, "inspect").json)).jsonObject.getValue("pageCount").jsonPrimitive.content.toInt()
            if (source == temporary) check(temporary.renameTo(destination)) { "The local PDF copy could not be saved." }
            syncDirectory(directory, context.filesDir)
            ImportedPdf(id, displayName(uri), destination.absolutePath, count)
        } catch (failure: Exception) {
            temporary.delete()
            destination.delete()
            throw failure
        }
    }

    suspend fun extractPages(path: String, startPage: Int = 0, existingCharacters: Int = 0, prepared: suspend (PdfSourcePage) -> Unit) = withContext(Dispatchers.IO) {
        var totalCharacters = existingCharacters
        suspend fun append(page: PdfSourcePage) {
            require(page.text.length <= 20_000_000 - totalCharacters) { "This document exceeds the 20 million character preparation limit. Your PDF and prepared pages are retained." }
            totalCharacters += page.text.length
            prepared(page)
        }
        val count = Json.parseToJsonElement(checkNotNull(remote.request(path, "inspect").json)).jsonObject.getValue("pageCount").jsonPrimitive.content.toInt()
        for (index in startPage until count) {
            coroutineContext.ensureActive()
            append(extractPage(path, index))
        }
    }

    suspend fun extractPage(path: String, index: Int): PdfSourcePage {
        val embedded = Json.decodeFromString<PdfSourcePage>(checkNotNull(remote.request(path, "text", index).json))
        if (usableText(embedded.text)) return embedded
        beforeOcr?.invoke()
        val geometry = pageGeometry(path, index)
        return rasterSlots.withPermit {
            val displayed = checkNotNull(remote.request(path, "render", index, 1800).bitmap)
            var upright = displayed
            try {
                if (geometry.rotation != 0) upright = Bitmap.createBitmap(displayed, 0, 0, displayed.width, displayed.height, Matrix().apply { postRotate(-geometry.rotation.toFloat()) }, true)
                ocr.recognize(upright, index).copy(geometrySpace = "cropTopLeftV1")
            } finally { if (upright !== displayed) upright.recycle(); displayed.recycle() }
        }
    }

    suspend fun pageGeometry(path: String, index: Int): PdfPageGeometry = Json.decodeFromString(checkNotNull(remote.request(path, "geometry", index).json))

    suspend fun render(path: String, index: Int, targetWidth: Int = 1400): Bitmap = rasterSlots.withPermit {
        checkNotNull(remote.request(path, "render", index, targetWidth.coerceIn(400, 2400)).bitmap)
    }

    suspend fun cover(path: String, sourceRevision: String): Bitmap = withContext(Dispatchers.IO) {
        coverRequests.withLock {
            val file = File(path)
            require(file.isFile && file.length() > 0)
            val key = "${file.canonicalPath}:${file.length()}:${file.lastModified()}:$sourceRevision"
            covers.get(key)?.let { return@withLock it }
            val bitmap = rasterSlots.withPermit { checkNotNull(remote.request(path, "cover", 0, 256).bitmap) }
            try {
                coroutineContext.ensureActive()
                covers.put(key, bitmap)
                bitmap
            } catch (failure: Exception) { bitmap.recycle(); throw failure }
        }
    }

    suspend fun outline(path: String): List<PdfOutlineDestination> = Json.decodeFromString(checkNotNull(remote.request(path, "outline").json))

    fun releaseParser() { remote.release() }
    fun releaseParserWhenIdle() { covers.evictAll(); remote.releaseWhenIdle() }

    private fun usableText(text: String): Boolean = text.count { it.isLetterOrDigit() } >= 3 && text.count { it == '\uFFFD' } * 5 < text.length

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.replace(Regex("(?i)\\.pdf$"), "")?.takeIf { it.isNotBlank() } ?: "Imported document" else "Imported document"
        } ?: "Imported document"
    }.getOrDefault("Imported document")
}
