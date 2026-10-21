import Foundation

enum TranscriptionFailure: LocalizedError {
    case unavailable, empty, busy, audio, runtime
    var errorDescription: String? {
        switch self {
        case .unavailable: "Download and select a transcription pack in Settings. Your recording is saved."
        case .empty: "No speech was recognized. Your recording is saved."
        case .audio: "This recording could not be read. Your saved recording is unchanged."
        case .runtime: "Transcription could not finish. Retry or check its download in Settings. Your recording is saved."
        case .busy: "Another note is being transcribed. Your recording is saved."
        }
    }
}
