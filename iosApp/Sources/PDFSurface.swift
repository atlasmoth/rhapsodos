import PDFKit
import SwiftUI

struct ExtractedPage: Encodable {
    let pageIndex: Int
    let text: String
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
    static func extract(at url: URL) throws -> PDFExtraction {
        guard let pdf = PDFDocument(url: url) else { throw PDFImportFailure.unreadable }
        guard !pdf.isLocked else { throw PDFImportFailure.locked }
        guard pdf.pageCount <= 10_000 else { throw PDFImportFailure.tooManyPages }
        var pages: [ExtractedPage] = []
        var characters = 0
        for index in 0..<pdf.pageCount {
            guard let page = pdf.page(at: index) else { throw PDFImportFailure.unreadable }
            let text = page.string ?? ""
            let count = text.utf16.count
            guard count <= 20_000_000 - characters else { throw PDFImportFailure.tooMuchText }
            characters += count
            let rect = page.bounds(for: .cropBox)
            pages.append(ExtractedPage(pageIndex: index, text: text, width: rect.width, height: rect.height))
        }
        return PDFExtraction(pages: pages)
    }
}

struct PDFSurface: UIViewRepresentable {
    let url: URL
    let pageIndex: Int
    let range: NSRange?
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
        context.coordinator.request(url: url, pageIndex: pageIndex, range: range, follow: follow)
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
        private var requestedFollow = true
        private var generation = UUID()
        private var loadTask: Task<Void, Never>?

        init(onBrowse: @escaping () -> Void, onLoadFailure: @escaping () -> Void) {
            self.onBrowse = onBrowse
            self.onLoadFailure = onLoadFailure
            spinner.hidesWhenStopped = true
            spinner.accessibilityLabel = "Opening original PDF"
        }

        func request(url: URL, pageIndex: Int, range: NSRange?, follow: Bool) {
            requestedPage = pageIndex
            requestedRange = range
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
            if let range = requestedRange, range.location != NSNotFound, range.location >= 0, range.length > 0 {
                let count = (page.string as NSString?)?.length ?? 0
                if range.location <= count, range.length <= count - range.location, let selection = page.selection(for: range) {
                    selection.color = UIColor.systemPurple.withAlphaComponent(0.2)
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
