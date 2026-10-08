import AVFoundation
import CoreML
import CryptoKit
import Foundation
import KokoroCoreML

enum NarrationFailure: LocalizedError {
    case unsupportedText, timedOut
    var errorDescription: String? {
        switch self {
        case .unsupportedText: "This English preview voice cannot pronounce part of this passage safely. Choose a built-in voice."
        case .timedOut: "This passage took too long to prepare. Choose a built-in voice, or try again when the device is cool."
        }
    }
}

enum NarrationText {
    static let version = "english-literals-v2"
    static func prepare(_ text: String) throws -> String {
        let names: [Character: String] = ["*": "asterisk", "<": "less than", ">": "greater than", "≤": "less than or equal to", "≥": "greater than or equal to", "≠": "not equal to", "×": "times", "÷": "divided by", "=": "equals", "+": "plus", "−": "minus", "±": "plus or minus", "%": "percent", "&": "and", "^": "caret", "|": "vertical bar", "~": "tilde", "∞": "infinity", "√": "square root", "α": "alpha", "β": "beta", "γ": "gamma", "δ": "delta", "θ": "theta", "λ": "lambda", "μ": "mu", "π": "pi", "σ": "sigma", "φ": "phi", "ω": "omega", "©": "copyright", "®": "registered", "™": "trademark"]
        var result = ""
        let characters = Array(text)
        for (index, character) in characters.enumerated() {
            if let name = names[character] { result += " " + name + " " }
            else if character == "-" {
                let left = characters[..<index].reversed().prefix(while: \.isLetter).count
                let right = characters[(index + 1)...].prefix(while: \.isLetter).count
                if left > 0 && right > 0 && (left > 1 || right > 1) { result.append(character) }
                else { result += " minus " }
            }
            else {
                guard !character.unicodeScalars.contains(where: { $0.properties.generalCategory == .mathSymbol || $0.properties.generalCategory == .otherSymbol || ($0.properties.generalCategory == .currencySymbol && !"$£€".contains(character)) }) else { throw NarrationFailure.unsupportedText }
                result.append(character)
            }
        }
        return result
    }
}


actor CoreMLVoice {
    private static let runtimeEdition = "fp32-fixed128-literal-source-chunks-v2"
    private var model: MLModel?
    private var loadedFolder: URL?
    private var vocabulary: [String: Int] = [:]
    private var voices: [String: [Float]] = [:]
    private var phonemizer: EnglishPhonemizer?

    func validate(at folder: URL) throws {
        guard #available(iOS 18.0, *) else { throw PackFailure.incompatible }
        unload()
        defer { unload() }
        try load(folder)
        for voice in ["af_heart", "af_bella", "am_michael"] {
            let samples = try synthesize("Listening begins here. Notes stay with the source.", voice: voice, speed: 1)
            try Task.checkCancellation()
            guard samples.count >= 2400, samples.contains(where: { abs($0) > 0.001 }) else { throw PackFailure.integrity }
        }
    }

    func render(_ passage: Passage, voice: String, pace: Float, pack: ModelPack, folder: URL, cache: URL) throws -> URL {
        guard #available(iOS 18.0, *) else { throw PackFailure.incompatible }
        guard pack.runtime == "kokoro-coreml-e2e-fp32" else { throw PackFailure.incompatible }
        try Task.checkCancellation()
        let text = try NarrationText.prepare(passage.spokenText)
        let identity = try JSONEncoder().encode([Self.runtimeEdition, NarrationText.version, pack.id, pack.version, passage.id, passage.spokenText, voice, String(pace)])
        let name = SHA256.hash(data: identity).map { String(format: "%02x", $0) }.joined() + ".wav"
        let target = cache.appendingPathComponent(name)
        if FileManager.default.fileExists(atPath: target.path) {
            if validCachedWave(target), let audio = try? AVAudioFile(forReading: target), audio.length > 0, audio.fileFormat.sampleRate == 24_000 { return target }
            try FileManager.default.removeItem(at: target)
        }
        try load(folder)
        guard let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 24_000, channels: 1, interleaved: false) else { throw VaultFailure.format }
        let partial = cache.appendingPathComponent(UUID().uuidString + ".wav")
        defer { try? FileManager.default.removeItem(at: partial) }
        var settings = format.settings
        settings[AVLinearPCMBitDepthKey] = 16
        settings[AVLinearPCMIsFloatKey] = false
        settings[AVLinearPCMIsNonInterleaved] = false
        var output: AVAudioFile? = try AVAudioFile(forWriting: partial, settings: settings, commonFormat: .pcmFormatFloat32, interleaved: false)
        let speed = min(2, max(0.5, pace / 0.5))
        let deadline = ContinuousClock.now.advanced(by: .seconds(120))
        var wrote = false
        func append(_ chunk: String, depth: Int = 0) throws {
            try Task.checkCancellation()
            guard ContinuousClock.now < deadline else { throw NarrationFailure.timedOut }
            let samples: [Float]
            do { samples = try synthesize(chunk, voice: voice, speed: speed) }
            catch is ChunkOverflow {
                guard depth < 12, let boundary = splitBoundary(chunk) else { throw NarrationFailure.unsupportedText }
                try append(String(chunk[..<boundary]).trimmingCharacters(in: .whitespacesAndNewlines), depth: depth + 1)
                try append(String(chunk[boundary...]).trimmingCharacters(in: .whitespacesAndNewlines), depth: depth + 1)
                return
            }
            if wrote, let silence = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 1200), let channel = silence.floatChannelData?[0] {
                silence.frameLength = 1200; memset(channel, 0, 1200 * 4); try output?.write(from: silence)
            }
            guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)), let channel = buffer.floatChannelData?[0] else { throw VaultFailure.format }
            buffer.frameLength = buffer.frameCapacity
            samples.withUnsafeBufferPointer { channel.update(from: $0.baseAddress!, count: samples.count) }
            try output?.write(from: buffer)
            wrote = true
        }
        var start = text.startIndex
        while start < text.endIndex {
            try Task.checkCancellation()
            var end = text.index(start, offsetBy: 120, limitedBy: text.endIndex) ?? text.endIndex
            if end != text.endIndex, let boundary = text[start..<end].lastIndex(where: { $0.isWhitespace || ".!?;".contains($0) }), boundary > start { end = text.index(after: boundary) }
            let chunk = String(text[start..<end]).trimmingCharacters(in: .whitespacesAndNewlines)
            start = end
            if !chunk.isEmpty { try append(chunk) }
        }
        output = nil
        guard wrote, try AVAudioFile(forReading: partial).length > 0 else { throw PackFailure.integrity }
        try Task.checkCancellation()
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: partial.path)
        let handle = try FileHandle(forWritingTo: partial); try handle.synchronize(); try handle.close()
        try FileManager.default.moveItem(at: partial, to: target)
        let candidates = try FileManager.default.contentsOfDirectory(at: cache, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey])
            .filter { $0.pathExtension == "wav" && $0.lastPathComponent.count == 68 && $0 != target }
            .sorted { ((try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) < ((try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) }
        var total = try FileManager.default.contentsOfDirectory(at: cache, includingPropertiesForKeys: [.fileSizeKey]).filter { $0.pathExtension == "wav" && $0.lastPathComponent.count == 68 }.reduce(Int64(0)) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
        for old in candidates where total > 256 * 1024 * 1024 { let size = Int64((try? old.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0); try? FileManager.default.removeItem(at: old); total -= size }
        return target
    }

    func unload() { model = nil; loadedFolder = nil; vocabulary = [:]; voices = [:]; phonemizer = nil }

    private func load(_ folder: URL) throws {
        if loadedFolder == folder, model != nil { return }
        unload()
        let config = MLModelConfiguration(); config.computeUnits = .cpuOnly
        let loaded = try MLModel(contentsOf: folder.appendingPathComponent("kokoro_5s.mlmodelc"), configuration: config)
        struct Vocabulary: Decodable { let vocab: [String: Int] }
        struct Voice: Decodable { let embedding: [Float] }
        let vocab = try JSONDecoder().decode(Vocabulary.self, from: Data(contentsOf: folder.appendingPathComponent("vocab_index.json"))).vocab
        var styles: [String: [Float]] = [:]
        for voice in ["af_heart", "af_bella", "am_michael"] {
            let embedding = try JSONDecoder().decode(Voice.self, from: Data(contentsOf: folder.appendingPathComponent("voices/" + voice + ".json"))).embedding
            guard embedding.count == 256, embedding.allSatisfy(\.isFinite) else { throw PackFailure.integrity }
            styles[voice] = embedding
        }
        model = loaded; vocabulary = vocab; voices = styles; phonemizer = EnglishPhonemizer(); loadedFolder = folder
    }

    private struct ChunkOverflow: Error {}
    private func validCachedWave(_ url: URL) -> Bool {
        do {
            let handle = try FileHandle(forReadingFrom: url); defer { try? handle.close() }
            let size = try handle.seekToEnd()
            guard size >= 44, size <= 256 * 1024 * 1024 else { return false }
            try handle.seek(toOffset: 0)
            guard let header = try handle.read(upToCount: 12), header.count == 12,
                  String(decoding: header[0..<4], as: UTF8.self) == "RIFF", String(decoding: header[8..<12], as: UTF8.self) == "WAVE" else { return false }
            func uint32(_ bytes: Data, _ offset: Int) -> UInt32 { bytes.withUnsafeBytes { UInt32(littleEndian: $0.loadUnaligned(fromByteOffset: offset, as: UInt32.self)) } }
            func uint16(_ bytes: Data, _ offset: Int) -> UInt16 { bytes.withUnsafeBytes { UInt16(littleEndian: $0.loadUnaligned(fromByteOffset: offset, as: UInt16.self)) } }
            guard UInt64(uint32(header, 4)) + 8 == size else { return false }
            var cursor: UInt64 = 12, formatFound = false, dataFound = false, chunks = 0
            while cursor < size {
                chunks += 1; guard chunks <= 128 else { return false }
                try handle.seek(toOffset: cursor)
                guard let chunk = try handle.read(upToCount: 8), chunk.count == 8 else { return false }
                let length = UInt64(uint32(chunk, 4)), start = cursor + 8
                guard length <= size - start, length + (length % 2) <= size - start else { return false }
                switch String(decoding: chunk[0..<4], as: UTF8.self) {
                case "fmt ":
                    guard !formatFound, (16...1024).contains(length), let fields = try handle.read(upToCount: Int(length)), fields.count == Int(length),
                          uint16(fields, 0) == 1, uint16(fields, 2) == 1, uint32(fields, 4) == 24_000, uint16(fields, 12) == 2, uint16(fields, 14) == 16 else { return false }
                    formatFound = true
                case "data":
                    guard !dataFound, length > 0, length % 2 == 0 else { return false }
                    try handle.seek(toOffset: start + length - 2)
                    guard try handle.read(upToCount: 2)?.count == 2 else { return false }
                    dataFound = true
                default: break
                }
                cursor = start + length + (length % 2)
            }
            return formatFound && dataFound && cursor == size
        } catch { return false }
    }
    private func splitBoundary(_ text: String) -> String.Index? {
        let midpoint = text.count / 2
        return text.indices.filter { text[$0].isWhitespace }.min { abs(text.distance(from: text.startIndex, to: $0) - midpoint) < abs(text.distance(from: text.startIndex, to: $1) - midpoint) }
    }
    private func synthesize(_ text: String, voice: String, speed: Float) throws -> [Float] {
        try autoreleasepool {
        try Task.checkCancellation()
        guard let model, let phonemizer, let embedding = voices[voice] else { throw PackFailure.integrity }
        let ipa = phonemizer.phonemize(text)
        guard !ipa.isEmpty else { throw NarrationFailure.unsupportedText }
        var ids = [0]
        for symbol in ipa { guard let token = vocabulary[String(symbol)] else { throw NarrationFailure.unsupportedText }; ids.append(token) }
        ids.append(0)
        guard ids.count <= 128 else { throw ChunkOverflow() }
        let input = try MLMultiArray(shape: [1, 128], dataType: .int32)
        let mask = try MLMultiArray(shape: [1, 128], dataType: .int32)
        for index in 0..<128 { input[index] = NSNumber(value: index < ids.count ? ids[index] : 0); mask[index] = NSNumber(value: index < ids.count ? 1 : 0) }
        let style = try MLMultiArray(shape: [1, 256], dataType: .float32)
        for index in 0..<256 { style[index] = NSNumber(value: embedding[index]) }
        let phases = try MLMultiArray(shape: [1, 9], dataType: .float32)
        for index in 0..<9 { phases[index] = 0 }
        let rate = try MLMultiArray(shape: [1], dataType: .float32); rate[0] = NSNumber(value: speed)
        let features = try MLDictionaryFeatureProvider(dictionary: ["input_ids": input, "attention_mask": mask, "ref_s": style, "speed": rate, "random_phases": phases])
        let result = try model.prediction(from: features)
        try Task.checkCancellation()
        guard let audio = result.featureValue(for: "audio")?.multiArrayValue,
              let length = result.featureValue(for: "audio_length_samples")?.multiArrayValue,
              let durations = result.featureValue(for: "pred_dur")?.multiArrayValue,
              audio.count == 120_000, length.count == 1, durations.count == 128 else { throw PackFailure.integrity }
        var frames = 0
        for index in 0..<128 {
            let value = durations[index].floatValue
            guard value.isFinite, value >= 0, value.rounded() == value, value < 10000, index < ids.count || value == 0 else { throw PackFailure.integrity }
            frames += Int(value)
        }
        let samples = length[0].intValue
        guard samples == frames * 600, samples > 0 else { throw PackFailure.integrity }
        // The model exposes an unclamped duration while its waveform buffer stays fixed at five seconds.
        guard samples <= 114_000 else { throw ChunkOverflow() }
        let values = (0..<samples).map { audio[$0].floatValue }
        guard values.allSatisfy(\.isFinite) else { throw PackFailure.integrity }
        return values
        }
    }
}
