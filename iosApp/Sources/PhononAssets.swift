import CryptoKit
import Foundation
import Darwin
import ZIPFoundation

enum PhononAssets {
    private struct Asset: Codable, Equatable { let path: String; let size: Int64; let sha256: String }
    private struct Manifest: Decodable { let files: [Asset] }
    private struct Receipt: Codable { let archive: String; let system: String; let files: [Asset] }
    private static let compiled = ["flow-c414-t1.mlmodelc", "flow-c414-t48.mlmodelc", "mimi-c266.mlmodelc"]
    private static let prefix = "phonon-31m-en-coreml/"
    private static let receiptName = "compiled-receipt.json"

    struct Preparation {
        let folder: URL
        let destination: URL
        func discard() { if folder != destination { try? FileManager.default.removeItem(at: folder) } }
        func activate() throws {
            try Task.checkCancellation()
            if folder != destination {
                let backup = destination.deletingLastPathComponent().appendingPathComponent(".phonon-backup-" + UUID().uuidString)
                let existed = FileManager.default.fileExists(atPath: destination.path)
                if existed { try FileManager.default.moveItem(at: destination, to: backup) }
                do { try FileManager.default.moveItem(at: folder, to: destination) }
                catch { if existed { try? FileManager.default.moveItem(at: backup, to: destination) }; throw error }
                let descriptor = open(destination.deletingLastPathComponent().path, O_RDONLY)
                if descriptor >= 0 { _ = fsync(descriptor); close(descriptor) }
                if existed { try? FileManager.default.removeItem(at: backup) }
            }
            for abandoned in try FileManager.default.contentsOfDirectory(at: destination.deletingLastPathComponent(), includingPropertiesForKeys: nil) where PhononAssets.isTemporary(abandoned.lastPathComponent) {
                try? FileManager.default.removeItem(at: abandoned)
            }
        }
    }

    // ModelPacks verifies the complete archive against the catalogue before this adapter reads its manifest.
    static func prepare(pack: ModelPack, folder: URL) throws -> Preparation {
        guard pack.adapter == "phonon-english-coreml", pack.runtime == "phonon-coreml", pack.files.count == 1,
              let asset = pack.files.first, asset.path == "phonon-31m-en-coreml.zip" else { throw PackFailure.incompatible }
        let archive = try Archive(url: folder.appendingPathComponent(asset.path), accessMode: .read)
        var entries: [Entry] = []
        for entry in archive {
            guard entries.count < 96 else { throw PackFailure.integrity }
            entries.append(entry)
        }
        guard Set(entries.map(\.path)).count == entries.count else { throw PackFailure.integrity }
        var directories = Set<String>([prefix])
        for path in paths {
            var components = path.split(separator: "/").map(String.init)
            components.removeLast()
            while !components.isEmpty { directories.insert(prefix + components.joined(separator: "/") + "/"); components.removeLast() }
        }
        let files = entries.filter { $0.type == .file }
        guard files.count == paths.count, Set(files.map { String($0.path.dropFirst(prefix.count)) }) == paths,
              entries.allSatisfy({ ($0.type == .file && $0.path.hasPrefix(prefix) && paths.contains(String($0.path.dropFirst(prefix.count)))) || ($0.type == .directory && directories.contains($0.path)) }),
              files.allSatisfy({ $0.uncompressedSize > 0 && $0.uncompressedSize <= 64_000_000 }),
              files.reduce(UInt64(0), { $0 + $1.uncompressedSize }) <= 200_000_000 else { throw PackFailure.integrity }
        func small(_ path: String) throws -> Data {
            guard let entry = archive[prefix + path], entry.uncompressedSize <= 65_536 else { throw PackFailure.integrity }
            var data = Data()
            _ = try archive.extract(entry) { chunk in
                try Task.checkCancellation()
                guard data.count + chunk.count <= 65_536 else { throw PackFailure.integrity }
                data.append(chunk)
            }
            return data
        }
        let bundle = try small("bundle.json"), source = try small("source.json")
        var expected = try JSONDecoder().decode(Manifest.self, from: bundle).files
        guard expected.count == paths.count - 2, Set(expected.map(\.path)) == paths.subtracting(["bundle.json", "source.json"]),
              expected.allSatisfy({ $0.size > 0 && $0.size <= 64_000_000 && $0.sha256.count == 64 && $0.sha256.allSatisfy({ $0.isHexDigit && !$0.isUppercase }) }),
              expected.allSatisfy({ archive[prefix + $0.path]?.uncompressedSize == UInt64($0.size) }) else { throw PackFailure.integrity }
        expected += [Asset(path: "bundle.json", size: Int64(bundle.count), sha256: digest(bundle)), Asset(path: "source.json", size: Int64(source.count), sha256: digest(source))]
        let destination = folder.appendingPathComponent("Phonon", isDirectory: true)
        if !FileManager.default.fileExists(atPath: destination.path),
           let backup = try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil).first(where: { $0.lastPathComponent.hasPrefix(".phonon-backup-") && isTemporary($0.lastPathComponent) }) {
            try FileManager.default.moveItem(at: backup, to: destination)
        }
        if verifySources(expected, at: destination), verifyCompiled(at: destination, archive: asset.sha256) { return Preparation(folder: destination, destination: destination) }
        try Task.checkCancellation()
        let available = try folder.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage
        if let available, available < expected.reduce(Int64(0), { $0 + $1.size }) + 240_000_000 { throw PackFailure.space }
        let staging = folder.appendingPathComponent(".phonon-" + UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: false)
        var prepared = false
        defer { if !prepared { try? FileManager.default.removeItem(at: staging) } }
        for item in expected {
            try Task.checkCancellation()
            guard let entry = archive[prefix + item.path] else { throw PackFailure.integrity }
            let output = staging.appendingPathComponent(item.path)
            try FileManager.default.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
            guard FileManager.default.createFile(atPath: output.path, contents: nil) else { throw PackFailure.integrity }
            let handle = try FileHandle(forWritingTo: output)
            defer { try? handle.close() }
            var hash = SHA256(), count: Int64 = 0
            _ = try archive.extract(entry) { chunk in
                try Task.checkCancellation()
                count += Int64(chunk.count)
                guard count <= item.size else { throw PackFailure.integrity }
                hash.update(data: chunk)
                try handle.write(contentsOf: chunk)
            }
            guard count == item.size, hash.finalize().map({ String(format: "%02x", $0) }).joined() == item.sha256 else { throw PackFailure.integrity }
            try handle.synchronize(); try handle.close()
        }
        try Task.checkCancellation()
        prepared = true
        return Preparation(folder: staging, destination: destination)
    }

    private static func isTemporary(_ name: String) -> Bool {
        for prefix in [".phonon-backup-", ".phonon-"] where name.hasPrefix(prefix) {
            if UUID(uuidString: String(name.dropFirst(prefix.count))) != nil { return true }
        }
        return false
    }

    static func recordCompiled(at folder: URL, pack: ModelPack) throws {
        let files = try compiledInventory(at: folder)
        let receipt = Receipt(archive: pack.files[0].sha256, system: ProcessInfo.processInfo.operatingSystemVersionString, files: files)
        let output = folder.appendingPathComponent(receiptName)
        try JSONEncoder().encode(receipt).write(to: output, options: .atomic)
        let handle = try FileHandle(forWritingTo: output); try handle.synchronize(); try handle.close()
    }

    private static func verifySources(_ files: [Asset], at folder: URL) -> Bool {
        do {
            guard try folder.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey]).isDirectory == true,
                  try folder.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink != true else { return false }
            guard let iterator = FileManager.default.enumerator(at: folder, includingPropertiesForKeys: [.isSymbolicLinkKey, .isRegularFileKey, .isDirectoryKey], errorHandler: { _, _ in false }) else { return false }
            var count = 0
            for case let url as URL in iterator {
                count += 1; guard count <= 4096 else { return false }
                let values = try url.resourceValues(forKeys: [.isSymbolicLinkKey, .isRegularFileKey, .isDirectoryKey])
                guard values.isSymbolicLink != true else { return false }
                let relative = String(url.path.dropFirst(folder.path.count + 1))
                if values.isDirectory == true {
                    guard paths.contains(where: { $0.hasPrefix(relative + "/") }) || compiled.contains(where: { relative == $0 || relative.hasPrefix($0 + "/") }) else { return false }
                } else {
                    guard values.isRegularFile == true, paths.contains(relative) || relative == receiptName || compiled.contains(where: { relative.hasPrefix($0 + "/") }) else { return false }
                }
            }
            for item in files { guard try inventoryFile(folder.appendingPathComponent(item.path), path: item.path) == item else { return false } }
            return true
        } catch { return false }
    }

    private static func verifyCompiled(at folder: URL, archive: String) -> Bool {
        do {
            let url = folder.appendingPathComponent(receiptName)
            guard try url.resourceValues(forKeys: [.fileSizeKey, .isSymbolicLinkKey]).fileSize ?? Int.max <= 1_000_000,
                  try url.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink != true else { return false }
            let receipt = try JSONDecoder().decode(Receipt.self, from: Data(contentsOf: url))
            guard receipt.archive == archive, receipt.system == ProcessInfo.processInfo.operatingSystemVersionString else { return false }
            return try receipt.files == compiledInventory(at: folder)
        } catch { return false }
    }

    private static func compiledInventory(at folder: URL) throws -> [Asset] {
        var result: [Asset] = [], total: Int64 = 0
        for name in compiled {
            let root = folder.appendingPathComponent(name)
            let values = try root.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
            guard values.isDirectory == true, values.isSymbolicLink != true,
                  let iterator = FileManager.default.enumerator(at: root, includingPropertiesForKeys: [.isRegularFileKey, .isDirectoryKey, .isSymbolicLinkKey], options: [], errorHandler: { _, _ in false }) else { throw PackFailure.integrity }
            var count = 0
            for case let url as URL in iterator {
                try Task.checkCancellation()
                count += 1; guard count <= 1024 else { throw PackFailure.integrity }
                let values = try url.resourceValues(forKeys: [.isRegularFileKey, .isDirectoryKey, .isSymbolicLinkKey])
                guard values.isSymbolicLink != true else { throw PackFailure.integrity }
                if values.isDirectory == true { continue }
                guard values.isRegularFile == true else { throw PackFailure.integrity }
                let item = try inventoryFile(url, path: String(url.path.dropFirst(folder.path.count + 1)))
                total += item.size
                guard total <= 240_000_000 else { throw PackFailure.integrity }
                result.append(item)
            }
            guard result.contains(where: { $0.path.hasPrefix(name + "/") }) else { throw PackFailure.integrity }
        }
        return result.sorted { $0.path < $1.path }
    }

    private static func inventoryFile(_ url: URL, path: String) throws -> Asset {
        let values = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
        guard values.isRegularFile == true, values.isSymbolicLink != true, let size = values.fileSize, size >= 0, size <= 256_000_000 else { throw PackFailure.integrity }
        let handle = try FileHandle(forReadingFrom: url); defer { try? handle.close() }
        var hash = SHA256(), count: Int64 = 0
        while let data = try handle.read(upToCount: 1_048_576), !data.isEmpty {
            try Task.checkCancellation(); count += Int64(data.count)
            guard count <= size else { throw PackFailure.integrity }
            hash.update(data: data)
        }
        guard count == size else { throw PackFailure.integrity }
        return Asset(path: path, size: count, sha256: hash.finalize().map { String(format: "%02x", $0) }.joined())
    }

    private static func digest(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    private static let paths: Set<String> = [
        "bundle.json",
        "flow-c414-t1.mlpackage/Data/com.apple.CoreML/model.mlmodel",
        "flow-c414-t1.mlpackage/Data/com.apple.CoreML/weights/weight.bin",
        "flow-c414-t1.mlpackage/Manifest.json",
        "flow-c414-t48.mlpackage/Data/com.apple.CoreML/model.mlmodel",
        "flow-c414-t48.mlpackage/Data/com.apple.CoreML/weights/weight.bin",
        "flow-c414-t48.mlpackage/Manifest.json",
        "host.safetensors",
        "mimi-c266.mlpackage/Data/com.apple.CoreML/model.mlmodel",
        "mimi-c266.mlpackage/Data/com.apple.CoreML/weights/weight.bin",
        "mimi-c266.mlpackage/Manifest.json",
        "source.json",
        "tokenizer.json",
        "voices/Alfie.safetensors",
        "voices/Azgorath.safetensors",
        "voices/Bryony.safetensors",
        "voices/Captain Silas Blackwater.safetensors",
        "voices/Delia.safetensors",
        "voices/Easton.safetensors",
        "voices/Edwin.safetensors",
        "voices/Elodie-Rose.safetensors",
        "voices/Everly.safetensors",
        "voices/Fairy Godmother.safetensors",
        "voices/Fennick the Young Elf.safetensors",
        "voices/Freya.safetensors",
        "voices/Grub the Ogre.safetensors",
        "voices/Hadley.safetensors",
        "voices/Harper.safetensors",
        "voices/Hugh.safetensors",
        "voices/Imogen.safetensors",
        "voices/Juniper.safetensors",
        "voices/Lottie.safetensors",
        "voices/Maeve.safetensors",
        "voices/Master Aldric.safetensors",
        "voices/Micah.safetensors",
        "voices/Miles.safetensors",
        "voices/Morwenna of the Deep.safetensors",
        "voices/Old Mortlake.safetensors",
        "voices/Pippa.safetensors",
        "voices/Preston.safetensors",
        "voices/Russell.safetensors",
        "voices/Sir Bramwell.safetensors",
        "voices/Spencer.safetensors",
        "voices/Teddy.safetensors",
        "voices/The Snow Queen.safetensors",
        "voices/The Thing Under the Stairs.safetensors",
        "voices/Toby.safetensors",
        "voices/Trevor.safetensors",
        "voices/Vyrmathrax the Ancient.safetensors",
        "voices/Zoey.safetensors",
        "voices/default.safetensors"
    ]
}
