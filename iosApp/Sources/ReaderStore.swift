import AVFoundation
import Combine
import SwiftUI

@MainActor
final class ReaderStore: ObservableObject {
    let audio = AudioCoordinator()
    let feedback = Feedback()
    @Published private(set) var library: [LibraryDocument] = []
    @Published private(set) var notes: [VoiceNote] = []
    @Published private(set) var document: DocumentSnapshot?
    @Published private(set) var selectedId: String?
    @Published private(set) var loading = true
    @Published private(set) var busy = false
    @Published private(set) var preparingDocument = false
    @Published private(set) var preparationProgress = 0.0
    @Published private(set) var bookmarks: [Bookmark] = []
    @Published private(set) var models: ModelPacks?
    @Published private(set) var preparation = "Opening your library"
    @Published var exportURL: URL?
    @Published var errorMessage: String?
    @Published var captureDraft: VoiceNote?
    @Published var captureMessage = ""
    @Published var typedThought = ""
    @Published var follow = true
    @Published var inspector = false
    var repository: SharedRepository?
    var files: LocalFiles?
    private var positions: [PlaybackPosition] = []
    private var latestProgress: [String: ListeningCursor] = [:]
    private var progressWrite: Task<Result<Void, Error>, Never>?
    private var noteTask: Task<Void, Never>?
    private var capturedDuration: Int64 = 0
    private var transcriptionQueue: [String] = []
    private var transcribing = false
    private var transcriptionTask: Task<Void, Never>?
    private var transcriptionGeneration: UUID?
    private var subscriptions: Set<AnyCancellable> = []
    private var activeTranscription: String?
    private var interruptedWhileBusy = false
    private var interruptedCaptureReason: String?
    private var pendingImport: URL?
    private let transcriber = OnDeviceTranscriber()
    let speechPacks = CoreMLSpeech()
    let voicePacks = CoreMLVoice()
    private var preparationTask: Task<Void, Never>?
    private var geometryRepairTask: Task<Void, Never>?
    private var geometryRepairID: UUID?
    private var geometryRepairAttempts: Set<String> = []
    private var sceneActive = true

    init() {
        audio.onProgress = { [weak self] cursor in self?.saveProgress(cursor) }
        audio.renderVoice = { [weak self] passage, voice, pace in
            guard let self, let files = self.files, let (pack, folder) = self.models?.ready(capability: "tts") else { throw PackFailure.notReady }
            if let worker = self.transcriptionTask { worker.cancel(); await worker.value }
            try Task.checkCancellation()
            await self.speechPacks.unload()
            do { return try await self.voicePacks.render(passage, voice: voice, pace: pace, pack: pack, folder: folder, cache: files.transient) }
            catch {
                if let installation = self.models?.installs.first(where: { $0.packId == pack.id }), !(error is CancellationError), !(error is NarrationFailure) {
                    try? await self.repository?.invalidateModel(id: pack.id, generation: installation.generation, error: error.localizedDescription)
                    try? await self.models?.refresh()
                }
                throw error
            }
        }
        audio.onError = { [weak self] in self?.errorMessage = $0 }
        audio.onCaptureInterrupted = { [weak self] reason in Task { await self?.interruptedCapture(message: reason) } }
        audio.$playing.merge(with: audio.$recording).merge(with: audio.$previewing).merge(with: audio.$replayPaused).sink { [weak self] _ in Task { @MainActor in self?.serviceTranscriptions() } }.store(in: &subscriptions)
        $preparingDocument.sink { [weak self] _ in Task { @MainActor in self?.serviceTranscriptions() } }.store(in: &subscriptions)
        $captureDraft.sink { [weak self] _ in Task { @MainActor in self?.serviceTranscriptions() } }.store(in: &subscriptions)
        $busy.sink { [weak self] _ in Task { @MainActor in self?.serviceTranscriptions(); self?.repairCurrentGeometry() } }.store(in: &subscriptions)
        audio.$index.sink { [weak self] _ in Task { @MainActor in self?.repairCurrentGeometry() } }.store(in: &subscriptions)
        $document.sink { [weak self] _ in Task { @MainActor in self?.repairCurrentGeometry() } }.store(in: &subscriptions)
        $captureDraft.sink { [weak self] _ in Task { @MainActor in self?.repairCurrentGeometry() } }.store(in: &subscriptions)
        Task { await openLibrary() }
    }

    var currentNotes: [VoiceNote] { notes.filter { $0.documentId == selectedId } }
    var currentPassage: Passage? { audio.current }
    var originalURL: URL? { document.flatMap { files?.ownedURL($0.document.filePath) } }
    var currentHighlightAnchor: PDFHighlightAnchor? {
        guard let document, let passage = currentPassage, passage.documentId == document.document.id, selectedId == passage.documentId else { return nil }
        return PDFHighlightAnchor(documentId: passage.documentId, sourceRevision: document.document.sourceRevision,
            passageId: passage.id, pageIndex: passage.pageIndex, sourceStart: passage.sourceStart, sourceEnd: passage.sourceEnd)
    }
    var canBeginNote: Bool {
        !busy && !loading && sceneActive && captureDraft == nil && !audio.recording && !audio.previewing && audio.replayingNote == nil && !audio.capturePending && currentHighlightAnchor != nil
    }
    var highlightSourceRange: NSRange? {
        guard let passage = currentPassage else { return nil }
        guard let spoken = audio.spokenRange else { return NSRange(location: passage.pageStart, length: passage.pageEnd - passage.pageStart) }
        let mapped = passage.spokenMapping.filter { $0.spokenStart < NSMaxRange(spoken) && $0.spokenEnd > spoken.location }.map { mapping -> (Int, Int) in
            if mapping.kind == "linear" && mapping.spokenEnd - mapping.spokenStart == mapping.sourceEnd - mapping.sourceStart {
                return (mapping.sourceStart + max(spoken.location, mapping.spokenStart) - mapping.spokenStart,
                        mapping.sourceStart + min(NSMaxRange(spoken), mapping.spokenEnd) - mapping.spokenStart)
            }
            return (mapping.sourceStart, mapping.sourceEnd)
        }
        guard let start = mapped.map({ $0.0 }).min(), let end = mapped.map({ $0.1 }).max() else { return NSRange(location: passage.pageStart, length: passage.pageEnd - passage.pageStart) }
        return NSRange(location: start, length: end - start)
    }
    var currentPage: SourcePage? {
        guard let passage = currentPassage else { return nil }
        return document?.pages.first { $0.pageIndex == passage.pageIndex }
    }
    var passageSourceRange: NSRange? {
        currentPassage.map { NSRange(location: $0.pageStart, length: $0.pageEnd - $0.pageStart) }
    }
    var passageRegions: [SourceRegion] {
        guard let page = currentPage, page.geometrySpace == "cropTopLeftV1", let range = passageSourceRange else { return [] }
        return page.regions.filter { $0.start < NSMaxRange(range) && $0.end > range.location }
    }
    var highlightRegions: [SourceRegion] {
        guard let page = currentPage, page.geometrySpace == "cropTopLeftV1", let range = highlightSourceRange else { return [] }
        return page.regions.filter { $0.start < NSMaxRange(range) && $0.end > range.location }
    }
    var currentBookmarks: [Bookmark] { bookmarks.filter { $0.documentId == selectedId } }

    private func openLibrary() async {
        do {
            let files = try LocalFiles()
            self.files = files
            repository = try await SharedRepository.open(path: files.database.path)
            if let repository { models = ModelPacks(repository: repository, files: files) { [weak self] pack, folder in
                guard let self else { throw CancellationError() }
                while self.audio.playing || self.audio.previewing || self.audio.replayPlaying || self.audio.recording || self.captureDraft != nil || self.preparingDocument || self.busy || !self.sceneActive {
                    try Task.checkCancellation(); try await Task.sleep(for: .milliseconds(100))
                }
                if let worker = self.transcriptionTask { worker.cancel(); await worker.value }
                try Task.checkCancellation()
                if pack.capability == "asr" { await self.voicePacks.unload(); try await self.speechPacks.validateASR(at: folder) }
                else if pack.capability == "tts" { await self.speechPacks.unload(); try await self.voicePacks.validate(at: folder) }
                else { throw PackFailure.incompatible }
            } }
            models?.onReady = { [weak self] pack in
                guard let self, pack.capability == "asr" else { return }
                for note in self.notes where ["pending", "pendingModel"].contains(note.status) && !(note.recordingPath ?? "").isEmpty { self.enqueue(note.id) }
            }
            models?.objectWillChange.sink { [weak self] _ in Task { @MainActor in self?.objectWillChange.send(); self?.serviceTranscriptions() } }.store(in: &subscriptions)
            if let models {
                models.$loading.merge(with: models.$validationActive).sink { [weak self] _ in Task { @MainActor in self?.serviceTranscriptions() } }.store(in: &subscriptions)
            }
            try await refresh()
            await removePendingFiles()
            for note in notes where note.status == "transcribing" {
                try await repository?.status(id: note.id, value: "pending")
            }
            try await refresh()
            let remembered = UserDefaults.standard.string(forKey: "selectedDocument")
            if let first = library.first(where: { $0.id == remembered }) ?? library.first { await select(first.id) }
            inspector = currentNotes.contains(where: \.isDraft)
            loading = false
            preparation = ""
            if let pendingImport {
                self.pendingImport = nil
                await importPDF(pendingImport)
            }
            for note in notes where note.status == "pending" && !(note.recordingPath ?? "").isEmpty { enqueue(note.id) }
            if let unfinished = library.first(where: { $0.extractionStatus == "preparing" }) { resumePreparation(unfinished.id) }
        } catch { loading = false; present(error) }
    }

    private func refresh() async throws {
        guard let repository else { return }
        let snapshot = try await repository.snapshot()
        library = snapshot.documents
        notes = snapshot.notes
        positions = snapshot.positions
        bookmarks = try await repository.bookmarks()
        if let cursor = selectedId.flatMap({ latestProgress[$0] }) { applyProgress(cursor) }
    }

    private func saveProgress(_ cursor: ListeningCursor) {
        guard let repository else { return }
        latestProgress[cursor.documentId] = cursor
        applyProgress(cursor)
        let previous = progressWrite
        progressWrite = Task { [weak self] in
            _ = await previous?.value
            do {
                try await repository.saveProgress(documentId: cursor.documentId, passageId: cursor.passageId,
                    offsetMillis: cursor.offsetMillis, passageFraction: cursor.passageFraction)
                return .success(())
            } catch { self?.present(error); return .failure(error) }
        }
    }

    private func applyProgress(_ cursor: ListeningCursor) {
        positions.removeAll { $0.documentId == cursor.documentId }
        positions.append(PlaybackPosition(documentId: cursor.documentId, passageId: cursor.passageId,
            offsetMillis: cursor.offsetMillis, passageFraction: cursor.passageFraction))
        guard let snapshot = document, snapshot.document.id == cursor.documentId,
              let end = snapshot.passages.last?.sourceEnd, end > 0,
              let index = library.firstIndex(where: { $0.id == cursor.documentId }) else { return }
        let value = library[index].extractionStatus == "preparing" ? nil : min(1, max(0,
            cursor.sourcePosition / Double(end)))
        library[index] = library[index].withListeningProgress(value)
    }

    private func flushProgress() async throws {
        audio.reportPosition()
        if let progressWrite, case .failure = await progressWrite.value { throw ProgressSaveFailure() }
    }

    func select(_ id: String) async {
        guard !busy, captureDraft == nil, let repository, document?.document.id != id else { return }
        busy = true
        preparation = "Opening your document"
        await audio.pauseAndJoinGeneration()
        audio.stopReplay()
        defer { busy = false; preparation = ""; serviceTranscriptions() }
        do {
            try await flushProgress()
            try await refresh()
            let selected = try await repository.document(id)
            document = selected
            selectedId = id
            UserDefaults.standard.set(id, forKey: "selectedDocument")
            let saved = positions.first { $0.documentId == id }
            let index = selected.passages.firstIndex { $0.id == saved?.passageId } ?? 0
            audio.load(selected.passages, title: selected.document.title, at: index, offsetMillis: saved?.offsetMillis ?? 0,
                passageFraction: saved?.passageFraction ?? 0, complete: selected.document.extractionStatus != "preparing")
            follow = true
            feedback.play(.selection)
        } catch { present(error) }
    }

    func importPDF(_ url: URL) async {
        if loading { pendingImport = url; return }
        guard !busy, !preparingDocument, captureDraft == nil, let repository, let files else { return }
        busy = true
        preparation = "Preparing your PDF"
        await audio.pauseAndJoinGeneration()
        let id = UUID().uuidString
        var copiedFile: URL?
        var registered = false
        do {
            try await flushProgress()
            let title = url.deletingPathExtension().lastPathComponent
            let copied = try await Task.detached(priority: .userInitiated) { try files.importPDF(url, id: id) }.value
            copiedFile = copied
            let session = try await Task.detached(priority: .userInitiated) { try PDFPreparationSession(url: copied) }.value
            let imported = try await repository.beginDocument(id: id, title: title, path: "Documents/" + id + ".pdf", pageCount: session.pageCount)
            registered = true
            try await refresh()
            document = imported
            selectedId = id
            UserDefaults.standard.set(id, forKey: "selectedDocument")
            audio.load(imported.passages, title: title, at: 0, complete: false)
            follow = true
            feedback.play(.saved)
            busy = false
            startPreparation(session: session, id: id, start: 0)
        } catch { if !registered, let copiedFile { try? FileManager.default.removeItem(at: copiedFile) }; present(error) }
        busy = false
        preparation = ""
    }

    func resumePreparation(_ id: String) {
        guard sceneActive, !busy, !preparingDocument, preparationTask == nil, let repository, let files else { return }
        preparationTask = Task { [weak self] in
            guard let self else { return }
            defer { if !self.preparingDocument { self.preparationTask = nil } }
            do {
                let snapshot = try await repository.document(id)
                guard snapshot.document.extractionStatus == "preparing", let url = files.ownedURL(snapshot.document.filePath) else { return }
                let characters = snapshot.pages.reduce(0) { $0 + $1.text.utf16.count }
                let session = try await Task.detached(priority: .utility) { try PDFPreparationSession(url: url, existingCharacters: characters) }.value
                try Task.checkCancellation()
                self.preparationTask = nil
                self.startPreparation(session: session, id: id, start: snapshot.pages.count)
            } catch is CancellationError { self.preparationTask = nil }
            catch { self.preparationTask = nil; self.present(error) }
        }
    }

    private func startPreparation(session: PDFPreparationSession, id: String, start: Int) {
        guard !preparingDocument else { return }
        preparingDocument = true
        preparationTask = Task { [weak self] in
            guard let self, let repository = self.repository else { return }
            defer {
                self.preparingDocument = false; self.preparation = ""; self.preparationTask = nil
                if Task.isCancelled, self.sceneActive, let unfinished = self.library.first(where: { $0.extractionStatus == "preparing" }) { self.resumePreparation(unfinished.id) }
                self.serviceTranscriptions()
            }
            do {
                let total = await session.pageCount
                var next = start
                while next < total {
                    try Task.checkCancellation()
                    self.preparation = "Preparing page \(next + 1) of \(total)"
                    let batch = try await session.batch(start: next, count: 16)
                    try Task.checkCancellation()
                    next += batch.count
                    let updated = try await repository.appendPages(documentId: id, pages: batch, finished: next == total)
                    self.preparationProgress = Double(next) / Double(max(1, total))
                    if self.selectedId == id {
                        self.document = updated
                        self.audio.updatePassages(updated.passages, complete: updated.document.extractionStatus != "preparing")
                    }
                    try await self.refresh()
                }
                let snapshot = try await repository.document(id)
                let sections = await session.outline(for: snapshot.passages)
                if !sections.isEmpty {
                    let outlined = try await repository.setSections(documentId: id, sections: sections)
                    if self.selectedId == id { self.document = outlined }
                }
            } catch is CancellationError {
            } catch { self.present(error) }
        }
    }

    private func repairCurrentGeometry() {
        guard geometryRepairTask == nil, sceneActive, !busy, !preparingDocument, captureDraft == nil,
              !audio.recording, !audio.capturePending, let snapshot = document, let page = currentPage,
              page.geometrySpace != "cropTopLeftV1", let url = originalURL, let repository else { return }
        let key = snapshot.document.id + ":" + snapshot.document.sourceRevision + ":" + String(page.pageIndex)
        guard !geometryRepairAttempts.contains(key) else { return }
        let id = UUID()
        geometryRepairID = id
        geometryRepairTask = Task { [weak self] in
            guard let self else { return }
            defer {
                if self.geometryRepairID == id {
                    self.geometryRepairTask = nil; self.geometryRepairID = nil
                    self.repairCurrentGeometry()
                }
            }
            let worker = Task.detached(priority: .utility) {
                try PDFImporter.repairGeometry(at: url, pageIndex: page.pageIndex, canonicalText: page.text, provenance: page.provenance)
            }
            do {
                let regions = try await withTaskCancellationHandler(operation: { try await worker.value }, onCancel: { worker.cancel() })
                try Task.checkCancellation()
                guard self.document?.document.id == snapshot.document.id,
                      self.document?.document.sourceRevision == snapshot.document.sourceRevision else { return }
                self.geometryRepairAttempts.insert(key)
                guard let regions else { return }
                let repaired = try await repository.updateGeometry(documentId: snapshot.document.id, pageIndex: page.pageIndex, regions: regions)
                if self.selectedId == snapshot.document.id { self.document = repaired }
            } catch is CancellationError {
            } catch { self.geometryRepairAttempts.insert(key) }
        }
    }

    func sceneChanged(_ phase: ScenePhase) {
        sceneActive = phase == .active
        if phase == .background { preparationTask?.cancel(); geometryRepairTask?.cancel() }
        if phase != .active { audio.reportPosition() }
        if phase == .active, !preparingDocument, let unfinished = library.first(where: { $0.extractionStatus == "preparing" }) {
            resumePreparation(unfinished.id)
        }
        serviceTranscriptions()
        if sceneActive { repairCurrentGeometry(); Task { await models?.recheckReadyPacks() } }
    }

    func toggleBookmark(_ passage: Passage) async {
        guard let repository else { return }
        do {
            _ = try await repository.toggleBookmark(id: UUID().uuidString, documentId: passage.documentId,
                passageId: passage.id, label: String(passage.displayText.prefix(80)))
            try await refresh(); feedback.play(.saved)
        } catch { present(error) }
    }

    func search(_ query: String, entireLibrary: Bool) async -> [SearchHit] {
        guard let repository else { return [] }
        do { return try await repository.search(documentId: entireLibrary ? "" : selectedId ?? "", query: query) }
        catch { present(error); return [] }
    }

    func goToPassage(documentId: String, passageId: String) async {
        if selectedId != documentId { await select(documentId) }
        if let passage = document?.passages.first(where: { $0.id == passageId }) { audio.pause(); seek(passage) }
    }

    func rename(_ id: String, title: String) async {
        guard !busy, captureDraft == nil, let repository else { return }
        do {
            try await repository.rename(documentId: id, title: title)
            if selectedId == id { document = try await repository.document(id); audio.title = title }
            try await refresh()
        } catch { present(error) }
    }

    func deleteDocument(_ id: String) async {
        guard !busy, captureDraft == nil, activeTranscription == nil, let repository else { return }
        busy = true
        defer { busy = false }
        preparationTask?.cancel()
        geometryRepairTask?.cancel()
        await preparationTask?.value
        await geometryRepairTask?.value
        do {
            if selectedId == id {
                await audio.pauseAndJoinGeneration()
                try await flushProgress()
                audio.load([], title: "", at: 0); audio.stopReplay(); document = nil; selectedId = nil
            }
            _ = try await repository.deleteDocument(id)
            latestProgress.removeValue(forKey: id)
            await removePendingFiles()
            try await refresh()
        } catch { present(error) }
    }

    func deleteNote(_ note: VoiceNote) async {
        guard !busy, captureDraft == nil, activeTranscription != note.id, let repository else { return }
        audio.stopReplay()
        do { _ = try await repository.deleteNote(note.id); await removePendingFiles(); try await refresh() }
        catch { present(error) }
    }

    private func removePendingFiles() async {
        guard let repository, let files else { return }
        do {
            for path in try await repository.pendingRemovals() {
                try files.removeOwnedAsset(path)
                try await repository.acknowledgeRemoval(path)
            }
        } catch { present(error) }
    }

    func togglePlayback() { feedback.play(.playback); audio.toggle() }
    func skip(_ amount: Int) { feedback.play(.selection); audio.move(by: amount); follow = true }
    func seek(_ passage: Passage) {
        guard let index = document?.passages.firstIndex(where: { $0.id == passage.id }) else { return }
        audio.seek(to: index)
        follow = true
        feedback.play(.selection)
    }

    @discardableResult func beginNote(expected: PDFHighlightAnchor? = nil) -> Bool {
        guard canBeginNote, let repository, let files, let initial = currentHighlightAnchor,
              let passage = currentPassage, expected == nil || expected == initial else { return false }
        busy = true
        models?.cancelRuntimeChecks()
        let worker = transcriptionTask
        worker?.cancel()
        let geometryWorker = geometryRepairTask
        geometryWorker?.cancel()
        let preparation = audio.reserveCapture()
        noteTask = Task { [weak self] in
            guard let self else { return }
            await self.startNoteCapture(anchor: initial, passage: passage, preparation: preparation, worker: worker, geometryWorker: geometryWorker, repository: repository, files: files)
            self.noteTask = nil
        }
        return true
    }

    private func startNoteCapture(anchor: PDFHighlightAnchor, passage: Passage, preparation: AudioCoordinator.CapturePreparation,
                                  worker: Task<Void, Never>?, geometryWorker: Task<Void, Never>?, repository: SharedRepository, files: LocalFiles) async {
        await audio.finishPreparingCapture(preparation)
        await models?.cancelAndJoinRuntimeChecks()
        await worker?.value
        await geometryWorker?.value
        await speechPacks.unload()
        await voicePacks.unload()
        guard currentHighlightAnchor == anchor, !audio.previewing, audio.replayingNote == nil, sceneActive else { audio.endCaptureMode(); busy = false; return }
        let frozenOffset = preparation.offsetMillis
        do {
            let id = UUID().uuidString
            let path = files.recordings.appendingPathComponent(id + ".voice")
            let draft = try await repository.createDraft(id: id, documentId: passage.documentId, passageId: passage.id, recordingPath: "Recordings/" + id + ".voice", offsetMillis: frozenOffset)
            captureDraft = draft
            typedThought = ""
            capturedDuration = 0
            captureMessage = "Your note is attached to this passage."
            feedback.play(.capture)
            if feedback.enabled { try await Task.sleep(for: .milliseconds(200)) }
            let permitted = try await audio.startCapture(at: path)
            if !permitted { captureMessage = "Microphone access is off. Type your thought here, or enable access in Settings." }
            try await refresh()
        } catch {
            captureMessage = error.localizedDescription
            if captureDraft == nil { audio.endCaptureMode(); present(error) }
            feedback.play(.failure)
        }
        busy = false
        if interruptedWhileBusy { interruptedWhileBusy = false; let reason = interruptedCaptureReason; interruptedCaptureReason = nil; await interruptedCapture(message: reason) }
    }

    func saveNote(resume: Bool = true) async {
        guard !busy, let draft = captureDraft, let repository else { return }
        busy = true
        do {
            let typed = typedThought.trimmingCharacters(in: .whitespacesAndNewlines)
            var duration = audio.recording ? try await audio.finishCapture() : capturedDuration
            capturedDuration = duration
            var recordingLocator = draft.recordingPath ?? ""
            let rawFile = draft.recordingPath.flatMap { files?.ownedURL($0) }
            if let input = rawFile, let files, FileManager.default.fileExists(atPath: input.path) {
                let recoveredName = input.deletingPathExtension().lastPathComponent + "-recovered.voice"
                let recoveryTarget = files.recordings.appendingPathComponent(recoveredName)
                do {
                    let completed = try await Task.detached(priority: .userInitiated) {
                        try VoiceVault.finalizeRecording(input, recoveredDestination: recoveryTarget)
                    }.value
                    duration = completed.durationMillis
                    capturedDuration = duration
                    if completed.url != input { recordingLocator = "Recordings/" + recoveredName }
                } catch { if typed.isEmpty { throw error }; duration = 0 }
            }
            guard duration > 0 || !typed.isEmpty else { throw VaultFailure.noAudio }
            if duration > 0 {
                let saved = try await repository.commit(id: draft.id, path: recordingLocator, durationMillis: duration)
                captureDraft = saved
                notes.removeAll { $0.id == saved.id }; notes.append(saved)
                enqueue(saved.id)
                if !typed.isEmpty { try await repository.editTranscript(id: draft.id, text: typed) }
            } else {
                let typedId = draft.id + "-typed"
                if let existing = try await repository.snapshot().notes.first(where: { $0.id == typedId }) {
                    guard existing.documentId == draft.documentId, existing.passageId == draft.passageId, existing.status == "text" else { throw VaultFailure.format }
                    try await repository.editTranscript(id: typedId, text: typed)
                } else {
                    _ = try await repository.saveText(id: typedId, documentId: draft.documentId, passageId: draft.passageId, text: typed, offsetMillis: draft.offsetMillis)
                }
                if !(rawFile.map { FileManager.default.fileExists(atPath: $0.path) } ?? false) {
                    try await repository.discard(id: draft.id)
                } else {
                    try await repository.status(id: draft.id, value: "interrupted")
                }
            }
            captureDraft = nil
            capturedDuration = 0
            audio.endCaptureMode()
            try await refresh()
            feedback.play(.saved)
            if resume { audio.play() }
            if duration > 0 { enqueue(draft.id) }
        } catch { captureMessage = error.localizedDescription; feedback.play(.failure) }
        busy = false
        serviceTranscriptions()
    }

    func discardDraft() async {
        guard !busy, let draft = captureDraft, draft.isDraft, let repository, let files else { return }
        busy = true
        if audio.recording { _ = try? await audio.finishCapture() }
        do {
            if let path = draft.recordingPath, !path.isEmpty { try files.removeOwnedAsset(path) }
            try await repository.discard(id: draft.id)
            captureDraft = nil
            capturedDuration = 0
            audio.endCaptureMode()
            try await refresh()
        } catch { captureMessage = error.localizedDescription }
        busy = false
    }

    func keepDraft() async {
        guard !busy, let draft = captureDraft, let repository else { return }
        busy = true
        if audio.recording { capturedDuration = (try? await audio.finishCapture()) ?? 0 }
        do {
            if !typedThought.isEmpty { try await repository.editTranscript(id: draft.id, text: typedThought) }
            if draft.isDraft { try await repository.status(id: draft.id, value: "interrupted") }
            captureDraft = nil
            capturedDuration = 0
            audio.endCaptureMode()
            try await refresh()
            inspector = true
            if !draft.isDraft { enqueue(draft.id) }
        } catch { captureMessage = error.localizedDescription }
        busy = false
        serviceTranscriptions()
    }

    private func interruptedCapture(message: String? = nil) async {
        guard audio.recording else { return }
        if busy { interruptedWhileBusy = true; interruptedCaptureReason = message; return }
        busy = true
        do { capturedDuration = try await audio.finishCapture() }
        catch { captureMessage = error.localizedDescription }
        captureMessage = message ?? "Recording stopped unexpectedly. Save the captured audio, or keep this draft for later."
        busy = false
        feedback.play(.failure)
    }

    func recover(_ note: VoiceNote) async {
        guard !busy, captureDraft == nil, let files else { return }
        busy = true
        await audio.prepareCapture()
        captureDraft = note
        typedThought = note.transcript
        capturedDuration = 0
        captureMessage = "This recording was interrupted. Its original attachment has been preserved."
        guard let path = note.recordingPath, let input = files.ownedURL(path), FileManager.default.fileExists(atPath: input.path) else { busy = false; return }
        let output = files.transient.appendingPathComponent(UUID().uuidString + ".caf")
        defer { try? FileManager.default.removeItem(at: output) }
        do {
            capturedDuration = try await Task.detached(priority: .userInitiated) { try VoiceVault.render(input, to: output, recoverInterrupted: true) }.value
        } catch { captureMessage = error.localizedDescription }
        busy = false
    }

    func replay(_ note: VoiceNote) async {
        guard !busy, !audio.recording, let path = note.recordingPath, let files, let input = files.ownedURL(path) else { return }
        if audio.replayingNote == note.id {
            do { if audio.replayPaused { try audio.resumeReplay() } else { audio.pauseReplay() } }
            catch { present(error) }
            return
        }
        busy = true
        let output = files.transient.appendingPathComponent(UUID().uuidString + ".caf")
        do {
            _ = try await Task.detached(priority: .userInitiated) { try VoiceVault.render(input, to: output, expectedDurationMillis: note.durationMillis) }.value
            try audio.replay(noteId: note.id, file: output)
        } catch { try? FileManager.default.removeItem(at: output); present(error) }
        busy = false
    }

    func jump(to note: VoiceNote) async {
        if selectedId != note.documentId { await select(note.documentId) }
        audio.pause()
        if let passage = document?.passages.first(where: { $0.id == note.passageId }) { seek(passage); audio.seekOffset(note.offsetMillis) }
        follow = true
    }

    func edit(_ note: VoiceNote, text: String) async {
        do { try await repository?.editTranscript(id: note.id, text: text); try await refresh(); feedback.play(.saved) }
        catch { present(error) }
    }

    func removePack(_ pack: ModelPack) async {
        guard !busy, !audio.playing, !audio.previewing, !audio.replayPlaying, !audio.recording, captureDraft == nil, !preparingDocument, let models else { errorMessage = "Pause playback and finish your current operation before removing a pack."; return }
        busy = true
        defer { busy = false; serviceTranscriptions() }
        let worker = transcriptionTask
        worker?.cancel()
        await worker?.value
        await audio.pauseAndJoinGeneration()
        do { await speechPacks.unload(); await voicePacks.unload(); try await models.remove(pack) }
        catch { present(error) }
    }

    func retry(_ note: VoiceNote) { enqueue(note.id) }

    private func enqueue(_ id: String) {
        guard !transcriptionQueue.contains(id), activeTranscription != id else { return }
        transcriptionQueue.append(id)
        serviceTranscriptions()
    }

    private var transcriptionDeferred: Bool {
        !sceneActive || audio.playing || audio.previewing || audio.replayPlaying || audio.recording || captureDraft != nil || preparingDocument || models?.loading == true || models?.validationActive == true || busy
    }
    private func serviceTranscriptions() {
        if audio.playing || audio.previewing || audio.replayPlaying || audio.recording || captureDraft != nil || preparingDocument || busy || !sceneActive { models?.cancelRuntimeChecks() }
        else if models?.loading == false && models?.validationActive == false { Task { await models?.recheckReadyPacks() } }
        if transcriptionDeferred { transcriptionTask?.cancel(); return }
        guard transcriptionTask == nil, !transcriptionQueue.isEmpty else { return }
        let generation = UUID(); transcriptionGeneration = generation
        transcriptionTask = Task { [weak self] in await self?.processTranscriptions(generation: generation) }
    }
    private func processTranscriptions(generation: UUID) async {
        guard !transcribing, let repository, let files else { transcriptionTask = nil; return }
        transcribing = true
        defer {
            transcribing = false; activeTranscription = nil
            if transcriptionGeneration == generation { transcriptionTask = nil; transcriptionGeneration = nil }
            serviceTranscriptions()
        }
        while !transcriptionQueue.isEmpty && !transcriptionDeferred && !Task.isCancelled {
            let id = transcriptionQueue.removeFirst()
            guard let note = notes.first(where: { $0.id == id }), let path = note.recordingPath, let input = files.ownedURL(path), !note.isDraft else { continue }
            activeTranscription = id
            let output = files.transient.appendingPathComponent(UUID().uuidString + ".caf")
            do {
                try await repository.status(id: id, value: "transcribing"); try await refresh()
                let rendering = Task.detached(priority: .utility) { try VoiceVault.render(input, to: output, expectedDurationMillis: note.durationMillis) }
                _ = try await withTaskCancellationHandler(operation: { try await rendering.value }, onCancel: { rendering.cancel() })
                try Task.checkCancellation()
                await voicePacks.unload()
                let text: String
                if let (_, folder) = models?.ready(capability: "asr") { text = try await speechPacks.transcribe(output, using: folder) }
                else { text = try await transcriber.transcribe(output) }
                try Task.checkCancellation()
                guard transcriptionGeneration == generation else { throw CancellationError() }
                try await repository.transcript(id: id, text: text, status: "transcribed")
            } catch is CancellationError {
                try? await repository.status(id: id, value: "pending")
                if !transcriptionQueue.contains(id) { transcriptionQueue.insert(id, at: 0) }
            } catch {
                let status = error is TranscriptionFailure ? "pendingModel" : "failed"
                try? await repository.status(id: id, value: status)
            }
            activeTranscription = nil; try? FileManager.default.removeItem(at: output); try? await refresh()
        }
    }

    func savePronunciation(term: String, replacement: String) async {
        guard !busy, captureDraft == nil, let id = selectedId, let repository else { return }
        busy = true
        defer { busy = false; serviceTranscriptions() }
        await audio.pauseAndJoinGeneration(restart: true)
        do { applyPronunciation(try await repository.pronunciation(id: UUID().uuidString, documentId: id, term: term, replacement: replacement)); try await refresh() }
        catch { present(error) }
    }
    func removePronunciation(_ rule: PronunciationRule) async {
        guard !busy, captureDraft == nil, let repository else { return }
        busy = true
        defer { busy = false; serviceTranscriptions() }
        await audio.pauseAndJoinGeneration(restart: true)
        do { applyPronunciation(try await repository.removePronunciation(rule.id)); try await refresh() }
        catch { present(error) }
    }
    private func applyPronunciation(_ snapshot: DocumentSnapshot) {
        let passageId = currentPassage?.id
        let fraction = positions.first(where: { $0.documentId == snapshot.document.id && $0.passageId == passageId })?.passageFraction ?? 0
        document = snapshot
        audio.load(snapshot.passages, title: snapshot.document.title, at: snapshot.passages.firstIndex(where: { $0.id == passageId }) ?? 0,
            passageFraction: fraction, complete: snapshot.document.extractionStatus != "preparing")
        feedback.play(.saved)
    }

    func exportSelected() async {
        guard !busy, captureDraft == nil, let id = selectedId, let repository, let files else { return }
        busy = true; preparation = "Preparing portable archive"
        defer { busy = false; preparation = ""; serviceTranscriptions() }
        do {
            try await flushProgress()
            let manifest = try await repository.exportDocument(id)
            exportURL = try await Task.detached(priority: .utility) { try DocumentArchives.export(manifest, files: files) }.value
        } catch { present(error) }
    }

    func restoreArchive(_ url: URL) async {
        guard !busy, captureDraft == nil, let repository, let files else { return }
        busy = true; preparation = "Verifying your archive"
        await audio.pauseAndJoinGeneration()
        defer { busy = false; preparation = ""; serviceTranscriptions() }
        var copied: [URL] = []
        do {
            try await flushProgress()
            let existing = Set(library.map(\.id))
            let (manifest, installed) = try await Task.detached(priority: .utility) { try DocumentArchives.prepareRestore(url, files: files, existing: existing) }.value
            copied = installed
            let restored = try await repository.restore(manifest)
            copied.removeAll()
            try await refresh()
            document = restored; selectedId = restored.document.id
            let saved = positions.first { $0.documentId == restored.document.id }
            let index = restored.passages.firstIndex { $0.id == saved?.passageId } ?? 0
            audio.load(restored.passages, title: restored.document.title, at: index, offsetMillis: saved?.offsetMillis ?? 0,
                passageFraction: saved?.passageFraction ?? 0, complete: restored.document.extractionStatus != "preparing")
            UserDefaults.standard.set(restored.document.id, forKey: "selectedDocument")
            feedback.play(.saved)
            for note in notes where note.documentId == restored.document.id && ["pending", "pendingModel", "transcribing"].contains(note.status) {
                if note.status == "transcribing" { try await repository.status(id: note.id, value: "pending") }
                enqueue(note.id)
            }
        } catch { for file in copied { try? FileManager.default.removeItem(at: file) }; present(error) }
    }

    func importAudio(_ url: URL, at passage: Passage) async {
        guard !busy, captureDraft == nil, let repository, let files else { return }
        busy = true; await audio.pauseAndJoinGeneration(); preparation = "Saving your attached recording"
        defer { busy = false; preparation = ""; serviceTranscriptions() }
        let id = UUID().uuidString
        let locator = "Recordings/" + id + ".voice"
        do {
            guard let target = files.ownedURL(locator) else { throw VaultFailure.write }
            let draft = try await repository.createDraft(id: id, documentId: passage.documentId, passageId: passage.id, recordingPath: locator)
            var committed = false
            do {
                try await refresh()
                let duration = try await Task.detached(priority: .utility) {
                    let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
                    let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                    guard size > 0 && size <= 128 * 1024 * 1024 else { throw ArchiveFailure.oversized }
                    return try VoiceVault.seal(url, to: target)
                }.value
                let saved = try await repository.commit(id: draft.id, path: locator, durationMillis: duration)
                committed = true
                notes.removeAll { $0.id == saved.id }; notes.append(saved)
                enqueue(id)
                feedback.play(.saved)
                try await refresh()
            } catch {
                if !committed { try? await repository.status(id: id, value: "interrupted") }
                try? await refresh(); throw error
            }
        } catch { present(error) }
    }

    private func present(_ error: Error) { errorMessage = error.localizedDescription; feedback.play(.failure) }
}
