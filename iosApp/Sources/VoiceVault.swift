import AVFoundation
import CryptoKit
import Security

enum VaultFailure: LocalizedError {
    case key, format, noAudio, write
    var errorDescription: String? {
        switch self {
        case .key: "The recording key is unavailable. Unlock your device and try again."
        case .format: "This recording could not be read. Its original file has been preserved."
        case .noAudio: "No audio was captured. The attached draft is still available."
        case .write: "Recording storage failed. The draft and recoverable audio have been preserved."
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
        guard SecItemAdd(create as CFDictionary, nil) == errSecSuccess else { throw VaultFailure.key }
        return generated
    }

    static func render(_ input: URL, to output: URL) throws -> Int64 {
        let handle = try FileHandle(forReadingFrom: input)
        defer { try? handle.close() }
        guard try handle.read(upToCount: signature.count) == signature,
              let headerCount = try readLength(handle), headerCount < 2048,
              let encodedHeader = try handle.read(upToCount: headerCount) else { throw VaultFailure.format }
        let header = try JSONDecoder().decode(Header.self, from: encodedHeader)
        guard header.channels > 0, header.channels <= 2, header.sampleRate > 0,
              let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: header.sampleRate,
                                         channels: AVAudioChannelCount(header.channels), interleaved: false) else { throw VaultFailure.format }
        let audio = try AVAudioFile(forWriting: output, settings: format.settings, commonFormat: .pcmFormatFloat32, interleaved: false)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: output.path)
        let secret = try key()
        var frames: Int64 = 0
        var sequence: UInt64 = 0
        while let length = try readLength(handle) {
            guard length > 0, length < 2_000_000 else { throw VaultFailure.format }
            guard let encrypted = try handle.read(upToCount: length), encrypted.count == length else { break }
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
            try audio.write(from: buffer)
            frames += Int64(frameCount)
        }
        guard frames > 0 else { throw VaultFailure.noAudio }
        return Int64(Double(frames) / header.sampleRate * 1000)
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
        guard let bytes = try handle.read(upToCount: 4), bytes.count == 4 else { return nil }
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
    var onFailure: (() -> Void)?

    init(url: URL, format: AVAudioFormat) throws {
        guard format.channelCount > 0, format.channelCount <= 2, format.commonFormat == .pcmFormatFloat32,
              !format.isInterleaved else { throw VaultFailure.format }
        self.url = url
        sampleRate = format.sampleRate
        secret = try VoiceVault.key()
        let header = try JSONEncoder().encode(VoiceVault.Header(sampleRate: format.sampleRate, channels: Int(format.channelCount)))
        authenticatedHeader = VoiceVault.signature + header
        guard FileManager.default.createFile(atPath: url.path, contents: nil,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]) else { throw VaultFailure.write }
        handle = try FileHandle(forWritingTo: url)
        try handle.write(contentsOf: VoiceVault.signature + VoiceVault.encodedLength(header.count) + header)
        try handle.synchronize()
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
                guard let encrypted = try AES.GCM.seal(data, using: secret,
                    authenticating: authenticatedHeader + VoiceVault.encodedSequence(sequence)).combined else { throw VaultFailure.write }
                try handle.write(contentsOf: VoiceVault.encodedLength(encrypted.count) + encrypted)
                frames += Int64(count)
                sequence += 1
            } catch { failure = error; onFailure?() }
        }
    }

    func finish() async throws -> Int64 {
        try await withCheckedThrowingContinuation { continuation in
            queue.async { [self] in
                do {
                    try handle.synchronize()
                    try handle.close()
                    if let failure { throw failure }
                    guard frames > 0 else { throw VaultFailure.noAudio }
                    continuation.resume(returning: Int64(Double(frames) / sampleRate * 1000))
                } catch { continuation.resume(throwing: error) }
            }
        }
    }
}
