import CryptoKit
import Foundation
import ZIPFoundation

enum ModelArchive {
    private struct Record {
        let path: String
        let directory: Bool
        let flags: UInt16
        let method: UInt16
        let checksum: UInt32
        let compressed: UInt32
        let expanded: UInt32
        let offset: UInt32
    }

    static func extract(_ source: URL, files: [ModelAsset], to destination: URL) throws -> [VerifiedModelFile] {
        try Task.checkCancellation()
        let targetPath = destination.standardizedFileURL.resolvingSymlinksInPath().path
        let sourcePath = source.standardizedFileURL.resolvingSymlinksInPath().path
        guard sourcePath != targetPath, !sourcePath.hasPrefix(targetPath + "/"),
              try destination.deletingLastPathComponent().resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey]).isDirectory == true else { throw PackFailure.path }
        guard !files.isEmpty, files.count <= 256,
              Set(files.map(\.path)).count == files.count,
              files.allSatisfy({ validPath($0.path) && $0.bytes > 0 && $0.bytes <= 2_147_483_648 }) else { throw PackFailure.integrity }
        let expanded = files.reduce(Int64(0)) { $0 + $1.bytes }
        guard expanded <= 2_147_483_648 else { throw PackFailure.integrity }
        let expected = Dictionary(uniqueKeysWithValues: files.map { ($0.path, $0) })
        var directories = Set<String>()
        for file in files {
            var components = file.path.split(separator: "/").map(String.init)
            components.removeLast()
            while !components.isEmpty { directories.insert(components.joined(separator: "/")); components.removeLast() }
        }
        let allPaths = Array(expected.keys) + Array(directories)
        guard Set(allPaths.map { $0.precomposedStringWithCanonicalMapping.lowercased() }).count == allPaths.count else { throw PackFailure.path }
        let records = try validateStructure(source, expected: expected, directories: directories)
        let available = try destination.deletingLastPathComponent().resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage
        if let available, available < expanded + 100_000_000 { throw PackFailure.space }
        let manager = FileManager.default
        if manager.fileExists(atPath: destination.path) { try manager.removeItem(at: destination) }
        try manager.createDirectory(at: destination, withIntermediateDirectories: false)
        var completed = false
        defer { if !completed { try? manager.removeItem(at: destination) } }
        let archive = try Archive(url: source, accessMode: .read)
        var entries: [String: Entry] = [:]
        for entry in archive {
            try Task.checkCancellation()
            guard entries.count < records.count, entries[entry.path] == nil,
                  let record = records[entry.path], entry.uncompressedSize == record.expanded,
                  entry.compressedSize == record.compressed, entry.checksum == record.checksum,
                  record.directory ? entry.type == .directory : entry.type == .file else { throw PackFailure.integrity }
            entries[entry.path] = entry
        }
        guard entries.count == records.count else { throw PackFailure.integrity }
        var verified: [VerifiedModelFile] = []
        for asset in files {
            try Task.checkCancellation()
            guard let entry = entries[asset.path] else { throw PackFailure.integrity }
            let output = destination.appendingPathComponent(asset.path)
            try manager.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
            guard manager.createFile(atPath: output.path, contents: nil) else { throw PackFailure.integrity }
            let handle = try FileHandle(forWritingTo: output)
            defer { try? handle.close() }
            var digest = SHA256(), count: Int64 = 0
            let checksum = try archive.extract(entry, bufferSize: 65_536) { chunk in
                try Task.checkCancellation()
                count += Int64(chunk.count)
                guard count <= asset.bytes else { throw PackFailure.integrity }
                digest.update(data: chunk)
                try handle.write(contentsOf: chunk)
            }
            let hash = digest.finalize().map { String(format: "%02x", $0) }.joined()
            guard count == asset.bytes, hash == asset.sha256, checksum == entry.checksum else { throw PackFailure.integrity }
            try handle.synchronize(); try handle.close()
            verified.append(VerifiedModelFile(path: asset.path, sha256: hash, bytes: count))
        }
        try Task.checkCancellation()
        completed = true
        return verified
    }

    // ZIPFoundation treats unknown Unix types as files. Validate the complete ZIP structure before reading entries.
    private static func validateStructure(_ source: URL, expected: [String: ModelAsset], directories: Set<String>) throws -> [String: Record] {
        let values = try source.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
        guard values.isRegularFile == true, values.isSymbolicLink != true,
              let size = values.fileSize, size >= 22, size <= 2_147_483_648 else { throw PackFailure.integrity }
        let handle = try FileHandle(forReadingFrom: source)
        defer { try? handle.close() }
        func read(_ offset: UInt64, _ count: Int) throws -> Data {
            try Task.checkCancellation()
            guard count >= 0, offset <= UInt64(size), UInt64(count) <= UInt64(size) - offset else { throw PackFailure.integrity }
            try handle.seek(toOffset: offset)
            guard let data = try handle.read(upToCount: count), data.count == count else { throw PackFailure.integrity }
            return data
        }
        let end = try read(UInt64(size - 22), 22)
        guard u32(end, 0) == 0x06054b50, u16(end, 4) == 0, u16(end, 6) == 0,
              u16(end, 8) == u16(end, 10), u16(end, 20) == 0 else { throw PackFailure.integrity }
        let count = Int(u16(end, 10)), centralSize = UInt64(u32(end, 12)), centralOffset = UInt64(u32(end, 16))
        guard count >= expected.count, count <= expected.count + directories.count,
              centralOffset + centralSize == UInt64(size - 22) else { throw PackFailure.integrity }
        var cursor = centralOffset, records: [String: Record] = [:], normalized = Set<String>()
        for _ in 0..<count {
            let header = try read(cursor, 46)
            guard u32(header, 0) == 0x02014b50, u16(header, 6) <= 20,
                  u16(header, 30) == 0, u16(header, 32) == 0, u16(header, 34) == 0 else { throw PackFailure.integrity }
            let nameLength = Int(u16(header, 28)), flags = u16(header, 8), method = u16(header, 10)
            guard nameLength > 0, nameLength <= 1_024, flags == 0 || flags == 0x0800,
                  method == 0 || method == 8 else { throw PackFailure.integrity }
            let name = try read(cursor + 46, nameLength)
            guard let path = String(data: name, encoding: .utf8), records[path] == nil else { throw PackFailure.path }
            let directory = path.hasSuffix("/"), plain = directory ? String(path.dropLast()) : path
            guard validPath(plain), normalized.insert(plain.precomposedStringWithCanonicalMapping.lowercased()).inserted else { throw PackFailure.path }
            let host = u16(header, 4) >> 8, attributes = u32(header, 38), mode = attributes >> 16 & 0xf000
            guard host == 0 || host == 3 || host == 19 else { throw PackFailure.integrity }
            if host == 3 || host == 19 {
                guard mode == (directory ? 0x4000 : 0x8000) else { throw PackFailure.integrity }
            } else {
                guard attributes & 0x08 == 0, (attributes & 0x10 != 0) == directory else { throw PackFailure.integrity }
            }
            let compressed = u32(header, 20), expanded = u32(header, 24)
            if directory {
                guard directories.contains(plain), expanded == 0, compressed == 0, method == 0, u32(header, 16) == 0 else { throw PackFailure.integrity }
            } else {
                guard let asset = expected[path], asset.bytes == Int64(expanded), compressed > 0 else { throw PackFailure.integrity }
            }
            if method == 0 { guard compressed == expanded else { throw PackFailure.integrity } }
            records[path] = Record(path: path, directory: directory, flags: flags, method: method,
                                   checksum: u32(header, 16), compressed: compressed, expanded: expanded, offset: u32(header, 42))
            cursor += UInt64(46 + nameLength)
        }
        guard cursor == centralOffset + centralSize,
              Set(records.values.filter { !$0.directory }.map(\.path)) == Set(expected.keys) else { throw PackFailure.integrity }
        cursor = 0
        for record in records.values.sorted(by: { $0.offset < $1.offset }) {
            guard UInt64(record.offset) == cursor else { throw PackFailure.integrity }
            let header = try read(cursor, 30), name = Data(record.path.utf8)
            guard u32(header, 0) == 0x04034b50, u16(header, 4) <= 20, u16(header, 6) == record.flags,
                  u16(header, 8) == record.method, u32(header, 14) == record.checksum,
                  u32(header, 18) == record.compressed, u32(header, 22) == record.expanded,
                  Int(u16(header, 26)) == name.count, u16(header, 28) == 0,
                  try read(cursor + 30, name.count) == name else { throw PackFailure.integrity }
            cursor += UInt64(30 + name.count) + UInt64(record.compressed)
            guard cursor <= centralOffset else { throw PackFailure.integrity }
        }
        guard cursor == centralOffset else { throw PackFailure.integrity }
        return records
    }

    private static func validPath(_ value: String) -> Bool {
        !value.isEmpty && value.utf8.count <= 1_024 && !value.contains("\\") && !value.contains(":") &&
        !value.unicodeScalars.contains(where: { $0.value < 32 || $0.value == 127 }) &&
        value.split(separator: "/", omittingEmptySubsequences: false).allSatisfy { !$0.isEmpty && $0 != "." && $0 != ".." }
    }
    private static func u16(_ data: Data, _ index: Int) -> UInt16 { UInt16(data[index]) | UInt16(data[index + 1]) << 8 }
    private static func u32(_ data: Data, _ index: Int) -> UInt32 { UInt32(u16(data, index)) | UInt32(u16(data, index + 2)) << 16 }
}
