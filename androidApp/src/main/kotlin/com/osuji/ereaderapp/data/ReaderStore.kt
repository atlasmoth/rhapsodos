package com.osuji.ereaderapp.data

import com.ereader.shared.ReaderCore
import com.osuji.ereaderapp.pdf.ImportedPdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable data class LibraryDocument(
    val id: String,
    val title: String,
    val filePath: String,
    val sourceRevision: String = "",
    val pageCount: Int,
    val passageCount: Int = 0,
    val extractionStatus: String = "originalOnly",
)

@Serializable data class ReaderPassage(
    val id: String,
    val documentId: String,
    val pageIndex: Int,
    val pageStart: Int = 0,
    val pageEnd: Int = 0,
    val sourceStart: Int,
    val sourceEnd: Int,
    val sourceText: String,
    val spokenText: String,
)

@Serializable data class VoiceNote(
    val id: String,
    val documentId: String,
    val passageId: String,
    val pageIndex: Int,
    val sourceStart: Int,
    val sourceEnd: Int,
    val quote: String,
    val recordingPath: String,
    val durationMillis: Long = 0,
    val offsetMillis: Long = 0,
    val status: String,
    val machineTranscript: String? = null,
    val editedTranscript: String? = null,
    val createdAt: Long = 0,
)

@Serializable data class SavedPosition(val documentId: String, val passageId: String, val offsetMillis: Long = 0)
@Serializable data class ReaderSnapshot(val version: String = "0.01", val documents: List<LibraryDocument> = emptyList(), val notes: List<VoiceNote> = emptyList(), val positions: List<SavedPosition> = emptyList())
@Serializable data class ReaderContents(val document: LibraryDocument, val passages: List<ReaderPassage>)

class ReaderStore(private val core: ReaderCore) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val mutex = Mutex()
    private val mutableSnapshot = MutableStateFlow(ReaderSnapshot())
    val snapshot = mutableSnapshot.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock { mutableSnapshot.value = json.decodeFromString<ReaderSnapshot>(core.snapshotJson()) }
    }

    suspend fun contents(documentId: String): ReaderContents = withContext(Dispatchers.IO) {
        mutex.withLock { json.decodeFromString(core.documentJson(documentId)) }
    }

    suspend fun import(pdf: ImportedPdf): ReaderContents = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pages = buildJsonArray {
                pdf.pages.forEach { page -> add(buildJsonObject { put("pageIndex", page.pageIndex); put("text", page.text) }) }
            }
            val result = json.decodeFromString<ReaderContents>(core.importDocument(pdf.id, pdf.title, pdf.path, pages.toString()))
            mutableSnapshot.value = json.decodeFromString(core.snapshotJson())
            result
        }
    }

    suspend fun saveProgress(documentId: String, passageId: String, offsetMillis: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            core.saveProgress(documentId, passageId, offsetMillis)
            mutableSnapshot.value = json.decodeFromString(core.snapshotJson())
        }
    }

    suspend fun createDraft(noteId: String, documentId: String, passageId: String, path: String, offsetMillis: Long): VoiceNote = mutate {
        core.createNoteDraft(noteId, documentId, passageId, path, offsetMillis)
    }

    suspend fun commit(noteId: String, path: String, durationMillis: Long): VoiceNote = mutate {
        core.commitNote(noteId, path, durationMillis)
    }

    suspend fun saveText(noteId: String, documentId: String, passageId: String, text: String, offsetMillis: Long): VoiceNote = mutate {
        core.saveTextNote(noteId, documentId, passageId, text, offsetMillis)
    }

    suspend fun markStatus(noteId: String, status: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            core.markNoteStatus(noteId, status)
            mutableSnapshot.value = json.decodeFromString(core.snapshotJson())
        }
    }

    suspend fun discardDraft(noteId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            core.discardDraft(noteId)
            mutableSnapshot.value = json.decodeFromString(core.snapshotJson())
        }
    }

    suspend fun editTranscript(noteId: String, text: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            core.editTranscript(noteId, text)
            mutableSnapshot.value = json.decodeFromString(core.snapshotJson())
        }
    }

    private suspend fun mutate(operation: () -> String): VoiceNote = withContext(Dispatchers.IO) {
        mutex.withLock {
            val note = json.decodeFromString<VoiceNote>(operation())
            mutableSnapshot.value = json.decodeFromString(core.snapshotJson())
            note
        }
    }
}
