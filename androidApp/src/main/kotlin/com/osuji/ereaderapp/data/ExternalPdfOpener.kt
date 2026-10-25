package com.osuji.ereaderapp.data

import android.content.Intent
import android.net.Uri
import androidx.annotation.MainThread
import com.osuji.ereaderapp.pdf.DocumentPreparation
import com.osuji.ereaderapp.pdf.ImportedPdf
import com.osuji.ereaderapp.pdf.PdfAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ExternalPdfOpenResult(val requestId: String, val documentId: String? = null, val error: String? = null)
data class ExternalPdfOpenState(val busy: Boolean = false, val results: List<ExternalPdfOpenResult> = emptyList())

class ExternalPdfOpener(
    private val pdf: PdfAdapter,
    private val store: ReaderStore,
    private val preparation: DocumentPreparation,
    private val scope: CoroutineScope,
) {
    private data class Request(val id: String, val uri: Uri?)

    private val mutableState = MutableStateFlow(ExternalPdfOpenState())
    val state = mutableState.asStateFlow()
    private val requests = ArrayDeque<Request>()
    private val seen = LinkedHashSet<String>()
    private var worker: Job? = null

    @MainThread
    fun submit(intent: Intent, requestId: String): Boolean {
        if (intent.action != Intent.ACTION_VIEW) return false
        require(UUID.fromString(requestId).toString() == requestId)
        if (!seen.add(requestId)) return true
        if (seen.size > 128) seen.remove(seen.first())
        val uri = intent.data
        if (uri?.scheme != "content" || uri.authority.isNullOrBlank() || intent.type != "application/pdf") {
            complete(ExternalPdfOpenResult(requestId, error = "Choose a PDF from a file app and open it with Rhapsodos."))
            return true
        }
        if (requests.size >= 8) {
            complete(ExternalPdfOpenResult(requestId, error = "Finish opening the queued PDFs, then try this file again."))
            return true
        }
        requests.addLast(Request(requestId, uri))
        mutableState.value = mutableState.value.copy(busy = true)
        if (worker?.isActive != true) worker = scope.launch {
            try {
                while (requests.isNotEmpty()) open(requests.removeFirst())
            } finally { mutableState.value = mutableState.value.copy(busy = false) }
        }
        return true
    }

    @MainThread
    fun acknowledge(requestId: String) {
        mutableState.value = mutableState.value.copy(results = mutableState.value.results.filterNot { it.requestId == requestId })
    }

    private suspend fun open(request: Request) {
        var imported: ImportedPdf? = null
        try {
            store.refresh()
            val existing = store.snapshot.value.documents.firstOrNull { it.id == request.id }
            if (existing == null) {
                imported = pdf.import(checkNotNull(request.uri), request.id)
                store.begin(imported)
            } else store.contents(existing.id)
            preparation.prepare(request.id)
            complete(ExternalPdfOpenResult(request.id, documentId = request.id))
        } catch (timeout: TimeoutCancellationException) {
            discardUnregistered(imported)
            complete(ExternalPdfOpenResult(request.id, error = "This PDF took too long to open. Please try again."))
        } catch (cancelled: CancellationException) {
            discardUnregistered(imported)
            throw cancelled
        } catch (failure: Exception) {
            discardUnregistered(imported)
            val message = if (failure is SecurityException) "This file could not be read. Open it again from the file app to grant access."
            else "This PDF could not be opened. It may be damaged, password-protected or unavailable. Please try another file."
            complete(ExternalPdfOpenResult(request.id, error = message))
        }
    }

    private suspend fun discardUnregistered(imported: ImportedPdf?) {
        if (imported == null) return
        withContext(NonCancellable) {
            runCatching {
                store.refresh()
                if (store.snapshot.value.documents.none { it.id == imported.id }) {
                    withContext(Dispatchers.IO) { File(imported.path).delete() }
                }
            }
        }
    }

    private fun complete(result: ExternalPdfOpenResult) {
        mutableState.value = mutableState.value.copy(results = mutableState.value.results + result)
    }
}
