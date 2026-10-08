import SwiftUI

struct ReaderTools: View {
    @EnvironmentObject private var store: ReaderStore
    @Environment(\.dismiss) private var dismiss
    @State private var navigationError: String?

    var body: some View {
        NavigationStack {
            List {
                if let navigationError { Text(navigationError).foregroundStyle(.red) }
                ForEach(store.document?.sections ?? []) { section in
                    Button {
                        guard let id = store.selectedId else { return }
                        Task {
                            do {
                                if try await store.goToPassage(documentId: id, passageId: section.passageId) { dismiss() }
                                else { navigationError = "Finish the current action and try opening this passage again." }
                            } catch { navigationError = error.localizedDescription }
                        }
                    } label: {
                        HStack { Label(section.title, systemImage: "doc.text"); Spacer(); Text("\(section.pageIndex + 1)").foregroundStyle(.secondary) }
                    }.disabled(!store.canNavigatePreparedSource)
                }
            }.overlay {
                if store.document?.sections.isEmpty != false { ContentUnavailableView("No prepared pages yet", systemImage: "doc.text") }
            }
            .navigationTitle("Contents").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
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
        NavigationStack {
            VStack(spacing: 0) {
                HStack {
                    Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                    TextField("Words or a phrase", text: Binding(get: { query }, set: { query = $0; clearSearch() }))
                        .textInputAutocapitalization(.never).autocorrectionDisabled().disabled(navigating)
                    if !query.isEmpty {
                        Button { query = ""; clearSearch() } label: { Image(systemName: "xmark.circle.fill").frame(width: 44, height: 44) }
                            .accessibilityLabel("Clear search").disabled(navigating)
                    }
                }.padding(.horizontal).frame(minHeight: 52).background(Palette.soft, in: RoundedRectangle(cornerRadius: 12)).padding()
                if navigating { ProgressView("Opening passage…").padding(.bottom) }
                if let navigationError {
                    HStack {
                        Text(navigationError).foregroundStyle(.red)
                        if let failedTarget { Button("Retry") { open(failedTarget) }.disabled(navigating) }
                    }.padding(.horizontal)
                }
                if searching {
                    ProgressView("Searching this device…").frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if let searchError {
                    ContentUnavailableView {
                        Label("Search unavailable", systemImage: "exclamationmark.magnifyingglass")
                    } description: { Text(searchError) } actions: {
                        Button("Retry") { clearSearch(); retry += 1 }.frame(minHeight: 44)
                    }
                } else if trimmedQuery.isEmpty {
                    ContentUnavailableView("Search locally", systemImage: "magnifyingglass", description: Text("Enter words or a phrase to search prepared passages and your thoughts."))
                } else if results.isEmpty {
                    ContentUnavailableView("No matches yet", systemImage: "magnifyingglass", description: Text("Prepared pages and your thoughts are searched locally."))
                } else {
                    List(results, id: \.stableId) { hit in
                        Button { open(hit) } label: {
                            VStack(alignment: .leading, spacing: 7) {
                                Text("Page \(hit.pageIndex + 1) · \(hit.kind == "note" ? "Note" : "Passage")").font(.caption).foregroundStyle(.secondary)
                                Text(hit.title).font(.headline)
                                Text(hit.text).font(.subheadline).lineLimit(4).foregroundStyle(.secondary)
                            }.padding(.vertical, 5)
                        }.disabled(navigating || !store.canNavigatePreparedSource)
                    }
                }
            }
            .navigationTitle(documentId.isEmpty ? "Search your library" : "Find a passage").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() }.disabled(navigating) } }
            .task(id: Request(query: query, retry: retry)) { await search() }
            .onDisappear { request = UUID() }
        }.interactiveDismissDisabled(navigating)
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
            searchError = error.localizedDescription
        }
    }

    private func open(_ hit: SearchHit) {
        guard !navigating, store.canNavigatePreparedSource else { return }
        navigating = true; navigationError = nil; failedTarget = nil
        Task {
            defer { navigating = false }
            do {
                if try await store.goToPassage(documentId: hit.documentId, passageId: hit.passageId) { dismiss() }
                else { failedTarget = hit; navigationError = "Finish the current action and try opening this passage again." }
            } catch is CancellationError {
            } catch { failedTarget = hit; navigationError = error.localizedDescription }
        }
    }
}

enum ReaderSearchFailure: LocalizedError {
    case unavailable
    var errorDescription: String? { "Your library is not ready yet. Try again." }
}
