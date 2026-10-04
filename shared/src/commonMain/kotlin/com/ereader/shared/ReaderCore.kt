package com.ereader.shared

import app.cash.sqldelight.db.SqlDriver
import com.ereader.shared.database.ReaderDatabase
import com.ereader.shared.database.Note
import com.ereader.shared.database.Bookmark as StoredBookmark
import com.ereader.shared.database.Model_install as StoredModelInstall
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
            bookmarks = queries.allBookmarks().executeAsList().map { it.toDomain() },
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
        validatePages(pages, 0)
        require(pages.isNotEmpty()) { "Document has no pages" }
        database.transaction {
            queries.insertDocument(id, title, filePath, "1", pages.size.toLong(), "preparing", epochMillis())
            appendPages(id, pages, true)
        }
        json.encodeToString(documentSnapshot(id))
    }

    @Throws(Exception::class)
    fun beginDocument(id: String, title: String, filePath: String, pageCount: Int): String = lock.withLock {
        require(id.isNotBlank() && title.isNotBlank() && filePath.isNotBlank() && pageCount in 1..10_000)
        require(queries.documentById(id).executeAsOneOrNull() == null) { "Document already exists" }
        queries.insertDocument(id, title.trim(), filePath, "1", pageCount.toLong(), "preparing", epochMillis())
        json.encodeToString(documentSnapshot(id))
    }

    @Throws(Exception::class)
    fun appendDocumentPages(documentId: String, pagesJson: String, finished: Boolean): String = lock.withLock {
        val pages = json.decodeFromString<List<SourcePage>>(pagesJson)
        database.transaction { appendPages(documentId, pages, finished) }
        json.encodeToString(documentSnapshot(documentId))
    }

    private fun appendPages(documentId: String, pages: List<SourcePage>, finished: Boolean) {
        val document = queries.documentById(documentId).executeAsOne()
        require(document.extraction_status == "preparing") { "Document preparation is already finalized" }
        val previous = sourcePages(documentId)
        validatePages(pages, previous.size)
        require(previous.size + pages.size <= document.page_count) { "Too many prepared pages" }
        require((previous + pages).sumOf { it.text.length.toLong() } <= 20_000_000L) { "Document text exceeds preparation limit" }
        val sourceOffset = previous.sumOf { it.text.length + 3 }
        val ordinalOffset = document.passage_count.toInt()
        val passages = PassageBuilder.build(documentId, pages)
        pages.forEach { queries.insertSourcePage(documentId, it.pageIndex.toLong(), it.text, it.provenance, it.languageTag, json.encodeToString(it.regions)) }
        passages.forEachIndexed { ordinal, passage ->
            queries.insertPassage(passage.id, documentId, (ordinalOffset + ordinal).toLong(), passage.pageIndex.toLong(),
                passage.pageStart.toLong(), passage.pageEnd.toLong(), (sourceOffset + passage.sourceStart).toLong(),
                (sourceOffset + passage.sourceEnd).toLong(), passage.sourceText, passage.spokenText)
        }
        if (finished) {
            require(previous.size + pages.size == document.page_count.toInt()) { "Prepared pages are incomplete" }
            val status = if (ordinalOffset + passages.size == 0) "originalOnly" else if ((previous + pages).any { it.text.isBlank() }) "partial" else "ready"
            queries.setDocumentExtractionStatus(status, documentId)
        }
    }

    private fun validatePages(pages: List<SourcePage>, firstPage: Int) {
        require(pages.size <= 10_000 && pages.map { it.pageIndex } == (firstPage until firstPage + pages.size).toList()) { "Pages must retain canonical order" }
        pages.forEach { page ->
            require(page.provenance in setOf("embedded", "ocr") && page.languageTag.isNotBlank()) { "Invalid source metadata" }
            page.regions.forEach { region ->
                require(region.start >= 0 && region.end in region.start..page.text.length && region.x.isFinite() && region.y.isFinite() && region.width.isFinite() && region.height.isFinite())
                require(region.x in 0.0..1.0 && region.y in 0.0..1.0 && region.width in 0.0..1.0 && region.height in 0.0..1.0 && region.x + region.width <= 1.0001 && region.y + region.height <= 1.0001)
                require(!splitsSurrogate(page.text, region.start) && !splitsSurrogate(page.text, region.end)) { "Region splits Unicode character" }
            }
        }
    }

    private fun splitsSurrogate(text: String, offset: Int) = offset > 0 && offset < text.length && text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate()

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

    @Throws(Exception::class)
    fun bookmarksJson(documentId: String): String = lock.withLock {
        json.encodeToString(bookmarks(documentId))
    }

    @Throws(Exception::class)
    fun toggleBookmark(bookmarkId: String, documentId: String, passageId: String, label: String): String = lock.withLock {
        require(bookmarkId.isNotBlank())
        val passage = requirePassage(documentId, passageId)
        database.transaction {
            val existing = queries.bookmarkForPassage(passage.id).executeAsOneOrNull()
            if (existing == null) queries.insertBookmark(bookmarkId, documentId, passage.id, label.trim().take(200), epochMillis())
            else queries.deleteBookmark(existing.id)
        }
        json.encodeToString(bookmarks(documentId))
    }

    @Throws(Exception::class)
    fun searchJson(documentId: String, query: String): String = lock.withLock {
        val term = query.trim().take(200)
        val results = if (term.isEmpty()) emptyList() else {
            val notes = queries.searchNotes(documentId, term).executeAsList().map {
                SearchHit("note", it.id, it.document_id, it.passage_id, it.title,
                    excerpt(it.edited_transcript ?: it.machine_transcript, term), it.page_index.toInt())
            }
            val passages = queries.searchPassages(documentId, term).executeAsList().map {
                SearchHit("passage", it.id, it.document_id, it.id, it.title, excerpt(it.source_text, term), it.page_index.toInt())
            }
            (notes + passages).take(100)
        }
        json.encodeToString(results)
    }

    @Throws(Exception::class)
    fun renameDocument(documentId: String, title: String) = lock.withLock {
        require(title.trim().isNotBlank() && title.length <= 500) { "Title is empty or too long" }
        queries.documentById(documentId).executeAsOne()
        queries.renameDocument(title.trim(), documentId)
    }

    @Throws(Exception::class)
    fun deleteDocumentJson(documentId: String): String = lock.withLock {
        val document = queries.documentById(documentId).executeAsOne()
        val paths = listOf(document.file_path) + queries.allNotes().executeAsList()
            .filter { it.document_id == documentId }.map { it.recording_path }.filter { it.isNotBlank() }
        database.transaction {
            paths.forEach { queries.enqueueFileRemoval(it) }
            queries.deleteDocumentNotes(documentId)
            queries.deleteDocumentBookmarks(documentId)
            queries.deleteDocumentPosition(documentId)
            queries.deleteDocumentPassages(documentId)
            queries.deleteDocumentPages(documentId)
            queries.deleteDocument(documentId)
        }
        json.encodeToString(FileRemoval(paths.distinct()))
    }

    @Throws(Exception::class)
    fun deleteNoteJson(noteId: String): String = lock.withLock {
        val note = queries.noteById(noteId).executeAsOne()
        val paths = listOf(note.recording_path).filter { it.isNotBlank() }
        database.transaction {
            paths.forEach { queries.enqueueFileRemoval(it) }
            queries.deleteNote(noteId)
        }
        json.encodeToString(FileRemoval(paths))
    }

    @Throws(Exception::class)
    fun pendingFileRemovalsJson(): String = lock.withLock {
        json.encodeToString(FileRemoval(queries.pendingFileRemovals().executeAsList()))
    }

    @Throws(Exception::class)
    fun acknowledgeFileRemoval(path: String) = lock.withLock { queries.acknowledgeFileRemoval(path) }

    @Throws(Exception::class)
    fun exportDocumentJson(documentId: String): String = lock.withLock {
        val snapshot = documentSnapshot(documentId)
        val notes = queries.allNotes().executeAsList().filter { it.document_id == documentId }.map { it.toDomain() }
        require(notes.none { it.status == "draft" || it.status == "interrupted" && it.durationMillis == 0L }) { "Finish or discard the recording draft before exporting" }
        json.encodeToString(DocumentArchive(document = snapshot.document, pages = snapshot.pages,
            notes = notes, bookmarks = bookmarks(documentId), position = queries.allPositions().executeAsList()
                .firstOrNull { it.document_id == documentId }?.let { PlaybackPosition(it.document_id, it.passage_id, it.offset_millis) }))
    }

    @Throws(Exception::class)
    fun restoreDocumentJson(archiveJson: String): String = lock.withLock {
        require(archiveJson.length <= 50_000_000) { "Archive manifest exceeds limit" }
        val archive = json.decodeFromString<DocumentArchive>(archiveJson)
        require(archive.format == "rhapsodos-document" && archive.formatVersion == 1) { "Unsupported archive format" }
        val document = archive.document
        require(document.id.isNotBlank() && document.title.isNotBlank() && document.filePath.isNotBlank() && document.sourceRevision == "1")
        require(queries.documentById(document.id).executeAsOneOrNull() == null) { "This document already exists" }
        require(archive.pages.size == document.pageCount && archive.pages.isNotEmpty()) { "Archive pages are incomplete" }
        validatePages(archive.pages, 0)
        require(archive.pages.sumOf { it.text.length.toLong() } <= 20_000_000L)
        val passages = PassageBuilder.build(document.id, archive.pages).associateBy { it.id }
        require(archive.notes.size <= 100_000 && archive.bookmarks.size <= 100_000)
        archive.notes.forEach { note ->
            val passage = passages[note.passageId] ?: error("Note source is missing")
            require(note.id.isNotBlank() && note.documentId == document.id && note.sourceRevision == document.sourceRevision && note.pageIndex == passage.pageIndex && note.sourceStart == passage.sourceStart && note.sourceEnd == passage.sourceEnd && note.quote == passage.sourceText) { "Note anchor does not match its source" }
            require(note.offsetMillis >= 0 && note.durationMillis >= 0 && note.transcriptRevision >= 0)
            require(note.status in setOf("text", "pending", "pendingModel", "transcribing", "transcribed", "failed"))
            require(if (note.status == "text") note.recordingPath.isEmpty() && note.durationMillis == 0L else note.recordingPath.isNotBlank() && note.durationMillis > 0)
        }
        archive.bookmarks.forEach { require(it.documentId == document.id && it.id.isNotBlank() && it.passageId in passages) }
        archive.position?.let { require(it.documentId == document.id && it.passageId in passages && it.offsetMillis >= 0) }
        database.transaction {
            queries.insertDocument(document.id, document.title, document.filePath, "1", document.pageCount.toLong(), "preparing", epochMillis())
            appendPages(document.id, archive.pages, true)
            archive.notes.forEach { note ->
                queries.insertNote(note.id, document.id, note.passageId, note.sourceRevision, note.pageIndex.toLong(), note.sourceStart.toLong(), note.sourceEnd.toLong(), note.quote, note.recordingPath,
                    note.durationMillis, note.offsetMillis, if (note.status == "transcribing") "pending" else note.status, note.machineTranscript, note.editedTranscript, note.transcriptRevision, note.createdAt)
            }
            archive.bookmarks.forEach { queries.insertBookmark(it.id, it.documentId, it.passageId, it.label, it.createdAt) }
            archive.position?.let { queries.savePosition(it.documentId, it.passageId, it.offsetMillis, epochMillis()) }
        }
        json.encodeToString(documentSnapshot(document.id))
    }

    @Throws(Exception::class)
    fun modelCatalogJson(platform: String): String = lock.withLock {
        require(platform in setOf("android", "ios"))
        json.encodeToString(ModelCatalog.packs.filter { it.platform == platform })
    }

    @Throws(Exception::class)
    fun modelInstallsJson(): String = lock.withLock { json.encodeToString(queries.allModelInstalls().executeAsList().map { it.toDomain() }) }

    @Throws(Exception::class)
    fun beginModelInstall(packId: String, generation: String): String = lock.withLock {
        val pack = requirePack(packId)
        require(generation.isNotBlank())
        val previous = queries.modelInstallById(packId).executeAsOneOrNull()
        require(previous?.status != "ready") { "This pack is already installed" }
        val install = ModelInstall(packId, generation, "downloading", previous?.bytes_downloaded ?: 0L, pack.totalBytes,
            previous?.installed_path ?: "", "", epochMillis())
        saveInstall(install)
        json.encodeToString(install)
    }

    @Throws(Exception::class)
    fun updateModelInstall(packId: String, generation: String, status: String, bytesDownloaded: Long, error: String) = lock.withLock {
        require(status in setOf("downloading", "paused", "verifying", "failed"))
        val previous = queries.modelInstallById(packId).executeAsOneOrNull() ?: return@withLock
        if (previous.generation != generation || previous.status == "ready") return@withLock
        require(bytesDownloaded in 0..previous.total_bytes)
        saveInstall(previous.toDomain().copy(status = status, bytesDownloaded = bytesDownloaded, error = error.take(500), updatedAt = epochMillis()))
    }

    @Throws(Exception::class)
    fun activateModel(packId: String, generation: String, installedPath: String, verifiedFilesJson: String) = lock.withLock {
        val pack = requirePack(packId)
        val previous = queries.modelInstallById(packId).executeAsOneOrNull() ?: return@withLock
        if (previous.generation != generation || previous.status == "ready") return@withLock
        require(previous.status == "verifying") { "Pack has not reached verification" }
        require(installedPath == "Models/${pack.id}/${pack.version}") { "Invalid installed model locator" }
        val verified = json.decodeFromString<List<VerifiedModelFile>>(verifiedFilesJson)
        require(verified.size == pack.files.size && verified.map { it.path }.toSet().size == verified.size)
        require(pack.files.all { expected -> verified.any { it.path == expected.path && it.sha256 == expected.sha256 && it.bytes == expected.bytes } }) { "Model assets do not match catalog" }
        saveInstall(previous.toDomain().copy(status = "ready", bytesDownloaded = pack.totalBytes, installedPath = installedPath, error = "", updatedAt = epochMillis()))
    }

    @Throws(Exception::class)
    fun removeModel(packId: String) = lock.withLock { queries.deleteModelInstall(packId) }

    private fun requirePack(id: String) = ModelCatalog.packs.firstOrNull { it.id == id } ?: error("Unknown speech pack")

    private fun saveInstall(install: ModelInstall) = queries.saveModelInstall(install.packId, install.generation, install.status, install.bytesDownloaded, install.totalBytes, install.installedPath, install.error, install.updatedAt)

    private fun bookmarks(documentId: String) = (if (documentId.isEmpty()) queries.allBookmarks().executeAsList() else queries.bookmarksForDocument(documentId).executeAsList()).map { it.toDomain() }

    private fun excerpt(text: String, query: String): String {
        val start = (text.indexOf(query, ignoreCase = true) - 60).coerceAtLeast(0)
        val safeStart = if (splitsSurrogate(text, start)) start - 1 else start
        var end = minOf(text.length, safeStart + 300)
        if (splitsSurrogate(text, end)) end--
        return (if (safeStart > 0) "…" else "") + text.substring(safeStart, end) + if (end < text.length) "…" else ""
    }

    private fun sourcePages(documentId: String): List<SourcePage> = queries.pagesForDocument(documentId).executeAsList().map {
        SourcePage(it.page_index.toInt(), it.source_text, it.provenance, it.language_tag, json.decodeFromString(it.regions_json))
    }

    private fun documentSnapshot(id: String): DocumentSnapshot {
        val row = queries.documentById(id).executeAsOne()
        val pages = sourcePages(id)
        val pagesByIndex = pages.associateBy { it.pageIndex }
        val passages = queries.passagesForDocument(id).executeAsList().map { stored ->
            val page = pagesByIndex.getValue(stored.page_index.toInt())
            stored.toDomain().copy(languageTag = page.languageTag, provenance = page.provenance)
        }
        return DocumentSnapshot(
            DocumentRecord(row.id, row.title, row.file_path, row.source_revision,
                row.page_count.toInt(), row.passage_count.toInt(), row.extraction_status),
            passages,
            pages,
            passages.distinctBy { it.pageIndex }.map { DocumentSection("$id:page:${it.pageIndex}", "Page ${it.pageIndex + 1}", it.id, it.pageIndex) },
        )
    }

    private fun requirePassage(documentId: String, passageId: String): StoredPassage {
        val passage = queries.passageById(passageId).executeAsOne()
        require(passage.document_id == documentId) { "Passage belongs to another document" }
        return passage
    }

    private fun StoredPassage.toDomain() = Passage(id, document_id, page_index.toInt(),
        page_start.toInt(), page_end.toInt(), source_start.toInt(), source_end.toInt(), source_text, spoken_text,
        spokenMapping = PassageBuilder.mapping(source_text, page_start.toInt()))

    private fun StoredBookmark.toDomain() = Bookmark(id, document_id, passage_id, label, created_at)

    private fun StoredModelInstall.toDomain() = ModelInstall(pack_id, generation, status, bytes_downloaded, total_bytes, installed_path, error, updated_at)

    private fun Note.toDomain() = VoiceNote(id, document_id, passage_id, source_revision, page_index.toInt(),
        source_start.toInt(), source_end.toInt(), quote, recording_path, duration_millis, offset_millis,
        status, machine_transcript, edited_transcript, transcript_revision, created_at)
}
