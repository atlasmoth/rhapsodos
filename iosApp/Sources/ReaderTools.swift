import SwiftUI

struct ReaderTools: View {
    enum Tab: Hashable { case contents, search }
    @EnvironmentObject private var store: ReaderStore
    @Environment(\.dismiss) private var dismiss
    @State private var tab: Tab
    @State private var query = ""
    @State private var librarySearch: Bool
    @State private var results: [SearchHit] = []

    init(initialTab: Tab = .contents, wholeLibrary: Bool = false) {
        _tab = State(initialValue: initialTab)
        _librarySearch = State(initialValue: wholeLibrary)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("Browse", selection: $tab) { Text("Contents").tag(Tab.contents); Text("Search").tag(Tab.search) }
                    .pickerStyle(.segmented).padding()
                if tab == .contents {
                    List(store.document?.sections ?? []) { section in
                        Button { Task { await store.goToPassage(documentId: store.selectedId ?? "", passageId: section.passageId); dismiss() } } label: {
                            HStack { Label(section.title, systemImage: "doc.text"); Spacer(); Text("\(section.pageIndex + 1)").foregroundStyle(.secondary) }
                        }
                    }.overlay { if store.document?.sections.isEmpty != false { ContentUnavailableView("No prepared pages yet", systemImage: "doc.text") } }
                } else if tab == .search {
                    VStack(spacing: 0) {
                        Toggle("Search the whole library", isOn: $librarySearch).font(.subheadline).padding(.horizontal)
                        List(results, id: \.stableId) { hit in
                            Button { Task { await store.goToPassage(documentId: hit.documentId, passageId: hit.passageId); dismiss() } } label: {
                                VStack(alignment: .leading, spacing: 7) {
                                    Text(hit.title).font(.headline)
                                    Text(hit.text).font(.subheadline).lineLimit(4).foregroundStyle(.secondary)
                                    Label("Page \(hit.pageIndex + 1) · \(hit.kind == "note" ? "Note" : "Passage")", systemImage: hit.kind == "note" ? "text.bubble" : "doc.text").font(.caption)
                                }
                            }
                        }.overlay { if results.isEmpty { ContentUnavailableView.search(text: query) } }
                    }.searchable(text: $query, prompt: "Search passages and notes")
                        .task(id: query + String(librarySearch)) {
                            do { try await Task.sleep(for: .milliseconds(250)); try Task.checkCancellation() }
                            catch { return }
                            let hits = await store.search(query, entireLibrary: librarySearch)
                            if !Task.isCancelled { results = hits }
                        }
                }
            }
            .navigationTitle(librarySearch && tab == .search ? "Search your library" : "Find your place").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}
