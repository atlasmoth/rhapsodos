import SwiftUI
import UniformTypeIdentifiers

enum Palette {
    static let accent = Color(red: 0.35, green: 0.27, blue: 0.62)
    static let paper = Color(uiColor: .systemBackground)
    static let soft = Color(uiColor: .secondarySystemBackground)
}

struct ReaderRoot: View {
    @EnvironmentObject private var store: ReaderStore
    @EnvironmentObject private var audio: AudioCoordinator
    @EnvironmentObject private var feedback: Feedback
    @State private var importer = false
    @State private var settings = false
    @State private var columns: NavigationSplitViewVisibility = .all
    @State private var compactColumn: NavigationSplitViewColumn = .detail

    var body: some View {
        NavigationSplitView(columnVisibility: $columns, preferredCompactColumn: $compactColumn) {
            List(selection: Binding(get: { store.selectedId }, set: { id in if let id { Task { await store.select(id) } } })) {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Label("YOUR LISTENING SPACE", systemImage: "waveform").font(.caption2.weight(.semibold)).foregroundStyle(Palette.accent)
                        Text("A little more\nroom to think.").font(.system(.title, design: .serif).weight(.medium))
                        Text("Listen. Follow. Remember.").font(.subheadline).foregroundStyle(.secondary)
                    }.padding(.vertical, 14).listRowBackground(Color.clear).accessibilityElement(children: .combine)
                }
                Section("Library") {
                    ForEach(store.library) { document in
                        NavigationLink(value: document.id) {
                            HStack(spacing: 12) {
                                Image(systemName: "book.closed.fill").font(.title3).foregroundStyle(Palette.accent)
                                    .frame(width: 44, height: 54).background(Palette.accent.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
                                VStack(alignment: .leading, spacing: 5) {
                                    Text(document.title).font(.subheadline.weight(.semibold)).lineLimit(2)
                                    Text("\(document.pageCount) pages · \(document.passageCount == 0 ? "Source only" : "Ready to listen")")
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                            }.padding(.vertical, 5)
                        }
                    }
                    if store.library.isEmpty {
                        Button { importer = true } label: { Label("Import a PDF", systemImage: "plus") }
                            .disabled(store.busy)
                    }
                }
                Section {
                    Label("On this device", systemImage: "lock.shield").font(.footnote).foregroundStyle(.secondary)
                    Text("Your PDFs and voice notes stay here. No account or cloud processing.").font(.caption).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("eReader")
            .navigationSplitViewColumnWidth(min: 240, ideal: 290, max: 350)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button { importer = true } label: { Image(systemName: "plus") }.accessibilityLabel("Import PDF").disabled(store.busy || store.captureDraft != nil) } }
        } detail: {
            Group {
                if store.loading { ProgressView(store.preparation) }
                else if let document = store.document {
                    ListeningView(snapshot: document)
                        .navigationTitle(document.document.title)
                        .navigationBarTitleDisplayMode(.inline)
                } else { EmptyLibrary(importer: $importer).navigationTitle("eReader").navigationBarTitleDisplayMode(.inline) }
            }
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if store.document != nil {
                        Button { store.inspector.toggle(); feedback.play(.selection) } label: { Image(systemName: "text.bubble") }.accessibilityLabel("Show anchored notes")
                    }
                    Button { settings = true } label: { Image(systemName: "slider.horizontal.3") }.accessibilityLabel("Voice and pace")
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
}

private struct EmptyLibrary: View {
    @Binding var importer: Bool
    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "book.pages").font(.system(size: 52, weight: .light)).foregroundStyle(Palette.accent)
                .padding(30).background(Palette.accent.opacity(0.08), in: RoundedRectangle(cornerRadius: 34))
            VStack(spacing: 12) {
                Text("Give your reading\na voice.").font(.system(.largeTitle, design: .serif).weight(.medium)).multilineTextAlignment(.center)
                Text("Bring a PDF. Settle into a passage.\nSave a thought without losing your place.").foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
            Button { importer = true } label: { Label("Import your first PDF", systemImage: "plus").padding(.horizontal, 12).padding(.vertical, 6) }
                .buttonStyle(.borderedProminent).buttonBorderShape(.capsule)
            Label("Private by default", systemImage: "lock").font(.caption).foregroundStyle(.secondary)
        }.padding(32).frame(maxWidth: .infinity, maxHeight: .infinity).background(Palette.paper)
    }
}

private struct ListeningView: View {
    let snapshot: DocumentSnapshot
    @EnvironmentObject private var store: ReaderStore
    @EnvironmentObject private var audio: AudioCoordinator
    @EnvironmentObject private var feedback: Feedback
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Picker("Presentation", selection: $store.sourceMode) {
                    Text("Listen & follow").tag(false)
                    Text("Original PDF").tag(true)
                }.pickerStyle(.segmented).frame(maxWidth: 350)
                Spacer(minLength: 8)
                if !store.follow {
                    Button { store.follow = true } label: { Label("Follow", systemImage: "scope") }.font(.caption)
                }
            }.padding(.horizontal, 20).padding(.vertical, 12)
            if store.sourceMode {
                if let url = store.originalURL {
                PDFSurface(url: url, pageIndex: audio.current?.pageIndex ?? 0,
                    range: audio.current.map { NSRange(location: $0.pageStart, length: $0.pageEnd - $0.pageStart) },
                    follow: store.follow, onBrowse: { store.follow = false },
                    onLoadFailure: { store.errorMessage = "The original PDF could not be opened. Your listening text and notes are preserved." })
                } else {
                    ContentUnavailableView("Original PDF unavailable", systemImage: "doc", description: Text("Your listening text and anchored notes are preserved."))
                }
            } else {
                ScrollViewReader { reader in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 24) {
                            HStack {
                                Label(audio.playing ? "NOW LISTENING" : "YOUR PLACE", systemImage: audio.playing ? "waveform" : "bookmark")
                                    .font(.caption2.weight(.semibold)).tracking(1.1).foregroundStyle(Palette.accent)
                                Spacer()
                                Text("Page \((audio.current?.pageIndex ?? 0) + 1) of \(snapshot.document.pageCount)").font(.caption).foregroundStyle(.secondary)
                            }
                            if snapshot.passages.isEmpty {
                                ContentUnavailableView("This PDF needs text preparation", systemImage: "doc.text.magnifyingglass",
                                    description: Text("The original page is available. OCR for scans and complex layouts is not included in this first build."))
                            }
                            ForEach(Array(snapshot.passages.enumerated()), id: \.element.id) { index, passage in
                                let current = index == audio.index
                                VStack(alignment: .leading, spacing: 12) {
                                    if current { Label("Current passage", systemImage: "speaker.wave.2").font(.caption.weight(.medium)).foregroundStyle(Palette.accent) }
                                    Text(passage.sourceText).font(.system(.title3, design: .serif)).lineSpacing(8).textSelection(.enabled)
                                        .foregroundStyle(current ? Color.primary : Color.secondary)
                                    if current {
                                        Button { Task { await store.beginNote() } } label: { Label("Pause & record thought", systemImage: "mic") }
                                            .buttonStyle(.bordered).buttonBorderShape(.capsule).disabled(store.busy)
                                    } else {
                                        Button("Listen from here") { store.seek(passage); audio.play() }.font(.caption).foregroundStyle(Palette.accent)
                                    }
                                }
                                .padding(24).frame(maxWidth: .infinity, alignment: .leading)
                                .background(current ? Palette.accent.opacity(0.065) : Color.clear, in: RoundedRectangle(cornerRadius: 24))
                                .overlay { RoundedRectangle(cornerRadius: 24).strokeBorder(current ? Palette.accent.opacity(0.15) : .clear) }
                                .modifier(QuietGlow(active: current && audio.playing, restrained: feedback.restrained || reduceMotion))
                                .id(passage.id)
                                .accessibilityElement(children: .contain)
                            }
                        }.frame(maxWidth: 760).padding(.horizontal, 20).padding(.vertical, 24).frame(maxWidth: .infinity)
                    }
                    .simultaneousGesture(DragGesture(minimumDistance: 10).onChanged { _ in store.follow = false })
                    .task(id: snapshot.document.id) {
                        await Task.yield()
                        if store.follow, let current = audio.current { reader.scrollTo(current.id, anchor: .center) }
                    }
                    .onChange(of: audio.index) { _, _ in
                        if store.follow, let current = audio.current { reader.scrollTo(current.id, anchor: .center) }
                    }
                    .onChange(of: store.follow) { _, following in
                        if following, let current = audio.current { reader.scrollTo(current.id, anchor: .center) }
                    }
                }
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) { PlaybackDock(count: snapshot.passages.count) }
        .background(Palette.paper)
    }
}

private struct PlaybackDock: View {
    let count: Int
    @EnvironmentObject private var store: ReaderStore
    @EnvironmentObject private var audio: AudioCoordinator
    var body: some View {
        VStack(spacing: 14) {
            ProgressView(value: Double(audio.index), total: Double(max(1, count - 1))).tint(Palette.accent)
                .accessibilityLabel("Passage \(min(audio.index + 1, count)) of \(count)")
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 18) { passageLabel; controls }
                VStack(spacing: 10) { passageLabel; controls }
            }.disabled(count == 0 || store.busy || store.captureDraft != nil)
        }.padding(.horizontal, 20).padding(.vertical, 16).background(.regularMaterial)
    }

    private var passageLabel: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(count > 0 ? "Passage \(audio.index + 1) of \(count)" : "Original PDF").font(.caption.weight(.semibold))
            Text("On-device voice").font(.caption2).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading).fixedSize(horizontal: true, vertical: false)
    }

    private var controls: some View {
            HStack(spacing: 14) {
                Button { store.skip(-1) } label: { Image(systemName: "backward.end.fill").font(.title3).frame(width: 44, height: 44) }.accessibilityLabel("Previous passage")
                Button { store.togglePlayback() } label: {
                    Image(systemName: audio.playing ? "pause.fill" : "play.fill").font(.title2).frame(width: 56, height: 56)
                        .foregroundStyle(.white).background(Palette.accent, in: Circle())
                }.accessibilityLabel(audio.playing ? "Pause narration" : "Play narration")
                Button { store.skip(1) } label: { Image(systemName: "forward.end.fill").font(.title3).frame(width: 44, height: 44) }.accessibilityLabel("Next passage")
                Button { Task { await store.beginNote() } } label: { Image(systemName: "mic").font(.title3).frame(width: 44, height: 44) }.accessibilityLabel("Pause and record a thought")
            }.foregroundStyle(Palette.accent)
    }
}

private struct VoiceSettings: View {
    @EnvironmentObject private var audio: AudioCoordinator
    @EnvironmentObject private var feedback: Feedback
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            Form {
                Section("Installed voices") {
                    Picker("Voice", selection: Binding(get: { audio.selectedVoice }, set: audio.changeVoice)) {
                        ForEach(audio.voices, id: \.identifier) { voice in Text("\(voice.name) · \(voice.language)").tag(voice.identifier) }
                    }
                    Text("These are voices already available on this device. Additional system voices can be managed in iOS Accessibility settings.").font(.caption).foregroundStyle(.secondary)
                }
                Section("Listening pace") {
                    Picker("Pace", selection: Binding(get: { audio.pace }, set: audio.changePace)) {
                        Text("Calm").tag(Float(0.43)); Text("Natural").tag(Float(0.5)); Text("Brisk").tag(Float(0.56))
                    }.pickerStyle(.segmented)
                }
                Section("Feel") {
                    Toggle("Touch feedback", isOn: $feedback.enabled)
                    Text("Motion and visual effects respect Reduce Motion, Low Power Mode and thermal conditions.").font(.caption).foregroundStyle(.secondary)
                }
                Section("Privacy & readiness") {
                    Label("Narration stays on this device", systemImage: "lock.shield")
                    Text("Saved voice notes use on-device transcription when English recognition is available. Otherwise the recording remains saved for later.").font(.footnote).foregroundStyle(.secondary)
                    Text("Narration follows passages. Pausing and resuming starts again from the attached passage, rather than claiming precise word timing.").font(.footnote).foregroundStyle(.secondary)
                    Text("eReader 0.01 · Build 1").font(.caption).foregroundStyle(.secondary)
                }
            }.navigationTitle("Make it your voice").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }.presentationDetents([.medium, .large])
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
                    Text(draft.quote).font(.system(.title3, design: .serif)).lineSpacing(6).lineLimit(6)
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
                        HStack { if store.busy { ProgressView().tint(.white) }; Text("Save & resume").font(.headline); Image(systemName: "arrow.right") }
                            .frame(maxWidth: .infinity).padding(.vertical, 10)
                    }.buttonStyle(.borderedProminent).buttonBorderShape(.capsule).disabled(store.busy)
                    Button("Save and stay paused") { Task { await store.saveNote(resume: false) } }.frame(maxWidth: .infinity).disabled(store.busy)
                    Button("Keep draft for later") { Task { await store.keepDraft() } }.font(.footnote).frame(maxWidth: .infinity).disabled(store.busy)
                    Text("This note stays at the frozen passage. Transcription can finish after you resume.").font(.caption).foregroundStyle(.secondary)
                }.padding(24).frame(maxWidth: 640).frame(maxWidth: .infinity)
            }.navigationTitle("Keep the thought").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Discard") { discard = true }.disabled(store.busy) } }
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
    @State private var text = ""
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Anchored notes").font(.headline).accessibilityAddTraits(.isHeader)
                Spacer()
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
                            Text(note.quote).font(.system(.subheadline, design: .serif)).lineLimit(3).foregroundStyle(.secondary)
                            if !note.transcript.isEmpty { Text(note.transcript).font(.body).textSelection(.enabled) }
                            else if !note.isDraft { Text(note.status == "transcribing" ? "Saved. Transcribing on this device…" : "Recording saved. Transcript pending.").font(.footnote).foregroundStyle(.secondary) }
                            HStack {
                                if note.isDraft {
                                    Button("Recover draft") { Task { await store.recover(note) } }
                                } else {
                                    if !(note.recordingPath ?? "").isEmpty {
                                        Button { Task { await store.replay(note) } } label: { Image(systemName: audio.replayingNote == note.id ? "stop.fill" : "play.fill") }.accessibilityLabel("Play original voice note")
                                    }
                                    Button("Passage") { Task { await store.jump(to: note) } }
                                    Spacer()
                                    Menu {
                                        Button("Edit transcript") { text = note.transcript; editing = note }
                                        if !(note.recordingPath ?? "").isEmpty { Button("Retry on-device transcription") { store.retry(note) } }
                                    } label: { Image(systemName: "ellipsis") }.accessibilityLabel("Note actions")
                                }
                            }.font(.subheadline).disabled(store.busy)
                        }.padding(18).background(Palette.soft, in: RoundedRectangle(cornerRadius: 20))
                    }
                }.padding(18)
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
