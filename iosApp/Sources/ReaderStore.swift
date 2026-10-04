import AVFoundation
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
    @Published private(set) var preparation = "Opening your library"
    @Published var errorMessage: String?
    @Published var captureDraft: VoiceNote?
    @Published var captureMessage = ""
    @Published var typedThought = ""
    @Published var follow = true
    @Published var inspector = false
    @Published var sourceMode = false
    private var repository: SharedRepository?
    private var files: LocalFiles?
    private var positions: [PlaybackPosition] = []
    private var capturedDuration: Int64 = 0
    private var transcriptionQueue: [String] = []
    private var transcribing = false
    private var activeTranscription: String?
    private var interruptedWhileBusy = false
    private var pendingImport: URL?
    private let transcriber = OnDeviceTranscriber()

    init() {
        audio.onPassage = { [weak self] index in
            guard let self, let document = self.document, document.passages.indices.contains(index), let repository = self.repository else { return }
            let passage = document.passages[index]
            Task {
                do { try await repository.saveProgress(documentId: document.document.id, passageId: passage.id) }
                catch { self.present(error) }
            }
        }
        audio.onError = { [weak self] in self?.errorMessage = $0 }
        audio.onCaptureInterrupted = { [weak self] in Task { await self?.interruptedCapture() } }
        Task { await openLibrary() }
    }

    var currentNotes: [VoiceNote] { notes.filter { $0.documentId == selectedId } }
    var currentPassage: Passage? { audio.current }
    var originalURL: URL? { document.flatMap { files?.ownedURL($0.document.filePath) } }

    private func openLibrary() async {
        do {
            let files = try LocalFiles()
            self.files = files
            repository = try await SharedRepository.open(path: files.database.path)
            try await refresh()
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
        } catch { loading = false; present(error) }
    }

    private func refresh() async throws {
        guard let repository else { return }
        let snapshot = try await repository.snapshot()
        library = snapshot.documents
        notes = snapshot.notes
        positions = snapshot.positions
    }

    func select(_ id: String) async {
        guard !busy, captureDraft == nil, let repository, document?.document.id != id else { return }
        busy = true
        preparation = "Opening your document"
        audio.pause()
        audio.stopReplay()
        defer { busy = false; preparation = "" }
        do {
            try await refresh()
            let selected = try await repository.document(id)
            document = selected
            selectedId = id
            UserDefaults.standard.set(id, forKey: "selectedDocument")
            let saved = positions.first { $0.documentId == id }?.passageId
            let index = selected.passages.firstIndex { $0.id == saved } ?? 0
            audio.load(selected.passages, title: selected.document.title, at: index)
            follow = true
            feedback.play(.selection)
        } catch { present(error) }
    }

    func importPDF(_ url: URL) async {
        if loading { pendingImport = url; return }
        guard !busy, captureDraft == nil, let repository, let files else { return }
        busy = true
        preparation = "Preparing your PDF"
        audio.pause()
        let id = UUID().uuidString
        do {
            let title = url.deletingPathExtension().lastPathComponent
            let copied = try await Task.detached(priority: .userInitiated) { try files.importPDF(url, id: id) }.value
            let extraction = try await Task.detached(priority: .userInitiated) { try PDFImporter.extract(at: copied) }.value
            let imported = try await repository.importDocument(id: id, title: title, filePath: "Documents/" + id + ".pdf", pages: extraction.pages)
            try await refresh()
            document = imported
            selectedId = id
            UserDefaults.standard.set(id, forKey: "selectedDocument")
            audio.load(imported.passages, title: title, at: 0)
            follow = true
            sourceMode = imported.passages.isEmpty
            feedback.play(.saved)
        } catch { present(error) }
        busy = false
        preparation = ""
    }

    func togglePlayback() { feedback.play(.playback); audio.toggle() }
    func skip(_ amount: Int) { feedback.play(.selection); audio.move(by: amount); follow = true }
    func seek(_ passage: Passage) {
        guard let index = document?.passages.firstIndex(where: { $0.id == passage.id }) else { return }
        audio.seek(to: index)
        follow = true
        feedback.play(.selection)
    }

    func beginNote() async {
        guard !busy, captureDraft == nil, let repository, let files, currentPassage != nil else { return }
        busy = true
        await audio.prepareCapture()
        guard let passage = currentPassage else { audio.endCaptureMode(); busy = false; return }
        do {
            let id = UUID().uuidString
            let path = files.recordings.appendingPathComponent(id + ".voice")
            let draft = try await repository.createDraft(id: id, documentId: passage.documentId, passageId: passage.id, recordingPath: "Recordings/" + id + ".voice")
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
        if interruptedWhileBusy { interruptedWhileBusy = false; await interruptedCapture() }
    }

    func saveNote(resume: Bool = true) async {
        guard !busy, let draft = captureDraft, let repository else { return }
        busy = true
        do {
            let typed = typedThought.trimmingCharacters(in: .whitespacesAndNewlines)
            var duration = audio.recording ? try await audio.finishCapture() : capturedDuration
            capturedDuration = duration
            let rawFile = draft.recordingPath.flatMap { files?.ownedURL($0) }
            if duration == 0, let input = rawFile, let files,
               FileManager.default.fileExists(atPath: input.path) {
                let output = files.transient.appendingPathComponent(UUID().uuidString + ".caf")
                defer { try? FileManager.default.removeItem(at: output) }
                do {
                    duration = try await Task.detached(priority: .userInitiated) { try VoiceVault.render(input, to: output) }.value
                    capturedDuration = duration
                } catch { if typed.isEmpty { throw error } }
            }
            guard duration > 0 || !typed.isEmpty else { throw VaultFailure.noAudio }
            if duration > 0 {
                _ = try await repository.commit(id: draft.id, path: draft.recordingPath ?? "", durationMillis: duration)
                if !typed.isEmpty { try await repository.editTranscript(id: draft.id, text: typed) }
            } else {
                _ = try await repository.saveText(id: UUID().uuidString, documentId: draft.documentId, passageId: draft.passageId, text: typed)
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
    }

    func discardDraft() async {
        guard !busy, let draft = captureDraft, let repository else { return }
        busy = true
        if audio.recording { _ = try? await audio.finishCapture() }
        do {
            try await repository.discard(id: draft.id)
            if let path = draft.recordingPath, let url = files?.ownedURL(path) { try? FileManager.default.removeItem(at: url) }
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
            try await repository.status(id: draft.id, value: "interrupted")
            captureDraft = nil
            capturedDuration = 0
            audio.endCaptureMode()
            try await refresh()
            inspector = true
        } catch { captureMessage = error.localizedDescription }
        busy = false
    }

    private func interruptedCapture() async {
        guard audio.recording else { return }
        if busy { interruptedWhileBusy = true; return }
        busy = true
        do { capturedDuration = try await audio.finishCapture() }
        catch { captureMessage = error.localizedDescription }
        captureMessage = "Recording stopped unexpectedly. Save the captured audio, or keep this draft for later."
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
            capturedDuration = try await Task.detached(priority: .userInitiated) { try VoiceVault.render(input, to: output) }.value
        } catch { captureMessage = error.localizedDescription }
        busy = false
    }

    func replay(_ note: VoiceNote) async {
        guard !busy, !audio.recording, let path = note.recordingPath, let files, let input = files.ownedURL(path) else { return }
        if audio.replayingNote == note.id { audio.stopReplay(); return }
        busy = true
        let output = files.transient.appendingPathComponent(UUID().uuidString + ".caf")
        do {
            _ = try await Task.detached(priority: .userInitiated) { try VoiceVault.render(input, to: output) }.value
            try audio.replay(noteId: note.id, file: output)
        } catch { try? FileManager.default.removeItem(at: output); present(error) }
        busy = false
    }

    func jump(to note: VoiceNote) async {
        if selectedId != note.documentId { await select(note.documentId) }
        audio.pause()
        if let passage = document?.passages.first(where: { $0.id == note.passageId }) { seek(passage) }
        follow = true
    }

    func edit(_ note: VoiceNote, text: String) async {
        do { try await repository?.editTranscript(id: note.id, text: text); try await refresh(); feedback.play(.saved) }
        catch { present(error) }
    }

    func retry(_ note: VoiceNote) { enqueue(note.id) }

    private func enqueue(_ id: String) {
        guard !transcriptionQueue.contains(id), activeTranscription != id else { return }
        transcriptionQueue.append(id)
        if !transcribing { Task { await processTranscriptions() } }
    }

    private func processTranscriptions() async {
        guard !transcribing, let repository, let files else { return }
        transcribing = true
        defer { transcribing = false }
        while !transcriptionQueue.isEmpty {
            let id = transcriptionQueue.removeFirst()
            guard let note = notes.first(where: { $0.id == id }), let path = note.recordingPath, let input = files.ownedURL(path), !note.isDraft else { continue }
            activeTranscription = id
            let output = files.transient.appendingPathComponent(UUID().uuidString + ".caf")
            defer { activeTranscription = nil; try? FileManager.default.removeItem(at: output) }
            do {
                try await repository.status(id: id, value: "transcribing")
                try await refresh()
                _ = try await Task.detached(priority: .utility) { try VoiceVault.render(input, to: output) }.value
                let text = try await transcriber.transcribe(output)
                try await repository.transcript(id: id, text: text, status: "transcribed")
            } catch {
                let status = error is TranscriptionFailure ? "pendingModel" : "failed"
                try? await repository.status(id: id, value: status)
            }
            try? await refresh()
        }
    }

    private func present(_ error: Error) { errorMessage = error.localizedDescription; feedback.play(.failure) }
}
