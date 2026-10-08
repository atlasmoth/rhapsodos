import PDFKit
import SwiftUI
import Vision

struct ExtractedPage: Encodable {
    let pageIndex: Int
    let text: String
    let width: Double
    let height: Double
    let provenance: String
    let languageTag: String
    let regions: [SourceRegion]
    let geometrySpace = "cropTopLeftV1"
}

struct SourceRegion: Codable, Hashable {
    let start: Int
    let end: Int
    let x: Double
    let y: Double
    let width: Double
    let height: Double
}

enum PDFImportFailure: LocalizedError {
    case unreadable, locked, tooLarge, tooManyPages, tooMuchText
    var errorDescription: String? {
        switch self {
        case .unreadable: "This file could not be opened as a PDF."
        case .locked: "This PDF is password protected. Unlock it before importing."
        case .tooLarge: "This PDF exceeds the current 512 MB import limit. Your original file has not been changed."
        case .tooManyPages: "This PDF exceeds the current 10,000 page preparation limit. Your original file has not been changed."
        case .tooMuchText: "This PDF exceeds the current text preparation limit. Your original file has not been changed."
        }
    }
}

struct PDFExtraction {
    let pages: [ExtractedPage]
}

enum PDFImporter {
    static func extract(at url: URL, progress: @escaping @Sendable (Int, Int) -> Void = { _, _ in }) throws -> PDFExtraction {
        guard let pdf = PDFDocument(url: url) else { throw PDFImportFailure.unreadable }
        guard !pdf.isLocked else { throw PDFImportFailure.locked }
        guard pdf.pageCount <= 10_000 else { throw PDFImportFailure.tooManyPages }
        var pages: [ExtractedPage] = []
        var characters = 0
        for index in 0..<pdf.pageCount {
            try Task.checkCancellation()
            guard let page = pdf.page(at: index) else { throw PDFImportFailure.unreadable }
            let extracted: ExtractedPage = try autoreleasepool {
                let rect = page.bounds(for: .cropBox)
                let embedded = page.string ?? ""
                if !embedded.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    guard embedded.utf16.count <= 20_000_000 - characters else { throw PDFImportFailure.tooMuchText }
                    return ExtractedPage(pageIndex: index, text: embedded, width: rect.width, height: rect.height,
                                         provenance: "embedded", languageTag: "en", regions: embeddedRegions(page, canonicalText: embedded) ?? [])
                }
                let recognized = try recognize(page)
                return ExtractedPage(pageIndex: index, text: recognized.text, width: rect.width, height: rect.height,
                                     provenance: "ocr", languageTag: "en", regions: recognized.regions)
            }
            let text = extracted.text
            let count = text.utf16.count
            guard count <= 20_000_000 - characters else { throw PDFImportFailure.tooMuchText }
            characters += count
            pages.append(extracted)
            progress(index + 1, pdf.pageCount)
        }
        return PDFExtraction(pages: pages)
    }

    static func repairGeometry(at url: URL, pageIndex: Int, canonicalText: String, provenance: String) throws -> [SourceRegion]? {
        try Task.checkCancellation()
        guard let pdf = PDFDocument(url: url), !pdf.isLocked, pdf.pageCount <= 10_000,
              pageIndex >= 0, let page = pdf.page(at: pageIndex) else { throw PDFImportFailure.unreadable }
        if provenance == "embedded" { return try autoreleasepool { try Task.checkCancellation(); return embeddedRegions(page, canonicalText: canonicalText) } }
        let recognized = try autoreleasepool { try recognize(page) }
        try Task.checkCancellation()
        return recognized.text.utf16.elementsEqual(canonicalText.utf16) ? recognized.regions : nil
    }

    fileprivate static func embeddedRegions(_ page: PDFPage, canonicalText: String) -> [SourceRegion]? {
        let raw = page.string ?? ""
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let original = raw as NSString
        let sourceRange: NSRange
        if raw.utf16.elementsEqual(canonicalText.utf16) { sourceRange = NSRange(location: 0, length: original.length) }
        else if trimmed.utf16.elementsEqual(canonicalText.utf16) { sourceRange = original.range(of: trimmed, options: .literal) }
        else { return nil }
        let crop = page.bounds(for: .cropBox)
        guard crop.width > 0, crop.height > 0, sourceRange.location != NSNotFound, sourceRange.length > 0,
              let all = page.selection(for: sourceRange) else { return [] }
        var regions: [SourceRegion] = []
        for line in all.selectionsByLine() {
            for index in 0..<line.numberOfTextRanges(on: page) {
                let range = NSIntersectionRange(sourceRange, line.range(at: index, on: page))
                guard range.location != NSNotFound, range.length > 0, NSMaxRange(range) <= original.length else { continue }
                let content = original.substring(with: range) as NSString
                let visible = (content as String).trimmingCharacters(in: .whitespacesAndNewlines)
                guard !visible.isEmpty else { continue }
                let local = content.range(of: visible, options: .literal)
                guard local.location != NSNotFound else { continue }
                let visibleRange = NSRange(location: range.location + local.location, length: local.length)
                let rect = page.selection(for: visibleRange)?.bounds(for: page).intersection(crop) ?? .null
                guard !rect.isNull, !rect.isEmpty else { continue }
                regions.append(SourceRegion(start: visibleRange.location - sourceRange.location,
                    end: NSMaxRange(visibleRange) - sourceRange.location,
                    x: (rect.minX - crop.minX) / crop.width, y: (crop.maxY - rect.maxY) / crop.height,
                    width: rect.width / crop.width, height: rect.height / crop.height))
            }
        }
        return regions.sorted { $0.start == $1.start ? $0.end < $1.end : $0.start < $1.start }
    }

    fileprivate static func recognize(_ page: PDFPage) throws -> (text: String, regions: [SourceRegion]) {
        let bounds = page.bounds(for: .cropBox)
        guard bounds.width > 0, bounds.height > 0 else { return ("", []) }
        let rotated = abs(page.rotation) % 180 == 90
        let width = rotated ? bounds.height : bounds.width
        let height = rotated ? bounds.width : bounds.height
        let scale = min(2_400 / max(width, height), 3)
        guard let image = page.thumbnail(of: CGSize(width: width * scale, height: height * scale), for: .cropBox).cgImage else { return ("", []) }
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.recognitionLanguages = ["en-US"]
        request.usesLanguageCorrection = true
        request.automaticallyDetectsLanguage = true
        try VNImageRequestHandler(cgImage: image, orientation: .up).perform([request])
        try Task.checkCancellation()
        let lines = OCRReadingOrder.sort(request.results ?? [])
        var text = ""
        var regions: [SourceRegion] = []
        for line in lines {
            guard let result = line.topCandidates(1).first, !result.string.isEmpty else { continue }
            if !text.isEmpty { text += "\n" }
            let start = text.utf16.count
            text += result.string
            let box = line.boundingBox
            let displayed = CGRect(x: box.minX, y: 1 - box.maxY, width: box.width, height: box.height)
            let source: CGRect
            switch ((page.rotation % 360) + 360) % 360 {
            case 90: source = CGRect(x: displayed.minY, y: 1 - displayed.maxX, width: displayed.height, height: displayed.width)
            case 180: source = CGRect(x: 1 - displayed.maxX, y: 1 - displayed.maxY, width: displayed.width, height: displayed.height)
            case 270: source = CGRect(x: 1 - displayed.maxY, y: displayed.minX, width: displayed.height, height: displayed.width)
            default: source = displayed
            }
            regions.append(SourceRegion(start: start, end: text.utf16.count, x: source.minX, y: source.minY,
                                        width: source.width, height: source.height))
        }
        return (text, regions)
    }
}

enum OCRReadingOrder {
    static func sort(_ lines: [VNRecognizedTextObservation]) -> [VNRecognizedTextObservation] {
        let turns = lines.map { line in
            Int((atan2(line.topRight.y - line.topLeft.y, line.topRight.x - line.topLeft.x) / (.pi / 2)).rounded())
        }
        let counts = Dictionary(grouping: turns, by: { $0 }).mapValues(\.count)
        let turn = counts.keys.sorted { a, b in
            if counts[a] != counts[b] { return counts[a]! > counts[b]! }
            return abs(a) == abs(b) ? a < b : abs(a) < abs(b)
        }.first ?? 0
        return sort(lines, turn: turn)
    }
    private static func sort(_ lines: [VNRecognizedTextObservation], turn: Int) -> [VNRecognizedTextObservation] {
        guard lines.count > 1 else { return lines }
        func bounds(_ line: VNRecognizedTextObservation) -> CGRect {
            let points = [line.topLeft, line.topRight, line.bottomLeft, line.bottomRight].map { point -> CGPoint in
                switch (turn + 4) % 4 {
                case 1: return CGPoint(x: point.y, y: 1 - point.x)
                case 2: return CGPoint(x: 1 - point.x, y: 1 - point.y)
                case 3: return CGPoint(x: 1 - point.y, y: point.x)
                default: return point
                }
            }
            let xs = points.map(\.x), ys = points.map(\.y)
            return CGRect(x: xs.min()!, y: ys.min()!, width: xs.max()! - xs.min()!, height: ys.max()! - ys.min()!)
        }
        func split(vertical: Bool) -> ([VNRecognizedTextObservation], [VNRecognizedTextObservation])? {
            let rawIntervals: [(CGFloat, CGFloat)] = lines.map { line in
                let box = bounds(line)
                return vertical ? (box.minX, box.maxX) : (box.minY, box.maxY)
            }
            let intervals = rawIntervals.sorted { a, b in a.0 == b.0 ? a.1 < b.1 : a.0 < b.0 }
            var end = intervals[0].1, gap: CGFloat = 0, cut: CGFloat = 0
            for interval in intervals.dropFirst() {
                if interval.0 - end > gap { gap = interval.0 - end; cut = (interval.0 + end) / 2 }
                end = max(end, interval.1)
            }
            let typicalHeight = lines.map { bounds($0).height }.sorted()[lines.count / 2]
            guard gap > (vertical ? max(0.015, typicalHeight * 0.7) : 0.001) else { return nil }
            let first = lines.filter { vertical ? bounds($0).midX < cut : bounds($0).midY > cut }
            let second = lines.filter { vertical ? bounds($0).midX >= cut : bounds($0).midY <= cut }
            guard !first.isEmpty, !second.isEmpty else { return nil }
            return (first, second)
        }
        if let parts = split(vertical: true) ?? split(vertical: false) { return sort(parts.0, turn: turn) + sort(parts.1, turn: turn) }
        return lines.sorted {
            let a = bounds($0), b = bounds($1)
            if a.midY != b.midY { return a.midY > b.midY }
            if a.minX != b.minX { return a.minX < b.minX }
            return a.width < b.width
        }
    }
}

actor PDFPreparationSession {
    private let pdf: PDFDocument
    let pageCount: Int
    private var characterCount = 0

    init(url: URL, existingCharacters: Int = 0) throws {
        guard let pdf = PDFDocument(url: url) else { throw PDFImportFailure.unreadable }
        guard !pdf.isLocked else { throw PDFImportFailure.locked }
        guard pdf.pageCount <= 10_000 else { throw PDFImportFailure.tooManyPages }
        self.pdf = pdf
        pageCount = pdf.pageCount
        characterCount = existingCharacters
    }

    func batch(start: Int, count: Int = 4) throws -> [ExtractedPage] {
        var result: [ExtractedPage] = []
        for index in start..<min(start + count, pageCount) {
            try Task.checkCancellation()
            let extracted: ExtractedPage = try autoreleasepool {
                guard let page = pdf.page(at: index) else { throw PDFImportFailure.unreadable }
                let bounds = page.bounds(for: .cropBox)
                let embedded = page.string ?? ""
                let usesOCR = embedded.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                guard usesOCR || embedded.utf16.count <= 20_000_000 - characterCount else { throw PDFImportFailure.tooMuchText }
                let recognized = usesOCR ? try PDFImporter.recognize(page) : (text: embedded, regions: PDFImporter.embeddedRegions(page, canonicalText: embedded) ?? [])
                return ExtractedPage(pageIndex: index, text: recognized.text, width: bounds.width, height: bounds.height,
                    provenance: usesOCR ? "ocr" : "embedded", languageTag: "en", regions: recognized.regions)
            }
            guard extracted.text.utf16.count <= 20_000_000 - characterCount else { throw PDFImportFailure.tooMuchText }
            characterCount += extracted.text.utf16.count
            result.append(extracted)
        }
        return result
    }

    func outline(for passages: [Passage]) -> [DocumentSection] {
        guard let root = pdf.outlineRoot, let documentId = passages.first?.documentId else { return [] }
        var result: [DocumentSection] = [], seen: Set<ObjectIdentifier> = []
        func visit(_ node: PDFOutline, depth: Int) {
            guard depth <= 32, seen.count < 5000, seen.insert(ObjectIdentifier(node)).inserted else { return }
            if let destination = node.destination ?? (node.action as? PDFActionGoTo)?.destination,
               let page = destination.page, let title = node.label?.trimmingCharacters(in: .whitespacesAndNewlines), !title.isEmpty {
                let index = pdf.index(for: page)
                if let passage = passages.first(where: { $0.pageIndex == index }) {
                    result.append(DocumentSection(id: documentId + ":outline:" + String(result.count), title: String(title.prefix(200)), passageId: passage.id, pageIndex: index))
                }
            }
            for index in 0..<node.numberOfChildren { if let child = node.child(at: index) { visit(child, depth: depth + 1) } }
        }
        visit(root, depth: 0)
        return result
    }
}

struct PDFHighlightAnchor: Equatable {
    let documentId: String
    let sourceRevision: String
    let passageId: String
    let pageIndex: Int
    let sourceStart: Int
    let sourceEnd: Int
}

struct PDFSurface: UIViewRepresentable {
    let url: URL
    let pageIndex: Int
    let baseRange: NSRange?
    let baseRegions: [SourceRegion]
    let range: NSRange?
    let regions: [SourceRegion]
    let pageContexts: [SourcePage]
    let preparedPassages: [Passage]
    let documentId: String
    let sourceRevision: String
    let geometrySpace: String
    let sourceText: String
    let anchor: PDFHighlightAnchor?
    let excerpt: String
    let recordingEnabled: Bool
    let follow: Bool
    let onBrowse: () -> Void
    let onRecord: (PDFHighlightAnchor) -> Bool
    let onLoadFailure: () -> Void

    func makeUIView(context: Context) -> PDFView {
        let view = ReaderPDFView()
        view.autoScales = true
        view.displayBox = .cropBox
        view.displayMode = .singlePageContinuous
        view.displayDirection = .vertical
        view.backgroundColor = UIColor(Palette.paper)
        context.coordinator.attach(to: view)
        return view
    }

    func updateUIView(_ view: PDFView, context: Context) {
        context.coordinator.onBrowse = onBrowse
        context.coordinator.onRecord = onRecord
        context.coordinator.onLoadFailure = onLoadFailure
        context.coordinator.request(url: url, pageIndex: pageIndex, baseRange: baseRange, baseRegions: baseRegions, range: range, regions: regions, pageContexts: pageContexts,
            preparedPassages: preparedPassages, documentId: documentId, sourceRevision: sourceRevision, geometrySpace: geometrySpace, sourceText: sourceText,
            anchor: anchor, excerpt: excerpt, recordingEnabled: recordingEnabled, follow: follow)
    }

    func makeCoordinator() -> Coordinator { Coordinator(onBrowse: onBrowse, onRecord: onRecord, onLoadFailure: onLoadFailure) }
    static func dismantleUIView(_ uiView: PDFView, coordinator: Coordinator) { coordinator.cancel() }

    @MainActor
    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        weak var view: PDFView?
        let spinner = UIActivityIndicatorView(style: .medium)
        let passageDoubleTap = PassageDoubleTapGestureRecognizer()
        let browsingDoubleTap = UITapGestureRecognizer()
        let accessibilityHighlight = HighlightAccessibilityView()
        let narrationOverlay = PDFNarrationOverlay()
        var onBrowse: () -> Void
        var onRecord: (PDFHighlightAnchor) -> Bool
        var onLoadFailure: () -> Void
        private var url: URL?
        private var page = -1
        private var requestedPage = 0
        private var requestedBaseRange: NSRange?
        private var requestedBaseRegions: [SourceRegion] = []
        private var requestedRange: NSRange?
        private var requestedRegions: [SourceRegion] = []
        private var requestedGeometrySpace = "unspecified"
        private var requestedSourceText = ""
        private var requestedAnchor: PDFHighlightAnchor?
        private var appliedAnchor: PDFHighlightAnchor?
        private var appliedPage: PDFPage?
        private var hitRects: [CGRect] = []
        private var paintedRects: [PDFNarrationRect] = []
        private var recordingEnabled = false
        private var touchAnchor: PDFHighlightAnchor?
        private var touchVersion: UUID?
        private var geometryVersion = UUID()
        private var pageContexts: [SourcePage] = []
        private var pageAccessibility: [Int: HighlightAccessibilityView] = [:]
        private var passageAccessibility: [String: HighlightAccessibilityView] = [:]
        private var passagesByPage: [Int: [Passage]] = [:]
        private var sourceRectCache: [String: [CGRect]] = [:]
        private var preparedPassageCount = 0
        private var preparedDocumentId = ""
        private var preparedSourceRevision = ""
        private var requestedFollow = true
        private var applyingNavigation = false
        private weak var observedPage: PDFPage?
        private var generation = UUID()
        private var loadTask: Task<Void, Never>?
        private var observations: [NSObjectProtocol] = []
        private var scrollObservations: [NSKeyValueObservation] = []

        init(onBrowse: @escaping () -> Void, onRecord: @escaping (PDFHighlightAnchor) -> Bool, onLoadFailure: @escaping () -> Void) {
            self.onBrowse = onBrowse
            self.onRecord = onRecord
            self.onLoadFailure = onLoadFailure
            spinner.hidesWhenStopped = true
            spinner.accessibilityLabel = "Opening original PDF"
            super.init()
        }

        func attach(to view: PDFView) {
            self.view = view
            (view as? ReaderPDFView)?.onBrowse = { [weak self] in self?.suspendFollowing() }
            spinner.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(spinner)
            NSLayoutConstraint.activate([spinner.centerXAnchor.constraint(equalTo: view.centerXAnchor), spinner.centerYAnchor.constraint(equalTo: view.centerYAnchor)])
            let pan = UIPanGestureRecognizer(target: self, action: #selector(browsing(_:)))
            pan.delegate = self
            pan.cancelsTouchesInView = false
            view.addGestureRecognizer(pan)
            let pinch = UIPinchGestureRecognizer(target: self, action: #selector(browsing(_:)))
            pinch.delegate = self
            pinch.cancelsTouchesInView = false
            view.addGestureRecognizer(pinch)
            passageDoubleTap.addTarget(self, action: #selector(tapped(_:)))
            passageDoubleTap.onReset = { [weak self] in self?.resetPassageTouch() }
            passageDoubleTap.delegate = self
            passageDoubleTap.numberOfTapsRequired = 2
            passageDoubleTap.numberOfTouchesRequired = 1
            passageDoubleTap.cancelsTouchesInView = false
            passageDoubleTap.delaysTouchesBegan = false
            passageDoubleTap.delaysTouchesEnded = false
            view.addGestureRecognizer(passageDoubleTap)
            browsingDoubleTap.addTarget(self, action: #selector(doubleTapBrowsing(_:)))
            browsingDoubleTap.delegate = self
            browsingDoubleTap.numberOfTapsRequired = 2
            browsingDoubleTap.numberOfTouchesRequired = 1
            browsingDoubleTap.cancelsTouchesInView = false
            browsingDoubleTap.delaysTouchesBegan = false
            browsingDoubleTap.delaysTouchesEnded = false
            view.addGestureRecognizer(browsingDoubleTap)
            narrationOverlay.frame = view.bounds
            narrationOverlay.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            narrationOverlay.rectangles = { [weak self] in
                guard let self, let view = self.view, let page = self.appliedPage, self.appliedAnchor == self.requestedAnchor else { return [] }
                return self.paintedRects.compactMap { rectangle in
                    let bounds = view.convert(rectangle.bounds, from: page).intersection(view.bounds)
                    return bounds.isEmpty || bounds.isNull ? nil : PDFNarrationRect(bounds: bounds, colour: rectangle.colour)
                }
            }
            view.addSubview(narrationOverlay)
            accessibilityHighlight.frame = view.bounds
            accessibilityHighlight.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            accessibilityHighlight.frameProvider = { [weak self] in self?.accessibleFrame() ?? .zero }
            accessibilityHighlight.activate = { [weak self] in self?.activateAccessibleHighlight() ?? false }
            accessibilityHighlight.accessibilityLabel = "Current narration passage"
            view.addSubview(accessibilityHighlight)
            observations.append(NotificationCenter.default.addObserver(forName: .PDFViewPageChanged, object: view, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.pageChanged() }
            })
            for name in [Notification.Name.PDFViewVisiblePagesChanged, .PDFViewScaleChanged, .PDFViewSelectionChanged] {
                observations.append(NotificationCenter.default.addObserver(forName: name, object: view, queue: .main) { [weak self] _ in
                    Task { @MainActor in self?.refreshAccessibility() }
                })
            }
        }

        func request(url: URL, pageIndex: Int, baseRange: NSRange?, baseRegions: [SourceRegion], range: NSRange?, regions: [SourceRegion], pageContexts: [SourcePage],
                     preparedPassages: [Passage], documentId: String, sourceRevision: String,
                     geometrySpace: String, sourceText: String, anchor: PDFHighlightAnchor?, excerpt: String, recordingEnabled: Bool, follow: Bool) {
            let baseChanged = requestedPage != pageIndex || requestedBaseRange != baseRange || requestedBaseRegions != baseRegions || requestedAnchor != anchor || requestedGeometrySpace != geometrySpace || requestedSourceText != sourceText
            let geometryChanged = baseChanged || requestedRange != range || requestedRegions != regions
            let followChanged = follow && !requestedFollow
            if documentId != preparedDocumentId || sourceRevision != preparedSourceRevision || preparedPassages.count != preparedPassageCount {
                if documentId != preparedDocumentId || sourceRevision != preparedSourceRevision { geometryVersion = UUID() }
                passagesByPage = Dictionary(grouping: preparedPassages, by: \.pageIndex)
                sourceRectCache.removeAll()
                preparedPassageCount = preparedPassages.count
            }
            for index in view?.visiblePages.compactMap({ view?.document?.index(for: $0) }) ?? [] {
                let previous = self.pageContexts.first(where: { $0.pageIndex == index })
                let latest = pageContexts.first(where: { $0.pageIndex == index })
                let textChanged = previous.map { previous in latest.map { !previous.text.utf16.elementsEqual($0.text.utf16) } ?? true } ?? (latest != nil)
                if previous?.regions != latest?.regions || previous?.geometrySpace != latest?.geometrySpace
                    || previous?.provenance != latest?.provenance || textChanged {
                    for passage in passagesByPage[index] ?? [] { sourceRectCache.removeValue(forKey: passage.id) }
                    if touchAnchor?.pageIndex == index { geometryVersion = UUID() }
                }
            }
            preparedDocumentId = documentId
            preparedSourceRevision = sourceRevision
            requestedBaseRange = baseRange
            requestedBaseRegions = baseRegions
            requestedGeometrySpace = geometrySpace
            requestedSourceText = sourceText
            self.pageContexts = pageContexts
            requestedPage = pageIndex
            requestedRange = range
            requestedRegions = regions
            requestedAnchor = anchor
            self.recordingEnabled = recordingEnabled
            accessibilityHighlight.accessibilityValue = excerpt
            if follow && !requestedFollow { page = -1 }
            requestedFollow = follow
            guard self.url != url else { if geometryChanged || followChanged { applyRequestedPosition() } else { refreshAccessibility() }; return }
            cancel()
            self.url = url
            page = -1
            view?.document = nil
            spinner.startAnimating()
            let id = UUID()
            generation = id
            let worker = Task.detached(priority: .userInitiated) { () -> PDFDocument? in
                guard !Task.isCancelled else { return nil }
                let document = PDFDocument(url: url)
                return Task.isCancelled ? nil : document
            }
            loadTask = Task { [weak self] in
                let document = await withTaskCancellationHandler(operation: { await worker.value }, onCancel: { worker.cancel() })
                guard !Task.isCancelled, let self, self.generation == id, self.url == url else { return }
                self.spinner.stopAnimating()
                self.applyingNavigation = true
                self.view?.document = document
                self.observeScrolling()
                self.applyRequestedPosition()
                self.observedPage = self.view?.currentPage
                self.applyingNavigation = false
                if document == nil { self.onLoadFailure() }
            }
        }

        func cancel() {
            generation = UUID(); loadTask?.cancel(); loadTask = nil
            observedPage = nil
            appliedAnchor = nil; appliedPage = nil; hitRects = []
            resetPassageTouch()
            accessibilityHighlight.isHidden = true
            paintedRects = []
            narrationOverlay.setNeedsDisplay()
            pageAccessibility.values.forEach { $0.removeFromSuperview() }; pageAccessibility.removeAll()
            passageAccessibility.values.forEach { $0.removeFromSuperview() }; passageAccessibility.removeAll()
            sourceRectCache.removeAll()
            scrollObservations.removeAll()
        }

        private func observeScrolling() {
            scrollObservations.removeAll()
            guard let view else { return }
            func observe(_ node: UIView) {
                if let scroll = node as? UIScrollView {
                    scrollObservations.append(scroll.observe(\.contentOffset) { [weak self] _, _ in Task { @MainActor in self?.refreshAccessibility() } })
                    scrollObservations.append(scroll.observe(\.bounds) { [weak self] _, _ in Task { @MainActor in self?.refreshAccessibility() } })
                }
                node.subviews.forEach(observe)
            }
            observe(view)
        }

        private func applyRequestedPosition() {
            guard let view, let page = view.document?.page(at: requestedPage) else { return }
            hitRects = []
            paintedRects = []
            appliedPage = page
            appliedAnchor = requestedAnchor
            let bounds = page.bounds(for: .cropBox)
            let narrationTraits = view.traitCollection.modifyingTraits { $0.userInterfaceStyle = .light }
            func paintRegions(_ regions: [SourceRegion], alpha: CGFloat) {
                guard requestedGeometrySpace == "cropTopLeftV1" else { return }
                for region in regions {
                    let rect = CGRect(x: bounds.minX + region.x * bounds.width,
                        y: bounds.maxY - (region.y + region.height) * bounds.height,
                        width: region.width * bounds.width, height: region.height * bounds.height).intersection(bounds)
                    guard !rect.isNull, !rect.isEmpty else { continue }
                    let colour = UIColor(Palette.accent).resolvedColor(with: narrationTraits).withAlphaComponent(alpha)
                    paintedRects.append(PDFNarrationRect(bounds: rect, colour: colour))
                    hitRects.append(rect)
                }
            }
            func paintSelection(_ range: NSRange?, alpha: CGFloat) {
                guard requestedSourceText.utf16.elementsEqual((page.string ?? "").utf16) else { return }
                guard let range, range.location != NSNotFound, range.location >= 0, range.length > 0 else { return }
                let count = (page.string as NSString?)?.length ?? 0
                guard range.location <= count, range.length <= count - range.location, let selection = page.selection(for: range) else { return }
                let rectangles = selection.selectionsByLine().filter { $0.pages.contains(where: { $0 === page }) }.map { $0.bounds(for: page).intersection(bounds) }.filter { !$0.isEmpty && !$0.isNull }
                let colour = UIColor(Palette.accent).resolvedColor(with: narrationTraits).withAlphaComponent(alpha)
                paintedRects += rectangles.map { PDFNarrationRect(bounds: $0, colour: colour) }
                hitRects += rectangles
            }
            if requestedBaseRange != nil && requestedSourceText.utf16.elementsEqual((page.string ?? "").utf16) {
                paintSelection(requestedBaseRange, alpha: 0.18)
                if requestedRange != requestedBaseRange { paintSelection(requestedRange, alpha: 0.32) }
            } else {
                paintRegions(requestedBaseRegions, alpha: 0.18)
                if requestedRegions != requestedBaseRegions { paintRegions(requestedRegions, alpha: 0.32) }
            }
            if requestedFollow, self.page != requestedPage {
                resetPassageTouch()
                self.page = requestedPage
                let wasApplying = applyingNavigation
                applyingNavigation = true
                view.go(to: page)
                observedPage = view.currentPage
                applyingNavigation = wasApplying
            }
            refreshAccessibility()
        }

        private func sourceRange(_ context: SourcePage, on page: PDFPage) -> NSRange? {
            let raw = page.string ?? ""
            if raw.utf16.elementsEqual(context.text.utf16) { return NSRange(location: 0, length: raw.utf16.count) }
            let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard trimmed.utf16.elementsEqual(context.text.utf16) else { return nil }
            let original = raw as NSString
            let prefix = original.rangeOfCharacter(from: .whitespacesAndNewlines.inverted).location
            guard prefix != NSNotFound, context.text.utf16.count <= original.length - prefix else { return nil }
            return NSRange(location: prefix, length: context.text.utf16.count)
        }
        private func anchor(for passage: Passage) -> PDFHighlightAnchor {
            PDFHighlightAnchor(documentId: passage.documentId, sourceRevision: preparedSourceRevision,
                passageId: passage.id, pageIndex: passage.pageIndex, sourceStart: passage.sourceStart, sourceEnd: passage.sourceEnd)
        }
        private func regionBounds(_ region: SourceRegion, on page: PDFPage) -> CGRect {
            let crop = page.bounds(for: .cropBox)
            return CGRect(x: crop.minX + region.x * crop.width, y: crop.maxY - (region.y + region.height) * crop.height,
                width: region.width * crop.width, height: region.height * crop.height).intersection(crop)
        }
        private func sourceRectangles(_ passage: Passage, on page: PDFPage, context: SourcePage) -> [CGRect] {
            if let rectangles = sourceRectCache[passage.id] { return rectangles }
            let rectangles: [CGRect]
            if context.provenance == "embedded", let source = sourceRange(context, on: page),
               passage.pageStart >= 0, passage.pageEnd > passage.pageStart, passage.pageEnd <= source.length,
               let selection = page.selection(for: NSRange(location: source.location + passage.pageStart, length: passage.pageEnd - passage.pageStart)) {
                rectangles = selection.selectionsByLine().filter { $0.pages.contains(where: { $0 === page }) }
                    .map { $0.bounds(for: page).intersection(page.bounds(for: .cropBox)) }.filter { !$0.isEmpty && !$0.isNull }
            } else if context.geometrySpace == "cropTopLeftV1" {
                rectangles = context.regions.filter { $0.start >= passage.pageStart && $0.end <= passage.pageEnd && $0.end > $0.start }
                    .map { regionBounds($0, on: page) }.filter { !$0.isEmpty && !$0.isNull }
            } else {
                rectangles = []
            }
            sourceRectCache[passage.id] = rectangles
            return rectangles
        }
        func recordablePassage(at point: CGPoint) -> PDFHighlightAnchor? {
            guard recordingEnabled, let view, view.bounds.contains(point), !preparedSourceRevision.isEmpty,
                  view.currentSelection?.string?.isEmpty != false,
                  let page = view.page(for: point, nearest: false), let document = view.document else { return nil }
            let index = document.index(for: page)
            guard let context = pageContexts.first(where: { $0.pageIndex == index }), let passages = passagesByPage[index] else { return nil }
            let position = view.convert(point, to: page)
            guard page.bounds(for: .cropBox).contains(position),
                  !page.annotations.contains(where: { ($0.type == "Link" || $0.type == "Widget") && $0.bounds.contains(position) }) else { return nil }
            if context.provenance == "embedded", let source = sourceRange(context, on: page) {
                let character = page.characterIndex(at: position)
                guard character >= source.location, character < NSMaxRange(source), page.characterBounds(at: character).contains(position) else { return nil }
                let offset = character - source.location
                let candidates = passages.filter { $0.documentId == preparedDocumentId && $0.pageStart <= offset && offset < $0.pageEnd }
                return candidates.count == 1 ? anchor(for: candidates[0]) : nil
            }
            guard context.geometrySpace == "cropTopLeftV1" else { return nil }
            let regions = context.regions.filter { regionBounds($0, on: page).contains(position) }
            guard !regions.isEmpty else { return nil }
            var matches: [String: Passage] = [:]
            for region in regions {
                let overlapping = passages.filter { $0.documentId == preparedDocumentId && $0.pageStart < region.end && $0.pageEnd > region.start }
                guard overlapping.count == 1, let passage = overlapping.first,
                      region.start >= passage.pageStart, region.end <= passage.pageEnd else { return nil }
                matches[passage.id] = passage
            }
            return matches.count == 1 ? matches.values.first.map { anchor(for: $0) } : nil
        }
        @discardableResult func activatePreparedPassage(at point: CGPoint, expected: PDFHighlightAnchor) -> Bool {
            guard recordablePassage(at: point) == expected else { return false }
            return onRecord(expected)
        }
        func beginPreparedTouch(at point: CGPoint) -> Bool {
            guard let anchor = recordablePassage(at: point) else { return false }
            if let touchAnchor { return touchVersion == geometryVersion && touchAnchor == anchor }
            touchAnchor = anchor; touchVersion = geometryVersion
            return true
        }
        func continuesPreparedTouch(at point: CGPoint) -> Bool {
            guard touchVersion == geometryVersion, let anchor = touchAnchor else { return false }
            return recordablePassage(at: point) == anchor
        }
        @discardableResult func finishPreparedTouch(at point: CGPoint) -> Bool {
            defer { resetPassageTouch() }
            guard continuesPreparedTouch(at: point), let anchor = touchAnchor else { return false }
            return activatePreparedPassage(at: point, expected: anchor)
        }
        private func resetPassageTouch() { touchAnchor = nil; touchVersion = nil }
        @objc private func tapped(_ gesture: UITapGestureRecognizer) {
            guard gesture.state == .ended else { return }
            finishPreparedTouch(at: gesture.location(in: view))
        }

        private func accessibleFrame() -> CGRect {
            guard let view, let page = appliedPage, appliedAnchor == requestedAnchor else { return .zero }
            let visible = hitRects.map { view.convert($0, from: page).intersection(view.bounds) }.filter { !$0.isEmpty && !$0.isNull }
            guard let first = visible.first else { return .zero }
            return UIAccessibility.convertToScreenCoordinates(visible.dropFirst().reduce(first) { $0.union($1) }, in: view)
        }
        private func accessiblePassageFrame(_ anchor: PDFHighlightAnchor) -> CGRect {
            guard let view, anchor.documentId == preparedDocumentId, anchor.sourceRevision == preparedSourceRevision,
                  let passage = passagesByPage[anchor.pageIndex]?.first(where: { self.anchor(for: $0) == anchor }),
                  let context = pageContexts.first(where: { $0.pageIndex == anchor.pageIndex }),
                  let page = view.document?.page(at: anchor.pageIndex) else { return .zero }
            let visible = sourceRectangles(passage, on: page, context: context)
                .map { view.convert($0, from: page).intersection(view.bounds) }.filter { !$0.isEmpty && !$0.isNull }
            guard let first = visible.first else { return .zero }
            return UIAccessibility.convertToScreenCoordinates(visible.dropFirst().reduce(first) { $0.union($1) }, in: view)
        }
        private func activateAccessiblePassage(_ anchor: PDFHighlightAnchor) -> Bool {
            guard recordingEnabled, !accessiblePassageFrame(anchor).isEmpty,
                  view?.currentSelection?.string?.isEmpty != false else { return false }
            return onRecord(anchor)
        }
        private func refreshAccessibility() {
            narrationOverlay.setNeedsDisplay()
            accessibilityHighlight.isHidden = accessibleFrame().isEmpty
            let enabled = recordingEnabled && view?.currentSelection?.string?.isEmpty != false
            accessibilityHighlight.accessibilityTraits = enabled ? [.button] : [.staticText]
            accessibilityHighlight.accessibilityHint = enabled ? "Double-tap to pause and record a thought at this passage." : nil
            accessibilityHighlight.accessibilityCustomActions = enabled ? [UIAccessibilityCustomAction(name: "Pause and record a thought", target: self, selector: #selector(accessibleRecording(_:)))] : nil
            guard let view, let document = view.document else { return }
            let visiblePages = Set(view.visiblePages.map { document.index(for: $0) })
            let contexts = pageContexts.filter { $0.provenance == "ocr" && visiblePages.contains($0.pageIndex) }
            let visibleContexts = Set(contexts.map(\.pageIndex))
            for index in Array(pageAccessibility.keys) where !visibleContexts.contains(index) { pageAccessibility.removeValue(forKey: index)?.removeFromSuperview() }
            for context in contexts {
                let element: HighlightAccessibilityView
                if let existing = pageAccessibility[context.pageIndex] { element = existing }
                else {
                    element = HighlightAccessibilityView(frame: view.bounds)
                    element.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                    let index = context.pageIndex
                    element.frameProvider = { [weak self] in
                        guard let view = self?.view, let page = view.document?.page(at: index) else { return .zero }
                        let frame = view.convert(page.bounds(for: .cropBox), from: page).intersection(view.bounds)
                        return frame.isNull || frame.isEmpty ? .zero : UIAccessibility.convertToScreenCoordinates(frame, in: view)
                    }
                    element.accessibilityTraits = [.staticText]
                    element.accessibilityLabel = "Page \(index + 1), recognized text"
                    view.addSubview(element)
                    pageAccessibility[index] = element
                }
                element.accessibilityValue = context.text
                element.isHidden = element.accessibilityFrame.isEmpty
            }
            let visiblePassages = visiblePages.sorted().flatMap { passagesByPage[$0] ?? [] }.filter { $0.documentId == preparedDocumentId }
            let visiblePassageIds = Set(visiblePassages.map(\.id))
            for id in Array(sourceRectCache.keys) where !visiblePassageIds.contains(id) { sourceRectCache.removeValue(forKey: id) }
            for id in Array(passageAccessibility.keys) where !visiblePassageIds.contains(id) {
                passageAccessibility.removeValue(forKey: id)?.removeFromSuperview()
            }
            for passage in visiblePassages {
                let expected = anchor(for: passage)
                let element: HighlightAccessibilityView
                if let existing = passageAccessibility[passage.id] { element = existing }
                else {
                    element = HighlightAccessibilityView(frame: view.bounds)
                    element.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                    view.addSubview(element)
                    passageAccessibility[passage.id] = element
                }
                element.frameProvider = { [weak self] in self?.accessiblePassageFrame(expected) ?? .zero }
                element.activate = { [weak self] in self?.activateAccessiblePassage(expected) ?? false }
                element.accessibilityLabel = "Page \(passage.pageIndex + 1), passage"
                element.accessibilityValue = passage.displayText
                element.accessibilityTraits = enabled ? [.button] : [.staticText]
                element.accessibilityHint = enabled ? "Record a thought at this passage." : nil
                element.accessibilityCustomActions = enabled ? [UIAccessibilityCustomAction(name: "Record a thought", actionHandler: { [weak self] _ in self?.activateAccessiblePassage(expected) ?? false })] : nil
                element.isHidden = element.accessibilityFrame.isEmpty
            }
            view.bringSubviewToFront(narrationOverlay)
            view.bringSubviewToFront(accessibilityHighlight)
        }
        func activateAccessibleHighlight() -> Bool {
            guard recordingEnabled, !accessibleFrame().isEmpty, let anchor = appliedAnchor, anchor == requestedAnchor, view?.currentSelection?.string?.isEmpty != false else { return false }
            return onRecord(anchor)
        }
        @objc private func accessibleRecording(_ action: UIAccessibilityCustomAction) -> Bool { activateAccessibleHighlight() }

        private func suspendFollowing() {
            guard !applyingNavigation else { return }
            page = -1; requestedFollow = false
            resetPassageTouch()
            onBrowse()
        }
        private func pageChanged() {
            guard let view, let currentPage = view.currentPage else { return }
            let changed = observedPage !== currentPage
            observedPage = currentPage
            if changed, !applyingNavigation, requestedFollow,
               currentPage !== view.document?.page(at: requestedPage) { suspendFollowing() }
            refreshAccessibility()
        }
        @objc func browsing(_ gesture: UIGestureRecognizer) {
            if gesture.state == .began { suspendFollowing() }
        }
        @objc private func doubleTapBrowsing(_ gesture: UITapGestureRecognizer) {
            if gesture.state == .ended { suspendFollowing() }
        }
        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            if gestureRecognizer === browsingDoubleTap {
                return !(touch.view is UIControl) && recordablePassage(at: touch.location(in: view)) == nil
            }
            guard gestureRecognizer === passageDoubleTap else { return true }
            if touch.view is UIControl { return false }
            return beginPreparedTouch(at: touch.location(in: view))
        }
        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            guard gestureRecognizer === passageDoubleTap else { return true }
            return continuesPreparedTouch(at: gestureRecognizer.location(in: view))
        }
        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRequireFailureOf other: UIGestureRecognizer) -> Bool {
            guard gestureRecognizer === passageDoubleTap else { return false }
            if let tap = other as? UITapGestureRecognizer { return tap.numberOfTapsRequired > 2 }
            return other is UILongPressGestureRecognizer || other is UIPanGestureRecognizer || other is UIPinchGestureRecognizer
        }
        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldBeRequiredToFailBy other: UIGestureRecognizer) -> Bool {
            guard gestureRecognizer === passageDoubleTap, let tap = other as? UITapGestureRecognizer else { return false }
            return tap.numberOfTapsRequired == 2
        }
        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
            gestureRecognizer !== passageDoubleTap && other !== passageDoubleTap
        }
        deinit { observations.forEach(NotificationCenter.default.removeObserver) }
    }
}

@MainActor
final class PassageDoubleTapGestureRecognizer: UITapGestureRecognizer {
    var onReset: (() -> Void)?
    override func reset() { super.reset(); onReset?() }
}

@MainActor
final class HighlightAccessibilityView: UIView {
    var frameProvider: (() -> CGRect)?
    var activate: (() -> Bool)?
    override init(frame: CGRect) { super.init(frame: frame); isAccessibilityElement = true; backgroundColor = .clear }
    required init?(coder: NSCoder) { super.init(coder: coder); isAccessibilityElement = true; backgroundColor = .clear }
    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool { false }
    override var accessibilityFrame: CGRect { get { frameProvider?() ?? .zero } set { super.accessibilityFrame = newValue } }
    override func accessibilityActivate() -> Bool { activate?() ?? false }
}

@MainActor
final class ReaderPDFView: PDFView {
    var onBrowse: (() -> Void)?

    override func accessibilityScroll(_ direction: UIAccessibilityScrollDirection) -> Bool {
        onBrowse?()
        return super.accessibilityScroll(direction)
    }

    override func goToFirstPage(_ sender: Any?) { onBrowse?(); super.goToFirstPage(sender) }
    override func goToLastPage(_ sender: Any?) { onBrowse?(); super.goToLastPage(sender) }
    override func goToNextPage(_ sender: Any?) { onBrowse?(); super.goToNextPage(sender) }
    override func goToPreviousPage(_ sender: Any?) { onBrowse?(); super.goToPreviousPage(sender) }
    override func goBack(_ sender: Any?) { onBrowse?(); super.goBack(sender) }
    override func goForward(_ sender: Any?) { onBrowse?(); super.goForward(sender) }

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        let navigationKeys: Set<UIKeyboardHIDUsage> = [.keyboardPageUp, .keyboardPageDown, .keyboardUpArrow,
            .keyboardDownArrow, .keyboardLeftArrow, .keyboardRightArrow, .keyboardHome, .keyboardEnd, .keyboardSpacebar]
        if presses.contains(where: { $0.key.map { navigationKeys.contains($0.keyCode) } ?? false }) { onBrowse?() }
        super.pressesBegan(presses, with: event)
    }
}

struct PDFNarrationRect {
    let bounds: CGRect
    let colour: UIColor
}

final class PDFNarrationOverlay: UIView {
    var rectangles: (() -> [PDFNarrationRect])?
    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        isUserInteractionEnabled = false
        accessibilityElementsHidden = true
        clipsToBounds = true
    }
    required init?(coder: NSCoder) {
        super.init(coder: coder)
        backgroundColor = .clear
        isOpaque = false
        isUserInteractionEnabled = false
        accessibilityElementsHidden = true
        clipsToBounds = true
    }
    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        for rectangle in rectangles?() ?? [] {
            context.setFillColor(rectangle.colour.cgColor)
            context.fill(rectangle.bounds)
        }
    }
}
