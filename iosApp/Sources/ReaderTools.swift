import SwiftUI

struct ReaderTools: View {
    @EnvironmentObject private var store: ReaderStore
    @EnvironmentObject private var audio: AudioCoordinator
    @Environment(\.dismiss) private var dismiss
    @State private var tab = 0
    @State private var query = ""
    @State private var librarySearch = false
    @State private var results: [SearchHit] = []
    @State private var term = ""
    @State private var replacement = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("Browse", selection: $tab) { Text("Contents").tag(0); Text("Saved").tag(1); Text("Search").tag(2); Text("Voice").tag(3) }
                    .pickerStyle(.segmented).padding()
                if tab == 0 {
                    List(store.document?.sections ?? []) { section in
                        Button { Task { await store.goToPassage(documentId: store.selectedId ?? "", passageId: section.passageId); dismiss() } } label: {
                            HStack { Label(section.title, systemImage: "doc.text"); Spacer(); Text("\(section.pageIndex + 1)").foregroundStyle(.secondary) }
                        }
                    }.overlay { if store.document?.sections.isEmpty != false { ContentUnavailableView("No prepared pages yet", systemImage: "doc.text") } }
                } else if tab == 1 {
                    List(store.currentBookmarks) { bookmark in
                        Button { Task { await store.goToPassage(documentId: bookmark.documentId, passageId: bookmark.passageId); dismiss() } } label: {
                            Label(bookmark.label, systemImage: "bookmark.fill").lineLimit(3)
                        }
                    }.overlay { if store.currentBookmarks.isEmpty { ContentUnavailableView("Keep your favorite passages", systemImage: "bookmark", description: Text("Use the bookmark button while following a passage.")) } }
                } else if tab == 2 {
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
                } else {
                    Form {
                        Section("Say it your way") {
                            TextField("Written term", text: $term).textInputAutocapitalization(.never)
                            TextField("Speak as", text: $replacement)
                            Button("Save pronunciation") { Task { await store.savePronunciation(term: term, replacement: replacement); term = ""; replacement = "" } }
                                .disabled(term.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || replacement.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || store.busy)
                            Text("Changes the narration for this document. The original text and note attachments stay intact. Playback pauses while the speech text is rebuilt.").font(.footnote).foregroundStyle(.secondary)
                        }
                        Section("This document") {
                            ForEach(store.document?.pronunciations ?? []) { rule in
                                HStack { VStack(alignment: .leading) { Text(rule.term).font(.headline); Text(rule.replacement).font(.subheadline).foregroundStyle(.secondary) }; Spacer(); Button("Remove", role: .destructive) { Task { await store.removePronunciation(rule) } } }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Find your place").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

struct SleepTimerView: View {
    @EnvironmentObject private var audio: AudioCoordinator
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            List {
                if let end = audio.sleepUntil {
                    Section { Label("Pause at \(end.formatted(date: .omitted, time: .shortened))", systemImage: "moon.zzz.fill") }
                    Button("Cancel sleep timer") { audio.setSleepTimer(minutes: nil); dismiss() }
                }
                Section("Keep listening for") {
                    ForEach([5, 15, 30, 45, 60], id: \.self) { minutes in
                        Button("\(minutes) minutes") { audio.setSleepTimer(minutes: minutes); dismiss() }
                    }
                }
                Text("The timer pauses narration and keeps your place.").font(.footnote).foregroundStyle(.secondary)
            }.navigationTitle("Sleep timer").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }.presentationDetents([.medium, .large])
    }
}
