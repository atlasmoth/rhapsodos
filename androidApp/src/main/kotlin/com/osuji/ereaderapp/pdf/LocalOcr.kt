package com.osuji.ereaderapp.pdf

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.googlecode.tesseract.android.TessBaseAPI
import com.osuji.ereaderapp.data.syncDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class LocalOcr(private val context: Context) {
    private val mutex = Mutex()
    private val worker = Dispatchers.IO.limitedParallelism(1)

    suspend fun recognize(bitmap: Bitmap, pageIndex: Int): PdfSourcePage = mutex.withLock {
        withTimeout(45_000) {
            withContext(worker) {
                val operation = coroutineContext
                val deadline = SystemClock.elapsedRealtime() + 45_000
                lateinit var engine: TessBaseAPI
                engine = TessBaseAPI {
                    if (!operation[kotlinx.coroutines.Job]!!.isActive || SystemClock.elapsedRealtime() > deadline) engine.stop()
                }
                try {
                    val root = prepareLanguage()
                    check(engine.init(root.absolutePath, "eng", TessBaseAPI.OEM_LSTM_ONLY)) { "The bundled English recognition model could not be opened." }
                    operation.ensureActive()
                    engine.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                    engine.setImage(bitmap)
                    engine.getHOCRText(pageIndex)
                    operation.ensureActive()
                    check(SystemClock.elapsedRealtime() <= deadline) { "This page took too long to recognize. Retry when reading is paused." }
                    val words = mutableListOf<PositionedText>()
                    var recognizedCharacters = 0
                    val iterator = engine.resultIterator
                    if (iterator != null) try {
                        iterator.begin()
                        do {
                            operation.ensureActive()
                            val word = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim().orEmpty()
                            if (word.isNotBlank()) {
                                val bounds = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                                words += PositionedText(word, bounds.left.toFloat(), bounds.top.toFloat(), bounds.width().toFloat(), bounds.height().toFloat().coerceAtLeast(1f), separatedWord = true)
                                recognizedCharacters += word.length + 1
                                require(recognizedCharacters <= 1_000_000 && words.size <= 100_000) { "This page contains too much recognized text to prepare safely." }
                            }
                        } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                    } finally { iterator.delete() }
                    PageReadingOrder.source(words, bitmap.width.toFloat(), bitmap.width.toFloat(), bitmap.height.toFloat(), pageIndex).copy(provenance = "ocr")
                } finally { engine.recycle() }
            }
        }
    }

    private fun prepareLanguage(): File {
        val root = File(context.filesDir, "ocr")
        val directory = File(root, "tessdata").apply { check(mkdirs() || isDirectory) }
        val destination = File(directory, "eng.traineddata")
        if (destination.length() == 4_113_088L && digest(destination) == MODEL_SHA256) return root
        val staging = File(directory, "eng.importing")
        try {
            context.assets.open("ocr/eng.traineddata").use { input -> staging.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
            require(staging.length() == 4_113_088L && digest(staging) == MODEL_SHA256) { "The bundled English recognition model failed its integrity check." }
            check(staging.renameTo(destination)) { "The English recognition model could not be prepared." }
            syncDirectory(directory)
        } finally { staging.delete() }
        return root
    }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MODEL_SHA256 = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
    }
}
