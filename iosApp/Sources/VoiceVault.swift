import AVFoundation
import CryptoKit
import Security

enum VaultFailure: LocalizedError {
    case key, format, noAudio, write, storage, tooLarge, limit
    var errorDescription: String? {
        switch self {
        case .key: "The recording key is unavailable. Unlock your device and try again."
        case .format: "This recording could not be read. Its original file has been preserved."
        case .noAudio: "No audio was captured. The attached draft is still available."
        case .write: "Recording storage failed. The draft and recoverable audio have been preserved."
        case .storage: "There is not enough free storage to save this recording. Its original file has been preserved."
        case .tooLarge: "This recording exceeds the 512 MB local note limit. Its original file has been preserved."
        case .limit: "The 30-minute recording limit was reached. Save the captured audio, or keep this attached draft."
        }
    }
}

enum VoiceVault {
    struct Header: Codable { let sampleRate: Double; let channels: Int }
    static let signature = Data("ERVOICE1\n".utf8)

    static func key() throws -> SymmetricKey {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.osuji.ereaderapp.recordings", kSecAttrAccount as String: "vault-v1"]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query.merging([kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]) { _, b in b } as CFDictionary, &result)
        if status == errSecSuccess, let data = result as? Data { return SymmetricKey(data: data) }
        guard status == errSecItemNotFound else { throw VaultFailure.key }
        let generated = SymmetricKey(size: .bits256)
        let data = generated.withUnsafeBytes { Data($0) }
        let create = query.merging([kSecValueData as String: data,
                                   kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                                   kSecAttrSynchronizable as String: false]) { _, b in b }
        let added = SecItemAdd(create as CFDictionary, nil)
        guard added == errSecSuccess else {
            #if DEBUG
            NSLog("Recording key creation failed with OSStatus %d", added)
            #endif
            throw VaultFailure.key
        }
        return generated
    }

    static func render(_ input: URL, to output: URL, expectedDurationMillis: Int64? = nil, recoverInterrupted: Bool = false) throws -> Int64 {
        let handle = try FileHandle(forReadingFrom: input)
        defer { try? handle.close() }
        guard try handle.read(upToCount: signature.count) == signature,
              let headerCount = try readLength(handle), headerCount < 2048,
              let encodedHeader = try handle.read(upToCount: headerCount), encodedHeader.count == headerCount else { throw VaultFailure.format }
        let header = try JSONDecoder().decode(Header.self, from: encodedHeader)
        guard (1...2).contains(header.channels), header.sampleRate.isFinite, (8_000...192_000).contains(header.sampleRate),
              let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: header.sampleRate,
                                         channels: AVAudioChannelCount(header.channels), interleaved: false) else { throw VaultFailure.format }
        var settings = format.settings
        if output.pathExtension.lowercased() == "wav" {
            settings[AVLinearPCMBitDepthKey] = 16
            settings[AVLinearPCMIsFloatKey] = false
            settings[AVLinearPCMIsBigEndianKey] = false
            settings[AVLinearPCMIsNonInterleaved] = false
        }
        let audio = try AVAudioFile(forWriting: output, settings: settings, commonFormat: .pcmFormatFloat32, interleaved: false)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: output.path)
        let secret = try key()
        var frames: Int64 = 0
        var sequence: UInt64 = 0
        while true {
            try Task.checkCancellation()
            let length: Int
            do { guard let next = try readLength(handle) else { break }; length = next }
            catch { if recoverInterrupted { break }; throw error }
            guard length > 0, length < 2_000_000 else { throw VaultFailure.format }
            guard let encrypted = try handle.read(upToCount: length), encrypted.count == length else {
                if recoverInterrupted { break }
                throw VaultFailure.format
            }
            let data = try AES.GCM.open(AES.GCM.SealedBox(combined: encrypted), using: secret,
                                        authenticating: signature + encodedHeader + encodedSequence(sequence))
            sequence += 1
            let frameCount = data.count / (header.channels * MemoryLayout<Float>.size)
            guard frameCount > 0, frameCount * header.channels * 4 == data.count,
                  let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frameCount)),
                  let channels = buffer.floatChannelData else { throw VaultFailure.format }
            buffer.frameLength = AVAudioFrameCount(frameCount)
            data.withUnsafeBytes { bytes in
                for channel in 0..<header.channels {
                    memcpy(channels[channel], bytes.baseAddress!.advanced(by: channel * frameCount * 4), frameCount * 4)
                }
            }
            guard frames + Int64(frameCount) <= Int64(header.sampleRate * 1800) else { throw VaultFailure.limit }
            try audio.write(from: buffer)
            frames += Int64(frameCount)
        }
        guard frames > 0 else { throw VaultFailure.noAudio }
        let duration = Int64(Double(frames) / header.sampleRate * 1000)
        guard expectedDurationMillis == nil || expectedDurationMillis == duration else { throw VaultFailure.format }
        return duration
    }

    static func seal(_ input: URL, to output: URL) throws -> Int64 {
        let audio = try AVAudioFile(forReading: input, commonFormat: .pcmFormatFloat32, interleaved: false)
        let format = audio.processingFormat
        guard (1...2).contains(format.channelCount), format.sampleRate >= 8_000, format.sampleRate <= 192_000,
              audio.length > 0, Double(audio.length) / format.sampleRate <= 1800 else { throw VaultFailure.format }
        let expandedBytes = audio.length * Int64(format.channelCount) * 4 + ((audio.length + 8191) / 8192) * 32 + 2048
        guard expandedBytes <= 512 * 1024 * 1024 else { throw VaultFailure.tooLarge }
        let storage = try output.deletingLastPathComponent().resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey, .volumeAvailableCapacityKey])
        let available = storage.volumeAvailableCapacityForImportantUsage ?? Int64(storage.volumeAvailableCapacity ?? 0)
        guard available >= expandedBytes + 50 * 1024 * 1024 else { throw VaultFailure.storage }
        let secret = try key()
        let header = try JSONEncoder().encode(Header(sampleRate: format.sampleRate, channels: Int(format.channelCount)))
        let partial = output.appendingPathExtension("import")
        defer { try? FileManager.default.removeItem(at: partial) }
        guard FileManager.default.createFile(atPath: partial.path, contents: nil, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]) else { throw VaultFailure.write }
        let handle = try FileHandle(forWritingTo: partial)
        defer { try? handle.close() }
        try handle.write(contentsOf: signature + encodedLength(header.count) + header)
        var sequence: UInt64 = 0; var frames: Int64 = 0
        while audio.framePosition < audio.length {
            try Task.checkCancellation()
            guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 8192) else { throw VaultFailure.format }
            try audio.read(into: buffer)
            guard buffer.frameLength > 0, let channels = buffer.floatChannelData else { throw VaultFailure.format }
            var data = Data()
            for index in 0..<Int(format.channelCount) { data.append(Data(bytes: channels[index], count: Int(buffer.frameLength) * 4)) }
            guard let sealed = try AES.GCM.seal(data, using: secret, authenticating: signature + header + encodedSequence(sequence)).combined else { throw VaultFailure.write }
            try handle.write(contentsOf: encodedLength(sealed.count) + sealed)
            sequence += 1; frames += Int64(buffer.frameLength)
        }
        try handle.synchronize(); try handle.close()
        try FileManager.default.moveItem(at: partial, to: output)
        try FileDurability.syncDirectory(output.deletingLastPathComponent())
        return Int64(Double(frames) / format.sampleRate * 1000)
    }

    static func finalizeRecording(_ input: URL, recoveredDestination: URL) throws -> (url: URL, durationMillis: Int64) {
        let handle = try FileHandle(forReadingFrom: input)
        defer { try? handle.close() }
        guard try handle.read(upToCount: signature.count) == signature,
              let size = try readLength(handle), size < 2048,
              let encodedHeader = try handle.read(upToCount: size), encodedHeader.count == size else { throw VaultFailure.format }
        let header = try JSONDecoder().decode(Header.self, from: encodedHeader)
        guard (1...2).contains(header.channels), header.sampleRate.isFinite, (8_000...192_000).contains(header.sampleRate) else { throw VaultFailure.format }
        let secret = try key()
        var validEnd = try handle.offset(), frames: Int64 = 0, sequence: UInt64 = 0, incomplete = false
        while true {
            try Task.checkCancellation()
            let length: Int
            do { guard let next = try readLength(handle) else { break }; length = next }
            catch { incomplete = true; break }
            guard length > 0, length < 2_000_000 else { throw VaultFailure.format }
            guard let encrypted = try handle.read(upToCount: length), encrypted.count == length else { incomplete = true; break }
            let data = try AES.GCM.open(AES.GCM.SealedBox(combined: encrypted), using: secret,
                authenticating: signature + encodedHeader + encodedSequence(sequence))
            let count = data.count / (header.channels * 4)
            guard count > 0, count * header.channels * 4 == data.count else { throw VaultFailure.format }
            sequence += 1; frames += Int64(count); validEnd = try handle.offset()
            guard validEnd <= 512 * 1024 * 1024, Double(frames) / header.sampleRate <= 1800 else { throw VaultFailure.tooLarge }
        }
        guard frames > 0 else { throw VaultFailure.noAudio }
        let duration = Int64(Double(frames) / header.sampleRate * 1000)
        guard incomplete else { return (input, duration) }
        if FileManager.default.fileExists(atPath: recoveredDestination.path) {
            let existing = try FileHandle(forReadingFrom: recoveredDestination)
            defer { try? existing.close() }
            guard try existing.seekToEnd() == validEnd else { throw VaultFailure.format }
            try existing.seek(toOffset: 0); try handle.seek(toOffset: 0)
            var remaining = validEnd
            while remaining > 0 {
                try Task.checkCancellation()
                let count = Int(min(remaining, 1024 * 1024))
                guard let source = try handle.read(upToCount: count), source.count == count,
                      try existing.read(upToCount: count) == source else { throw VaultFailure.format }
                remaining -= UInt64(count)
            }
            let writer = try FileHandle(forWritingTo: recoveredDestination); try writer.synchronize(); try writer.close()
            try FileDurability.syncDirectory(recoveredDestination.deletingLastPathComponent())
            return (recoveredDestination, duration)
        }
        let storage = try recoveredDestination.deletingLastPathComponent().resourceValues(forKeys: [.volumeAvailableCapacityKey])
        guard Int64(storage.volumeAvailableCapacity ?? 0) >= Int64(validEnd) + 50 * 1024 * 1024 else { throw VaultFailure.storage }
        let partial = recoveredDestination.appendingPathExtension("recovery")
        defer { try? FileManager.default.removeItem(at: partial) }
        guard FileManager.default.createFile(atPath: partial.path, contents: nil,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]) else { throw VaultFailure.write }
        let writer = try FileHandle(forWritingTo: partial)
        do {
            try handle.seek(toOffset: 0)
            var remaining = validEnd
            while remaining > 0 {
                try Task.checkCancellation()
                let count = Int(min(remaining, 1024 * 1024))
                guard let block = try handle.read(upToCount: count), block.count == count else { throw VaultFailure.format }
                try writer.write(contentsOf: block); remaining -= UInt64(count)
            }
            try writer.synchronize(); try writer.close()
            try FileManager.default.moveItem(at: partial, to: recoveredDestination)
            try FileDurability.syncDirectory(recoveredDestination.deletingLastPathComponent())
        } catch { try? writer.close(); throw error }
        return (recoveredDestination, duration)
    }

    static func encodedLength(_ length: Int) -> Data {
        var value = UInt32(length).littleEndian
        return withUnsafeBytes(of: &value) { Data($0) }
    }

    static func encodedSequence(_ sequence: UInt64) -> Data {
        var value = sequence.littleEndian
        return withUnsafeBytes(of: &value) { Data($0) }
    }

    private static func readLength(_ handle: FileHandle) throws -> Int? {
        guard let bytes = try handle.read(upToCount: 4), !bytes.isEmpty else { return nil }
        guard bytes.count == 4 else { throw VaultFailure.format }
        return bytes.withUnsafeBytes { Int(UInt32(littleEndian: $0.loadUnaligned(as: UInt32.self))) }
    }
}

final class CaptureWriter: @unchecked Sendable {
    let url: URL
    private let queue = DispatchQueue(label: "com.osuji.ereaderapp.capture", qos: .userInitiated)
    private let secret: SymmetricKey
    private let handle: FileHandle
    private let sampleRate: Double
    private let authenticatedHeader: Data
    private let lock = NSLock()
    private var queuedBytes = 0
    private var failure: Error?
    private var frames: Int64 = 0
    private var sequence: UInt64 = 0
    private var writtenBytes: Int64 = 0
    private var storageCheckFrames: Int64 = 0
    var onFailure: ((Error) -> Void)?

    init(url: URL, format: AVAudioFormat) throws {
        guard format.channelCount > 0, format.channelCount <= 2, format.commonFormat == .pcmFormatFloat32,
              !format.isInterleaved, format.sampleRate.isFinite, (8_000...192_000).contains(format.sampleRate) else { throw VaultFailure.format }
        self.url = url
        sampleRate = format.sampleRate
        try Self.checkStorage(at: url, required: Int64(format.sampleRate * Double(format.channelCount) * 4 * 60))
        secret = try VoiceVault.key()
        let header = try JSONEncoder().encode(VoiceVault.Header(sampleRate: format.sampleRate, channels: Int(format.channelCount)))
        authenticatedHeader = VoiceVault.signature + header
        guard FileManager.default.createFile(atPath: url.path, contents: nil,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]) else { throw VaultFailure.write }
        handle = try FileHandle(forWritingTo: url)
        try handle.write(contentsOf: VoiceVault.signature + VoiceVault.encodedLength(header.count) + header)
        writtenBytes = Int64(VoiceVault.signature.count + 4 + header.count)
        try handle.synchronize()
        try FileDurability.syncDirectory(url.deletingLastPathComponent())
    }

    func append(_ buffer: AVAudioPCMBuffer) {
        guard let channels = buffer.floatChannelData else { return }
        let count = Int(buffer.frameLength)
        var data = Data(capacity: count * Int(buffer.format.channelCount) * 4)
        for channel in 0..<Int(buffer.format.channelCount) { data.append(Data(bytes: channels[channel], count: count * 4)) }
        lock.lock()
        queuedBytes += data.count
        let overloaded = queuedBytes > 2_000_000
        lock.unlock()
        queue.async { [self] in
            defer { lock.lock(); queuedBytes -= data.count; lock.unlock() }
            guard failure == nil else { return }
            do {
                if overloaded { throw VaultFailure.write }
                guard frames + Int64(count) <= Int64(sampleRate * 1800) else { throw VaultFailure.limit }
                guard writtenBytes + Int64(data.count) + 32 <= 512 * 1024 * 1024 else { throw VaultFailure.tooLarge }
                if frames - storageCheckFrames >= Int64(sampleRate) {
                    try Self.checkStorage(at: url, required: Int64(data.count) + 32)
                    storageCheckFrames = frames
                }
                guard let encrypted = try AES.GCM.seal(data, using: secret,
                    authenticating: authenticatedHeader + VoiceVault.encodedSequence(sequence)).combined else { throw VaultFailure.write }
                try handle.write(contentsOf: VoiceVault.encodedLength(encrypted.count) + encrypted)
                frames += Int64(count)
                writtenBytes += Int64(encrypted.count + 4)
                sequence += 1
            } catch { failure = error; onFailure?(error) }
        }
    }

    func finish() async throws -> Int64 {
        try await withCheckedThrowingContinuation { continuation in
            queue.async { [self] in
                do {
                    try handle.synchronize()
                    try handle.close()
                    try FileDurability.syncDirectory(url.deletingLastPathComponent())
                    if let failure { throw failure }
                    guard frames > 0 else { throw VaultFailure.noAudio }
                    continuation.resume(returning: Int64(Double(frames) / sampleRate * 1000))
                } catch { continuation.resume(throwing: error) }
            }
        }
    }
    private static func checkStorage(at url: URL, required: Int64) throws {
        let storage = try url.deletingLastPathComponent().resourceValues(forKeys: [.volumeAvailableCapacityKey])
        guard Int64(storage.volumeAvailableCapacity ?? 0) >= required + 50 * 1024 * 1024 else { throw VaultFailure.storage }
    }
}
