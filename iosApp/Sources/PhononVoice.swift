import AVFoundation
import CryptoKit
import Foundation
import PhononRuntime

actor PhononVoice {
    private var engine: PhononEngine?
    private var loadedIdentity: String?
    private var occupied = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func validate(pack: ModelPack, at folder: URL) async throws {
        await acquire(); defer { release() }
        try Task.checkCancellation()
        await close()
        do {
            let prepared = try PhononAssets.prepare(pack: pack, folder: folder)
            defer { prepared.discard() }
            let runtime = try await load(prepared.folder, identity: identity(pack, folder: folder))
            let samples = try await runtime.synthesize("Listening begins here. Notes stay with the source.", deadline: .now.advanced(by: .seconds(120)))
            guard samples.count >= 2400, samples.contains(where: { abs($0) > 0.001 }) else { throw PackFailure.integrity }
            try Task.checkCancellation()
            try PhononAssets.recordCompiled(at: prepared.folder, pack: pack)
            await close()
            try prepared.activate()
        } catch { await close(); throw error }
    }

    func render(_ passage: Passage, voice: String, pack: ModelPack, folder: URL, cache: URL) async throws -> URL {
        await acquire(); defer { release() }
        try Task.checkCancellation()
        guard pack.adapter == "phonon-english-coreml", pack.runtime == "phonon-coreml", voice == "default" else { throw PackFailure.incompatible }
        let text = try NarrationText.prepare(passage.spokenText)
        guard !text.isEmpty, text.count <= 20_000 else { throw NarrationFailure.unsupportedText }
        let encoded = try JSONEncoder().encode(["phonon-0.4.0-eos1-source-chunks-v1", NarrationText.version, identity(pack, folder: folder), passage.id, passage.spokenText, voice])
        let name = SHA256.hash(data: encoded).map { String(format: "%02x", $0) }.joined() + ".wav"
        let target = cache.appendingPathComponent(name)
        if FileManager.default.fileExists(atPath: target.path) {
            if NarrationWave.isComplete(target), let audio = try? AVAudioFile(forReading: target), audio.length > 0 { return target }
            try FileManager.default.removeItem(at: target)
        }
        let runtime = try await load(folder.appendingPathComponent("Phonon"), identity: identity(pack, folder: folder))
        guard let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 24_000, channels: 1, interleaved: false) else { throw VaultFailure.format }
        let partial = cache.appendingPathComponent(UUID().uuidString + ".wav")
        defer { try? FileManager.default.removeItem(at: partial) }
        var settings = format.settings
        settings[AVLinearPCMBitDepthKey] = 16
        settings[AVLinearPCMIsFloatKey] = false
        settings[AVLinearPCMIsNonInterleaved] = false
        var output: AVAudioFile? = try AVAudioFile(forWriting: partial, settings: settings, commonFormat: .pcmFormatFloat32, interleaved: false)
        let deadline = ContinuousClock.now.advanced(by: .seconds(120))
        var wrote = false, frameCount = 0
        func append(_ chunk: String, depth: Int = 0) async throws {
            try Task.checkCancellation()
            guard ContinuousClock.now < deadline else { throw NarrationFailure.timedOut }
            let samples: [Float]
            do { samples = try await runtime.synthesize(chunk, deadline: deadline) }
            catch PhononFailure.incomplete {
                let midpoint = chunk.count / 2
                guard depth < 12, let boundary = chunk.indices.filter({ chunk[$0].isWhitespace }).min(by: {
                    abs(chunk.distance(from: chunk.startIndex, to: $0) - midpoint) < abs(chunk.distance(from: chunk.startIndex, to: $1) - midpoint)
                }) else { throw NarrationFailure.unsupportedText }
                try await append(String(chunk[..<boundary]).trimmingCharacters(in: .whitespacesAndNewlines), depth: depth + 1)
                try await append(String(chunk[boundary...]).trimmingCharacters(in: .whitespacesAndNewlines), depth: depth + 1)
                return
            }
            catch {
                if error is CancellationError { throw error }
                if ContinuousClock.now >= deadline { throw NarrationFailure.timedOut }
                if error is PhononFailure { throw NarrationFailure.voiceFailed }
                throw error
            }
            try Task.checkCancellation()
            frameCount += samples.count + (wrote ? 1200 : 0)
            guard frameCount <= 24_000 * 600 else { throw NarrationFailure.unsupportedText }
            if wrote, let silence = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 1200), let channel = silence.floatChannelData?[0] {
                silence.frameLength = 1200; memset(channel, 0, 1200 * 4); try output?.write(from: silence)
            }
            guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)), let channel = buffer.floatChannelData?[0] else { throw VaultFailure.format }
            buffer.frameLength = buffer.frameCapacity
            samples.withUnsafeBufferPointer { channel.update(from: $0.baseAddress!, count: samples.count) }
            try output?.write(from: buffer)
            wrote = true
        }
        do {
            var start = text.startIndex
            while start < text.endIndex {
                var end = text.index(start, offsetBy: 240, limitedBy: text.endIndex) ?? text.endIndex
                if end != text.endIndex {
                    if let boundary = text[start..<end].lastIndex(where: { ".!?;".contains($0) }), boundary > start { end = text.index(after: boundary) }
                    else if let boundary = text[start..<end].lastIndex(where: \.isWhitespace), boundary > start { end = text.index(after: boundary) }
                    else if let boundary = text[end...].firstIndex(where: \.isWhitespace) { end = text.index(after: boundary) }
                    else { end = text.endIndex }
                }
                let chunk = String(text[start..<end]).trimmingCharacters(in: .whitespacesAndNewlines)
                start = end
                if !chunk.isEmpty { try await append(chunk) }
            }
            output = nil
            try Task.checkCancellation()
            guard wrote, NarrationWave.isComplete(partial) else { throw PackFailure.integrity }
            try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: partial.path)
            let handle = try FileHandle(forWritingTo: partial); try handle.synchronize(); try handle.close()
            try FileManager.default.moveItem(at: partial, to: target)
            try evictCache(cache, preserving: target)
            return target
        } catch { output = nil; await close(); throw error }
    }

    func unload() async { await acquire(); defer { release() }; await close() }

    private func acquire() async {
        if !occupied { occupied = true; return }
        await withCheckedContinuation { waiters.append($0) }
    }
    private func release() {
        if waiters.isEmpty { occupied = false } else { waiters.removeFirst().resume() }
    }
    private func identity(_ pack: ModelPack, folder: URL) -> String { pack.id + ":" + pack.version + ":" + pack.files.map(\.sha256).joined(separator: ":") + ":" + folder.path }

    private func load(_ folder: URL, identity: String) async throws -> PhononEngine {
        if loadedIdentity == identity, let engine { return engine }
        await close()
        let runtime = PhononEngine()
        do {
#if targetEnvironment(simulator)
            try await runtime.load(directory: folder, neuralEngine: false)
#else
            try await runtime.load(directory: folder, neuralEngine: true)
#endif
            try Task.checkCancellation()
            engine = runtime; loadedIdentity = identity
            return runtime
        } catch { await runtime.close(); throw error }
    }
    private func close() async {
        if let runtime = engine { await runtime.close() }
        engine = nil; loadedIdentity = nil
    }
    private func evictCache(_ cache: URL, preserving target: URL) throws {
        let files = try FileManager.default.contentsOfDirectory(at: cache, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey])
            .filter { $0.pathExtension == "wav" && $0.lastPathComponent.count == 68 }
            .sorted { ((try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) < ((try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) }
        var bytes = files.reduce(0) { $0 + ((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
        for file in files where bytes > 256 * 1024 * 1024 && file != target {
            let size = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            try? FileManager.default.removeItem(at: file); bytes -= size
        }
    }
}
