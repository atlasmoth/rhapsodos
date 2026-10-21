import CryptoKit
import Foundation
import SwiftUI
import Darwin

struct ModelAsset: Codable { let path: String; let url: String; let sha256: String; let bytes: Int64 }
struct ModelPack: Codable, Identifiable {
    let id: String; let name: String; let capability: String; let platform: String
    let runtime: String; let adapter: String; let version: String; let languageTags: [String]
    let minimumOS: Int; let minimumMemoryMB: Int; let license: String; let licenseUrl: String
    let description: String; let files: [ModelAsset]; let totalBytes: Int64
    var requiredStorageBytes: Int64 { totalBytes * 4 + 100_000_000 }
    var supported: Bool {
        capability == "tts" && adapter == "phonon-english-coreml" || capability == "asr" && adapter == "whisper-tiny-en-coreml"
    }
    var eligible: Bool { supported && ProcessInfo.processInfo.operatingSystemVersion.majorVersion >= minimumOS && ProcessInfo.processInfo.physicalMemory / 1_048_576 >= minimumMemoryMB }
}
struct ModelInstall: Codable, Identifiable {
    let packId: String; let generation: String; let status: String; let bytesDownloaded: Int64
    let totalBytes: Int64; let installedPath: String; let error: String; let updatedAt: Int64
    var id: String { packId }
}
struct VerifiedModelFile: Codable { let path: String; let sha256: String; let bytes: Int64 }
struct ModelReadinessCheck {
    let completedAt: Date
    let seconds: TimeInterval
}
private struct Transfer: Codable { let packId: String; let generation: String; let fileIndex: Int }

enum PackFailure: LocalizedError {
    case incompatible, notReady, integrity, transport, path, space
    var errorDescription: String? {
        switch self {
        case .incompatible: "This pack requires a newer OS or more device memory."
        case .notReady: "Download and finish checking this pack in Settings before using it."
        case .integrity: "A model file failed verification. Resume to download it again."
        case .transport: "The model transfer could not be completed. Resume when connected."
        case .path: "The model pack contains an invalid file path."
        case .space: "There is not enough free storage to install this pack."
        }
    }
}

@MainActor
final class ModelPacks: NSObject, ObservableObject, URLSessionDownloadDelegate {
    @Published private(set) var catalog: [ModelPack] = []
    @Published private(set) var installs: [ModelInstall] = []
    @Published var message: String?
    @Published private(set) var loading = true
    @Published private(set) var cleaningUnsupported = false
    @Published private(set) var validationActive = false
    @Published private(set) var refreshingCatalog = false
    @Published private(set) var catalogStatus: ModelCatalogStatus?
    @Published private(set) var catalogMessage: String?
    @Published private(set) var checks: [String: ModelReadinessCheck] = [:]
    var onReady: ((ModelPack) -> Void)?
    private let repository: SharedRepository
    private let files: LocalFiles
    private let isIdle: () -> Bool
    private let validate: (ModelPack, URL) async throws -> Void
    private var jobs: [String: Transfer] = [:]
    private var active: [String: URLSessionDownloadTask] = [:]
    private var finalizing: Set<String> = []
    private var removing: Set<String> = []
    private var operations: [String: (generation: String, task: Task<Void, Never>)] = [:]
    private var readyChecks: [String: Task<Void, Never>] = [:]
    private var pausedGenerations: [String: String] = [:]
    private var qualified: Set<String> = []
    private var validationTasks: [String: Task<Void, Error>] = [:]
    private var progressUpdate: [String: Date] = [:]
    private var backgroundEventsFinished = false
    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.background(withIdentifier: "com.osuji.ereaderapp.models.v1")
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false
        configuration.waitsForConnectivity = true
        configuration.httpCookieStorage = nil
        configuration.urlCredentialStorage = nil
        configuration.urlCache = nil
        return URLSession(configuration: configuration, delegate: self, delegateQueue: .main)
    }()
    var backgroundCompletion: (() -> Void)?

    init(repository: SharedRepository, files: LocalFiles, isIdle: @escaping () -> Bool = { true }, validate: @escaping (ModelPack, URL) async throws -> Void) {
        self.repository = repository; self.files = files; self.isIdle = isIdle; self.validate = validate
        super.init()
        ModelBackgroundBridge.manager = self
        backgroundCompletion = ModelBackgroundBridge.pendingCompletion
        ModelBackgroundBridge.pendingCompletion = nil
        Task { await reconnect() }
    }

    func refresh() async throws {
        catalog = try await repository.catalog(); installs = try await repository.installs()
        catalogStatus = try await repository.catalogStatus()
    }
    var liveCatalogConfigured: Bool { ModelCatalogClient.configured }
    private var catalogRefreshSafe: Bool {
        !loading && !validationActive && active.isEmpty && operations.isEmpty && removing.isEmpty && isIdle()
    }
    var canRefreshCatalog: Bool { liveCatalogConfigured && !refreshingCatalog && catalogRefreshSafe }
    func refreshCatalog() async {
        guard liveCatalogConfigured else { catalogMessage = ModelCatalogFailure.configuration.localizedDescription; return }
        guard canRefreshCatalog else { catalogMessage = ModelCatalogFailure.busy.localizedDescription; return }
        refreshingCatalog = true
        catalogMessage = nil
        defer { refreshingCatalog = false }
        do {
            let payload = try await ModelCatalogClient.fetch()
            try Task.checkCancellation()
            guard catalogRefreshSafe else { throw ModelCatalogFailure.busy }
            let changed: Bool
            do { changed = try await repository.acceptCatalog(payload) }
            catch { throw ModelCatalogFailure.payload }
            try await refresh()
            catalogMessage = changed ? "Catalogue updated. Existing downloads and choices are preserved." : "Catalogue is up to date."
        } catch is CancellationError { }
        catch { catalogMessage = error is ModelCatalogFailure ? error.localizedDescription : ModelCatalogFailure.transport.localizedDescription }
    }
    func ready(capability: String, id: String? = nil) -> (ModelPack, URL)? {
        for pack in catalog where pack.capability == capability && pack.eligible && (id == nil || pack.id == id) {
            if qualified.contains(pack.id), let installation = installs.first(where: { $0.packId == pack.id && $0.status == "ready" && !$0.installedPath.isEmpty }),
               let folder = files.modelURL(installation.installedPath), FileManager.default.fileExists(atPath: folder.path) { return (pack, folder) }
        }
        return nil
    }
    private func reconnect() async {
        defer { loading = false }
        do {
            try await refresh()
            let tasks = await session.allTasks
            for task in tasks {
                guard let download = task as? URLSessionDownloadTask, let transfer = decode(task.taskDescription),
                      catalog.contains(where: { $0.id == transfer.packId && $0.supported }),
                      installs.contains(where: { $0.packId == transfer.packId && $0.generation == transfer.generation }) else { task.cancel(); continue }
                jobs[transfer.packId] = transfer; active[transfer.packId] = download
            }
            for item in installs where ["downloading", "verifying"].contains(item.status) && active[item.packId] == nil {
                try await repository.updateInstall(id: item.packId, generation: item.generation, status: "paused", bytes: item.bytesDownloaded)
            }
            try await refresh()
            await removeUnsupportedDownloads()
            await recheckReadyPacks()
        } catch { message = error.localizedDescription }
    }
    var hasUnsupportedDownloads: Bool { installs.contains { item in !catalog.contains { $0.id == item.packId && $0.supported } } }

    func removeUnsupportedDownloads() async {
        guard !refreshingCatalog, !cleaningUnsupported, isIdle() else { return }
        cleaningUnsupported = true
        message = nil
        defer { cleaningUnsupported = false }
        let unsupported = installs.filter { item in !catalog.contains { $0.id == item.packId && $0.supported } }
        for item in unsupported {
            do { try await remove(packId: item.packId) }
            catch { message = "Unused speech downloads could not be removed. Retry cleanup in the downloads sheet." }
        }
    }

    func canCheck(_ pack: ModelPack) -> Bool {
        !refreshingCatalog && !loading && !validationActive && pack.eligible && isIdle() && active.isEmpty && operations.isEmpty
            && installs.contains(where: { $0.packId == pack.id && $0.status == "ready" }) && !removing.contains(pack.id)
    }
    func checkOnDevice(_ pack: ModelPack) async {
        guard canCheck(pack) else { return }
        qualified.remove(pack.id)
        checks.removeValue(forKey: pack.id)
        await recheckReadyPacks()
    }
    func cancelRuntimeChecks() {
        readyChecks.values.forEach { $0.cancel() }
        validationTasks.values.forEach { $0.cancel() }
    }
    func cancelAndJoinRuntimeChecks() async {
        let checks = Array(readyChecks.values)
        let validations = Array(validationTasks.values)
        checks.forEach { $0.cancel() }; validations.forEach { $0.cancel() }
        for check in checks { await check.value }
        for validation in validations { _ = try? await validation.value }
    }
    func recheckReadyPacks() async {
        guard !refreshingCatalog, installs.contains(where: { item in
            item.status == "ready" && !qualified.contains(item.packId) && readyChecks[item.packId] == nil
                && catalog.contains(where: { $0.id == item.packId && $0.eligible })
        }) else { return }
        do { try await refresh() } catch { message = error.localizedDescription; return }
        for item in installs where item.status == "ready" && !qualified.contains(item.packId) {
            guard readyChecks[item.packId] == nil, !removing.contains(item.packId), operations[item.packId] == nil else { continue }
            let task = Task { await self.checkReady(item) }
            readyChecks[item.packId] = task
            updateValidationState()
            await task.value
            readyChecks.removeValue(forKey: item.packId)
            updateValidationState()
        }
    }
    private func checkReady(_ item: ModelInstall) async {
        guard UIApplication.shared.applicationState == .active else { return }
        guard let pack = catalog.first(where: { $0.id == item.packId }), pack.eligible, let folder = files.modelURL(item.installedPath) else { return }
        do {
            for asset in pack.files { _ = try await verifyAsset(try assetURL(asset.path, in: folder), asset: asset) }
            try Task.checkCancellation()
            try await runtimeCheck(pack, folder: folder)
            try Task.checkCancellation()
            try await refresh()
            guard !removing.contains(pack.id), installs.contains(where: { $0.packId == pack.id && $0.generation == item.generation && $0.status == "ready" }) else { return }
            qualified.insert(item.packId); onReady?(pack)
        } catch is CancellationError {
            // Resource preemption leaves the verified inventory intact for a later idle check.
        } catch {
            try? await repository.invalidateModel(id: pack.id, generation: item.generation, error: error.localizedDescription)
            try? await refresh()
            message = "The installed \(pack.name) pack could not be verified. Resume to repair it, or remove it."
        }
    }
    private func updateValidationState() { validationActive = !finalizing.isEmpty || !readyChecks.isEmpty }
    private func runtimeCheck(_ pack: ModelPack, folder: URL) async throws {
        try Task.checkCancellation()
        checks.removeValue(forKey: pack.id)
        let started = ProcessInfo.processInfo.systemUptime
        let task = Task { try await self.validate(pack, folder); try Task.checkCancellation() }
        validationTasks[pack.id] = task
        defer { validationTasks.removeValue(forKey: pack.id) }
        try await withTaskCancellationHandler(operation: { try await task.value }, onCancel: { task.cancel() })
        try Task.checkCancellation()
        checks[pack.id] = ModelReadinessCheck(completedAt: Date(), seconds: ProcessInfo.processInfo.systemUptime - started)
    }
    private func verifyAsset(_ url: URL, asset: ModelAsset) async throws -> VerifiedModelFile {
        let task = Task.detached(priority: .utility) { try Self.verify(url, asset: asset) }
        return try await withTaskCancellationHandler(operation: { try await task.value }, onCancel: { task.cancel() })
    }
    func download(_ pack: ModelPack) async {
        guard !refreshingCatalog, active[pack.id] == nil, operations[pack.id] == nil, !finalizing.contains(pack.id), !removing.contains(pack.id), readyChecks[pack.id] == nil else { return }
        let generation = UUID().uuidString
        let transfer = Transfer(packId: pack.id, generation: generation, fileIndex: 0)
        let task = beginOperation(transfer) {
            guard pack.eligible else { throw PackFailure.incompatible }
            let existing = self.installs.first { $0.packId == pack.id }
            _ = try await self.repository.beginInstall(id: pack.id, generation: generation)
            try Task.checkCancellation()
            if let previous = existing, ["paused", "failed"].contains(previous.status) {
                let old = try self.stage(pack.id, previous.generation), next = try self.stage(pack.id, generation)
                if FileManager.default.fileExists(atPath: old.path) { try FileManager.default.moveItem(at: old, to: next) }
                for index in pack.files.indices {
                    let oldResume = self.resumeURL(Transfer(packId: pack.id, generation: previous.generation, fileIndex: index))
                    let nextResume = self.resumeURL(Transfer(packId: pack.id, generation: generation, fileIndex: index))
                    if FileManager.default.fileExists(atPath: oldResume.path) { try FileManager.default.moveItem(at: oldResume, to: nextResume) }
                }
            }
            let free = try self.files.staging.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage ?? 0
            guard free > pack.requiredStorageBytes else { throw PackFailure.space }
            try await self.proceed(pack, generation: generation)
        }
        await task.value
    }
    private func beginOperation(_ transfer: Transfer, body: @escaping @MainActor () async throws -> Void) -> Task<Void, Never> {
        let task = Task {
            defer {
                if self.operations[transfer.packId]?.generation == transfer.generation { self.operations.removeValue(forKey: transfer.packId) }
                self.finishBackgroundEventsIfIdle()
            }
            do { try Task.checkCancellation(); try await body() }
            catch { await self.failed(transfer, error) }
        }
        operations[transfer.packId] = (transfer.generation, task)
        return task
    }
    func pause(_ pack: ModelPack) {
        if let generation = operations[pack.id]?.generation ?? jobs[pack.id]?.generation ?? installs.first(where: { $0.packId == pack.id })?.generation { pausedGenerations[pack.id] = generation }
        operations[pack.id]?.task.cancel()
        validationTasks[pack.id]?.cancel()
        guard let task = active.removeValue(forKey: pack.id), let transfer = jobs.removeValue(forKey: pack.id) else { return }
        task.cancel { [weak self] data in
            Task { @MainActor in
                guard let self else { return }
                try? await self.refresh()
                guard !self.removing.contains(pack.id), self.installs.contains(where: { $0.packId == pack.id && $0.generation == transfer.generation }) else { return }
                if let data { try? data.write(to: self.resumeURL(transfer), options: .atomic) }
                try? await self.repository.updateInstall(id: pack.id, generation: transfer.generation, status: "paused", bytes: self.installs.first(where: { $0.packId == pack.id })?.bytesDownloaded ?? 0)
                try? await self.refresh()
            }
        }
    }
    func remove(_ pack: ModelPack) async throws { try await remove(packId: pack.id) }

    private func remove(packId: String) async throws {
        guard !refreshingCatalog, !removing.contains(packId) else { throw PackFailure.transport }
        removing.insert(packId)
        qualified.remove(packId)
        checks.removeValue(forKey: packId)
        defer { removing.remove(packId) }
        active.removeValue(forKey: packId)?.cancel()
        jobs.removeValue(forKey: packId)
        let operation = operations[packId]?.task
        let readyCheck = readyChecks[packId]
        let validation = validationTasks[packId]
        operation?.cancel(); readyCheck?.cancel(); validation?.cancel()
        await operation?.value
        await readyCheck?.value
        _ = try? await validation?.value
        try await refresh()
        guard safeComponent(packId) else { throw PackFailure.path }
        do {
            let directory = files.models.appendingPathComponent(packId, isDirectory: true)
            if FileManager.default.fileExists(atPath: directory.path) { try FileManager.default.removeItem(at: directory) }
            for url in try FileManager.default.contentsOfDirectory(at: files.staging, includingPropertiesForKeys: nil)
                where Self.ownsStagingFile(url.lastPathComponent, packId: packId)
                    && !catalog.contains(where: { $0.id != packId && Self.ownsStagingFile(url.lastPathComponent, packId: $0.id) }) {
                try FileManager.default.removeItem(at: url)
            }
            try Self.syncDirectory(files.models)
            try Self.syncDirectory(files.staging)
        } catch {
            if let current = installs.first(where: { $0.packId == packId && $0.status == "ready" }) {
                try? await repository.invalidateModel(id: packId, generation: current.generation, error: "Removal could not finish. Retry removal or repair this pack. " + error.localizedDescription)
                try? await refresh()
            }
            throw error
        }
        try await repository.removeModel(packId)
        try await refresh()
    }
    nonisolated static func ownsStagingFile(_ name: String, packId: String) -> Bool {
        let prefix = packId + "-"
        guard name.hasPrefix(prefix) else { return false }
        let suffix = String(name.dropFirst(prefix.count))
        if UUID(uuidString: suffix) != nil { return true }
        if suffix.hasPrefix("old-"), UUID(uuidString: String(suffix.dropFirst(4))) != nil { return true }
        guard suffix.count > 44, suffix.hasSuffix(".resume") else { return false }
        let generation = String(suffix.prefix(36))
        let index = suffix.dropFirst(36).dropLast(7)
        return UUID(uuidString: generation) != nil && index.first == "-"
            && !index.dropFirst().isEmpty && index.dropFirst().allSatisfy(\.isNumber)
    }

    private func stage(_ id: String, _ generation: String) throws -> URL {
        guard safeComponent(id), safeComponent(generation) else { throw PackFailure.path }
        return files.staging.appendingPathComponent(id + "-" + generation, isDirectory: true)
    }
    private func assetURL(_ path: String, in folder: URL) throws -> URL {
        let pieces = path.split(separator: "/", omittingEmptySubsequences: false)
        guard !pieces.isEmpty, pieces.allSatisfy({ safeComponent(String($0)) }) else { throw PackFailure.path }
        return pieces.reduce(folder) { $0.appendingPathComponent(String($1)) }
    }
    private func safeComponent(_ value: String) -> Bool { !value.isEmpty && value != "." && value != ".." && !value.contains("/") && !value.contains("\\") && !value.contains("\0") }
    private func resumeURL(_ transfer: Transfer) -> URL { files.staging.appendingPathComponent(transfer.packId + "-" + transfer.generation + "-" + String(transfer.fileIndex) + ".resume") }
    private func resumeData(_ transfer: Transfer) -> Data? {
        let url = resumeURL(transfer)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        guard let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size > 0, size <= 1_048_576,
              let data = try? Data(contentsOf: url),
              (try? PropertyListSerialization.propertyList(from: data, options: [], format: nil)) is [String: Any] else {
            try? FileManager.default.removeItem(at: url)
            return nil
        }
        return data
    }
    private func proceed(_ pack: ModelPack, generation: String) async throws {
        try Task.checkCancellation()
        let folder = try stage(pack.id, generation)
        let locator = "Models/" + pack.id + "/" + pack.version
        guard let target = files.modelURL(locator) else { throw PackFailure.path }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var verified: [VerifiedModelFile] = []
        var activationFolder = folder
        if FileManager.default.fileExists(atPath: target.path) {
            for asset in pack.files {
                let url = try assetURL(asset.path, in: target)
                guard let item = try? await verifyAsset(url, asset: asset) else { verified = []; break }
                verified.append(item)
            }
            if verified.count == pack.files.count { activationFolder = target }
        }
        if activationFolder != target {
            for (index, asset) in pack.files.enumerated() {
                try Task.checkCancellation()
                let file = try assetURL(asset.path, in: folder)
                if FileManager.default.fileExists(atPath: file.path), let result = try? await verifyAsset(file, asset: asset) { verified.append(result); continue }
                try Task.checkCancellation()
                try? FileManager.default.removeItem(at: file)
                let transfer = Transfer(packId: pack.id, generation: generation, fileIndex: index)
                guard let url = URL(string: asset.url), url.scheme == "https", Self.allowed(url) else { throw PackFailure.transport }
                let task: URLSessionDownloadTask
                if let data = resumeData(transfer) { task = session.downloadTask(withResumeData: data) }
                else { var request = URLRequest(url: url); request.httpMethod = "GET"; task = session.downloadTask(with: request) }
                task.taskDescription = String(decoding: try JSONEncoder().encode(transfer), as: UTF8.self)
                jobs[pack.id] = transfer; active[pack.id] = task
                try await repository.updateInstall(id: pack.id, generation: generation, status: "downloading", bytes: verified.reduce(0) { $0 + $1.bytes })
                try await refresh()
                try Task.checkCancellation()
        guard !removing.contains(pack.id), pausedGenerations[pack.id] != generation, installs.contains(where: { $0.packId == pack.id && $0.generation == generation && $0.status == "downloading" }) else { throw CancellationError() }
                task.resume(); return
            }
        }
        guard !finalizing.contains(pack.id) else { return }
        finalizing.insert(pack.id); updateValidationState()
        defer { finalizing.remove(pack.id); updateValidationState() }
        try await repository.updateInstall(id: pack.id, generation: generation, status: "verifying", bytes: pack.totalBytes)
        try await refresh()
        guard UIApplication.shared.applicationState == .active else {
            try await repository.updateInstall(id: pack.id, generation: generation, status: "paused", bytes: pack.totalBytes)
            try await refresh(); return
        }
        let validationFolder = activationFolder
        try await runtimeCheck(pack, folder: validationFolder)
        try Task.checkCancellation()
        try await refresh()
        guard installs.contains(where: { $0.packId == pack.id && $0.generation == generation && $0.status == "verifying" }) else { throw CancellationError() }
        try await Task.detached(priority: .utility) { try Self.syncTree(validationFolder) }.value
        try Task.checkCancellation()
        var backup: URL?
        if activationFolder != target {
            try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            if FileManager.default.fileExists(atPath: target.path) {
                let retained = try stage(pack.id, "old-" + UUID().uuidString)
                try FileManager.default.moveItem(at: target, to: retained); backup = retained
            }
            do {
                try FileManager.default.moveItem(at: folder, to: target)
                try Self.syncDirectory(target.deletingLastPathComponent())
                try Self.syncDirectory(files.staging)
            }
            catch {
                if FileManager.default.fileExists(atPath: target.path), !FileManager.default.fileExists(atPath: folder.path) { try? FileManager.default.moveItem(at: target, to: folder) }
                if let backup { try? FileManager.default.moveItem(at: backup, to: target) }
                throw error
            }
        }
        do { try await repository.activate(id: pack.id, generation: generation, path: locator, files: verified) }
        catch {
            if activationFolder != target { try? FileManager.default.moveItem(at: target, to: folder); if let backup { try? FileManager.default.moveItem(at: backup, to: target) } }
            throw error
        }
        if let backup { try? FileManager.default.removeItem(at: backup) }
        if activationFolder == target { try? FileManager.default.removeItem(at: folder) }
        try await refresh()
        guard installs.contains(where: { $0.packId == pack.id && $0.generation == generation && $0.status == "ready" }) else { throw CancellationError() }
        qualified.insert(pack.id); onReady?(pack)
        if let versions = try? FileManager.default.contentsOfDirectory(at: target.deletingLastPathComponent(), includingPropertiesForKeys: [.isDirectoryKey, .isSymbolicLinkKey]) {
            for previous in versions where previous != target {
                let values = try? previous.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
                if values?.isDirectory == true && values?.isSymbolicLink != true { try? FileManager.default.removeItem(at: previous) }
            }
        }
    }
    nonisolated static func verify(_ url: URL, asset: ModelAsset) throws -> VerifiedModelFile {
        let handle = try FileHandle(forReadingFrom: url); defer { try? handle.close() }
        var digest = SHA256(); var count: Int64 = 0
        while let bytes = try handle.read(upToCount: 1_048_576), !bytes.isEmpty { try Task.checkCancellation(); count += Int64(bytes.count); guard count <= asset.bytes else { throw PackFailure.integrity }; digest.update(data: bytes) }
        let hash = digest.finalize().map { String(format: "%02x", $0) }.joined()
        guard count == asset.bytes, hash == asset.sha256 else { throw PackFailure.integrity }
        return VerifiedModelFile(path: asset.path, sha256: hash, bytes: count)
    }
    nonisolated private static func syncTree(_ url: URL) throws {
        guard let enumerator = FileManager.default.enumerator(at: url, includingPropertiesForKeys: [.isRegularFileKey]) else { throw PackFailure.path }
        for case let file as URL in enumerator where try file.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile == true { let handle = try FileHandle(forWritingTo: file); try handle.synchronize(); try handle.close() }
        try syncDirectory(url)
    }
    nonisolated private static func syncDirectory(_ url: URL) throws {
        let descriptor = open(url.path, O_RDONLY); guard descriptor >= 0 else { throw PackFailure.path }; defer { close(descriptor) }; guard fsync(descriptor) == 0 else { throw PackFailure.transport }
    }
    nonisolated private static func allowed(_ url: URL) -> Bool {
        guard url.scheme == "https", url.user == nil, url.password == nil, url.port == nil || url.port == 443, let host = url.host else { return false }
        return host == "huggingface.co" || host == "cdn-lfs.huggingface.co" || host == "cdn-lfs-us-1.hf.co" || host == "cdn-lfs-eu-1.hf.co" || host == "cas-bridge.xethub.hf.co" || host == "cas-server.xethub.hf.co" || host == "github.com" || host == "release-assets.githubusercontent.com" || host == "raw.githubusercontent.com"
    }
    private func decode(_ description: String?) -> Transfer? { description.flatMap { try? JSONDecoder().decode(Transfer.self, from: Data($0.utf8)) } }
    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(request.url.flatMap { Self.allowed($0) ? request : nil }) }
    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        let retained = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        do { try FileManager.default.moveItem(at: location, to: retained) }
        catch {
            Task { @MainActor [weak self] in
                guard let self, let transfer = self.decode(downloadTask.taskDescription), self.active[transfer.packId]?.taskIdentifier == downloadTask.taskIdentifier else { return }
                self.active.removeValue(forKey: transfer.packId); self.jobs.removeValue(forKey: transfer.packId)
                try? FileManager.default.removeItem(at: self.resumeURL(transfer))
                guard self.operations[transfer.packId] == nil else { return }
                await self.beginOperation(transfer) { throw error }.value
            }
            return
        }
        Task { @MainActor [weak self] in
            guard let self, let transfer = self.decode(downloadTask.taskDescription) else { try? FileManager.default.removeItem(at: retained); return }
            guard self.installs.contains(where: { $0.packId == transfer.packId && $0.generation == transfer.generation && $0.status == "downloading" }),
                  self.active[transfer.packId] == nil || self.active[transfer.packId]?.taskIdentifier == downloadTask.taskIdentifier,
                  !self.removing.contains(transfer.packId), self.pausedGenerations[transfer.packId] != transfer.generation, self.operations[transfer.packId] == nil,
                  let pack = self.catalog.first(where: { $0.id == transfer.packId }), pack.files.indices.contains(transfer.fileIndex) else { try? FileManager.default.removeItem(at: retained); return }
            let operation = self.beginOperation(transfer) {
                defer { try? FileManager.default.removeItem(at: retained) }
                try await self.refresh()
                try Task.checkCancellation()
                guard self.installs.contains(where: { $0.packId == pack.id && $0.generation == transfer.generation && $0.status == "downloading" }) else { throw CancellationError() }
                guard let response = downloadTask.response as? HTTPURLResponse, response.statusCode == 200 || response.statusCode == 206 else {
                    try? FileManager.default.removeItem(at: self.resumeURL(transfer))
                    throw PackFailure.transport
                }
                let asset = pack.files[transfer.fileIndex]
                _ = try await self.verifyAsset(retained, asset: asset)
                try Task.checkCancellation()
                let destination = try self.assetURL(asset.path, in: self.stage(pack.id, transfer.generation))
                try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
                try FileManager.default.moveItem(at: retained, to: destination)
                try? FileManager.default.removeItem(at: self.resumeURL(transfer))
                try await self.proceed(pack, generation: transfer.generation)
            }
            self.active.removeValue(forKey: pack.id); self.jobs.removeValue(forKey: pack.id)
            await operation.value
        }
    }
    private func failed(_ transfer: Transfer, _ error: Error) async {
        if let failure = error as? PackFailure, case .integrity = failure { try? FileManager.default.removeItem(at: resumeURL(transfer)) }
        try? await repository.updateInstall(id: transfer.packId, generation: transfer.generation, status: error is CancellationError ? "paused" : "failed", bytes: installs.first(where: { $0.packId == transfer.packId })?.bytesDownloaded ?? 0, error: error.localizedDescription)
        try? await refresh(); if !(error is CancellationError) { message = error.localizedDescription }
    }
    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error else { return }
        Task { @MainActor [weak self] in
            guard let self, let transfer = self.decode(task.taskDescription), self.active[transfer.packId]?.taskIdentifier == task.taskIdentifier else { return }
            self.active.removeValue(forKey: transfer.packId); self.jobs.removeValue(forKey: transfer.packId)
            if let resume = (error as NSError).userInfo[NSURLSessionDownloadTaskResumeData] as? Data { try? resume.write(to: self.resumeURL(transfer), options: .atomic) }
            else { try? FileManager.default.removeItem(at: self.resumeURL(transfer)) }
            guard self.operations[transfer.packId] == nil else { return }
            await self.beginOperation(transfer) { throw error }.value
        }
    }
    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        Task { @MainActor [weak self] in
            guard let self, let transfer = self.decode(downloadTask.taskDescription), self.active[transfer.packId]?.taskIdentifier == downloadTask.taskIdentifier,
                  let pack = self.catalog.first(where: { $0.id == transfer.packId }), pack.files.indices.contains(transfer.fileIndex) else { return }
            if totalBytesWritten > pack.files[transfer.fileIndex].bytes || totalBytesExpectedToWrite > pack.files[transfer.fileIndex].bytes {
                self.active.removeValue(forKey: pack.id); self.jobs.removeValue(forKey: pack.id)
                downloadTask.cancel()
                guard self.operations[transfer.packId] == nil else { return }
                await self.beginOperation(transfer) { throw PackFailure.integrity }.value
                return
            }
            let now = Date()
            if let last = self.progressUpdate[pack.id], now.timeIntervalSince(last) < 0.5 { return }
            self.progressUpdate[pack.id] = now
            let before = pack.files.prefix(transfer.fileIndex).reduce(Int64(0)) { $0 + $1.bytes }
            try? await self.repository.updateInstall(id: pack.id, generation: transfer.generation, status: "downloading", bytes: min(pack.totalBytes, before + totalBytesWritten))
            try? await self.refresh()
        }
    }
    private func finishBackgroundEventsIfIdle() {
        guard backgroundEventsFinished, operations.isEmpty, finalizing.isEmpty else { return }
        backgroundEventsFinished = false
        backgroundCompletion?(); backgroundCompletion = nil
    }
    nonisolated func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor [weak self] in self?.backgroundEventsFinished = true; self?.finishBackgroundEventsIfIdle() }
    }
}

@MainActor enum ModelBackgroundBridge {
    static weak var manager: ModelPacks?
    static var pendingCompletion: (() -> Void)?
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        guard identifier == "com.osuji.ereaderapp.models.v1" else { completionHandler(); return }
        if let manager = ModelBackgroundBridge.manager { manager.backgroundCompletion = completionHandler }
        else { ModelBackgroundBridge.pendingCompletion = completionHandler }
    }
}

struct SpeechSetupSheet: View {
    let capability: SpeechCapability?
    @ObservedObject var models: ModelPacks
    @EnvironmentObject private var store: ReaderStore
    @Environment(\.dismiss) private var dismiss
    @State private var detent: PresentationDetent = .large
    @State private var catalogDetails = false

    var body: some View {
        VStack(spacing: 0) {
            ReaderSheetHeader(title: capability?.title ?? "Voices and transcription") { store.feedback.play(.selection); dismiss() }
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 18) {
                    if models.loading { ProgressView("Checking available downloads…") }
                    ForEach(SpeechCapability.allCases.filter { capability == nil || $0 == capability }) { section in
                        if capability == nil { Text(section.title).font(.headline).accessibilityAddTraits(.isHeader) }
                        ForEach(models.catalog.filter { $0.capability == section.rawValue }) { pack in
                            SpeechPackCard(pack: pack, models: models)
                        }
                    }
                    if models.liveCatalogConfigured {
                        DisclosureGroup("Catalogue", isExpanded: $catalogDetails) {
                            VStack(alignment: .leading, spacing: 12) {
                                if let status = models.catalogStatus, status.acceptedAt > 0 {
                                    Text("Revision \(status.revision)").font(.caption)
                                }
                                Button("Refresh catalogue", systemImage: "arrow.clockwise") { store.feedback.play(.selection); Task { await models.refreshCatalog() } }
                                    .frame(minHeight: 44).disabled(!models.canRefreshCatalog)
                                if models.refreshingCatalog { ProgressView("Refreshing…") }
                                if let message = models.catalogMessage { Text(message).font(.caption).foregroundStyle(Palette.muted) }
                            }.padding(.top, 12)
                        }.padding(18).background(Palette.soft, in: RoundedRectangle(cornerRadius: 20))
                    }
                    if models.hasUnsupportedDownloads {
                        Button("Retry removing unused downloads") { store.feedback.play(.selection); Task { await models.removeUnsupportedDownloads() } }
                            .frame(minHeight: 44).disabled(models.cleaningUnsupported || store.busy)
                    }
                }.padding(20).frame(maxWidth: 640).frame(maxWidth: .infinity)
            }
        }.background(Palette.paper).foregroundStyle(Palette.ink)
            .presentationDetents([.medium, .large], selection: $detent).presentationDragIndicator(.visible)
            .onChange(of: catalogDetails) { _, _ in store.feedback.play(.selection) }
            .alert("Speech download", isPresented: Binding(get: { models.message != nil }, set: { if !$0 { models.message = nil } })) {
                Button("OK") { models.message = nil }
            } message: { Text(models.message ?? "") }
    }
}

private struct SpeechPackCard: View {
    let pack: ModelPack
    @ObservedObject var models: ModelPacks
    @EnvironmentObject private var store: ReaderStore
    @EnvironmentObject private var audio: AudioCoordinator
    @State private var details = false
    private var install: ModelInstall? { models.installs.first { $0.packId == pack.id } }
    private var pending: SpeechSetupIntent? { store.speechSetup.pending.values.first { $0.packId == pack.id } }
    private var ready: Bool { models.ready(capability: pack.capability, id: pack.id) != nil }
    private var selected: Bool { store.speechPackSelected(pack) }
    private var paused: Bool { pending?.paused == true || install?.status == "paused" }
    private var working: Bool { !paused && install?.status != "failed" && (pending != nil || ["downloading", "verifying"].contains(install?.status ?? "") || install?.status == "ready" && !ready) }
    private var checkingExisting: Bool { !paused && install?.status == "ready" && !ready && pending == nil && (pack.capability == "tts" ? DownloadedVoice(audio.selectedVoice)?.packId == pack.id : store.selectedTranscription == pack.id) }
    private var actionTitle: String {
        if paused { return "Resume" }
        if checkingExisting { return "Checking…" }
        if working && pending != nil { return "Pause" }
        if selected { return pack.capability == "tts" ? "Selected" : "On" }
        if install?.status == "failed" { return "Retry" }
        if ready { return pack.capability == "tts" ? "Use voice" : "Enable" }
        return pack.capability == "tts" ? "Download & use" : "Download & enable"
    }
    private var actionDisabled: Bool {
        selected && pending == nil && !paused || checkingExisting || !pack.eligible || models.loading || models.refreshingCatalog
    }
    private var removalUnavailable: Bool {
        store.busy || store.loading || store.preparingDocument || audio.playing || audio.replayingNote != nil
            || audio.recording || audio.capturePending || store.captureDraft != nil || models.loading || models.refreshingCatalog
    }
    var body: some View {
        ReaderSheetCard(title: pack.speechName) {
            Text("\(pack.speechLanguages) · \(size(pack.totalBytes))").font(.subheadline).foregroundStyle(Palette.muted)
            if !pack.eligible {
                Text("Unavailable on this device").font(.subheadline).foregroundStyle(Palette.muted)
            } else if paused || working && !checkingExisting || install?.status == "failed" {
                Text(store.speechPackStatus(pack)).font(.subheadline).foregroundStyle(Palette.accent)
            }
            if let install, ["downloading", "paused"].contains(install.status) {
                ProgressView(value: Double(install.bytesDownloaded), total: Double(max(1, install.totalBytes)))
                Text("\(size(install.bytesDownloaded)) of \(size(pack.totalBytes))").font(.caption).foregroundStyle(Palette.muted)
            } else if working && !ready { ProgressView().accessibilityLabel("Checking speech download") }
            if let install, !install.error.isEmpty { Text(install.error).font(.caption).foregroundStyle(.red) }
            Button {
                store.feedback.play(.selection)
                if working && !paused && pending != nil { store.pauseSpeechSetup(pack) }
                else { store.startSpeechSetup(pack) }
            } label: {
                Text(actionTitle).font(.headline).foregroundStyle(actionDisabled ? Palette.muted : Palette.onAccent)
                    .multilineTextAlignment(.center).frame(maxWidth: .infinity, minHeight: 44)
            }.buttonStyle(.borderedProminent).buttonBorderShape(.capsule)
                .disabled(actionDisabled)
            DisclosureGroup("Details", isExpanded: $details) {
                VStack(alignment: .leading, spacing: 12) {
                    Text("\(pack.runtime) · \(pack.version)").font(.caption)
                    Text("Requires iOS \(pack.minimumOS)+, \(size(Int64(pack.minimumMemoryMB) * 1_048_576)) device memory and \(size(pack.requiredStorageBytes)) free storage.")
                        .font(.caption).foregroundStyle(Palette.muted)
                    if let check = models.checks[pack.id] {
                        Text("Last check: \(check.completedAt.formatted(date: .omitted, time: .shortened)) · \(check.seconds.formatted(.number.precision(.fractionLength(1)))) s").font(.caption).foregroundStyle(Palette.muted)
                    }
                    if install?.status == "ready" {
                        Button("Check on this device", systemImage: "checkmark.shield") { store.feedback.play(.selection); Task { await models.checkOnDevice(pack) } }
                            .frame(minHeight: 44).disabled(!models.canCheck(pack))
                    }
                    if install != nil {
                        Button("Remove downloaded files", role: .destructive) { store.feedback.play(.selection); Task { await store.removePack(pack) } }
                            .frame(minHeight: 44).disabled(removalUnavailable)
                    }
                }.frame(maxWidth: .infinity, alignment: .leading).padding(.top, 12)
            }.padding(.top, 4)
        }.onChange(of: details) { _, _ in store.feedback.play(.selection) }
    }
    private func size(_ bytes: Int64) -> String { ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file) }
}

extension ModelPack {
    var speechName: String { name.components(separatedBy: " • ").first ?? name }
    var speechLanguages: String {
        languageTags.map { Locale.current.localizedString(forLanguageCode: String($0.split(separator: "-").first ?? Substring($0))) ?? $0 }.joined(separator: ", ")
    }
}

extension ReaderStore {
    func speechPackSelected(_ pack: ModelPack) -> Bool {
        guard models?.ready(capability: pack.capability, id: pack.id) != nil else { return false }
        return pack.capability == "tts" ? DownloadedVoice(audio.selectedVoice)?.packId == pack.id : selectedTranscription == pack.id
    }

    func speechPackStatus(_ pack: ModelPack) -> String {
        let intent = speechSetup.pending.values.first { $0.packId == pack.id }
        let status = models?.installs.first { $0.packId == pack.id }?.status
        if intent?.paused == true || status == "paused" { return "Paused" }
        if status == "failed" { return "Try again" }
        if status == "downloading" { return "Downloading…" }
        if models?.ready(capability: pack.capability, id: pack.id) != nil {
            if intent != nil {
                if audio.recording || audio.capturePending || captureDraft != nil { return "Finish recording to continue" }
                if audio.replayingNote != nil { return "Ready — waiting for note playback to finish" }
                if busy || preparingDocument { return "Ready — waiting for your current task" }
                return "Finishing setup…"
            }
            return speechPackSelected(pack) ? (pack.capability == "tts" ? "Selected" : "On") : "Ready"
        }
        if status == "ready" || status == "verifying" {
            if audio.playing || audio.replayPlaying { return "Waiting for playback to pause" }
            if audio.recording || audio.capturePending || captureDraft != nil { return "Finish recording to continue" }
            return "Checking…"
        }
        return intent != nil ? "Starting download…" : "Not downloaded"
    }

    func speechSummary(_ capability: SpeechCapability) -> String {
        if let intent = speechSetup.pending[capability], let pack = models?.catalog.first(where: { $0.id == intent.packId }) {
            return speechPackStatus(pack)
        }
        if capability == .transcription { return transcriptionRequested ? "On" : "Off" }
        if let choice = DownloadedVoice(audio.selectedVoice), let pack = models?.catalog.first(where: { $0.id == choice.packId }) {
            if speechPackSelected(pack) { return "\(pack.speechName) · \(pack.speechLanguages)" }
            return speechPackStatus(pack)
        }
        return "Not downloaded"
    }
}
