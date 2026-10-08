import SwiftUI

struct ReaderTools: View {
    @EnvironmentObject private var store: ReaderStore
    @Environment(\.dismiss) private var dismiss
    @State private var navigationError: String?
    @State private var navigating = false

    var body: some View {
        VStack(spacing: 0) {
            ReaderSheetHeader(title: "Find your place") { dismiss() }.disabled(navigating)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 18) {
                    if let navigationError { Text(navigationError).foregroundStyle(.red) }
                    if let snapshot = store.document {
                        if !snapshot.sections.isEmpty {
                            Text("CHAPTERS").font(.headline).accessibilityAddTraits(.isHeader)
                            ForEach(snapshot.sections) { section in
                                Button {
                                    open(section.passageId, documentId: snapshot.document.id, sourceRevision: snapshot.document.sourceRevision)
                                } label: {
                                    Text(section.title).frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).padding(.horizontal, 16)
                                        .background(Palette.soft, in: RoundedRectangle(cornerRadius: 16))
                                }.buttonStyle(.plain).disabled(navigating || !store.canNavigatePreparedSource)
                            }
                        }
                        Text("PAGES").font(.headline).accessibilityAddTraits(.isHeader)
                        ForEach(0..<snapshot.document.pageCount, id: \.self) { index in
                            Button {
                                if store.browseToPage(index, documentId: snapshot.document.id, sourceRevision: snapshot.document.sourceRevision) { dismiss() }
                                else { navigationError = "Finish the current action and try opening this page again." }
                            } label: {
                                Text("Page \(index + 1)").frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).padding(.horizontal, 16)
                                    .background(Palette.soft, in: RoundedRectangle(cornerRadius: 16))
                            }.buttonStyle(.plain).disabled(navigating || !store.canBrowseDocumentPages)
                        }
                    } else {
                        Text("No prepared pages yet").foregroundStyle(.secondary)
                    }
                }.padding(.horizontal, 24).padding(.vertical, 16)
            }
        }.background(Palette.paper).foregroundStyle(Palette.ink)
            .presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
            .interactiveDismissDisabled(navigating)
    }

    private func open(_ passageId: String, documentId: String, sourceRevision: String) {
        guard !navigating, store.canNavigatePreparedSource, store.selectedId == documentId,
              store.document?.document.sourceRevision == sourceRevision else { return }
        navigating = true
        navigationError = nil
        Task {
            defer { navigating = false }
            do {
                if try await store.goToPassage(documentId: documentId, passageId: passageId) { dismiss() }
                else { navigationError = "Finish the current action and try opening this passage again." }
            } catch { navigationError = error.localizedDescription }
        }
    }
}

struct ReaderSearchScope: Identifiable {
    let id = UUID()
    let documentId: String
}

struct ReaderSearchSheet: View {
    let documentId: String
    @EnvironmentObject private var store: ReaderStore
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var results: [SearchHit] = []
    @State private var searching = false
    @State private var searchError: String?
    @State private var navigationError: String?
    @State private var retry = 0
    @State private var request = UUID()
    @State private var navigating = false
    @State private var failedTarget: SearchHit?

    private struct Request: Hashable { let query: String; let retry: Int }
    private var trimmedQuery: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        VStack(spacing: 0) {
            ReaderSheetHeader(title: documentId.isEmpty ? "Search your library" : "Find a passage") { dismiss() }.disabled(navigating)
            VStack(spacing: 16) {
                HStack(spacing: 12) {
                    Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                    TextField("Words or a phrase", text: Binding(get: { query }, set: { query = $0; clearSearch() }))
                        .textInputAutocapitalization(.never).autocorrectionDisabled().disabled(navigating)
                        .accessibilityLabel("Words or a phrase")
                    if !query.isEmpty {
                        Button { query = ""; clearSearch() } label: { Image(systemName: "xmark.circle.fill").frame(width: 44, height: 44) }
                            .accessibilityLabel("Clear search").disabled(navigating)
                    }
                }.padding(.horizontal, 16).frame(minHeight: 56)
                    .background(Palette.soft, in: RoundedRectangle(cornerRadius: 12))
                    .overlay { RoundedRectangle(cornerRadius: 12).stroke(Palette.muted.opacity(0.5), lineWidth: 1) }
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        if searching || navigating {
                            HStack(spacing: 12) {
                                ProgressView()
                                Text(navigating ? "Opening passage…" : "Searching this device…").font(.subheadline)
                            }.frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        }
                        if let searchError {
                            HStack {
                                Text(searchError).foregroundStyle(.red)
                                Spacer(minLength: 8)
                                Button("Retry") { clearSearch(); retry += 1 }.frame(minHeight: 44).disabled(navigating)
                            }
                        }
                        if let navigationError {
                            HStack {
                                Text(navigationError).foregroundStyle(.red)
                                Spacer(minLength: 8)
                                if let failedTarget { Button("Retry") { open(failedTarget) }.frame(minHeight: 44).disabled(navigating) }
                            }
                        }
                        if trimmedQuery.isEmpty {
                            Text("Enter words or a phrase to search prepared passages and your thoughts.").padding(.vertical, 20)
                        } else if !searching && searchError == nil && results.isEmpty {
                            Text("No matches yet. Prepared pages and your thoughts are searched locally.").padding(.vertical, 20)
                        }
                        ForEach(results, id: \.stableId) { hit in
                            Button { open(hit) } label: {
                                VStack(alignment: .leading, spacing: 6) {
                                    Text(hit.title).font(.headline).lineLimit(1)
                                    Text("Page \(hit.pageIndex + 1) · \(hit.kind == "note" ? "Note" : "Passage")").font(.caption).foregroundStyle(.secondary)
                                    Text(hit.text).lineLimit(4).foregroundStyle(.secondary)
                                }.frame(maxWidth: .infinity, alignment: .leading).padding(16)
                                    .background(Palette.soft, in: RoundedRectangle(cornerRadius: 16))
                            }.buttonStyle(.plain).disabled(navigating || !store.canNavigatePreparedSource)
                        }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }.scrollDismissesKeyboard(.interactively)
            }.padding(.horizontal, 24).padding(.top, 16).padding(.bottom, 24)
        }.background(Palette.paper).foregroundStyle(Palette.ink)
            .presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
            .task(id: Request(query: query, retry: retry)) { await search() }
            .onDisappear { request = UUID() }
            .interactiveDismissDisabled(navigating)
    }

    private func clearSearch() {
        request = UUID(); results = []; searchError = nil; navigationError = nil; failedTarget = nil
        searching = !trimmedQuery.isEmpty
    }

    private func search() async {
        let id = UUID(), text = trimmedQuery
        request = id; results = []; searchError = nil
        guard !text.isEmpty else { searching = false; return }
        searching = true
        defer { if request == id { searching = false } }
        do {
            try await Task.sleep(for: .milliseconds(200))
            try Task.checkCancellation()
            let hits = try await store.search(text, documentId: documentId)
            try Task.checkCancellation()
            guard request == id, trimmedQuery == text else { return }
            results = hits
        } catch is CancellationError {
        } catch {
            guard request == id, !Task.isCancelled else { return }
            searchError = "Search could not finish. Try again."
        }
    }

    private func open(_ hit: SearchHit) {
        guard !navigating, store.canNavigatePreparedSource, documentId.isEmpty || hit.documentId == documentId else { return }
        navigating = true; navigationError = nil; failedTarget = nil
        Task {
            defer { navigating = false }
            do {
                if try await store.goToPassage(documentId: hit.documentId, passageId: hit.passageId) { dismiss() }
                else { failedTarget = hit; navigationError = "This passage could not be opened. Finish any recording or preview, then try again." }
            } catch is CancellationError {
            } catch { failedTarget = hit; navigationError = "This passage could not be opened. Try again." }
        }
    }
}

enum ReaderSearchFailure: LocalizedError {
    case unavailable
    var errorDescription: String? { "Your library is not ready yet. Try again." }
}
