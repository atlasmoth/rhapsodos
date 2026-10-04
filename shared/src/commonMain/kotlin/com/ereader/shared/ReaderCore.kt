package com.ereader.shared

import app.cash.sqldelight.db.SqlDriver
import com.ereader.shared.database.ReaderDatabase
import com.ereader.shared.database.Note
import com.ereader.shared.database.Passage as StoredPassage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ReaderCore internal constructor(driver: SqlDriver) {
    private val database = ReaderDatabase(driver)
    private val queries = database.readerQueries
    private val lock = StoreLock()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Throws(Exception::class)
    fun snapshotJson(): String = lock.withLock {
        json.encodeToString(LibrarySnapshot(
            documents = queries.allDocuments().executeAsList().map { row ->
                DocumentRecord(row.id, row.title, row.file_path, row.source_revision,
                    row.page_count.toInt(), row.passage_count.toInt(), row.extraction_status)
            },
            notes = queries.allNotes().executeAsList().map { it.toDomain() },
            positions = queries.allPositions().executeAsList().map {
                PlaybackPosition(it.document_id, it.passage_id, it.offset_millis)
            },
        ))
    }

    @Throws(Exception::class)
    fun documentJson(documentId: String): String = lock.withLock {
        json.encodeToString(documentSnapshot(documentId))
    }

    @Throws(Exception::class)
    fun importDocument(id: String, title: String, filePath: String, pagesJson: String): String = lock.withLock {
        require(id.isNotBlank() && title.isNotBlank() && filePath.isNotBlank()) { "Missing document metadata" }
        require(queries.documentById(id).executeAsOneOrNull() == null) { "Document already exists" }
        val pages = json.decodeFromString<List<SourcePage>>(pagesJson)
        require(pages.isNotEmpty()) { "Document has no pages" }
        require(pages.map { it.pageIndex } == pages.indices.toList()) { "Pages must retain canonical order" }
        require(pages.sumOf { it.text.length.toLong() } <= 20_000_000L) { "Document text exceeds this build's preparation limit" }
        val passages = PassageBuilder.build(id, pages)
        val status = if (passages.isEmpty()) "originalOnly" else if (pages.any { it.text.isBlank() }) "partial" else "ready"
        database.transaction {
            queries.insertDocument(id, title, filePath, "1", pages.size.toLong(), status, epochMillis())
            pages.forEach { queries.insertSourcePage(id, it.pageIndex.toLong(), it.text) }
            passages.forEachIndexed { ordinal, passage ->
                queries.insertPassage(passage.id, id, ordinal.toLong(), passage.pageIndex.toLong(),
                    passage.pageStart.toLong(), passage.pageEnd.toLong(), passage.sourceStart.toLong(),
                    passage.sourceEnd.toLong(), passage.sourceText, passage.spokenText)
            }
        }
        json.encodeToString(documentSnapshot(id))
    }

    @Throws(Exception::class)
    fun saveProgress(documentId: String, passageId: String, offsetMillis: Long) = lock.withLock {
        val passage = requirePassage(documentId, passageId)
        require(offsetMillis >= 0) { "Negative playback position" }
        queries.savePosition(documentId, passage.id, offsetMillis, epochMillis())
    }

    @Throws(Exception::class)
    fun createNoteDraft(noteId: String, documentId: String, passageId: String, recordingPath: String, offsetMillis: Long): String = lock.withLock {
        require(noteId.isNotBlank() && recordingPath.isNotBlank() && offsetMillis >= 0) { "Invalid note draft" }
        val passage = requirePassage(documentId, passageId)
        val document = queries.documentById(documentId).executeAsOne()
        val existing = queries.noteById(noteId).executeAsOneOrNull()
        if (existing != null) {
            require(existing.document_id == documentId && existing.passage_id == passageId && existing.recording_path == recordingPath) { "Note ID refers to a different attachment" }
            return@withLock json.encodeToString(existing.toDomain())
        }
        queries.insertNote(noteId, documentId, passageId, document.source_revision, passage.page_index,
            passage.source_start, passage.source_end, passage.source_text, recordingPath, 0L, offsetMillis,
            "draft", "", null, 0L, epochMillis())
        json.encodeToString(queries.noteById(noteId).executeAsOne().toDomain())
    }

    @Throws(Exception::class)
    fun commitNote(noteId: String, recordingPath: String, durationMillis: Long): String = lock.withLock {
        require(recordingPath.isNotBlank() && durationMillis > 0) { "Recording is not finalized" }
        val existing = queries.noteById(noteId).executeAsOne()
        if (existing.status !in setOf("draft", "interrupted") || existing.duration_millis > 0) {
            require(existing.recording_path == recordingPath && existing.duration_millis == durationMillis) { "Committed recording is immutable" }
            return@withLock json.encodeToString(existing.toDomain())
        }
        queries.commitNote(recordingPath, durationMillis, noteId)
        json.encodeToString(queries.noteById(noteId).executeAsOne().toDomain())
    }

    @Throws(Exception::class)
    fun saveTextNote(noteId: String, documentId: String, passageId: String, text: String, offsetMillis: Long): String = lock.withLock {
        require(noteId.isNotBlank() && text.isNotBlank() && offsetMillis >= 0) { "A text note needs content and an attachment" }
        val passage = requirePassage(documentId, passageId)
        val document = queries.documentById(documentId).executeAsOne()
        val existing = queries.noteById(noteId).executeAsOneOrNull()
        if (existing != null) {
            require(existing.document_id == documentId && existing.passage_id == passageId && existing.edited_transcript == text) { "Note ID refers to different content" }
            return@withLock json.encodeToString(existing.toDomain())
        }
        queries.insertNote(noteId, documentId, passageId, document.source_revision, passage.page_index,
            passage.source_start, passage.source_end, passage.source_text, "", 0L, offsetMillis,
            "text", "", text, 1L, epochMillis())
        json.encodeToString(queries.noteById(noteId).executeAsOne().toDomain())
    }

    @Throws(Exception::class)
    fun markNoteStatus(noteId: String, status: String) = lock.withLock {
        require(status in setOf("pending", "pendingModel", "transcribing", "transcribed", "failed", "interrupted")) { "Invalid note state" }
        val note = queries.noteById(noteId).executeAsOne()
        val recoverableDraft = note.duration_millis == 0L && note.status in setOf("draft", "interrupted")
        require((status == "interrupted" && recoverableDraft) || note.duration_millis > 0) { "Save the recording before transcription" }
        queries.markNoteStatus(status, noteId)
    }

    @Throws(Exception::class)
    fun updateTranscript(noteId: String, text: String, status: String) = lock.withLock {
        require(status in setOf("transcribed", "failed", "pendingModel")) { "Invalid transcription state" }
        val note = queries.noteById(noteId).executeAsOne()
        require(note.status != "draft" && note.duration_millis > 0) { "Recording has not been committed" }
        queries.updateTranscript(text, status, noteId, note.recording_path)
    }

    @Throws(Exception::class)
    fun editTranscript(noteId: String, text: String) = lock.withLock {
        queries.noteById(noteId).executeAsOne()
        queries.editTranscript(text, noteId)
    }

    @Throws(Exception::class)
    fun discardDraft(noteId: String) = lock.withLock {
        val existing = queries.noteById(noteId).executeAsOneOrNull() ?: return@withLock
        require(existing.status in setOf("draft", "interrupted") && existing.duration_millis == 0L) { "Saved recordings cannot be discarded as drafts" }
        queries.discardDraft(noteId)
    }

    private fun documentSnapshot(id: String): DocumentSnapshot {
        val row = queries.documentById(id).executeAsOne()
        return DocumentSnapshot(
            DocumentRecord(row.id, row.title, row.file_path, row.source_revision,
                row.page_count.toInt(), row.passage_count.toInt(), row.extraction_status),
            queries.passagesForDocument(id).executeAsList().map { it.toDomain() },
        )
    }

    private fun requirePassage(documentId: String, passageId: String): StoredPassage {
        val passage = queries.passageById(passageId).executeAsOne()
        require(passage.document_id == documentId) { "Passage belongs to another document" }
        return passage
    }

    private fun StoredPassage.toDomain() = Passage(id, document_id, page_index.toInt(),
        page_start.toInt(), page_end.toInt(), source_start.toInt(), source_end.toInt(), source_text, spoken_text)

    private fun Note.toDomain() = VoiceNote(id, document_id, passage_id, source_revision, page_index.toInt(),
        source_start.toInt(), source_end.toInt(), quote, recording_path, duration_millis, offset_millis,
        status, machine_transcript, edited_transcript, transcript_revision, created_at)
}
