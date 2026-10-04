import Speech

enum TranscriptionFailure: LocalizedError {
    case unavailable, denied, timedOut, empty, busy
    var errorDescription: String? {
        switch self {
        case .unavailable: "On-device transcription is unavailable for English on this device. Your recording is saved."
        case .denied: "Speech permission is off. Your recording is saved and can be transcribed later."
        case .timedOut: "Transcription took too long. Your recording is saved; you can retry."
        case .empty: "No speech was recognized. Your recording is saved."
        case .busy: "Another note is being transcribed. Your recording is saved."
        }
    }
}

@MainActor
final class OnDeviceTranscriber {
    private var task: SFSpeechRecognitionTask?
    private var deadline: Task<Void, Never>?
    private var continuation: CheckedContinuation<String, Error>?
    private var recognizer: SFSpeechRecognizer?
    private var activeID: UUID?

    func transcribe(_ url: URL) async throws -> String {
        guard activeID == nil else { throw TranscriptionFailure.busy }
        let id = UUID()
        activeID = id
        defer { if activeID == id { activeID = nil } }
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-US")),
              recognizer.supportsOnDeviceRecognition, recognizer.isAvailable else { throw TranscriptionFailure.unavailable }
        let permission = await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0) }
        }
        try Task.checkCancellation()
        guard permission == .authorized else { throw TranscriptionFailure.denied }
        self.recognizer = recognizer
        let request = SFSpeechURLRecognitionRequest(url: url)
        request.requiresOnDeviceRecognition = true
        request.shouldReportPartialResults = false
        request.taskHint = .dictation
        return try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                self.continuation = continuation
                task = recognizer.recognitionTask(with: request) { [weak self] result, error in
                    Task { @MainActor in
                        if let result, result.isFinal {
                            let text = result.bestTranscription.formattedString.trimmingCharacters(in: .whitespacesAndNewlines)
                            self?.complete(text.isEmpty ? .failure(TranscriptionFailure.empty) : .success(text), id: id)
                        } else if let error { self?.complete(.failure(error), id: id) }
                    }
                }
                deadline = Task { [weak self] in
                    do { try await Task.sleep(for: .seconds(90)); self?.complete(.failure(TranscriptionFailure.timedOut), id: id) }
                    catch { }
                }
            }
        }, onCancel: { [weak self] in Task { @MainActor in self?.complete(.failure(CancellationError()), id: id) } })
    }

    private func complete(_ result: Result<String, Error>, id: UUID) {
        guard activeID == id, let continuation else { return }
        self.continuation = nil
        activeID = nil
        deadline?.cancel()
        deadline = nil
        task?.cancel()
        task = nil
        recognizer = nil
        continuation.resume(with: result)
    }
}
