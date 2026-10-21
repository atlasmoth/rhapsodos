import Combine
import Foundation

enum SpeechCapability: String, Codable, CaseIterable, Identifiable {
    case narration = "tts", transcription = "asr"
    var id: String { rawValue }
    var title: String { self == .narration ? "Reading voice" : "Transcribe voice notes" }
}

struct SpeechSetupIntent: Codable, Equatable {
    let id: UUID
    let capability: SpeechCapability
    let packId: String
    let voiceId: String?
    var paused = false
}

@MainActor
final class SpeechSetupIntents: ObservableObject {
    @Published private(set) var pending: [SpeechCapability: SpeechSetupIntent]
    private let defaults: UserDefaults
    private let key = "pendingSpeechSetup"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let saved = defaults.data(forKey: key).flatMap { try? JSONDecoder().decode([SpeechSetupIntent].self, from: $0) } ?? []
        pending = saved.reduce(into: [:]) { $0[$1.capability] = $1 }
    }

    func request(_ capability: SpeechCapability, packId: String, voiceId: String? = nil) -> SpeechSetupIntent {
        let intent = SpeechSetupIntent(id: UUID(), capability: capability, packId: packId, voiceId: voiceId)
        pending[capability] = intent
        persist()
        return intent
    }

    func contains(_ intent: SpeechSetupIntent) -> Bool { pending[intent.capability]?.id == intent.id }
    func isActive(_ intent: SpeechSetupIntent) -> Bool { contains(intent) && pending[intent.capability]?.paused == false }

    func pause(packId: String) {
        for capability in SpeechCapability.allCases where pending[capability]?.packId == packId {
            pending[capability]?.paused = true
        }
        persist()
    }

    func cancel(_ capability: SpeechCapability, matching id: UUID? = nil) {
        guard let intent = pending[capability], id == nil || intent.id == id else { return }
        pending.removeValue(forKey: capability)
        persist()
    }

    func cancel(packId: String) {
        for intent in pending.values where intent.packId == packId { cancel(intent.capability, matching: intent.id) }
    }

    private func persist() {
        defaults.set(try? JSONEncoder().encode(Array(pending.values)), forKey: key)
    }
}
