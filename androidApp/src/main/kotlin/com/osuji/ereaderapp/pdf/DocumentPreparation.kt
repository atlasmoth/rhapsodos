package com.osuji.ereaderapp.pdf

import com.osuji.ereaderapp.audio.PlaybackCoordinator
import com.osuji.ereaderapp.data.ReaderStore
import com.osuji.ereaderapp.data.ReaderSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PreparationProgress(val documentId: String, val completedPages: Int, val totalPages: Int, val running: Boolean, val error: String? = null)

class DocumentPreparation(private val pdf: PdfAdapter, private val store: ReaderStore, private val playback: PlaybackCoordinator, private val scope: CoroutineScope) {
    private val tasks = mutableMapOf<String, Job>()
    private val mutableProgress = MutableStateFlow<Map<String, PreparationProgress>>(emptyMap())
    val progress = mutableProgress.asStateFlow()

    fun prepare(documentId: String) {
        if (tasks[documentId]?.isActive == true) return
        tasks[documentId] = scope.launch {
            try {
                val original = store.contents(documentId)
                val document = original.document
                if (original.pages.size == document.pageCount && document.extractionStatus != "preparing") return@launch
                update(PreparationProgress(documentId, original.pages.size, document.pageCount, true))
                val batch = mutableListOf<PdfSourcePage>()
                pdf.extractPages(document.filePath, original.pages.size, original.pages.sumOf { it.text.length }) { page ->
                    batch += page
                    val finished = page.pageIndex + 1 == document.pageCount
                    if (page.pageIndex == 0 || batch.size >= 12 || finished) {
                        val updated = store.append(documentId, batch.toList(), finished)
                        batch.clear()
                        playback.updateContents(updated)
                        if (finished) {
                            val outline = runCatching { pdf.outline(document.filePath) }.getOrDefault(emptyList())
                            val sections = outline.mapIndexedNotNull { index, item ->
                                updated.passages.firstOrNull { it.pageIndex == item.pageIndex }?.let { ReaderSection("$documentId:outline:$index", item.title, it.id, item.pageIndex) }
                            }
                            if (sections.isNotEmpty()) playback.updateContents(store.sections(documentId, sections))
                        }
                        update(PreparationProgress(documentId, updated.pages.size, document.pageCount, !finished))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val previous = mutableProgress.value[documentId] ?: PreparationProgress(documentId, 0, 0, false)
                update(previous.copy(running = false, error = failure.message ?: "Page preparation stopped. Your PDF and prepared pages are safe; retry to continue."))
            } finally { tasks.remove(documentId) }
        }
    }

    fun recover() = scope.launch {
        runCatching { store.refresh() }.onSuccess {
            store.snapshot.value.documents.filter { it.extractionStatus == "preparing" }.forEach { prepare(it.id) }
        }
    }

    suspend fun repairGeometry(documentId: String, pageIndex: Int): com.osuji.ereaderapp.data.ReaderContents {
        val document = store.contents(documentId)
        val source = document.pages.firstOrNull { it.pageIndex == pageIndex } ?: error("This page has not been prepared yet.")
        val prepared = pdf.extractPage(document.document.filePath, pageIndex)
        val outerTrim = prepared.text != source.text && source.provenance == "embedded" && prepared.provenance == "embedded"
        val first = if (outerTrim) prepared.text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0) else 0
        val last = if (outerTrim) prepared.text.indexOfLast { !it.isWhitespace() } + 1 else prepared.text.length
        require(last >= first && prepared.text.substring(first, last) == source.text) { "This page's highlighting could not be verified. Its text, listening position and thoughts are unchanged." }
        val regions = prepared.regions.mapNotNull { region ->
            val start = maxOf(region.start, first)
            val end = minOf(region.end, last)
            if (start < end) region.copy(start = start - first, end = end - first) else null
        }
        return store.updatePageGeometry(documentId, pageIndex, regions, prepared.geometrySpace)
    }

    fun cancel(documentId: String) { tasks.remove(documentId)?.cancel(); mutableProgress.value = mutableProgress.value - documentId }
    private fun update(progress: PreparationProgress) { mutableProgress.value = mutableProgress.value + (progress.documentId to progress) }
}
