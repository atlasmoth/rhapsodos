package com.osuji.ereaderapp.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class PdfTextBox(
    val start: Int,
    val end: Int,
    val bounds: List<RectF>,
)

data class PdfSourcePage(val pageIndex: Int, val text: String, val boxes: List<PdfTextBox>)

data class ImportedPdf(
    val id: String,
    val title: String,
    val path: String,
    val fingerprint: String,
    val pageCount: Int,
    val pages: List<PdfSourcePage>,
    val extractionMessage: String?,
)

class PdfAdapter(private val context: Context) {
    suspend fun import(uri: Uri): ImportedPdf = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "documents").apply { mkdirs() }
        val temporary = File(directory, "$id.importing")
        val destination = File(directory, "$id.pdf")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= 512L * 1024 * 1024) { "This PDF exceeds the current 512 MB import limit." }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            } ?: error("The file provider could not open this PDF.")
            require(temporary.renameTo(destination)) { "The local PDF copy could not be saved." }
            inspect(id, displayName(uri), destination, digest.digest().joinToString("") { "%02x".format(it) })
        } catch (failure: Exception) {
            temporary.delete()
            destination.delete()
            throw failure
        }
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).removeSuffix(".pdf") else "Imported document"
        } ?: "Imported document"

    private fun inspect(id: String, title: String, file: File, fingerprint: String): ImportedPdf {
        val pages = mutableListOf<PdfSourcePage>()
        var extractedCharacters = 0L
        val pageCount = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            require(renderer.pageCount > 0) { "This PDF contains no viewable pages." }
            require(renderer.pageCount <= 10_000) { "This PDF exceeds the current page limit." }
            if (Build.VERSION.SDK_INT >= 35) {
                repeat(renderer.pageCount) { index ->
                    renderer.openPage(index).use { page ->
                        val source = StringBuilder()
                        val boxes = mutableListOf<PdfTextBox>()
                        page.textContents.forEach { content ->
                            val text = content.text
                            if (text.isNotBlank()) {
                                extractedCharacters += text.length + 1
                                require(extractedCharacters <= 20_000_000) { "This PDF exceeds the current text-preparation limit." }
                                val sourceStart = source.length
                                source.append(text).append('\n')
                                boxes += PdfTextBox(sourceStart, sourceStart + text.length, content.bounds.map { RectF(it.left / page.width, it.top / page.height, it.right / page.width, it.bottom / page.height) })
                            }
                        }
                        pages += PdfSourcePage(index, source.toString(), boxes)
                    }
                }
            } else {
                repeat(renderer.pageCount) { pages += PdfSourcePage(it, "", emptyList()) }
            }
            renderer.pageCount
        }
        }
        val message = when {
            Build.VERSION.SDK_INT < 35 -> "Original pages are available. Embedded-text listening currently requires Android 15 or newer."
            pages.all { it.text.isBlank() } -> "No embedded text was found. You can view the original pages; scanned-page listening is not available in this build."
            else -> null
        }
        return ImportedPdf(id, title, file.absolutePath, fingerprint, pageCount, pages, message)
    }

    suspend fun render(path: String, index: Int, targetWidth: Int = 1200): Bitmap = withContext(Dispatchers.IO) {
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            renderer.openPage(index.coerceIn(0, renderer.pageCount - 1)).use { page ->
                val width = targetWidth.coerceIn(300, 1600)
                val height = (width * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                require(height <= 6000) { "This page is too tall for the current rendering limit." }
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
        }
    }
}
