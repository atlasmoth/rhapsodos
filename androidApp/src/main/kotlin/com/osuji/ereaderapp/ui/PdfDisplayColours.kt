package com.osuji.ereaderapp.ui

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal object PdfDisplayColours {
    private val worker = Dispatchers.Default.limitedParallelism(1)

    suspend fun recolour(source: Bitmap, paper: Int, ink: Int): Bitmap = withContext(worker) {
        require(source.width.toLong() * source.height <= 6_000_000)
        val target = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        try {
            val rows = minOf(source.height, 64)
            val pixels = IntArray(source.width * rows)
            for (top in 0 until source.height step rows) {
                coroutineContext.ensureActive()
                val count = minOf(rows, source.height - top)
                source.getPixels(pixels, 0, source.width, 0, top, source.width, count)
                for (index in 0 until source.width * count) {
                    val pixel = pixels[index]
                    val red = pixel shr 16 and 255
                    val green = pixel shr 8 and 255
                    val blue = pixel and 255
                    val chroma = maxOf(red, green, blue) - minOf(red, green, blue)
                    if (chroma >= 64) continue
                    val luminance = (2126 * red + 7152 * green + 722 * blue + 5000) / 10000
                    val weight = if (chroma <= 24) 40 else 64 - chroma
                    fun channel(shift: Int): Int {
                        val original = pixel shr shift and 255
                        val mapped = ((ink shr shift and 255) * (255 - luminance) + (paper shr shift and 255) * luminance + 127) / 255
                        return (mapped * weight + original * (40 - weight) + 20) / 40
                    }
                    pixels[index] = (pixel and -0x1000000) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
                }
                target.setPixels(pixels, 0, source.width, 0, top, source.width, count)
            }
            target
        } catch (failure: Throwable) { target.recycle(); throw failure }
    }
}
