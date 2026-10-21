import Foundation

enum TranscriptionFailure: LocalizedError {
    case unavailable, empty, busy
    var errorDescription: String? {
        switch self {
        case .unavailable: "Download and select a transcription pack in Settings. Your recording is saved."
        case .empty: "No speech was recognized. Your recording is saved."
        case .busy: "Another note is being transcribed. Your recording is saved."
        }
    }
}
