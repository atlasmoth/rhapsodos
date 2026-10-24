package com.osuji.ereaderapp.data

import com.ereader.shared.ReaderCore
import com.osuji.ereaderapp.pdf.ImportedPdf
import com.osuji.ereaderapp.pdf.PdfSourcePage
import com.osuji.ereaderapp.pdf.SourceRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class LibraryDocument(
    val id: String, val title: String, val filePath: String, val sourceRevision: String = "",
    val pageCount: Int, val passageCount: Int = 0, val extractionStatus: String = "originalOnly",
    val listeningProgress: Double? = null,
)
@Serializable data class SpokenMapping(val spokenStart: Int, val spokenEnd: Int, val sourceStart: Int, val sourceEnd: Int, val kind: String = "linear")
@Serializable data class ReaderPassage(
    val id: String, val documentId: String, val pageIndex: Int, val pageStart: Int = 0, val pageEnd: Int = 0,
    val sourceStart: Int, val sourceEnd: Int, val sourceText: String, val spokenText: String,
    val displayText: String = sourceText, val languageTag: String = "en", val mappingVersion: String = "whitespace-v1",
    val spokenMapping: List<SpokenMapping> = emptyList(), val provenance: String = "embedded",
)
@Serializable data class VoiceNote(
    val id: String, val documentId: String, val passageId: String, val pageIndex: Int, val sourceStart: Int, val sourceEnd: Int,
    val quote: String, val recordingPath: String, val durationMillis: Long = 0, val offsetMillis: Long = 0,
    val status: String, val machineTranscript: String? = null, val editedTranscript: String? = null, val createdAt: Long = 0,
)
@Serializable data class SavedPosition(val documentId: String, val passageId: String, val offsetMillis: Long = 0, val passageFraction: Double = 0.0)
@Serializable data class SourceSeekTarget(val documentId: String, val sourceRevision: String, val passageId: String, val passageFraction: Double)
@Serializable data class ReaderSnapshot(val version: String = "0.01", val documents: List<LibraryDocument> = emptyList(), val notes: List<VoiceNote> = emptyList(), val positions: List<SavedPosition> = emptyList())
@Serializable data class ReaderSection(val id: String, val title: String, val passageId: String, val pageIndex: Int)
@Serializable data class ReaderContents(val document: LibraryDocument, val passages: List<ReaderPassage>, val pages: List<PdfSourcePage> = emptyList(), val sections: List<ReaderSection> = emptyList())
@Serializable data class SearchMatch(val kind: String, val id: String, val documentId: String, val passageId: String, val title: String, val text: String, val pageIndex: Int)
@Serializable data class RemovedFiles(val paths: List<String> = emptyList())
@Serializable data class PackFile(val path: String, val url: String, val sha256: String, val bytes: Long)
@Serializable data class ModelVoice(val id: String, val name: String, val languageTag: String, val category: String)
@Serializable data class ModelPack(
    val id: String, val name: String, val capability: String, val platform: String, val runtime: String, val version: String,
    val languageTags: List<String>, val minimumOS: Int, val minimumMemoryMB: Int, val license: String, val licenseUrl: String,
    val description: String, val files: List<PackFile>, val totalBytes: Long, val adapter: String = id, val voices: List<ModelVoice> = emptyList(), val archive: PackFile? = null,
)
@Serializable data class ModelInstall(val packId: String, val generation: String, val status: String, val bytesDownloaded: Long, val totalBytes: Long, val installedPath: String = "", val error: String = "", val updatedAt: Long)
@Serializable data class VerifiedPackFile(val path: String, val sha256: String, val bytes: Long)

class ReaderStore(private val core: ReaderCore) {
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val mutableSnapshot = MutableStateFlow(ReaderSnapshot())
    val snapshot = mutableSnapshot.asStateFlow()

    suspend fun refresh() = locked { updateSnapshot() }
    suspend fun contents(documentId: String): ReaderContents = locked { json.decodeFromString(core.documentJson(documentId)) }
    suspend fun begin(pdf: ImportedPdf): ReaderContents = locked {
        json.decodeFromString<ReaderContents>(core.beginDocument(pdf.id, pdf.title, pdf.path, pdf.pageCount)).also { updateSnapshot() }
    }
    suspend fun append(documentId: String, pages: List<PdfSourcePage>, finished: Boolean): ReaderContents = locked {
        json.decodeFromString<ReaderContents>(core.appendDocumentPages(documentId, json.encodeToString(pages), finished)).also { updateSnapshot() }
    }
    suspend fun updatePageGeometry(documentId: String, pageIndex: Int, regions: List<SourceRegion>, geometrySpace: String): ReaderContents = locked {
        json.decodeFromString<ReaderContents>(core.updatePageGeometry(documentId, pageIndex, json.encodeToString(regions), geometrySpace))
    }
    suspend fun saveProgress(documentId: String, passageId: String, offsetMillis: Long, passageFraction: Double = 0.0) = locked { core.saveProgress(documentId, passageId, offsetMillis, passageFraction); updateSnapshot() }
    suspend fun resolveSourceSeek(documentId: String, sourceRevision: String, fraction: Double): SourceSeekTarget = locked { json.decodeFromString(core.resolveSourceSeekJson(documentId, sourceRevision, fraction)) }
    suspend fun createDraft(noteId: String, documentId: String, passageId: String, path: String, offsetMillis: Long): VoiceNote = mutate { core.createNoteDraft(noteId, documentId, passageId, path, offsetMillis) }
    suspend fun commit(noteId: String, path: String, durationMillis: Long): VoiceNote = mutate { core.commitNote(noteId, path, durationMillis) }
    suspend fun saveText(noteId: String, documentId: String, passageId: String, text: String, offsetMillis: Long): VoiceNote = mutate { core.saveTextNote(noteId, documentId, passageId, text, offsetMillis) }
    suspend fun markStatus(noteId: String, status: String) = locked { core.markNoteStatus(noteId, status); updateSnapshot() }
    suspend fun updateTranscript(noteId: String, text: String, status: String) = locked { core.updateTranscript(noteId, text, status); updateSnapshot() }
    suspend fun discardDraft(noteId: String) = locked { core.discardDraft(noteId); updateSnapshot() }
    suspend fun editTranscript(noteId: String, text: String) = locked { core.editTranscript(noteId, text); updateSnapshot() }
    suspend fun search(documentId: String, query: String): List<SearchMatch> = locked { json.decodeFromString(core.searchJson(documentId, query)) }
    suspend fun rename(documentId: String, title: String) = locked { core.renameDocument(documentId, title); updateSnapshot() }
    suspend fun deleteDocument(documentId: String): List<String> = locked { json.decodeFromString<RemovedFiles>(core.deleteDocumentJson(documentId)).paths.also { updateSnapshot() } }
    suspend fun deleteNote(noteId: String): List<String> = locked { json.decodeFromString<RemovedFiles>(core.deleteNoteJson(noteId)).paths.also { updateSnapshot() } }
    suspend fun pendingRemovals(): List<String> = locked { json.decodeFromString<RemovedFiles>(core.pendingFileRemovalsJson()).paths }
    suspend fun acknowledgeRemoval(path: String) = locked { core.acknowledgeFileRemoval(path) }
    suspend fun exportManifest(documentId: String): String = locked { core.exportDocumentJson(documentId) }
    suspend fun restoreManifest(manifest: String) = locked { core.restoreDocumentJson(manifest); updateSnapshot() }
    suspend fun modelCatalog(): List<ModelPack> = locked { json.decodeFromString(core.modelCatalogJson("android")) }
    suspend fun acceptModelCatalog(payload: String, allowed: () -> Boolean): Boolean? = locked {
        if (!allowed()) return@locked null
        core.acceptModelCatalogJson(payload)
    }
    suspend fun modelInstalls(): List<ModelInstall> = locked { json.decodeFromString(core.modelInstallsJson()) }
    suspend fun invalidateModel(packId: String, generation: String, error: String) = locked { core.invalidateModel(packId, generation, error) }
    suspend fun sections(documentId: String, sections: List<ReaderSection>): ReaderContents = locked { json.decodeFromString(core.setDocumentSections(documentId, json.encodeToString(sections))) }
    suspend fun beginModelInstall(packId: String, generation: String): ModelInstall = locked { json.decodeFromString(core.beginModelInstall(packId, generation)) }
    suspend fun updateModelInstall(packId: String, generation: String, status: String, bytes: Long, error: String = "") = locked { core.updateModelInstall(packId, generation, status, bytes, error) }
    suspend fun activateModel(packId: String, generation: String, path: String, files: List<VerifiedPackFile>) = locked { core.activateModel(packId, generation, path, json.encodeToString(files)) }
    suspend fun removeModel(packId: String) = locked { core.removeModel(packId) }

    private fun updateSnapshot() { mutableSnapshot.value = json.decodeFromString(core.snapshotJson()) }
    private suspend fun mutate(operation: () -> String): VoiceNote = locked { json.decodeFromString<VoiceNote>(operation()).also { updateSnapshot() } }
    private suspend fun <T> locked(operation: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { operation() } }
}
