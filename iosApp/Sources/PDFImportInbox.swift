import Foundation

struct PendingPDFImport: Codable, Identifiable, Sendable {
    let id: String
    let title: String
    let createdAt: Double
    var failure: String?
    var hasCopy: Bool
}

final class PDFImportSource: @unchecked Sendable {
    let url: URL
    private let scoped: Bool

    init(url: URL) {
        self.url = url
        scoped = url.startAccessingSecurityScopedResource()
    }

    deinit { if scoped { url.stopAccessingSecurityScopedResource() } }
}

actor PDFImportInbox {
    private struct Record: Codable {
        var request: PendingPDFImport
        var copying: Bool
    }

    private let root: URL
    private let manager = FileManager.default
    private let limit = 512 * 1024 * 1024

    init() throws {
        let support = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        root = support.appendingPathComponent("eReader/IncomingPDFs", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication, .posixPermissions: 0o700])
        var excluded = root
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try excluded.setResourceValues(values)
    }

    func receive(_ source: PDFImportSource) throws -> PendingPDFImport {
        guard source.url.isFileURL else { throw PDFInboxFailure.unavailable }
        let title = source.url.deletingPathExtension().lastPathComponent.trimmingCharacters(in: .whitespacesAndNewlines)
        let request = PendingPDFImport(id: UUID().uuidString, title: title.isEmpty ? "Imported PDF" : title,
            createdAt: Date().timeIntervalSince1970, failure: nil, hasCopy: false)
        let directory = try folder(request.id)
        try manager.createDirectory(at: directory, withIntermediateDirectories: false,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication, .posixPermissions: 0o700])
        var record = Record(request: request, copying: true)
        try write(record)
        try FileDurability.syncDirectory(root)
        let partial = directory.appendingPathComponent("source.partial")
        do {
            var coordinationError: NSError?
            var copyError: Error?
            withExtendedLifetime(source) {
                NSFileCoordinator().coordinate(readingItemAt: source.url, options: .withoutChanges, error: &coordinationError) { coordinated in
                    do { try copy(coordinated, to: partial) }
                    catch { copyError = error }
                }
            }
            if let coordinationError { throw coordinationError }
            if let copyError { throw copyError }
            try manager.moveItem(at: partial, to: sourceURL(for: request))
            try FileDurability.syncDirectory(directory)
            record.copying = false
            record.request.hasCopy = true
            try write(record)
            return record.request
        } catch {
            try? manager.removeItem(at: partial)
            let failure = error is CancellationError ? PDFInboxFailure.interrupted : (error as? PDFInboxFailure) ?? .unavailable
            if !manager.fileExists(atPath: sourceURL(for: request).path) {
                record.copying = false
                record.request.failure = failure.localizedDescription
                try? write(record)
            }
            throw failure
        }
    }

    func requests() throws -> [PendingPDFImport] {
        var requests: [PendingPDFImport] = []
        for directory in try manager.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isDirectoryKey, .creationDateKey]) {
            let id = directory.lastPathComponent
            guard UUID(uuidString: id)?.uuidString == id, try directory.resourceValues(forKeys: [.isDirectoryKey]).isDirectory == true else { continue }
            let record: Record
            do { record = try read(id) }
            catch {
                guard error is DecodingError || error is PDFInboxFailure || (error as? CocoaError)?.code == .fileReadNoSuchFile else { throw error }
                let createdAt = (try? directory.resourceValues(forKeys: [.creationDateKey]).creationDate)?.timeIntervalSince1970 ?? 0
                let hasCopy = manager.fileExists(atPath: directory.appendingPathComponent("source.pdf").path)
                let damaged = Record(request: PendingPDFImport(id: id, title: "Imported PDF", createdAt: createdAt,
                    failure: (hasCopy ? PDFInboxFailure.metadata : .interrupted).localizedDescription, hasCopy: hasCopy), copying: false)
                try write(damaged)
                requests.append(damaged.request)
                continue
            }
            var recovered = record
            let hasCopy = manager.fileExists(atPath: sourceURL(for: record.request).path)
            if record.copying {
                recovered.copying = false
                recovered.request.failure = hasCopy ? nil : PDFInboxFailure.interrupted.localizedDescription
                try? manager.removeItem(at: directory.appendingPathComponent("source.partial"))
            } else if record.request.hasCopy && !hasCopy {
                recovered.request.failure = PDFInboxFailure.unavailable.localizedDescription
            }
            recovered.request.hasCopy = hasCopy
            if record.copying || recovered.request.hasCopy != record.request.hasCopy || recovered.request.failure != record.request.failure { try write(recovered) }
            requests.append(recovered.request)
        }
        return requests.sorted { $0.createdAt == $1.createdAt ? $0.id < $1.id : $0.createdAt < $1.createdAt }
    }

    func sourceURL(for request: PendingPDFImport) -> URL {
        root.appendingPathComponent(UUID(uuidString: request.id)?.uuidString ?? "invalid", isDirectory: true).appendingPathComponent("source.pdf")
    }

    func markFailed(id: String, message: String) throws {
        var record = try read(id)
        record.copying = false
        record.request.failure = message
        record.request.hasCopy = manager.fileExists(atPath: sourceURL(for: record.request).path)
        try write(record)
    }

    func retry(id: String) throws {
        var record = try read(id)
        guard manager.fileExists(atPath: sourceURL(for: record.request).path) else { throw PDFInboxFailure.unavailable }
        record.copying = false
        record.request.hasCopy = true
        record.request.failure = nil
        try write(record)
    }

    func remove(id: String) throws {
        let directory = try folder(id)
        if manager.fileExists(atPath: directory.path) { try manager.removeItem(at: directory) }
        try FileDurability.syncDirectory(root)
    }

    private func folder(_ id: String) throws -> URL {
        guard UUID(uuidString: id)?.uuidString == id else { throw PDFInboxFailure.unavailable }
        return root.appendingPathComponent(id, isDirectory: true)
    }

    private func read(_ id: String) throws -> Record {
        let file = try folder(id).appendingPathComponent("request.json")
        let record = try JSONDecoder().decode(Record.self, from: Data(contentsOf: file))
        guard record.request.id == id, record.request.createdAt.isFinite else { throw PDFInboxFailure.unavailable }
        return record
    }

    private func write(_ record: Record) throws {
        let directory = try folder(record.request.id)
        let file = directory.appendingPathComponent("request.json")
        try JSONEncoder().encode(record).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        try manager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
        let handle = try FileHandle(forWritingTo: file)
        defer { try? handle.close() }
        try handle.synchronize()
        try FileDurability.syncDirectory(directory)
    }

    private func copy(_ source: URL, to target: URL) throws {
        try Task.checkCancellation()
        let values = try source.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
        guard values.isRegularFile == true else { throw PDFInboxFailure.unavailable }
        if let size = values.fileSize, size > limit { throw PDFInboxFailure.tooLarge }
        let reader = try FileHandle(forReadingFrom: source)
        defer { try? reader.close() }
        guard manager.createFile(atPath: target.path, contents: nil,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication, .posixPermissions: 0o600]) else { throw PDFInboxFailure.unavailable }
        let writer = try FileHandle(forWritingTo: target)
        defer { try? writer.close() }
        var copied = 0
        while let data = try reader.read(upToCount: 1024 * 1024), !data.isEmpty {
            try Task.checkCancellation()
            guard data.count <= limit - copied else { throw PDFInboxFailure.tooLarge }
            try writer.write(contentsOf: data)
            copied += data.count
        }
        guard copied > 0 else { throw PDFInboxFailure.unavailable }
        try writer.synchronize()
    }
}

private enum PDFInboxFailure: LocalizedError {
    case unavailable, interrupted, tooLarge, metadata

    var errorDescription: String? {
        switch self {
        case .unavailable: "This PDF could not be copied. Open the original PDF again from Files."
        case .interrupted: "Copying this PDF was interrupted. Open the original PDF again from Files."
        case .tooLarge: "This PDF exceeds the 512 MB import limit."
        case .metadata: "This PDF's queued details could not be read. Retry to open the saved copy."
        }
    }
}
