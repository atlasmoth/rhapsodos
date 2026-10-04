import AVFoundation
import CryptoKit
import Foundation
import SwiftUI
import UniformTypeIdentifiers
import ZIPFoundation

extension UTType { static let rhapsodos = UTType(exportedAs: "com.osuji.rhapsodos.document", conformingTo: .archive) }
private struct ArchiveAsset: Codable { let path: String; let sha256: String; let bytes: Int64 }
enum ArchiveFailure: LocalizedError {
    case invalid, oversized, collision
    var errorDescription: String? {
        switch self {
        case .invalid: "This archive is incomplete or failed its integrity check. Your library has not been changed."
        case .oversized: "This archive exceeds the 600 MB portable archive limit."
        case .collision: "This document is already in your library. Remove that copy before restoring it."
        }
    }
}

enum DocumentArchives {
    static func export(_ json: String, files: LocalFiles) throws -> URL {
        guard var manifest = try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any],
              var document = manifest["document"] as? [String: Any], let path = document["filePath"] as? String,
              let original = files.ownedURL(path), var notes = manifest["notes"] as? [[String: Any]] else { throw ArchiveFailure.invalid }
        let staging = files.transient.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: staging) }
        let pdf = staging.appendingPathComponent("document.pdf")
        try FileManager.default.copyItem(at: original, to: pdf)
        document["filePath"] = "document.pdf"; manifest["document"] = document
        var assets: [ArchiveAsset] = [try describe(pdf, path: "document.pdf")]
        for index in notes.indices {
            guard let locator = notes[index]["recordingPath"] as? String, !locator.isEmpty else { continue }
            guard let original = files.ownedURL(locator) else { throw ArchiveFailure.invalid }
            let portable = "audio/\(index).wav"
            let audio = staging.appendingPathComponent(portable)
            try FileManager.default.createDirectory(at: audio.deletingLastPathComponent(), withIntermediateDirectories: true)
            guard let duration = notes[index]["durationMillis"] as? NSNumber else { throw ArchiveFailure.invalid }
            _ = try VoiceVault.render(original, to: audio, expectedDurationMillis: duration.int64Value)
            assets.append(try describe(audio, path: portable)); notes[index]["recordingPath"] = portable
        }
        manifest["notes"] = notes
        guard assets.reduce(Int64(0), { $0 + $1.bytes }) <= 600 * 1024 * 1024 else { throw ArchiveFailure.oversized }
        let descriptors = try JSONSerialization.jsonObject(with: JSONEncoder().encode(assets))
        let envelope = try JSONSerialization.data(withJSONObject: ["manifest": manifest, "files": descriptors], options: [.sortedKeys])
        try envelope.write(to: staging.appendingPathComponent("manifest.json"), options: .atomic)
        let title = (document["title"] as? String ?? "Document").components(separatedBy: CharacterSet.alphanumerics.inverted).filter { !$0.isEmpty }.joined(separator: " ")
        let output = files.transient.appendingPathComponent(String(title.prefix(80)) + "-" + UUID().uuidString.prefix(8) + ".rhapsodos")
        let archive = try Archive(url: output, accessMode: .create)
        for path in ["manifest.json"] + assets.map(\.path) { try Task.checkCancellation(); try archive.addEntry(with: path, relativeTo: staging, compressionMethod: .deflate) }
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: output.path)
        return output
    }

    static func prepareRestore(_ source: URL, files: LocalFiles, existing: Set<String>) throws -> (String, [URL]) {
        let granted = source.startAccessingSecurityScopedResource(); defer { if granted { source.stopAccessingSecurityScopedResource() } }
        let archive = try Archive(url: source, accessMode: .read)
        let entries = Array(archive)
        guard entries.count <= 100_002, entries.allSatisfy({ $0.type == .file }), Set(entries.map(\.path)).count == entries.count,
              let header = archive["manifest.json"], header.uncompressedSize <= 50_000_000 else { throw ArchiveFailure.invalid }
        var envelopeData = Data()
        _ = try archive.extract(header) { bytes in guard envelopeData.count + bytes.count <= 50_000_000 else { throw ArchiveFailure.invalid }; envelopeData.append(bytes) }
        guard let envelope = try JSONSerialization.jsonObject(with: envelopeData) as? [String: Any],
              var manifest = envelope["manifest"] as? [String: Any], var document = manifest["document"] as? [String: Any],
              let id = document["id"] as? String, let list = envelope["files"], var notes = manifest["notes"] as? [[String: Any]], document["filePath"] as? String == "document.pdf" else { throw ArchiveFailure.invalid }
        guard !existing.contains(id) else { throw ArchiveFailure.collision }
        let assets = try JSONDecoder().decode([ArchiveAsset].self, from: JSONSerialization.data(withJSONObject: list))
        guard assets.count + 1 == entries.count, Set(assets.map(\.path)).count == assets.count,
              assets.allSatisfy({ ($0.path == "document.pdf" || $0.path.range(of: "^audio/[0-9]+\\.wav$", options: .regularExpression) != nil) && $0.bytes >= 0 && $0.bytes <= 600 * 1024 * 1024 && $0.sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil }),
              assets.reduce(Int64(0), { $0 + $1.bytes }) <= 600 * 1024 * 1024 else { throw ArchiveFailure.invalid }
        let recordings = notes.compactMap { $0["recordingPath"] as? String }.filter { !$0.isEmpty }
        guard Set(recordings).count == recordings.count,
              Set(assets.map(\.path)) == Set(["document.pdf"] + recordings) else { throw ArchiveFailure.invalid }
        let staging = files.transient.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: staging) }
        for asset in assets {
            try Task.checkCancellation()
            guard let entry = archive[asset.path], Int64(entry.uncompressedSize) == asset.bytes else { throw ArchiveFailure.invalid }
            let output = staging.appendingPathComponent(asset.path)
            try FileManager.default.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
            _ = try archive.extract(entry, to: output)
            let actual = try describe(output, path: asset.path)
            guard actual.sha256 == asset.sha256, actual.bytes == asset.bytes else { throw ArchiveFailure.invalid }
        }
        var installed: [URL] = []
        do {
            let localPDF = UUID().uuidString + ".pdf"
            let pdf = files.documents.appendingPathComponent(localPDF)
            try FileManager.default.copyItem(at: staging.appendingPathComponent("document.pdf"), to: pdf); installed.append(pdf)
            document["filePath"] = "Documents/" + localPDF; manifest["document"] = document
            for index in notes.indices {
                guard let portable = notes[index]["recordingPath"] as? String, !portable.isEmpty else { continue }
                guard assets.contains(where: { $0.path == portable && portable != "document.pdf" }) else { throw ArchiveFailure.invalid }
                let input = staging.appendingPathComponent(portable)
                let header = try FileHandle(forReadingFrom: input)
                let signature = try header.read(upToCount: 12); try header.close()
                guard let signature, signature.count == 12, signature.prefix(4) == Data("RIFF".utf8), signature.suffix(4) == Data("WAVE".utf8) else { throw ArchiveFailure.invalid }
                let audio = try AVAudioFile(forReading: input)
                let settings = audio.fileFormat.settings
                guard (8_000...48_000).contains(audio.fileFormat.sampleRate), (1...2).contains(audio.fileFormat.channelCount),
                      settings[AVLinearPCMBitDepthKey] as? Int == 16, settings[AVLinearPCMIsFloatKey] as? Bool != true,
                      let expected = notes[index]["durationMillis"] as? NSNumber,
                      Int64(Double(audio.length) / audio.fileFormat.sampleRate * 1000) == expected.int64Value else { throw ArchiveFailure.invalid }
                let name = UUID().uuidString + ".voice", target = files.recordings.appendingPathComponent(name)
                installed.append(target)
                _ = try VoiceVault.seal(input, to: target)
                notes[index]["recordingPath"] = "Recordings/" + name
            }
            manifest["notes"] = notes
            for target in installed { try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: target.path); let handle = try FileHandle(forWritingTo: target); try handle.synchronize(); try handle.close() }
            for directory in Set(installed.map { $0.deletingLastPathComponent() }) { try FileDurability.syncDirectory(directory) }
            return (String(decoding: try JSONSerialization.data(withJSONObject: manifest), as: UTF8.self), installed)
        } catch { for target in installed { try? FileManager.default.removeItem(at: target) }; throw error }
    }

    private static func describe(_ file: URL, path: String) throws -> ArchiveAsset {
        let handle = try FileHandle(forReadingFrom: file); defer { try? handle.close() }
        var digest = SHA256(), bytes: Int64 = 0
        while let block = try handle.read(upToCount: 1_048_576), !block.isEmpty { bytes += Int64(block.count); guard bytes <= 600 * 1024 * 1024 else { throw ArchiveFailure.oversized }; digest.update(data: block) }
        return ArchiveAsset(path: path, sha256: digest.finalize().map { String(format: "%02x", $0) }.joined(), bytes: bytes)
    }
}

struct ExportPicker: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController { UIDocumentPickerViewController(forExporting: [url], asCopy: true) }
    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}
}
