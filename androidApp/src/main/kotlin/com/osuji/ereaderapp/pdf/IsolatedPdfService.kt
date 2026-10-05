package com.osuji.ereaderapp.pdf

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.sqrt

class IsolatedPdfService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val queued = AtomicInteger()
    private var identity: String? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var document: PDDocument? = null
    private val binder = object : IPdfWorker.Stub() {
        override fun request(pdf: ParcelFileDescriptor, token: String, operation: String, pageIndex: Int, targetWidth: Int): ParcelFileDescriptor {
            require(token.length <= 128 && operation in setOf("inspect", "text", "render", "outline", "geometry"))
            val pipe = ParcelFileDescriptor.createReliablePipe()
            if (queued.incrementAndGet() > 2) { queued.decrementAndGet(); pdf.close(); pipe.forEach { it.close() }; error("The PDF worker is busy.") }
            worker.execute {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    val data = DataOutputStream(output)
                    try {
                        pdf.use { open(it, token) }
                        if (operation == "render") {
                            val bitmap = render(pageIndex, targetWidth)
                            try {
                                data.writeByte(2); data.writeInt(bitmap.width); data.writeInt(bitmap.height)
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, data))
                            } finally { bitmap.recycle() }
                        } else {
                            val payload = when (operation) {
                                "inspect" -> "{\"pageCount\":${checkNotNull(renderer).pageCount}}"
                                "text" -> Json.encodeToString(embedded(pageIndex))
                                "geometry" -> Json.encodeToString(geometry(pageIndex))
                                else -> Json.encodeToString(outline())
                            }.toByteArray(Charsets.UTF_8)
                            require(payload.size <= 4_100_000) { "The prepared page exceeds the safe transfer limit." }
                            data.writeByte(1); data.writeInt(payload.size); data.write(payload)
                        }
                    } catch (failure: Exception) {
                        runCatching { data.writeByte(0); data.writeUTF((failure.message ?: "This PDF could not be prepared.").take(300)) }
                    } finally { queued.decrementAndGet() }
                }
            }
            return pipe[0]
        }
    }

    override fun onCreate() { super.onCreate(); PDFBoxResourceLoader.init(this) }
    override fun onBind(intent: Intent?): IBinder = binder
    override fun onUnbind(intent: Intent?): Boolean { stopSelf(); return false }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy(); Process.killProcess(Process.myPid()) }

    private fun open(source: ParcelFileDescriptor, token: String) {
        if (identity == token && renderer != null) return
        document?.close(); renderer?.close(); descriptor?.close()
        document = null; renderer = null; descriptor = null; identity = null
        val retained = ParcelFileDescriptor.dup(source.fileDescriptor)
        try {
            val renderingDescriptor = ParcelFileDescriptor.dup(retained.fileDescriptor)
            val opened = try { PdfRenderer(renderingDescriptor) } catch (failure: Exception) { renderingDescriptor.close(); throw failure }
            if (opened.pageCount !in 1..10_000) { opened.close(); error("This PDF must contain between 1 and 10,000 pages.") }
            renderer = opened; descriptor = retained; identity = token
        } catch (failure: Exception) { retained.close(); throw failure }
    }

    private fun document(): PDDocument? {
        if (document == null) {
            ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(checkNotNull(descriptor).fileDescriptor)).use { input ->
                android.system.Os.lseek(input.fd, 0, android.system.OsConstants.SEEK_SET)
                document = PDDocument.load(input, MemoryUsageSetting.setupMainMemoryOnly(128L * 1024 * 1024))
            }
        }
        return document
    }

    private fun embedded(index: Int): PdfSourcePage {
        val pdf = checkNotNull(renderer)
        require(index in 0 until pdf.pageCount)
        val dimensions = pdf.openPage(index).use { it.width.toFloat() to it.height.toFloat() }
        val parsed = document()
        if (parsed != null) {
            val page = parsed.getPage(index)
            val runs = mutableListOf<PositionedText>()
            val stripper = object : PDFTextStripper() {
                override fun writeString(text: String, positions: MutableList<TextPosition>) {
                    positions.forEach { position ->
                        val value = position.unicode.orEmpty()
                        if (value.isNotEmpty()) {
                            require(runs.size < 100_000) { "This page contains too many text fragments to prepare safely." }
                            val advance = position.widthDirAdj.coerceAtLeast(0f)
                            val height = position.heightDir.coerceAtLeast(1f)
                            val rectangle = when ((position.dir.toInt() - page.rotation + 360) % 360) {
                                90 -> floatArrayOf(position.x - height, position.y - advance, height, advance)
                                180 -> floatArrayOf(position.x - advance, position.y, advance, height)
                                270 -> floatArrayOf(position.x, position.y, height, advance)
                                else -> floatArrayOf(position.x, position.y - height, advance, height)
                            }
                            runs += PositionedText(value, position.xDirAdj, position.yDirAdj - height, advance, height, rectangle[0], rectangle[1], rectangle[2], rectangle[3])
                        }
                    }
                }
            }.apply { startPage = index + 1; endPage = index + 1; sortByPosition = false }
            stripper.getText(parsed)
            val display = PageReadingOrder.source(runs, page.cropBox.width, dimensions.first, dimensions.second, index)
            val rotation = geometry(index).rotation
            val result = display.copy(regions = display.regions.map { PdfPageCoordinates.canonical(it, rotation) }, geometrySpace = "cropTopLeftV1")
            require(result.text.length <= 1_000_000) { "This page contains too much text to prepare safely." }
            if (usable(result.text)) return result
        }
        if (Build.VERSION.SDK_INT >= 35) {
            val rotation = geometry(index).rotation
            return pdf.openPage(index).use { page ->
                val items = page.textContents.mapNotNull { content ->
                    if (content.text.isBlank()) null else {
                        val bounds = content.bounds
                        if (bounds.isEmpty()) null else {
                            val left = bounds.minOf { it.left }; val top = bounds.minOf { it.top }
                            PositionedText(content.text, left, top, bounds.maxOf { it.right } - left, bounds.maxOf { it.bottom } - top)
                        }
                    }
                }
                val display = PageReadingOrder.source(items, page.width.toFloat(), dimensions.first, dimensions.second, index)
                val result = display.copy(regions = display.regions.map { PdfPageCoordinates.canonical(it, rotation) }, geometrySpace = "cropTopLeftV1")
                require(result.text.length <= 1_000_000)
                result
            }
        }
        return PdfSourcePage(index, "", geometrySpace = "cropTopLeftV1")
    }

    private fun geometry(index: Int): PdfPageGeometry {
        val pdf = checkNotNull(renderer)
        require(index in 0 until pdf.pageCount)
        val page = checkNotNull(document()).getPage(index)
        val rotation = ((page.rotation % 360) + 360) % 360
        require(rotation in setOf(0, 90, 180, 270)) { "This PDF page uses an unsupported rotation." }
        return pdf.openPage(index).use { displayed -> PdfPageGeometry(rotation, page.cropBox.width, page.cropBox.height, displayed.width.toFloat(), displayed.height.toFloat()) }
    }

    private fun render(index: Int, requestedWidth: Int): Bitmap = checkNotNull(renderer).openPage(index).use { page ->
        val width = requestedWidth.coerceIn(400, 2400)
        val scale = min(width.toFloat() / page.width, min(6000f / page.height, sqrt(6_000_000.0 / (page.width.toDouble() * page.height)).toFloat()))
        val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); bitmap }
        catch (failure: Exception) { bitmap.recycle(); throw failure }
    }

    private fun outline(): List<PdfOutlineDestination> {
        val parsed = document() ?: return emptyList()
        val result = mutableListOf<PdfOutlineDestination>()
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<com.tom_roush.pdfbox.cos.COSDictionary, Boolean>())
        fun visit(first: com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem?, depth: Int) {
            if (depth > 16) return
            var item = first
            while (item != null && result.size < 1000) {
                val current = item
                if (!visited.add(current.cosObject) || visited.size > 5000) break
                val title = current.title?.trim().orEmpty().take(300)
                val destination = runCatching { current.findDestinationPage(parsed) }.getOrNull()
                val index = destination?.let { parsed.pages.indexOf(it) } ?: -1
                if (title.isNotBlank() && index in 0 until parsed.numberOfPages) result += PdfOutlineDestination(title, index)
                visit(current.firstChild, depth + 1); item = current.nextSibling
            }
        }
        visit(parsed.documentCatalog.documentOutline?.firstChild, 0)
        return result
    }
    private fun usable(text: String) = text.count { it.isLetterOrDigit() } >= 3 && text.count { it == '\uFFFD' } * 5 < text.length
}
