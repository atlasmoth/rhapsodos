package com.ereader.shared

import kotlinx.serialization.Serializable

@Serializable
data class SourcePage(val pageIndex: Int, val text: String)

@Serializable
data class DocumentRecord(
    val id: String,
    val title: String,
    val filePath: String,
    val sourceRevision: String,
    val pageCount: Int,
    val passageCount: Int,
    val extractionStatus: String,
)

@Serializable
data class Passage(
    val id: String,
    val documentId: String,
    val pageIndex: Int,
    val pageStart: Int,
    val pageEnd: Int,
    val sourceStart: Int,
    val sourceEnd: Int,
    val sourceText: String,
    val spokenText: String,
)

@Serializable
data class DocumentSnapshot(val document: DocumentRecord, val passages: List<Passage>)

@Serializable
data class VoiceNote(
    val id: String,
    val documentId: String,
    val passageId: String,
    val sourceRevision: String,
    val pageIndex: Int,
    val sourceStart: Int,
    val sourceEnd: Int,
    val quote: String,
    val recordingPath: String,
    val durationMillis: Long,
    val offsetMillis: Long,
    val status: String,
    val machineTranscript: String,
    val editedTranscript: String?,
    val transcriptRevision: Long,
    val createdAt: Long,
    val precision: String = "passage",
)

@Serializable
data class PlaybackPosition(
    val documentId: String,
    val passageId: String,
    val offsetMillis: Long,
)

@Serializable
data class LibrarySnapshot(
    val documents: List<DocumentRecord>,
    val notes: List<VoiceNote>,
    val positions: List<PlaybackPosition>,
    val version: String = "0.01",
    val privacyMode: String = "localOnly",
)

class ContentEgressPolicy {
    fun permitsDocumentUpload(): Boolean = false
    fun permitsRecordingUpload(): Boolean = false
}
