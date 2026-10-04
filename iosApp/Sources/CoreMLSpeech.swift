import CoreML
import Foundation
import Tokenizers
import WhisperKit

actor CoreMLSpeech {
    private var recognizer: OfflineWhisper?
    private var loadedPath: URL?
    private var working = false

    func validateASR(at folder: URL) async throws {
        guard !working else { throw TranscriptionFailure.busy }
        working = true
        defer { working = false }
        let recognizer = try await load(at: folder)
        do { let _: [TranscriptionResult] = try await recognizer.transcribe(audioArray: [Float](repeating: 0, count: 16_000), decodeOptions: options); await clear() }
        catch { await clear(); throw error }
    }

    func transcribe(_ audio: URL, using folder: URL) async throws -> String {
        guard !working else { throw TranscriptionFailure.busy }
        working = true
        defer { working = false }
        try Task.checkCancellation()
        let recognizer = try await load(at: folder)
        let cancellation = SpeechCancellation()
        let results: [TranscriptionResult]
        do {
            results = try await withTaskCancellationHandler(operation: {
                try await recognizer.transcribe(audioPath: audio.path, decodeOptions: options, callback: { _ in !cancellation.cancelled })
            }, onCancel: { cancellation.cancel() })
            await clear()
            try Task.checkCancellation()
        } catch { await clear(); throw error }
        let text = results.map(\.text).joined(separator: " ").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { throw TranscriptionFailure.empty }
        return text
    }

    func unload() async { guard !working else { return }; await clear() }

    private func clear() async {
        await recognizer?.unloadModels()
        recognizer = nil
        loadedPath = nil
    }

    private var options: DecodingOptions {
        DecodingOptions(verbose: false, task: .transcribe, language: "en", temperature: 0,
            temperatureFallbackCount: 1, usePrefillPrompt: true, usePrefillCache: false,
            skipSpecialTokens: true, withoutTimestamps: true, wordTimestamps: false,
            concurrentWorkerCount: 1, chunkingStrategy: .vad)
    }

    private func load(at folder: URL) async throws -> OfflineWhisper {
        if loadedPath == folder, let recognizer { return recognizer }
        await clear()
        let config = WhisperKitConfig(model: "tiny.en", modelFolder: folder.path, tokenizerFolder: folder,
            computeOptions: ModelComputeOptions(), verbose: false, prewarm: false, load: false, download: false)
        let result = try await OfflineWhisper(config)
        try await result.loadModels()
        recognizer = result
        loadedPath = folder
        return result
    }
}

private final class OfflineWhisper: WhisperKit {
    // The upstream loader can fetch a tokenizer after a local parsing failure, even with download disabled.
    override func loadTokenizerIfNeeded() async throws {
        guard tokenizer == nil else { return }
        guard let modelFolder else { throw TranscriptionFailure.unavailable }
        let local = try await AutoTokenizer.from(modelFolder: modelFolder)
        tokenizer = try LocalWhisperTokenizer(local)
        textDecoder.isModelMultilingual = false
    }
}

private final class LocalWhisperTokenizer: WhisperTokenizer {
    private let base: any Tokenizer
    let specialTokens: SpecialTokens
    let allLanguageTokens: Set<Int>

    init(_ base: any Tokenizer) throws {
        func token(_ value: String) throws -> Int {
            guard let id = base.convertTokenToId(value) else { throw TranscriptionFailure.unavailable }
            return id
        }
        self.base = base
        specialTokens = try SpecialTokens(endToken: token("<|endoftext|>"), englishToken: token("<|en|>"),
            noSpeechToken: token("<|nospeech|>"), noTimestampsToken: token("<|notimestamps|>"),
            specialTokenBegin: token("<|endoftext|>"), startOfPreviousToken: token("<|startofprev|>"),
            startOfTranscriptToken: token("<|startoftranscript|>"), timeTokenBegin: token("<|0.00|>"),
            transcribeToken: token("<|transcribe|>"), translateToken: token("<|translate|>"),
            whitespaceToken: base.encode(text: " ", addSpecialTokens: false).first ?? 220)
        allLanguageTokens = Set((50_000..<52_000).filter {
            guard let value = base.convertIdToToken($0) else { return false }
            let code = value.dropFirst(2).dropLast(2)
            return value.hasPrefix("<|") && value.hasSuffix("|>") && (2...3).contains(code.count) && code.allSatisfy(\.isLetter)
        })
    }

    func encode(text: String) -> [Int] { base.encode(text: text, addSpecialTokens: false) }
    func decode(tokens: [Int]) -> String { base.decode(tokens: tokens) }
    func convertTokenToId(_ token: String) -> Int? { base.convertTokenToId(token) }
    func convertIdToToken(_ id: Int) -> String? { base.convertIdToToken(id) }

    func splitToWordTokens(tokenIds: [Int]) -> (words: [String], wordTokens: [[Int]]) {
        var words: [String] = []
        var groups: [[Int]] = []
        var pending: [Int] = []
        for id in tokenIds {
            pending.append(id)
            let decoded = base.decode(tokens: pending)
            if decoded.contains("\u{fffd}") { continue }
            if decoded.hasPrefix(" ") || id >= specialTokens.specialTokenBegin || words.isEmpty {
                words.append(decoded); groups.append(pending)
            } else {
                words[words.count - 1] += decoded; groups[groups.count - 1] += pending
            }
            pending.removeAll(keepingCapacity: true)
        }
        if !pending.isEmpty { words.append(base.decode(tokens: pending)); groups.append(pending) }
        return (words, groups)
    }
}

private final class SpeechCancellation: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false
    var cancelled: Bool { lock.lock(); defer { lock.unlock() }; return value }
    func cancel() { lock.lock(); value = true; lock.unlock() }
}
