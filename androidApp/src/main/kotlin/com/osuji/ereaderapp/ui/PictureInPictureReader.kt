package com.osuji.ereaderapp.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.osuji.ereaderapp.audio.PlaybackSnapshot
import com.osuji.ereaderapp.data.ReaderContents
import com.osuji.ereaderapp.pdf.PdfAdapter
import com.osuji.ereaderapp.pdf.PdfPageCoordinates
import com.osuji.ereaderapp.pdf.PdfPageGeometry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

@Composable
fun PictureInPictureReader(document: ReaderContents?, audio: PlaybackSnapshot, pdf: PdfAdapter, modifier: Modifier = Modifier) {
    val contents = document?.takeIf { it.document.id == audio.documentId && audio.noteId == null && it.document.pageCount > 0 }
    val passage = contents?.passages?.firstOrNull { it.id == audio.passageId }
    val pageIndex = contents?.let { (passage?.pageIndex ?: audio.pageIndex).coerceIn(0, it.document.pageCount - 1) }
    val identity = contents?.document
    var bitmap by remember(identity?.id, identity?.sourceRevision, pageIndex) { mutableStateOf<Bitmap?>(null) }
    var geometry by remember(identity?.id, identity?.sourceRevision, pageIndex) { mutableStateOf<PdfPageGeometry?>(null) }
    var failed by remember(identity?.id, identity?.sourceRevision, pageIndex) { mutableStateOf(false) }

    LaunchedEffect(identity?.id, identity?.sourceRevision, pageIndex, pdf) {
        if (identity == null || pageIndex == null) return@LaunchedEffect
        var rendered: Bitmap? = null
        var delivered = false
        try {
            geometry = try { pdf.pageGeometry(identity.filePath, pageIndex) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            rendered = pdf.render(identity.filePath, pageIndex, 1600)
            coroutineContext.ensureActive()
            bitmap = rendered
            delivered = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { if (!delivered) rendered?.recycle() }
    }

    val prepared = contents?.pages?.firstOrNull { it.pageIndex == pageIndex }
    val highlights = remember(prepared, geometry, passage) {
        val rotation = geometry?.rotation
        if (prepared?.geometrySpace != "cropTopLeftV1" || rotation == null || passage == null) emptyList()
        else prepared.regions.asSequence()
            .filter { it.start < passage.pageEnd && it.end > passage.pageStart &&
                it.x.isFinite() && it.y.isFinite() && it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0 }
            .map { PdfPageCoordinates.displayed(it, rotation) }
            .map {
                Rect(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f),
                    (it.x + it.width).coerceIn(0f, 1f), (it.y + it.height).coerceIn(0f, 1f))
            }
            .filter { it.width > 0 && it.height > 0 }
            .toList()
    }
    Box(modifier.fillMaxSize().clipToBounds().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            val pixels = remember(image) { image.asImageBitmap() }
            val rectangles = remember(image, highlights) {
                highlights.map { Rect(it.left * image.width, it.top * image.height, it.right * image.width, it.bottom * image.height) }
            }
            Canvas(Modifier.fillMaxSize().semantics {
                contentDescription = if (passage != null) "Current narration passage. ${passage.sourceText}" else "PDF page ${(pageIndex ?: 0) + 1}"
            }) {
                if (size.width <= 0 || size.height <= 0) return@Canvas
                val crop = pictureInPictureCrop(image.width.toFloat(), image.height.toFloat(), rectangles, size.width / size.height)
                val scale = minOf(size.width / crop.width, size.height / crop.height)
                val left = (size.width - crop.width * scale) / 2
                val top = (size.height - crop.height * scale) / 2
                clipRect(left, top, left + crop.width * scale, top + crop.height * scale) {
                    withTransform({ translate(left - crop.left * scale, top - crop.top * scale); scale(scale, scale, Offset.Zero) }) {
                        drawImage(pixels)
                        rectangles.forEach { drawRect(Color(0xFF9F3800).copy(alpha = 0.12f), it.topLeft, it.size) }
                    }
                }
            }
        } else {
            Text(if (failed) "Page unavailable" else "Preparing page…", modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
    }
}

internal fun pictureInPictureCrop(width: Float, height: Float, rectangles: List<Rect>, aspect: Float): Rect {
    val page = Rect(0f, 0f, width, height)
    if (rectangles.isEmpty() || !aspect.isFinite() || aspect <= 0) return page
    val focus = Rect(rectangles.minOf { it.left }, rectangles.minOf { it.top }, rectangles.maxOf { it.right }, rectangles.maxOf { it.bottom })
    var cropWidth = maxOf(focus.width + width * 0.08f, width * 0.4f)
    var cropHeight = maxOf(focus.height + height * 0.04f, height * 0.08f)
    if (cropWidth / cropHeight > aspect) cropHeight = cropWidth / aspect else cropWidth = cropHeight * aspect
    cropWidth = cropWidth.coerceAtMost(width)
    cropHeight = cropHeight.coerceAtMost(height)
    val left = (focus.center.x - cropWidth / 2).coerceIn(0f, width - cropWidth)
    val top = (focus.center.y - cropHeight / 2).coerceIn(0f, height - cropHeight)
    return Rect(left, top, left + cropWidth, top + cropHeight)
}
