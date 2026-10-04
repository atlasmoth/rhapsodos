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
                    return ExtractedPage(pageIndex: index, text: embedded, width: rect.width, height: rect.height,
                                         provenance: "embedded", languageTag: "en", regions: [])
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
                let recognized = usesOCR ? try PDFImporter.recognize(page) : (text: embedded, regions: [SourceRegion]())
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

struct PDFSurface: UIViewRepresentable {
    let url: URL
    let pageIndex: Int
    let range: NSRange?
    let regions: [SourceRegion]
    let follow: Bool
    let onBrowse: () -> Void
    let onLoadFailure: () -> Void

    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.displayDirection = .vertical
        view.backgroundColor = UIColor.secondarySystemBackground
        context.coordinator.view = view
        let spinner = context.coordinator.spinner
        spinner.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(spinner)
        NSLayoutConstraint.activate([spinner.centerXAnchor.constraint(equalTo: view.centerXAnchor), spinner.centerYAnchor.constraint(equalTo: view.centerYAnchor)])
        let pan = UIPanGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.browsing(_:)))
        pan.delegate = context.coordinator
        pan.cancelsTouchesInView = false
        view.addGestureRecognizer(pan)
        return view
    }

    func updateUIView(_ view: PDFView, context: Context) {
        context.coordinator.onBrowse = onBrowse
        context.coordinator.onLoadFailure = onLoadFailure
        context.coordinator.request(url: url, pageIndex: pageIndex, range: range, regions: regions, follow: follow)
    }

    func makeCoordinator() -> Coordinator { Coordinator(onBrowse: onBrowse, onLoadFailure: onLoadFailure) }
    static func dismantleUIView(_ uiView: PDFView, coordinator: Coordinator) { coordinator.cancel() }

    @MainActor
    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        weak var view: PDFView?
        let spinner = UIActivityIndicatorView(style: .medium)
        var onBrowse: () -> Void
        var onLoadFailure: () -> Void
        private var url: URL?
        private var page = -1
        private var requestedPage = 0
        private var requestedRange: NSRange?
        private var requestedRegions: [SourceRegion] = []
        private var overlayAnnotations: [(PDFPage, PDFAnnotation)] = []
        private var requestedFollow = true
        private var generation = UUID()
        private var loadTask: Task<Void, Never>?

        init(onBrowse: @escaping () -> Void, onLoadFailure: @escaping () -> Void) {
            self.onBrowse = onBrowse
            self.onLoadFailure = onLoadFailure
            spinner.hidesWhenStopped = true
            spinner.accessibilityLabel = "Opening original PDF"
        }

        func request(url: URL, pageIndex: Int, range: NSRange?, regions: [SourceRegion], follow: Bool) {
            requestedPage = pageIndex
            requestedRange = range
            requestedRegions = regions
            if follow && !requestedFollow { page = -1 }
            requestedFollow = follow
            guard self.url != url else { applyRequestedPosition(); return }
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
                self.view?.document = document
                self.applyRequestedPosition()
                if document == nil { self.onLoadFailure() }
            }
        }

        func cancel() { generation = UUID(); loadTask?.cancel(); loadTask = nil }

        private func applyRequestedPosition() {
            guard let view, let page = view.document?.page(at: requestedPage) else { return }
            view.highlightedSelections = []
            overlayAnnotations.forEach { $0.0.removeAnnotation($0.1) }
            overlayAnnotations.removeAll()
            let bounds = page.bounds(for: .cropBox)
            for region in requestedRegions {
                let rect = CGRect(x: bounds.minX + region.x * bounds.width,
                    y: bounds.maxY - (region.y + region.height) * bounds.height,
                    width: region.width * bounds.width, height: region.height * bounds.height)
                let annotation = PDFAnnotation(bounds: rect, forType: .square, withProperties: nil)
                annotation.color = .clear
                annotation.interiorColor = UIColor(Palette.ember).withAlphaComponent(0.18)
                let border = PDFBorder(); border.lineWidth = 0; annotation.border = border
                page.addAnnotation(annotation)
                overlayAnnotations.append((page, annotation))
            }
            if let range = requestedRange, range.location != NSNotFound, range.location >= 0, range.length > 0 {
                let count = (page.string as NSString?)?.length ?? 0
                if range.location <= count, range.length <= count - range.location, let selection = page.selection(for: range) {
                    selection.color = UIColor(Palette.ember).withAlphaComponent(0.24)
                    view.highlightedSelections = [selection]
                }
            }
            if requestedFollow, self.page != requestedPage {
                self.page = requestedPage
                view.go(to: page)
            }
        }

        @objc func browsing(_ gesture: UIPanGestureRecognizer) {
            if gesture.state == .began { page = -1; requestedFollow = false; onBrowse() }
        }
        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool { true }
    }
}
