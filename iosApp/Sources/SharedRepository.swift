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
    let listeningProgress: Double?

    func withListeningProgress(_ value: Double?) -> LibraryDocument {
        LibraryDocument(id: id, title: title, filePath: filePath, sourceRevision: sourceRevision,
            pageCount: pageCount, passageCount: passageCount, extractionStatus: extractionStatus, listeningProgress: value)
    }
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
    let displayText: String
    let languageTag: String
    let mappingVersion: String
    let spokenMapping: [SpokenMapping]
    let provenance: String
}

struct SpokenMapping: Codable, Hashable {
    let spokenStart: Int
    let spokenEnd: Int
    let sourceStart: Int
    let sourceEnd: Int
    let kind: String
}

struct SourcePage: Codable {
    let pageIndex: Int
    let text: String
    let provenance: String
    let languageTag: String
    let regions: [SourceRegion]
    let geometrySpace: String

    init(pageIndex: Int, text: String, provenance: String, languageTag: String, regions: [SourceRegion], geometrySpace: String = "unspecified") {
        self.pageIndex = pageIndex; self.text = text; self.provenance = provenance
        self.languageTag = languageTag; self.regions = regions; self.geometrySpace = geometrySpace
    }
    private enum CodingKeys: String, CodingKey { case pageIndex, text, provenance, languageTag, regions, geometrySpace }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        pageIndex = try values.decode(Int.self, forKey: .pageIndex)
        text = try values.decode(String.self, forKey: .text)
        provenance = try values.decode(String.self, forKey: .provenance)
        languageTag = try values.decode(String.self, forKey: .languageTag)
        regions = try values.decode([SourceRegion].self, forKey: .regions)
        geometrySpace = try values.decodeIfPresent(String.self, forKey: .geometrySpace) ?? "unspecified"
    }
}

struct DocumentSection: Codable, Identifiable {
    let id: String
    let title: String
    let passageId: String
    let pageIndex: Int
}

struct VoiceNote: Codable, Identifiable, Hashable {
    let id: String
    let documentId: String
    let passageId: String
    let pageIndex: Int
    let sourceStart: Int
    let sourceEnd: Int
    let quote: String
    let offsetMillis: Int64
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
    let passageFraction: Double

    init(documentId: String, passageId: String, offsetMillis: Int64, passageFraction: Double = 0) {
        self.documentId = documentId; self.passageId = passageId; self.offsetMillis = offsetMillis
        self.passageFraction = passageFraction
    }
    private enum CodingKeys: String, CodingKey { case documentId, passageId, offsetMillis, passageFraction }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        documentId = try values.decode(String.self, forKey: .documentId)
        passageId = try values.decode(String.self, forKey: .passageId)
        offsetMillis = try values.decode(Int64.self, forKey: .offsetMillis)
        passageFraction = try values.decodeIfPresent(Double.self, forKey: .passageFraction) ?? 0
    }
}

struct SourceSeekTarget: Codable {
    let documentId: String
    let sourceRevision: String
    let passageId: String
    let passageFraction: Double
}

struct SearchHit: Codable, Identifiable {
    let kind: String
    let id: String
    let documentId: String
    let passageId: String
    let title: String
    let text: String
    let pageIndex: Int
    var stableId: String { kind + ":" + id }
}

struct FileRemoval: Codable { let paths: [String] }

struct LibrarySnapshot: Codable {
    let version: String
    let documents: [LibraryDocument]
    let notes: [VoiceNote]
    let positions: [PlaybackPosition]
}

struct DocumentSnapshot: Codable {
    let document: LibraryDocument
    let passages: [Passage]
    let pages: [SourcePage]
    let sections: [DocumentSection]
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
    func resolveSourceSeek(documentId: String, sourceRevision: String, fraction: Double) throws -> SourceSeekTarget {
        try decode(core.resolveSourceSeekJson(documentId: documentId, sourceRevision: sourceRevision, fraction: fraction))
    }
    func beginDocument(id: String, title: String, path: String, pageCount: Int) throws -> DocumentSnapshot {
        try decode(core.beginDocument(id: id, title: title, filePath: path, pageCount: Int32(pageCount)))
    }
    func appendPages(documentId: String, pages: [ExtractedPage], finished: Bool) throws -> DocumentSnapshot {
        let json = String(decoding: try JSONEncoder().encode(pages), as: UTF8.self)
        return try decode(core.appendDocumentPages(documentId: documentId, pagesJson: json, finished: finished))
    }
    func updateGeometry(documentId: String, pageIndex: Int, regions: [SourceRegion]) throws -> DocumentSnapshot {
        let json = String(decoding: try JSONEncoder().encode(regions), as: UTF8.self)
        return try decode(core.updatePageGeometry(documentId: documentId, pageIndex: Int32(pageIndex), regionsJson: json, geometrySpace: "cropTopLeftV1"))
    }
    func search(documentId: String = "", query: String) throws -> [SearchHit] { try decode(core.searchJson(documentId: documentId, query: query)) }
    func rename(documentId: String, title: String) throws { try core.renameDocument(documentId: documentId, title: title) }
    func deleteDocument(_ id: String) throws -> FileRemoval { try decode(core.deleteDocumentJson(documentId: id)) }
    func deleteNote(_ id: String) throws -> FileRemoval { try decode(core.deleteNoteJson(noteId: id)) }
    func pendingRemovals() throws -> [String] { let result: FileRemoval = try decode(core.pendingFileRemovalsJson()); return result.paths }
    func acknowledgeRemoval(_ path: String) throws { try core.acknowledgeFileRemoval(path: path) }
    func export(_ documentId: String) throws -> String { try core.exportDocumentJson(documentId: documentId) }
    func restore(_ json: String) throws -> DocumentSnapshot { try decode(core.restoreDocumentJson(archiveJson: json)) }
    func setSections(documentId: String, sections: [DocumentSection]) throws -> DocumentSnapshot {
        let json = String(decoding: try JSONEncoder().encode(sections), as: UTF8.self)
        return try decode(core.setDocumentSections(documentId: documentId, sectionsJson: json))
    }
    func invalidateModel(id: String, generation: String, error: String) throws { _ = try core.invalidateModel(packId: id, generation: generation, error: error) }
    func catalog() throws -> [ModelPack] { try decode(core.modelCatalogJson(platform: "ios")) }
    func catalogStatus() throws -> ModelCatalogStatus { try decode(core.modelCatalogStatusJson()) }
    func acceptCatalog(_ payload: String) throws -> Bool { try core.acceptModelCatalogJson(payloadJson: payload) }
    func installs() throws -> [ModelInstall] { try decode(core.modelInstallsJson()) }
    func beginInstall(id: String, generation: String) throws -> ModelInstall { try decode(core.beginModelInstall(packId: id, generation: generation)) }
    func updateInstall(id: String, generation: String, status: String, bytes: Int64, error: String = "") throws {
        try core.updateModelInstall(packId: id, generation: generation, status: status, bytesDownloaded: bytes, error: error)
    }
    func activate(id: String, generation: String, path: String, files: [VerifiedModelFile]) throws {
        let json = String(decoding: try JSONEncoder().encode(files), as: UTF8.self)
        try core.activateModel(packId: id, generation: generation, installedPath: path, verifiedFilesJson: json)
    }
    func removeModel(_ id: String) throws { try core.removeModel(packId: id) }

    func importDocument(id: String, title: String, filePath: String, pages: [ExtractedPage]) throws -> DocumentSnapshot {
        let json = String(decoding: try JSONEncoder().encode(pages), as: UTF8.self)
        return try decode(core.importDocument(id: id, title: title, filePath: filePath, pagesJson: json))
    }

    func saveProgress(documentId: String, passageId: String, offsetMillis: Int64 = 0, passageFraction: Double = 0) throws {
        try core.saveProgress(documentId: documentId, passageId: passageId, offsetMillis: offsetMillis, passageFraction: passageFraction)
    }

    func createDraft(id: String, documentId: String, passageId: String, recordingPath: String, offsetMillis: Int64 = 0) throws -> VoiceNote {
        try decode(core.createNoteDraft(noteId: id, documentId: documentId, passageId: passageId,
                                      recordingPath: recordingPath, offsetMillis: offsetMillis))
    }

    func commit(id: String, path: String, durationMillis: Int64) throws -> VoiceNote {
        try decode(core.commitNote(noteId: id, recordingPath: path, durationMillis: durationMillis))
    }

    func saveText(id: String, documentId: String, passageId: String, text: String, offsetMillis: Int64 = 0) throws -> VoiceNote {
        try decode(core.saveTextNote(noteId: id, documentId: documentId, passageId: passageId, text: text, offsetMillis: offsetMillis))
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
    let models: URL
    let staging: URL
    var database: URL { root.appendingPathComponent("reader.sqlite") }

    init() throws {
        let fm = FileManager.default
        root = try fm.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("eReader", isDirectory: true)
        documents = root.appendingPathComponent("Documents", isDirectory: true)
        recordings = root.appendingPathComponent("Recordings", isDirectory: true)
        models = root.appendingPathComponent("Models", isDirectory: true)
        staging = root.appendingPathComponent("ModelStaging", isDirectory: true)
        transient = try fm.url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("eReaderTransient", isDirectory: true)
        for url in [root, documents, recordings, models, staging, transient] {
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

    func modelURL(_ locator: String) -> URL? {
        let parts = locator.split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0] == "Models", parts.dropFirst().allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." }) else { return nil }
        return models.appendingPathComponent(String(parts[1]), isDirectory: true).appendingPathComponent(String(parts[2]), isDirectory: true)
    }

    func removeOwnedAsset(_ locator: String) throws {
        guard let target = ownedURL(locator) else { throw VaultFailure.format }
        let name = target.deletingPathExtension().lastPathComponent
        if locator.hasPrefix("Recordings/"), name.hasSuffix("-recovered"),
           let originalID = name.components(separatedBy: "-recovered").first, UUID(uuidString: originalID) != nil,
           let original = ownedURL("Recordings/" + originalID + ".voice"),
           FileManager.default.fileExists(atPath: original.path) { try FileManager.default.removeItem(at: original) }
        if locator.hasPrefix("Recordings/"), UUID(uuidString: name) != nil,
           let recovered = ownedURL("Recordings/" + name + "-recovered.voice"),
           FileManager.default.fileExists(atPath: recovered.path) { try FileManager.default.removeItem(at: recovered) }
        if FileManager.default.fileExists(atPath: target.path) { try FileManager.default.removeItem(at: target) }
        try FileDurability.syncDirectory(target.deletingLastPathComponent())
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
        do { try FileDurability.syncDirectory(target.deletingLastPathComponent()) }
        catch { try? FileManager.default.removeItem(at: target); throw error }
        return target
    }
}

struct ListeningCursor {
    let documentId: String
    let passageId: String
    let offsetMillis: Int64
    let passageFraction: Double
    let sourcePosition: Double
}

struct ProgressSaveFailure: LocalizedError {
    var errorDescription: String? { "Your listening position could not be saved. Try again before switching books or restoring an archive." }
}
