import SwiftUI
import UniformTypeIdentifiers
import CoreGraphics
import MediaPlayer

struct ReaderRoot: View {
    @EnvironmentObject private var store: ReaderStore
    @EnvironmentObject private var audio: AudioCoordinator
    @EnvironmentObject private var feedback: Feedback
    @State private var importer = false
    @State private var settings = false
    @State private var gallery = false
    @State private var tools = false
    @State private var librarySearch = false
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var renameTarget: LibraryDocument?
    @State private var renamedTitle = ""
    @State private var deleteTarget: LibraryDocument?
    @State private var columns: NavigationSplitViewVisibility = .all
    @State private var compactColumn: NavigationSplitViewColumn = .detail

    var body: some View {
        NavigationSplitView(columnVisibility: $columns, preferredCompactColumn: $compactColumn) {
            List(selection: Binding(get: { store.selectedId }, set: { id in
                if let id { compactColumn = .detail; Task { await store.select(id) } }
            })) {
                Section {
                    VStack(alignment: .leading, spacing: 16) {
                        HStack(spacing: 6) {
                            RhapsodosMark().frame(width: 38, height: 38)
                            Text("Rhapsodos").font(BrandFont.display(26)).foregroundStyle(Palette.ink)
                        }.accessibilityElement(children: .combine)
                        ViewThatFits(in: .horizontal) {
                            HStack(spacing: 8) { libraryActions }.fixedSize(horizontal: true, vertical: false)
                            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) { libraryActions }
                        }
                    }.padding(.vertical, 8).listRowBackground(Color.clear)
                }
                Section("Library") {
                    ForEach(store.library) { document in
                        NavigationLink(value: document.id) {
                            HStack(spacing: 12) {
                                BookThumbnail(document: document, url: store.files?.ownedURL(document.filePath))
                                    .frame(width: 44, height: 58)
                                VStack(alignment: .leading, spacing: 5) {
                                    Text(document.title).font(.subheadline.weight(.semibold)).lineLimit(2)
                                    LibraryProgress(document: document)
                                }
                            }.padding(.vertical, 5)
                        }.accessibilityHint("Opens this book at your saved listening position.").contextMenu {
                            Button("Rename", systemImage: "pencil") { renameTarget = document; renamedTitle = document.title }
                            if document.extractionStatus == "preparing" { Button("Resume preparation", systemImage: "arrow.clockwise") { store.resumePreparation(document.id) } }
                            Button("Delete from this device", systemImage: "trash", role: .destructive) { deleteTarget = document }
                        }
                    }
                    if store.library.isEmpty {
                        Button { importer = true } label: { Label("Import a PDF", systemImage: "plus") }
                            .disabled(store.busy)
                    }
                }
            }
            .navigationTitle("Library")
            .navigationSplitViewColumnWidth(min: 240, ideal: 290, max: 350)
        } detail: {
            Group {
                if store.loading { ProgressView(store.preparation) }
                else if let document = store.document {
                    ListeningView(snapshot: document)
                        .navigationTitle(document.document.title)
                        .navigationBarTitleDisplayMode(.inline)
                } else { EmptyLibrary(importer: $importer).navigationTitle("Rhapsodos").navigationBarTitleDisplayMode(.inline) }
            }
            .toolbar {
                if horizontalSizeClass == .compact {
                    ToolbarItem(placement: .topBarLeading) {
                        Button { compactColumn = .sidebar; columns = .all } label: { Image(systemName: "books.vertical") }.accessibilityLabel("Open library")
                    }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if store.document != nil {
                        Button { librarySearch = false; tools = true } label: { Image(systemName: "text.magnifyingglass") }.accessibilityLabel("Pages and search")
                        Menu {
                            if !store.follow { Button("Follow narration", systemImage: "scope") { store.follow = true } }
                        } label: { Image(systemName: "ellipsis.circle") }.accessibilityLabel("Listening tools")
                        Button { store.inspector.toggle(); feedback.play(.selection) } label: { Image(systemName: "text.bubble") }.accessibilityLabel("Show anchored notes")
                    }
                }
            }
            .inspector(isPresented: $store.inspector) { NotesPanel().inspectorColumnWidth(min: 280, ideal: 330, max: 420) }
        }
        .navigationSplitViewStyle(.balanced)
        .onChange(of: store.selectedId) { _, id in if id != nil { compactColumn = .detail } }
        .fileImporter(isPresented: $importer, allowedContentTypes: [.pdf]) { result in
            switch result {
            case .success(let url): Task { await store.importPDF(url) }
            case .failure(let error): store.errorMessage = error.localizedDescription
            }
        }
        .sheet(isPresented: $settings) { VoiceSettings() }
        .sheet(isPresented: $gallery) { if let models = store.models { ModelGallery(models: models) } }
        .sheet(isPresented: $tools) { ReaderTools(initialTab: librarySearch ? .search : .contents, wholeLibrary: librarySearch) }
        .alert("Rename your document", isPresented: Binding(get: { renameTarget != nil }, set: { if !$0 { renameTarget = nil } })) {
            TextField("Title", text: $renamedTitle)
            Button("Save") { if let target = renameTarget { Task { await store.rename(target.id, title: renamedTitle) } }; renameTarget = nil }
            Button("Cancel", role: .cancel) { renameTarget = nil }
        }
        .confirmationDialog("Delete this document and its notes from this device?", isPresented: Binding(get: { deleteTarget != nil }, set: { if !$0 { deleteTarget = nil } }), titleVisibility: .visible) {
            Button("Delete document and notes", role: .destructive) { if let target = deleteTarget { Task { await store.deleteDocument(target.id) } }; deleteTarget = nil }
        }
        .sheet(item: $store.captureDraft) { draft in CaptureSheet(draft: draft).interactiveDismissDisabled() }
        .alert("Something needs attention", isPresented: Binding(get: { store.errorMessage != nil }, set: { if !$0 { store.errorMessage = nil } })) {
            Button("OK") { store.errorMessage = nil }
        } message: { Text(store.errorMessage ?? "") }
        .overlay(alignment: .top) {
            if store.busy && store.captureDraft == nil {
                HStack { ProgressView(); Text(store.preparation.isEmpty ? "Saving…" : store.preparation).font(.subheadline) }
                    .padding(14).background(.regularMaterial, in: Capsule()).padding(.top, 10)
            }
        }
    }

    private var libraryActions: some View {
        Group {
            LibraryActionButton(title: "Search", symbol: "magnifyingglass") { librarySearch = true; tools = true }
                .accessibilityLabel("Search the library")
            LibraryActionButton(title: "Voices", symbol: "headphones") { gallery = true }
                .accessibilityLabel("Offline voices and transcription").disabled(store.models == nil)
            LibraryActionButton(title: "Settings", symbol: "slider.horizontal.3") { settings = true }
                .accessibilityLabel("Voice, pace and appearance")
            LibraryActionButton(title: "Import", symbol: "plus") { importer = true }
                .accessibilityLabel("Import PDF").disabled(store.busy || store.captureDraft != nil)
        }
    }
}

private struct LibraryActionButton: View {
    let title: String
    let symbol: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            VStack(spacing: 7) {
                Image(systemName: symbol).font(.title3)
                Text(title).font(.caption).fixedSize(horizontal: false, vertical: true)
            }.frame(minWidth: 44, minHeight: 54).padding(6)
                .foregroundStyle(Palette.accent).background(Palette.soft, in: RoundedRectangle(cornerRadius: 12))
        }.buttonStyle(.plain)
    }
}

private struct LibraryProgress: View {
    let document: LibraryDocument
    private var status: String {
        if document.extractionStatus == "preparing" { return "Preparing listening text" }
        guard document.passageCount > 0 else { return "Source only" }
        guard let progress = document.listeningProgress, progress > 0 else { return "Not started" }
        if progress >= 1 { return "Finished" }
        return "\(Int((progress * 100).rounded(.down)))% listened"
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text("\(document.pageCount) pages · \(status)").font(.caption).foregroundStyle(.secondary)
            if let progress = document.listeningProgress, document.passageCount > 0 {
                ProgressView(value: min(1, max(0, progress))).tint(Palette.accent).accessibilityHidden(true)
            }
        }.accessibilityElement(children: .ignore).accessibilityLabel("\(document.pageCount) pages").accessibilityValue(status)
    }
}

private struct EmptyLibrary: View {
    @Binding var importer: Bool
    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "book.pages").font(.system(size: 52, weight: .light)).foregroundStyle(Palette.accent)
                .padding(30).background(Palette.accent.opacity(0.08), in: RoundedRectangle(cornerRadius: 34))
            VStack(spacing: 12) {
                Text("Give your reading\na voice.").font(BrandFont.display(34, relativeTo: .largeTitle)).multilineTextAlignment(.center)
            }
            Button { importer = true } label: { Label("Import your first PDF", systemImage: "plus").foregroundStyle(Palette.onAccent).padding(.horizontal, 12).padding(.vertical, 6) }
                .buttonStyle(.borderedProminent).buttonBorderShape(.capsule)
            Label("Private by default", systemImage: "lock").font(.caption).foregroundStyle(.secondary)
        }.padding(32).frame(maxWidth: .infinity, maxHeight: .infinity).background(Palette.paper)
    }
}

private struct ListeningView: View {
    let snapshot: DocumentSnapshot
    @EnvironmentObject private var store: ReaderStore
    @EnvironmentObject private var audio: AudioCoordinator
    @AppStorage("originalPDFColours") private var originalPDFColours = false

    var body: some View {
        VStack(spacing: 0) {
            if let url = store.originalURL {
                PDFSurface(url: url, pageIndex: audio.current?.pageIndex ?? 0,
                    baseRange: audio.current.flatMap { $0.provenance == "embedded" ? store.passageSourceRange : nil },
                    baseRegions: store.passageRegions,
                    range: audio.current.flatMap { $0.provenance == "embedded" ? store.highlightSourceRange : nil },
                    regions: store.highlightRegions, pageContexts: snapshot.pages.filter { $0.provenance == "ocr" },
                    geometrySpace: store.currentPage?.geometrySpace ?? "unspecified", sourceText: store.currentPage?.text ?? "", anchor: store.currentHighlightAnchor,
                    excerpt: audio.current?.displayText ?? "", recordingEnabled: store.canBeginNote,
                    originalColours: originalPDFColours, follow: store.follow, onBrowse: { store.follow = false },
                    onHighlight: { anchor in
                        guard store.canBeginNote, store.currentHighlightAnchor == anchor else { return false }
                        return store.beginNote(expected: anchor)
                    },
                    onLoadFailure: { store.errorMessage = "The original PDF could not be opened. Your listening text and notes are preserved." })
            } else {
                ContentUnavailableView("Original PDF unavailable", systemImage: "doc", description: Text("Your listening text and anchored notes are preserved."))
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) { PlaybackDock(document: snapshot.document, count: snapshot.passages.count).id(snapshot.document.id) }
        .background(Palette.paper)
    }
}

private struct PlaybackDock: View {
    let document: LibraryDocument
    let count: Int
    @EnvironmentObject private var store: ReaderStore
    @EnvironmentObject private var audio: AudioCoordinator
    @State private var seekPreview: Double?
    @State private var volumePresented = false
    var body: some View {
        VStack(spacing: 14) {
            if store.preparingDocument { ProgressView(store.preparation, value: store.preparationProgress).font(.caption2) }
            HStack(spacing: 12) {
                documentProgress
                Button { volumePresented = true } label: {
                    Image(systemName: "speaker.wave.2").font(.title3).frame(width: 44, height: 44)
                }.foregroundStyle(Palette.accent).accessibilityLabel("Output volume")
                    .popover(isPresented: $volumePresented) { OutputVolumePanel().presentationCompactAdaptation(.sheet).presentationDetents([.medium]) }
            }
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 18) { playbackStatus; controls }
                VStack(spacing: 10) { playbackStatus; controls }
            }.disabled(count == 0 || store.busy || store.captureDraft != nil)
        }.padding(.horizontal, 20).padding(.vertical, 16).background(.regularMaterial)
            .onChange(of: store.canSeekDocument) { _, enabled in if !enabled { seekPreview = nil } }
    }

    @ViewBuilder private var documentProgress: some View {
        if let progress = store.documentListeningProgress {
            HStack(spacing: 12) {
                Slider(value: Binding(get: { seekPreview ?? progress }, set: { seekPreview = $0 }), in: 0...1) { editing in
                    if editing { seekPreview = progress }
                    else if let value = seekPreview { commitSeek(value) }
                }.tint(Palette.accent)
                    .accessibilityLabel("Document listening progress")
                    .accessibilityValue("\(percent(seekPreview ?? progress)) percent")
                    .accessibilityAdjustableAction { direction in
                        switch direction {
                        case .increment: commitSeek(min(1, progress + 0.05))
                        case .decrement: commitSeek(max(0, progress - 0.05))
                        @unknown default: break
                        }
                    }
                    .disabled(!store.canSeekDocument)
                Text("\(percent(seekPreview ?? progress))%").font(BrandFont.mono()).fixedSize().accessibilityHidden(true)
            }
        } else {
            Text(document.extractionStatus == "preparing" ? "Document progress is available after preparation." : "No listening text is available.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    private func percent(_ progress: Double) -> Int { Int((min(1, max(0, progress)) * 100).rounded(.down)) }
    private func commitSeek(_ value: Double) {
        let id = document.id, revision = document.sourceRevision
        seekPreview = nil
        Task { await store.seekDocument(to: value, documentId: id, sourceRevision: revision) }
    }

    private var playbackStatus: some View {
        HStack(spacing: 12) {
            if let document = store.document?.document {
                BookThumbnail(document: document, url: store.originalURL)
                    .frame(width: 36, height: 48)
            }
            VStack(alignment: .leading, spacing: 4) {
                if audio.generating { Text("Preparing local audio…").font(.caption2).foregroundStyle(.secondary) }
            }
        }.frame(maxWidth: .infinity, alignment: .leading).fixedSize(horizontal: true, vertical: false)
    }

    private var controls: some View {
            HStack(spacing: 14) {
                Button { store.skip(-1) } label: { Image(systemName: "backward.end.fill").font(.title3).frame(width: 44, height: 44) }.accessibilityLabel("Previous passage")
                Button { store.togglePlayback() } label: {
                    Image(systemName: audio.playing ? "pause.fill" : "play.fill").font(.title2).frame(width: 56, height: 56)
                        .foregroundStyle(Palette.onAccent).background(Palette.accent, in: Circle())
                }.accessibilityLabel(audio.playing ? "Pause narration" : "Play narration")
                Button { store.skip(1) } label: { Image(systemName: "forward.end.fill").font(.title3).frame(width: 44, height: 44) }.accessibilityLabel("Next passage")
                Button { store.beginNote() } label: { Image(systemName: "mic").font(.title3).frame(width: 44, height: 44) }.accessibilityLabel("Pause and record a thought").disabled(!store.canBeginNote)
            }.foregroundStyle(Palette.accent)
    }
}

private struct OutputVolumePanel: View {
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text("Output volume").font(.headline)
                Spacer()
                Button("Done") { dismiss() }.frame(minWidth: 44, minHeight: 44)
            }
            SystemOutputVolume().frame(height: 44)
            #if targetEnvironment(simulator)
            Text("Volume changes work on a physical device.").font(.caption).foregroundStyle(.secondary)
            #endif
        }.padding(20).frame(idealWidth: 300).foregroundStyle(Palette.ink)
    }
}

private struct SystemOutputVolume: UIViewRepresentable {
    func makeUIView(context: Context) -> MPVolumeView {
        let view = MPVolumeView(frame: .zero)
        view.showsVolumeSlider = true
        view.backgroundColor = .clear
        view.tintColor = UIColor(Palette.accent)
        return view
    }
    func updateUIView(_ view: MPVolumeView, context: Context) { view.tintColor = UIColor(Palette.accent) }
}

private struct VoiceSettings: View {
    @EnvironmentObject private var audio: AudioCoordinator
    @EnvironmentObject private var store: ReaderStore
    @EnvironmentObject private var feedback: Feedback
    @State private var restorePicker = false
    @AppStorage("appearance") private var appearance = "system"
    @AppStorage("originalPDFColours") private var originalPDFColours = false
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            Form {
                Section("Installed voices") {
                    Picker("Voice", selection: Binding(get: { audio.selectedVoice }, set: { value in Task { await audio.changeVoice(value) } })) {
                        if audio.selectedVoice.hasPrefix("kokoro:"), store.models?.ready(capability: "tts") == nil { Text("Unavailable downloaded voice").tag(audio.selectedVoice).disabled(true) }
                        if store.models?.ready(capability: "tts") != nil { Text("Preview · Heart").tag("kokoro:af_heart"); Text("Preview · Bella").tag("kokoro:af_bella"); Text("Preview · Michael").tag("kokoro:am_michael") }
                        ForEach(audio.voices, id: \.identifier) { voice in Text("\(voice.name) · \(voice.language)").tag(voice.identifier) }
                    }
                    Button(audio.previewing ? "Stop sample" : "Listen to a sample") { if audio.previewing { audio.stopPreview() } else { audio.audition() } }
                        .disabled(store.busy || store.captureDraft != nil || audio.recording || (audio.selectedVoice.hasPrefix("kokoro:") && store.models?.ready(capability: "tts") == nil))
                    Text("These are voices already available on this device. Additional system voices can be managed in iOS Accessibility settings.").font(.caption).foregroundStyle(.secondary)
                }
                Section("Listening pace") {
                    Picker("Pace", selection: Binding(get: { audio.pace }, set: { value in Task { await audio.changePace(value) } })) {
                        Text("Calm").tag(Float(0.43)); Text("Natural").tag(Float(0.5)); Text("Brisk").tag(Float(0.56))
                    }.pickerStyle(.segmented)
                }
                Section("Appearance") {
                    Picker("Theme", selection: $appearance) { Text("System").tag("system"); Text("Light").tag("light"); Text("Dark").tag("dark") }.pickerStyle(.segmented)
                    Toggle("Original colours", isOn: $originalPDFColours)
                    Text("Strong colours are preserved. Use original colours for photographs.").font(.caption).foregroundStyle(.secondary)
                }
                Section("Feel") {
                    Toggle("Touch feedback", isOn: $feedback.enabled)
                    Text("Motion and visual effects respect Reduce Motion, Low Power Mode and thermal conditions.").font(.caption).foregroundStyle(.secondary)
                }
                Section("Documents") {
                    Button("Restore a document archive", systemImage: "square.and.arrow.down") { restorePicker = true }
                        .disabled(store.busy || store.captureDraft != nil || audio.recording)
                }
                Section("Privacy & readiness") {
                    Label("Narration stays on this device", systemImage: "lock.shield")
                    Text("Saved voice notes use on-device transcription when English recognition is available. Otherwise the recording remains saved for later.").font(.footnote).foregroundStyle(.secondary)
                    Text("Narration follows passages. A regular pause continues the current voice. Voice notes remain attached to the frozen passage; recording resumes narration from that passage.").font(.footnote).foregroundStyle(.secondary)
                    Text("Rhapsodos 0.01 · Build 1").font(.caption).foregroundStyle(.secondary)
                }
            }.navigationTitle("Make it your voice").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }.presentationDetents([.medium, .large])
            .fileImporter(isPresented: $restorePicker, allowedContentTypes: [.rhapsodos, .zip, .archive]) { result in
                dismiss()
                switch result {
                case .success(let url): Task { await store.restoreArchive(url) }
                case .failure(let error): store.errorMessage = error.localizedDescription
                }
            }
            .onDisappear { audio.stopPreview() }
    }
}

private struct CaptureSheet: View {
    let draft: VoiceNote
    @EnvironmentObject private var store: ReaderStore
    @EnvironmentObject private var audio: AudioCoordinator
    @State private var discard = false
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Label("ATTACHED TO PAGE \(draft.pageIndex + 1)", systemImage: "pin.fill").font(.caption.weight(.semibold)).foregroundStyle(Palette.accent)
                    Text(draft.quote).font(BrandFont.body(21, relativeTo: .title3)).lineSpacing(6).lineLimit(6)
                        .padding(20).frame(maxWidth: .infinity, alignment: .leading).background(Palette.accent.opacity(0.06), in: RoundedRectangle(cornerRadius: 20))
                    VStack(spacing: 12) {
                        HStack(spacing: 7) {
                            ForEach(0..<9) { index in
                                Capsule().fill(audio.recording ? Palette.accent : Color.secondary.opacity(0.3))
                                    .frame(width: 7, height: 8 + CGFloat(audio.level) * CGFloat(22 + (index % 4) * 12))
                            }
                        }.frame(height: 64).accessibilityHidden(true)
                        Text(audio.recording ? "Recording your thought" : "Your thought, safely attached").font(.headline)
                        if audio.recording { Text(Duration.seconds(audio.recordingSeconds).formatted(.time(pattern: .minuteSecond))).font(.title2.monospacedDigit()) }
                    }.frame(maxWidth: .infinity)
                    Text(store.captureMessage).font(.subheadline).foregroundStyle(.secondary)
                    if !audio.recording {
                        TextField("Or write your thought…", text: $store.typedThought, axis: .vertical).lineLimit(4...10)
                            .padding(16).background(Palette.soft, in: RoundedRectangle(cornerRadius: 16)).accessibilityLabel("Typed note")
                    }
                    Button { Task { await store.saveNote() } } label: {
                        HStack { if store.busy { ProgressView().tint(Palette.onAccent) }; Text("Save & resume").font(.headline).foregroundStyle(Palette.onAccent); Image(systemName: "arrow.right").foregroundStyle(Palette.onAccent) }
                            .frame(maxWidth: .infinity).padding(.vertical, 10)
                    }.buttonStyle(.borderedProminent).buttonBorderShape(.capsule).disabled(store.busy)
                    Button("Save and stay paused") { Task { await store.saveNote(resume: false) } }.frame(maxWidth: .infinity).disabled(store.busy)
                    Button(store.captureDraft?.isDraft == false ? "Close saved thought" : "Keep draft for later") { Task { await store.keepDraft() } }.font(.footnote).frame(maxWidth: .infinity).disabled(store.busy)
                    Text("This note stays at the frozen passage. Transcription can finish after you resume.").font(.caption).foregroundStyle(.secondary)
                }.padding(24).frame(maxWidth: 640).frame(maxWidth: .infinity)
            }.navigationTitle("Keep the thought").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) {
                    if store.captureDraft?.isDraft == true { Button("Discard") { discard = true }.disabled(store.busy) }
                    else { Button("Done") { Task { await store.keepDraft() } }.disabled(store.busy) }
                } }
                .confirmationDialog("Discard this unsaved thought?", isPresented: $discard, titleVisibility: .visible) {
                    Button("Discard draft", role: .destructive) { Task { await store.discardDraft() } }
                }
        }
    }
}

private struct NotesPanel: View {
    @EnvironmentObject private var store: ReaderStore
    @EnvironmentObject private var audio: AudioCoordinator
    @State private var editing: VoiceNote?
    @State private var deleting: VoiceNote?
    @State private var audioImporter = false
    @State private var importedAnchor: Passage?
    @State private var text = ""
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Anchored notes").font(.headline).accessibilityAddTraits(.isHeader)
                Spacer()
                Menu {
                    Button("Import saved audio", systemImage: "waveform.badge.plus") { importedAnchor = store.currentPassage; audio.pause(); audioImporter = importedAnchor != nil }
                } label: { Image(systemName: "plus") }.accessibilityLabel("Attach a saved recording").disabled(store.busy || store.currentPassage == nil)
                Button("Done") { store.inspector = false }
            }.padding(20)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 18) {
                    if store.currentNotes.isEmpty {
                        ContentUnavailableView("Keep a thought", systemImage: "text.bubble", description: Text("Pause and record. Your note will stay connected to the passage."))
                    }
                    ForEach(store.currentNotes) { note in
                        VStack(alignment: .leading, spacing: 14) {
                            HStack { Label("Page \(note.pageIndex + 1)", systemImage: "pin").font(.caption.weight(.semibold)); Spacer(); status(note) }
                            Text(note.quote).font(BrandFont.body(15, relativeTo: .subheadline)).lineLimit(3).foregroundStyle(.secondary)
                            if !note.transcript.isEmpty { Text(note.transcript).font(.body).textSelection(.enabled) }
                            else if !note.isDraft { Text(note.status == "transcribing" ? "Saved. Transcribing on this device…" : audio.playing ? "Recording saved. Transcription waits while you listen." : "Recording saved. Transcript pending.").font(.footnote).foregroundStyle(.secondary) }
                            HStack {
                                if note.isDraft {
                                    Button("Recover draft") { Task { await store.recover(note) } }
                                } else {
                                    if !(note.recordingPath ?? "").isEmpty {
                                        Button { Task { await store.replay(note) } } label: { Image(systemName: audio.replayingNote == note.id && !audio.replayPaused ? "pause.fill" : "play.fill") }.accessibilityLabel(audio.replayingNote == note.id && !audio.replayPaused ? "Pause voice note" : "Play original voice note")
                                    }
                                    Button("Passage") { Task { await store.jump(to: note) } }
                                    Spacer()
                                    Menu {
                                        Button("Edit transcript") { text = note.transcript; editing = note }
                                        Button("Delete note", systemImage: "trash", role: .destructive) { deleting = note }
                                        if !(note.recordingPath ?? "").isEmpty { Button("Retry on-device transcription") { store.retry(note) } }
                                    } label: { Image(systemName: "ellipsis") }.accessibilityLabel("Note actions")
                                }
                            }.font(.subheadline).disabled(store.busy)
                        }.padding(18).background(Palette.soft, in: RoundedRectangle(cornerRadius: 20))
                    }
                }.padding(18)
            }
                .fileImporter(isPresented: $audioImporter, allowedContentTypes: [.audio]) { result in
                    if case .success(let url) = result, let anchor = importedAnchor { Task { await store.importAudio(url, at: anchor) } }
                    if case .failure(let error) = result { store.errorMessage = error.localizedDescription }
                    importedAnchor = nil
                }
                .confirmationDialog("Delete this note and its original recording?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
                    Button("Delete note", role: .destructive) { if let note = deleting { Task { await store.deleteNote(note) } }; deleting = nil }
                }
                .sheet(item: $editing) { note in
                    NavigationStack {
                        TextEditor(text: $text).padding(20).navigationTitle("Edit thought")
                            .toolbar {
                                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { editing = nil } }
                                ToolbarItem(placement: .confirmationAction) { Button("Save") { Task { await store.edit(note, text: text); editing = nil } } }
                            }
                    }
                }
        }
    }

    private func status(_ note: VoiceNote) -> some View {
        Text(note.isDraft ? "Draft" : ["transcribed", "text"].contains(note.status) ? "Saved" : note.status == "transcribing" ? "Transcribing" : "Pending")
            .font(.caption2.weight(.medium)).foregroundStyle(Palette.accent)
            .padding(.horizontal, 8).padding(.vertical, 5).background(Palette.accent.opacity(0.08), in: Capsule())
    }
}

private struct BookThumbnail: View {
    let document: LibraryDocument
    let url: URL?
    @State private var image: UIImage?
    @State private var loadedIdentity: String?

    private var identity: String { document.id + ":" + document.sourceRevision + ":" + (url?.path ?? "") }

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 5).fill(Palette.soft)
            if loadedIdentity == identity, let image {
                Image(uiImage: image).resizable().scaledToFit()
            } else {
                Image(systemName: "doc.richtext").font(.title3).foregroundStyle(Palette.muted)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 5))
        .overlay { RoundedRectangle(cornerRadius: 5).strokeBorder(Palette.muted.opacity(0.18), lineWidth: 0.5) }
        .accessibilityHidden(true)
        .task(id: identity, priority: .utility) {
            image = nil
            loadedIdentity = nil
            guard let url else { return }
            let requested = identity
            let thumbnail = await BookThumbnailCache.shared.image(document: document, url: url)
            guard !Task.isCancelled else { return }
            image = thumbnail
            loadedIdentity = requested
        }
    }
}

private actor BookThumbnailCache {
    static let shared = BookThumbnailCache()
    private let images = NSCache<NSString, UIImage>()
    private let pixelLimit = 256

    init() {
        images.countLimit = 48
        images.totalCostLimit = 8 * 1024 * 1024
    }

    func image(document: LibraryDocument, url: URL) -> UIImage? {
        guard !Task.isCancelled, let signature = fileSignature(url) else { return nil }
        let key = ["original-cover-v1", document.id, document.sourceRevision, url.lastPathComponent, signature, String(pixelLimit)]
            .joined(separator: "\u{0}") as NSString
        if let cached = images.object(forKey: key) { return cached }
        let rendered = autoreleasepool { render(url) }
        guard !Task.isCancelled, fileSignature(url) == signature, let rendered else { return nil }
        let cost = (rendered.cgImage?.bytesPerRow ?? 0) * (rendered.cgImage?.height ?? 0)
        images.setObject(rendered, forKey: key, cost: cost)
        return rendered
    }

    private func render(_ url: URL) -> UIImage? {
        guard let pdf = CGPDFDocument(url as CFURL), pdf.isUnlocked, let page = pdf.page(at: 1) else { return nil }
        let bounds = page.getBoxRect(.cropBox).intersection(page.getBoxRect(.mediaBox))
        guard bounds.width.isFinite, bounds.height.isFinite, bounds.width > 0, bounds.height > 0 else { return nil }
        let quarterTurn = abs(page.rotationAngle % 180) == 90
        let size = CGSize(width: quarterTurn ? bounds.height : bounds.width, height: quarterTurn ? bounds.width : bounds.height)
        let scale = CGFloat(pixelLimit) / max(size.width, size.height)
        let width = max(1, min(pixelLimit, Int((size.width * scale).rounded())))
        let height = max(1, min(pixelLimit, Int((size.height * scale).rounded())))
        guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        let target = CGRect(x: 0, y: 0, width: width, height: height)
        context.setFillColor(UIColor.white.cgColor)
        context.fill(target)
        context.concatenate(page.getDrawingTransform(.cropBox, rect: target, rotate: 0, preserveAspectRatio: true))
        context.drawPDFPage(page)
        guard let image = context.makeImage() else { return nil }
        return UIImage(cgImage: image)
    }

    private func fileSignature(_ url: URL) -> String? {
        guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]),
            values.isRegularFile == true, let bytes = values.fileSize, bytes > 0, bytes <= 512 * 1024 * 1024,
            let modified = values.contentModificationDate else { return nil }
        return String(bytes) + ":" + String(modified.timeIntervalSince1970)
    }
}
