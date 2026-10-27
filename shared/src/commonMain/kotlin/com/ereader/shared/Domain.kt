package com.ereader.shared

import kotlinx.serialization.Serializable

@Serializable
data class SourcePage(
    val pageIndex: Int,
    val text: String,
    val provenance: String = "embedded",
    val languageTag: String = "en",
    val regions: List<SourceRegion> = emptyList(),
    val geometrySpace: String = "unspecified",
)

@Serializable
data class SourceRegion(val start: Int, val end: Int, val x: Double, val y: Double, val width: Double, val height: Double)

@Serializable
data class SpokenMapping(val spokenStart: Int, val spokenEnd: Int, val sourceStart: Int, val sourceEnd: Int, val kind: String = "linear")

@Serializable
data class DocumentRecord(
    val id: String,
    val title: String,
    val filePath: String,
    val sourceRevision: String,
    val pageCount: Int,
    val passageCount: Int,
    val extractionStatus: String,
    val listeningProgress: Double? = null,
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
    val displayText: String = sourceText,
    val languageTag: String = "en",
    val mappingVersion: String = "whitespace-v1",
    val spokenMapping: List<SpokenMapping> = emptyList(),
    val provenance: String = "embedded",
)

@Serializable
data class DocumentSnapshot(
    val document: DocumentRecord,
    val passages: List<Passage>,
    val pages: List<SourcePage> = emptyList(),
    val sections: List<DocumentSection> = emptyList(),
)

@Serializable
data class DocumentSection(val id: String, val title: String, val passageId: String, val pageIndex: Int)

@Serializable
data class SearchHit(val kind: String, val id: String, val documentId: String, val passageId: String, val title: String, val text: String, val pageIndex: Int)

@Serializable
data class FileRemoval(val paths: List<String>)

@Serializable
data class DocumentArchive(
    val format: String = "rhapsodos-document",
    val formatVersion: Int = 1,
    val document: DocumentRecord,
    val pages: List<SourcePage>,
    val notes: List<VoiceNote>,
    val position: PlaybackPosition?,
    val sections: List<DocumentSection> = emptyList(),
)

@Serializable
data class ModelFile(val path: String, val url: String, val sha256: String, val bytes: Long)

@Serializable
data class VerifiedModelFile(val path: String, val sha256: String, val bytes: Long)

@Serializable
data class ModelVoice(val id: String, val name: String, val languageTag: String, val category: String)

@Serializable
data class ModelPack(
    val id: String,
    val name: String,
    val capability: String,
    val platform: String,
    val runtime: String,
    val version: String,
    val languageTags: List<String>,
    val minimumOS: Int,
    val minimumMemoryMB: Int,
    val license: String,
    val licenseUrl: String,
    val description: String,
    val files: List<ModelFile>,
    val archive: ModelFile? = null,
    val totalBytes: Long = archive?.bytes ?: files.sumOf { it.bytes },
    val adapter: String = id,
    val voices: List<ModelVoice> = emptyList(),
)

@Serializable
data class ModelCatalogUpdate(
    val formatVersion: Int,
    val revision: Long,
    val publishedAt: Long,
    val expiresAt: Long? = null,
    val packs: List<ModelPack>,
)

@Serializable
data class ModelCatalogStatus(val revision: Long, val acceptedAt: Long, val source: String)

@Serializable
data class ModelInstall(
    val packId: String,
    val generation: String,
    val status: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val installedPath: String,
    val error: String,
    val updatedAt: Long,
)

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
    val passageFraction: Double = 0.0,
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
    fun permitsModelDownload(packId: String, url: String): Boolean =
        ModelCatalog.packs.any { pack -> pack.id == packId &&
            (pack.archive?.let { it.url == url } ?: pack.files.any { it.url == url }) }
}
