import Foundation
import SharedCore

struct LibraryDocument: Codable, Identifiable, Hashable {
    let id: String
    let title: String
    let filePath: String
    let sourceRevision: String
    let pageCount: Int
    let passageCount: Int
    let extractionStatus: String
}

struct Passage: Codable, Identifiable, Hashable {
    let id: String
    let documentId: String
    let pageIndex: Int
    let pageStart: Int
    let pageEnd: Int
    let sourceStart: Int
    let sourceEnd: Int
    let sourceText: String
    let spokenText: String
}

struct VoiceNote: Codable, Identifiable, Hashable {
    let id: String
    let documentId: String
    let passageId: String
    let pageIndex: Int
    let sourceStart: Int
    let sourceEnd: Int
    let quote: String
    let recordingPath: String?
    let durationMillis: Int64
    let status: String
    let machineTranscript: String?
    let editedTranscript: String?
    let createdAt: Int64
    var transcript: String { editedTranscript ?? machineTranscript ?? "" }
    var isDraft: Bool { durationMillis == 0 && (status == "draft" || status == "interrupted") }
}

struct PlaybackPosition: Codable {
    let documentId: String
    let passageId: String
    let offsetMillis: Int64
}

struct LibrarySnapshot: Codable {
    let version: String
    let documents: [LibraryDocument]
    let notes: [VoiceNote]
    let positions: [PlaybackPosition]
}

struct DocumentSnapshot: Codable {
    let document: LibraryDocument
    let passages: [Passage]
}

actor SharedRepository {
    private let core: ReaderCore
    private let decoder = JSONDecoder()

    private init(path: String) throws {
        core = try ReaderCoreFactory.shared.open(path: path)
        _ = try core.snapshotJson()
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication, .posixPermissions: 0o600], ofItemAtPath: path)
    }

    static func open(path: String) async throws -> SharedRepository {
        try await Task.detached(priority: .userInitiated) { try SharedRepository(path: path) }.value
    }

    func snapshot() throws -> LibrarySnapshot { try decode(core.snapshotJson()) }
    func document(_ id: String) throws -> DocumentSnapshot { try decode(core.documentJson(documentId: id)) }

    func importDocument(id: String, title: String, filePath: String, pages: [ExtractedPage]) throws -> DocumentSnapshot {
        let json = String(decoding: try JSONEncoder().encode(pages), as: UTF8.self)
        return try decode(core.importDocument(id: id, title: title, filePath: filePath, pagesJson: json))
    }

    func saveProgress(documentId: String, passageId: String) throws {
        try core.saveProgress(documentId: documentId, passageId: passageId, offsetMillis: 0)
    }

    func createDraft(id: String, documentId: String, passageId: String, recordingPath: String) throws -> VoiceNote {
        try decode(core.createNoteDraft(noteId: id, documentId: documentId, passageId: passageId,
                                      recordingPath: recordingPath, offsetMillis: 0))
    }

    func commit(id: String, path: String, durationMillis: Int64) throws -> VoiceNote {
        try decode(core.commitNote(noteId: id, recordingPath: path, durationMillis: durationMillis))
    }

    func saveText(id: String, documentId: String, passageId: String, text: String) throws -> VoiceNote {
        try decode(core.saveTextNote(noteId: id, documentId: documentId, passageId: passageId, text: text, offsetMillis: 0))
    }

    func status(id: String, value: String) throws { try core.markNoteStatus(noteId: id, status: value) }
    func transcript(id: String, text: String, status: String) throws { try core.updateTranscript(noteId: id, text: text, status: status) }
    func editTranscript(id: String, text: String) throws { try core.editTranscript(noteId: id, text: text) }
    func discard(id: String) throws { try core.discardDraft(noteId: id) }

    private func decode<T: Decodable>(_ json: String) throws -> T { try decoder.decode(T.self, from: Data(json.utf8)) }
}

struct LocalFiles {
    let root: URL
    let documents: URL
    let recordings: URL
    let transient: URL
    var database: URL { root.appendingPathComponent("reader.sqlite") }

    init() throws {
        let fm = FileManager.default
        root = try fm.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("eReader", isDirectory: true)
        documents = root.appendingPathComponent("Documents", isDirectory: true)
        recordings = root.appendingPathComponent("Recordings", isDirectory: true)
        transient = try fm.url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("eReaderTransient", isDirectory: true)
        for url in [root, documents, recordings, transient] {
            try fm.createDirectory(at: url, withIntermediateDirectories: true,
                                   attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
            try fm.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication, .posixPermissions: 0o700], ofItemAtPath: url.path)
        }
        var excludedRoot = root
        var policy = URLResourceValues()
        policy.isExcludedFromBackup = true
        try excludedRoot.setResourceValues(policy)
        for url in try fm.contentsOfDirectory(at: transient, includingPropertiesForKeys: nil) {
            try? fm.removeItem(at: url)
        }
    }

    func ownedURL(_ locator: String) -> URL? {
        let parts = locator.split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 2, !parts[1].isEmpty, parts[1] != ".", parts[1] != ".." else { return nil }
        let folder: URL
        switch parts[0] {
        case "Documents": folder = documents
        case "Recordings": folder = recordings
        default: return nil
        }
        return folder.appendingPathComponent(String(parts[1]))
    }

    func importPDF(_ input: URL, id: String) throws -> URL {
        let accessed = input.startAccessingSecurityScopedResource()
        defer { if accessed { input.stopAccessingSecurityScopedResource() } }
        let target = documents.appendingPathComponent(id + ".pdf")
        let temporary = documents.appendingPathComponent(id + ".import")
        let limit = 512 * 1024 * 1024
        let sourceSize = try input.resourceValues(forKeys: [.fileSizeKey]).fileSize
        if let sourceSize, sourceSize > limit { throw PDFImportFailure.tooLarge }
        let reader = try FileHandle(forReadingFrom: input)
        defer { try? reader.close() }
        guard FileManager.default.createFile(atPath: temporary.path, contents: nil,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication, .posixPermissions: 0o600]) else { throw VaultFailure.write }
        var moved = false
        defer { if !moved { try? FileManager.default.removeItem(at: temporary) } }
        let writer = try FileHandle(forWritingTo: temporary)
        defer { try? writer.close() }
        var copied = 0
        while let chunk = try reader.read(upToCount: 1024 * 1024), !chunk.isEmpty {
            guard chunk.count <= limit - copied else { throw PDFImportFailure.tooLarge }
            try writer.write(contentsOf: chunk)
            copied += chunk.count
        }
        try writer.synchronize()
        try writer.close()
        try FileManager.default.moveItem(at: temporary, to: target)
        moved = true
        return target
    }
}
