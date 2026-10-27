import SwiftUI
import UniformTypeIdentifiers
import CoreGraphics
import MediaPlayer
import AVFoundation

struct ReaderRoot: View {
    @EnvironmentObject private var store: ReaderStore
    @EnvironmentObject private var audio: AudioCoordinator
    @EnvironmentObject private var feedback: Feedback
    @State private var importer = false
    @State private var settings = false
    @State private var tools = false
    @State private var searchScope: ReaderSearchScope?
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var renameTarget: LibraryDocument?
    @State private var renamedTitle = ""
    @State private var deleteTarget: LibraryDocument?
    @State private var columns: NavigationSplitViewVisibility = .all
    @State private var compactColumn: NavigationSplitViewColumn = .sidebar
    @State private var phoneLibrary = false
    @State private var importQueue = false

    private let usesPhoneNavigation = UIDevice.current.userInterfaceIdiom == .phone

    var body: some View {
        Group {
            if usesPhoneNavigation { phoneNavigation }
            else { splitNavigation }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if !usesPhoneNavigation { playbackDock }
        }
        .overlay(alignment: .leading) {
            if !libraryVisible && !settings && !tools && searchScope == nil && store.captureDraft == nil && store.preparedArchive == nil {
                LibraryEdgeReveal { setLibraryVisible(true) }
                    .frame(width: 24).frame(maxHeight: .infinity)
                    .padding(.top, 44).accessibilityHidden(true)
            }
        }
        .onChange(of: store.selectedId, initial: true) { _, id in
            compactColumn = id == nil ? .sidebar : .detail
            phoneLibrary = false
            if id == nil { columns = .all }
        }
        .fileImporter(isPresented: $importer, allowedContentTypes: [.pdf]) { result in
            switch result {
            case .success(let url): phoneLibrary = false; store.receivePDF(url)
            case .failure(let error): store.errorMessage = error.localizedDescription
            }
        }
        .sheet(isPresented: $settings) { VoiceSettings() }
        .sheet(isPresented: $importQueue, onDismiss: { phoneLibrary = false }) { PDFImportQueueSheet() }
        .sheet(item: $store.speechSetupSheet) { capability in
            if let models = store.models { SpeechSetupSheet(capability: capability, models: models) }
        }
        .sheet(isPresented: $tools) { ReaderTools() }
        .sheet(item: $searchScope) { scope in ReaderSearchSheet(documentId: scope.documentId) }
        .alert("Rename your document", isPresented: Binding(get: { renameTarget != nil }, set: { if !$0 { renameTarget = nil } })) {
            TextField("Title", text: $renamedTitle)
            Button("Save") { if let target = renameTarget { Task { await store.rename(target.id, title: renamedTitle) } }; renameTarget = nil }
            Button("Cancel", role: .cancel) { renameTarget = nil }
        }
        .confirmationDialog("Delete this document and its notes from this device?", isPresented: Binding(get: { deleteTarget != nil }, set: { if !$0 { deleteTarget = nil } }), titleVisibility: .visible) {
            Button("Delete document and notes", role: .destructive) { if let target = deleteTarget { Task { await store.deleteDocument(target.id) } }; deleteTarget = nil }
        }
        .sheet(item: $store.captureDraft) { draft in
            CaptureSheet(draft: draft).interactiveDismissDisabled().modifier(ImportNavigationBlocker(blocked: true))
        }
        .alert("Something needs attention", isPresented: Binding(get: { store.errorMessage != nil }, set: { if !$0 { store.errorMessage = nil } })) {
            Button("OK") { store.errorMessage = nil }
        } message: { Text(store.errorMessage ?? "") }
        .overlay(alignment: .top) {
            if store.busy && store.captureDraft == nil {
                HStack { ProgressView(); Text(store.preparation.isEmpty ? "Saving…" : store.preparation).font(.subheadline) }
                    .padding(14).background(.regularMaterial, in: Capsule()).padding(.top, 10)
            }
        }
        .modifier(ImportNavigationBlocker(blocked: importer || settings || tools || importQueue || searchScope != nil
            || renameTarget != nil || deleteTarget != nil))
    }

    private var phoneNavigation: some View {
        NavigationStack {
            phoneReader
                .inspector(isPresented: $store.inspector) { NotesPanel().inspectorColumnWidth(min: 280, ideal: 330, max: 420) }
        }
    }

    @ViewBuilder private var phoneReader: some View {
        if #available(iOS 27.1, *) {
            phoneContent.modifier(PhoneReaderChrome(
                libraryVisible: libraryVisible, document: playbackDocument, header: readerHeader,
                showLibrary: { setLibraryVisible(true) }, showContents: { tools = true },
                showSearch: { if let id = store.selectedId { searchScope = ReaderSearchScope(documentId: id) } },
                showNotes: { store.inspector.toggle(); feedback.play(.selection) }))
        } else {
            phoneContent
                .safeAreaInset(edge: .top, spacing: 0) { if !libraryVisible { readerHeader } }
                .safeAreaInset(edge: .bottom, spacing: 0) { playbackDock }
                .toolbar(.hidden, for: .navigationBar)
        }
    }

    private var phoneContent: some View {
        ZStack {
            readerContent
                .opacity(libraryVisible ? 0 : 1)
                .allowsHitTesting(!libraryVisible)
                .accessibilityHidden(libraryVisible)
            if libraryVisible {
                libraryList.listStyle(.insetGrouped).transition(.move(edge: .leading).combined(with: .opacity))
            }
        }
    }

    @ViewBuilder private var playbackDock: some View {
        if let document = playbackDocument { PlaybackDock(document: document, count: audio.passages.count).id(document.id) }
    }

    private var splitNavigation: some View {
        NavigationSplitView(columnVisibility: $columns, preferredCompactColumn: $compactColumn) {
            libraryList
                .listStyle(.sidebar)
                .navigationSplitViewColumnWidth(min: 240, ideal: 290, max: 350)
                .toolbar(removing: .sidebarToggle)
                .toolbar(.hidden, for: .navigationBar)
        } detail: {
            readerContent
                .safeAreaInset(edge: .top, spacing: 0) { readerHeader }
                .toolbar(.hidden, for: .navigationBar)
                .toolbar(removing: .sidebarToggle)
                .navigationBarBackButtonHidden()
                .inspector(isPresented: $store.inspector) { NotesPanel().inspectorColumnWidth(min: 280, ideal: 330, max: 420) }
        }
        .toolbar(removing: .sidebarToggle)
        .navigationSplitViewStyle(.balanced)
    }

    private var libraryList: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 16) {
                    HStack(spacing: 6) {
                        HStack(spacing: 6) {
                            RhapsodosMark().frame(width: 38, height: 38)
                            Text("Rhapsodos").font(BrandFont.display(26)).foregroundStyle(Palette.ink)
                        }.accessibilityElement(children: .combine)
                        Spacer(minLength: 0)
                        if store.document != nil {
                            Button { setLibraryVisible(false) } label: {
                                Image(systemName: "sidebar.left").frame(width: 44, height: 44)
                            }
                            .buttonStyle(.plain)
                            .foregroundStyle(Palette.accent)
                            .accessibilityLabel("Hide library")
                            .accessibilityHint("Focuses the document while keeping your listening position")
                        }
                    }
                    HStack(spacing: 12) { libraryActions }
                }.padding(.vertical, 8).listRowBackground(Color.clear)
            }
            if !store.incomingPDFs.isEmpty || store.receivingPDFs > 0 {
                Section {
                    Button { importQueue = true; feedback.play(.selection) } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "arrow.down.document")
                            Text("PDF imports").font(.subheadline.weight(.semibold))
                            Spacer(minLength: 0)
                            if store.receivingPDFs > 0 { ProgressView() }
                            else { Text(store.incomingPDFs.count.formatted()).foregroundStyle(Palette.muted) }
                            Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(Palette.muted)
                        }.frame(minHeight: 44).contentShape(Rectangle())
                    }.buttonStyle(.plain)
                }
            }
            Section("Library") {
                ForEach(store.library) { document in
                    Button { selectDocument(document.id) } label: {
                        HStack(spacing: 12) {
                            BookThumbnail(document: document, url: store.files?.ownedURL(document.filePath))
                                .frame(width: 44, height: 58)
                            VStack(alignment: .leading, spacing: 5) {
                                Text(document.title).font(.subheadline.weight(.semibold)).lineLimit(2)
                                LibraryProgress(document: document)
                            }
                            Spacer(minLength: 0)
                        }.padding(.vertical, 5).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(store.selectedId == document.id ? Palette.accent.opacity(0.15) : nil)
                    .accessibilityAddTraits(store.selectedId == document.id ? .isSelected : [])
                    .accessibilityHint("Opens this book at your saved listening position.")
                    .contextMenu {
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
    }

    @ViewBuilder private var readerContent: some View {
        if store.loading { ProgressView(store.preparation) }
        else if let document = store.document { ListeningView(snapshot: document) }
        else { EmptyLibrary(importer: $importer) }
    }

    private var readerHeader: some View {
        HStack(spacing: 4) {
            if !libraryVisible {
                Button { setLibraryVisible(true) } label: { Image(systemName: "sidebar.left").frame(width: 44, height: 44) }
                    .accessibilityLabel("Show library")
                    .accessibilityHint("Reveals the library without changing narration")
            }
            Spacer(minLength: 0)
            if store.document != nil {
                Button { tools = true } label: { Image(systemName: "list.bullet.rectangle").frame(width: 44, height: 44) }
                    .accessibilityLabel("Document contents")
                Button { if let id = store.selectedId { searchScope = ReaderSearchScope(documentId: id) } } label: { Image(systemName: "magnifyingglass").frame(width: 44, height: 44) }
                    .accessibilityLabel("Find a passage").disabled(!store.canNavigatePreparedSource)
                Button { store.inspector.toggle(); feedback.play(.selection) } label: { Image(systemName: "text.bubble").frame(width: 44, height: 44) }
                    .accessibilityLabel("Show anchored notes")
            }
        }
        .buttonStyle(.plain)
        .foregroundStyle(Palette.accent)
        .padding(.horizontal, 8)
        .background(.bar)
    }

    private var libraryVisible: Bool {
        if usesPhoneNavigation { return phoneLibrary || (store.document == nil && !store.loading) }
        return horizontalSizeClass == .compact ? compactColumn == .sidebar : columns != .detailOnly
    }

    private func selectDocument(_ id: String) {
        Task {
            await store.select(id)
            if store.selectedId == id {
                compactColumn = .detail
                phoneLibrary = false
            }
        }
    }

    private func setLibraryVisible(_ visible: Bool) {
        feedback.play(.selection)
        withAnimation(reduceMotion ? nil : .snappy(duration: 0.24)) {
            phoneLibrary = visible
            columns = visible ? .all : .detailOnly
            compactColumn = visible && horizontalSizeClass == .compact ? .sidebar : .detail
        }
    }

    private var playbackDocument: LibraryDocument? {
        if let id = audio.current?.documentId {
            return store.library.first { $0.id == id } ?? (store.document?.document.id == id ? store.document?.document : nil)
        }
        return store.document?.document
    }

    private var libraryActions: some View {
        Group {
            LibraryActionButton(symbol: "magnifyingglass") { searchScope = ReaderSearchScope(documentId: "") }
                .accessibilityLabel("Search the library").disabled(!store.canNavigatePreparedSource)
            LibraryActionButton(symbol: "plus") { importer = true }
                .accessibilityLabel("Import PDF").disabled(store.busy || store.captureDraft != nil)
            LibraryActionButton(symbol: "slider.horizontal.3") { settings = true }
                .accessibilityLabel("Settings")
        }
    }
}

@available(iOS 27.1, *)
private struct PhoneReaderChrome<Header: View>: ViewModifier {
    let libraryVisible: Bool
    let document: LibraryDocument?
    let header: Header
    let showLibrary: () -> Void
    let showContents: () -> Void
    let showSearch: () -> Void
    let showNotes: () -> Void
    @Environment(\.toolbarVerticalEdge) private var verticalEdge
    @EnvironmentObject private var store: ReaderStore
    @EnvironmentObject private var audio: AudioCoordinator
    @State private var playbackPresented = false

    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .top, spacing: 0) { if !usesRail && !libraryVisible { header } }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if !usesRail, let document { PlaybackDock(document: document, count: audio.passages.count).id(document.id) }
            }
            .toolbar {
                if usesRail {
                    if !libraryVisible {
                        ToolbarItem(placement: .topBarLeading) {
                            Button("Show library", systemImage: "sidebar.left", action: showLibrary)
                        }.visibilityPriority(.high)
                        if store.document != nil {
                            ToolbarItemGroup(placement: .topBarTrailing) {
                                Button("Document contents", systemImage: "list.bullet.rectangle", action: showContents)
                                Button("Find a passage", systemImage: "magnifyingglass", action: showSearch)
                                    .disabled(!store.canNavigatePreparedSource)
                                Button("Show anchored notes", systemImage: "text.bubble", action: showNotes)
                            }.visibilityPriority(.low)
                        }
                    }
                    if let document {
                        ToolbarItem(placement: .bottomBar) {
                            Button("Previous passage", systemImage: "backward.end.fill") { store.skip(-1) }
                                .disabled(transportUnavailable || audio.replayingNote != nil)
                        }
                        ToolbarItem(placement: .bottomBar) {
                            Button(audio.playing ? "Pause narration" : "Play narration", systemImage: audio.playing ? "pause.fill" : "play.fill") {
                                store.togglePlayback()
                            }.disabled(transportUnavailable)
                        }.visibilityPriority(.init(higherThan: .high))
                        ToolbarItem(placement: .bottomBar) {
                            Button("Next passage", systemImage: "forward.end.fill") { store.skip(1) }
                                .disabled(transportUnavailable || audio.replayingNote != nil)
                        }
                        ToolbarItem(placement: .bottomBar) {
                            Button { playbackPresented = true; store.feedback.play(.selection) } label: {
                                VStack(spacing: 3) {
                                    if store.preparingDocument || audio.generating { ProgressView().controlSize(.small) }
                                    else { Image(systemName: "book") }
                                    if let progressLabel { Text(progressLabel).font(BrandFont.mono(11)) }
                                }
                            }
                            .accessibilityLabel("Playback controls")
                            .accessibilityValue([document.title, playbackStatus].filter { !$0.isEmpty }.joined(separator: ", "))
                            .accessibilityHint("Shows document details, speed, volume, and listening progress")
                            .popover(isPresented: $playbackPresented) {
                                VStack(spacing: 0) {
                                    ReaderSheetHeader(title: "Playback") { playbackPresented = false }
                                    PlaybackDock(document: document, count: audio.passages.count).id(document.id)
                                }
                                .frame(idealWidth: 380)
                                .background(Palette.paper)
                                .presentationCompactAdaptation(.popover)
                            }
                        }.axisBehavior(.verticalPreferred).visibilityPriority(.high)
                    }
                }
            }
            .toolbarVerticalBehavior(.automatic)
            .toolbar(usesRail ? .visible : .hidden, for: .navigationBar, .bottomBar)
            .onChange(of: usesRail) { _, enabled in if !enabled { playbackPresented = false } }
            .modifier(ImportNavigationBlocker(blocked: playbackPresented))
    }

    private var usesRail: Bool { verticalEdge != nil }

    private var transportUnavailable: Bool {
        audio.passages.isEmpty || store.busy || store.loading || store.captureDraft != nil || audio.recording || audio.capturePending
    }

    private var progressLabel: String? {
        store.documentListeningProgress.map { "\(Int((min(1, max(0, $0)) * 100).rounded(.down)))%" }
    }

    private var playbackStatus: String {
        if store.preparingDocument { return store.preparation }
        if audio.generating { return "Preparing local audio" }
        return progressLabel.map { "\($0) listened" } ?? "No listening text is available"
    }
}

private struct ImportNavigationBlocker: ViewModifier {
    let blocked: Bool
    @EnvironmentObject private var store: ReaderStore
    @State private var owner = UUID()

    func body(content: Content) -> some View {
        content
            .onAppear { store.setImportBlocked(blocked, owner: owner) }
            .onChange(of: blocked) { _, value in store.setImportBlocked(value, owner: owner) }
            .onDisappear { store.setImportBlocked(false, owner: owner) }
    }
}

private struct PDFImportQueueSheet: View {
    @EnvironmentObject private var store: ReaderStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            ReaderSheetHeader(title: "PDF imports") { dismiss() }
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 16) {
                    if store.receivingPDFs > 0 { ProgressView("Copying PDFs…").frame(minHeight: 44) }
                    ForEach(store.incomingPDFs) { request in
                        ReaderSheetCard(title: request.title) {
                            Text(request.failure ?? "Waiting to open").font(.subheadline).foregroundStyle(Palette.muted)
                            if request.failure != nil {
                                HStack(spacing: 16) {
                                    if request.hasCopy {
                                        Button("Retry") { store.retryPDFImport(request.id) }.frame(minWidth: 44, minHeight: 44)
                                    }
                                    Button("Remove", role: .destructive) { store.removePDFImport(request.id) }.frame(minWidth: 44, minHeight: 44)
                                }
                            }
                        }
                    }
                    if store.incomingPDFs.isEmpty && store.receivingPDFs == 0 {
                        Text("No pending imports").foregroundStyle(Palette.muted).frame(maxWidth: .infinity, minHeight: 100)
                    }
                }.padding(20).frame(maxWidth: 640).frame(maxWidth: .infinity)
            }
        }.foregroundStyle(Palette.ink).background(Palette.paper)
            .presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
            .modifier(ImportNavigationBlocker(blocked: true))
    }
}

private struct LibraryEdgeReveal: UIViewRepresentable {
    let reveal: () -> Void
    func makeCoordinator() -> Coordinator { Coordinator(reveal: reveal) }
    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .clear
        let gesture = UIPanGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.swiped(_:)))
        gesture.maximumNumberOfTouches = 1
        view.addGestureRecognizer(gesture)
        return view
    }
    func updateUIView(_ view: UIView, context: Context) { context.coordinator.reveal = reveal }
    final class Coordinator: NSObject {
        var reveal: () -> Void
        init(reveal: @escaping () -> Void) { self.reveal = reveal }
        @objc func swiped(_ gesture: UIPanGestureRecognizer) {
            let translation = gesture.translation(in: gesture.view)
            if gesture.state == .ended, translation.x >= 48, translation.x > abs(translation.y) { reveal() }
        }
    }
}

private struct LibraryActionButton: View {
    let symbol: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: symbol).font(.title3).frame(width: 56, height: 56)
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

    var body: some View {
        VStack(spacing: 0) {
            if let url = store.originalURL {
                PDFSurface(url: url, pageIndex: audio.current?.pageIndex ?? 0,
                    baseRange: audio.current.flatMap { $0.provenance == "embedded" ? store.passageSourceRange : nil },
                    baseRegions: store.passageRegions,
                    range: audio.current.flatMap { $0.provenance == "embedded" ? store.highlightSourceRange : nil },
                    regions: store.highlightRegions, pageContexts: snapshot.pages, preparedPassages: snapshot.passages,
                    documentId: snapshot.document.id, sourceRevision: snapshot.document.sourceRevision,
                    geometrySpace: store.currentPage?.geometrySpace ?? "unspecified", sourceText: store.currentPage?.text ?? "", anchor: store.currentHighlightAnchor,
                    excerpt: audio.current?.displayText ?? "", recordingEnabled: store.canBeginNote,
                    follow: store.follow, browseDestination: store.pageDestination, onBrowse: { store.pauseForBrowsing() },
                    onRecord: { anchor in store.beginNote(expected: anchor) },
                    onLoadFailure: { store.errorMessage = "The original PDF could not be opened. Your listening text and notes are preserved." })
            } else {
                ContentUnavailableView("Original PDF unavailable", systemImage: "doc", description: Text("Your listening text and anchored notes are preserved."))
            }
        }
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
    @State private var detailsPresented = false
    var body: some View {
        VStack(spacing: 14) {
            if store.preparingDocument { ProgressView(store.preparation, value: store.preparationProgress).font(.caption2) }
            PlaybackHeaderLayout {
                bookIdentity
                speedControl
                volumeControl
                controls.disabled(transportUnavailable)
            }
            documentProgress
        }.padding(.horizontal, 20).padding(.vertical, 16).background(.regularMaterial)
            .onChange(of: store.canSeekDocument) { _, enabled in if !enabled { seekPreview = nil } }
            .sheet(isPresented: $detailsPresented) { DocumentDetailsSheet(document: document) }
            .modifier(ImportNavigationBlocker(blocked: detailsPresented || volumePresented || seekPreview != nil))
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

    private var transportUnavailable: Bool {
        count == 0 || store.busy || store.loading || store.captureDraft != nil || audio.recording || audio.capturePending
    }

    private var speedControl: some View {
        Button {
            let next = audio.playbackSpeed >= 2 ? Float(0.75) : min(2, (audio.playbackSpeed * 4).rounded(.down) / 4 + 0.25)
            store.feedback.play(.selection)
            Task { await audio.changePace(next) }
        } label: {
            Text("\(audio.playbackSpeed.formatted(.number.precision(.fractionLength(0...2))))×")
                .font(BrandFont.mono()).frame(minWidth: 44, minHeight: 44)
        }.foregroundStyle(Palette.accent)
            .accessibilityLabel("Playback speed")
            .accessibilityValue("\(audio.playbackSpeed.formatted(.number.precision(.fractionLength(0...2)))) times normal")
            .accessibilityHint("Cycles from three quarters to twice normal speed")
            .disabled(transportUnavailable || audio.replayingNote != nil)
    }

    private var volumeControl: some View {
        Button { volumePresented = true } label: {
            Image(systemName: "speaker.wave.2").font(.title3).frame(width: 48, height: 48)
        }.foregroundStyle(Palette.accent).accessibilityLabel("Output volume")
            .popover(isPresented: $volumePresented) { OutputVolumePanel().presentationCompactAdaptation(.sheet).presentationDetents([.medium]) }
    }

    private var bookIdentity: some View {
        Button {
            store.feedback.play(.selection)
            detailsPresented = true
        } label: {
            HStack(spacing: 12) {
                BookThumbnail(document: document, url: store.files?.ownedURL(document.filePath)).frame(width: 36, height: 46)
                VStack(alignment: .leading, spacing: 4) {
                    Text(document.title).font(BrandFont.body(15, relativeTo: .subheadline).weight(.semibold)).lineLimit(2)
                    if audio.generating { Text("Preparing local audio…").font(.caption2).foregroundStyle(.secondary) }
                }.frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(Palette.muted)
            }
            .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading).contentShape(Rectangle())
        }.buttonStyle(.plain).multilineTextAlignment(.leading)
            .accessibilityLabel("Document details")
            .accessibilityValue(document.title)
            .accessibilityHint("Shows the full title, page count, and file size")
    }

    private var controls: some View {
            HStack(spacing: 14) {
                Button { store.skip(-1) } label: { Image(systemName: "backward.end.fill").font(.title3).frame(width: 48, height: 48) }.accessibilityLabel("Previous passage").disabled(audio.replayingNote != nil)
                Button { store.togglePlayback() } label: {
                    Image(systemName: audio.playing ? "pause.fill" : "play.fill").font(.title2).frame(width: 52, height: 52)
                        .foregroundStyle(Palette.onAccent).background(Palette.accent, in: Circle())
                }.accessibilityLabel(audio.playing ? "Pause narration" : "Play narration")
                Button { store.skip(1) } label: { Image(systemName: "forward.end.fill").font(.title3).frame(width: 48, height: 48) }.accessibilityLabel("Next passage").disabled(audio.replayingNote != nil)
            }.foregroundStyle(Palette.accent)
    }
}

private struct DocumentDetailsSheet: View {
    let document: LibraryDocument
    @EnvironmentObject private var store: ReaderStore
    @Environment(\.dismiss) private var dismiss
    @State private var fileSize = "Loading…"

    var body: some View {
        VStack(spacing: 0) {
            ReaderSheetHeader(title: "Document details") { dismiss() }
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text(document.title).font(BrandFont.display(23, relativeTo: .title2))
                        .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                    VStack(spacing: 16) {
                        LabeledContent("Pages", value: document.pageCount > 0 ? document.pageCount.formatted() : "Unavailable")
                        Divider()
                        LabeledContent("File size", value: fileSize)
                    }.padding(18).background(Palette.soft, in: RoundedRectangle(cornerRadius: 20))
                }.padding(20).frame(maxWidth: 640).frame(maxWidth: .infinity, alignment: .leading)
            }
        }.foregroundStyle(Palette.ink).background(Palette.paper)
            .presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
            .task(id: [document.id, document.filePath, document.sourceRevision]) {
                fileSize = "Loading…"
                let url = store.files?.ownedURL(document.filePath)
                let bytes = await Task.detached(priority: .utility) { () -> Int? in
                    guard let url, let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
                          values.isRegularFile == true else { return nil }
                    return values.fileSize
                }.value
                guard !Task.isCancelled else { return }
                fileSize = bytes.map { ByteCountFormatter.string(fromByteCount: Int64($0), countStyle: .file) } ?? "Unavailable"
            }
    }
}

private struct PlaybackHeaderLayout: Layout {
    private func metrics(width: CGFloat, subviews: Subviews) -> (sizes: [CGSize], wide: Bool, height: CGFloat) {
        let speed = subviews[1].sizeThatFits(.unspecified)
        let volume = subviews[2].sizeThatFits(.unspecified)
        let transport = subviews[3].sizeThatFits(.unspecified)
        let wide = width >= 600 && width - speed.width - volume.width - transport.width - 36 >= 96
        let identityWidth = max(0, width - speed.width - volume.width - (wide ? transport.width + 36 : 24))
        let identity = subviews[0].sizeThatFits(ProposedViewSize(width: identityWidth, height: nil))
        let firstRow = max(identity.height, speed.height, volume.height)
        return ([identity, speed, volume, transport], wide, wide ? max(firstRow, transport.height) : firstRow + 10 + transport.height)
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 600
        return CGSize(width: width, height: metrics(width: width, subviews: subviews).height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let layout = metrics(width: bounds.width, subviews: subviews)
        let firstRow = layout.wide ? layout.height : layout.height - layout.sizes[3].height - 10
        var x = bounds.minX
        for index in 0..<(layout.wide ? 4 : 3) {
            let size = layout.sizes[index]
            subviews[index].place(at: CGPoint(x: x, y: bounds.minY + (firstRow - size.height) / 2), proposal: ProposedViewSize(size))
            x += size.width + 12
        }
        if !layout.wide {
            let size = layout.sizes[3]
            subviews[3].place(at: CGPoint(x: bounds.midX - size.width / 2, y: bounds.maxY - size.height), proposal: ProposedViewSize(size))
        }
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

struct ReaderSheetHeader: View {
    let title: String
    let onDone: () -> Void
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    var body: some View {
        Group {
            if dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 8) {
                    heading
                    HStack { Spacer(minLength: 0); done }
                }
            } else {
                HStack(spacing: 16) { heading; Spacer(minLength: 0); done }
            }
        }.padding(.horizontal, 20).padding(.top, 12).padding(.bottom, 8).foregroundStyle(Palette.ink)
    }
    private var heading: some View {
        Text(title).font(BrandFont.display(25, relativeTo: .title2)).fixedSize(horizontal: false, vertical: true)
            .accessibilityAddTraits(.isHeader)
    }
    private var done: some View { Button("Done", action: onDone).frame(minWidth: 44, minHeight: 44) }
}

struct ReaderSheetCard<Content: View>: View {
    let title: String
    let content: Content
    init(title: String, @ViewBuilder content: () -> Content) { self.title = title; self.content = content() }
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title).font(.headline).accessibilityAddTraits(.isHeader)
            content
        }.frame(maxWidth: .infinity, alignment: .leading).padding(18)
            .background(Palette.soft, in: RoundedRectangle(cornerRadius: 20))
    }
}

private struct VoiceSettings: View {
    @EnvironmentObject private var audio: AudioCoordinator
    @EnvironmentObject private var store: ReaderStore
    @State private var restorePicker = false
    @State private var exportTask: Task<Void, Never>?
    @State private var setup: SpeechCapability?
    @State private var gallery = false
    @State private var detent: PresentationDetent = .large
    @AppStorage("appearance") private var appearance = "system"
    @Environment(\.dismiss) private var dismiss
    private let speeds: [Float] = [0.75, 1, 1.25, 1.5, 1.75, 2]
    private var transportUnavailable: Bool {
        store.busy || store.loading || store.captureDraft != nil || audio.recording || audio.capturePending
    }
    var body: some View {
        VStack(spacing: 0) {
            ReaderSheetHeader(title: "Settings") { dismiss() }.disabled(store.busy)
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Button { store.feedback.play(.selection); gallery = true } label: {
                        HStack(spacing: 16) {
                            Text("Download voices and transcription").font(.headline).multilineTextAlignment(.leading)
                            Spacer(minLength: 0)
                            Image(systemName: "chevron.right").font(.subheadline.weight(.semibold)).foregroundStyle(Palette.muted).accessibilityHidden(true)
                        }.frame(maxWidth: .infinity, alignment: .leading).padding(18)
                            .background(Palette.soft, in: RoundedRectangle(cornerRadius: 20)).contentShape(Rectangle())
                    }.buttonStyle(.plain).disabled(store.models == nil)
                    Button { store.feedback.play(.selection); setup = .narration } label: {
                        HStack(spacing: 16) {
                            VStack(alignment: .leading, spacing: 6) {
                                Text("Reading voice").font(.headline)
                                Text(store.speechSummary(.narration)).font(.subheadline).foregroundStyle(Palette.muted)
                            }
                            Spacer(minLength: 0)
                            Image(systemName: "chevron.right").font(.subheadline.weight(.semibold)).foregroundStyle(Palette.muted)
                        }.frame(maxWidth: .infinity, alignment: .leading).padding(18)
                            .background(Palette.soft, in: RoundedRectangle(cornerRadius: 20)).contentShape(Rectangle())
                    }.buttonStyle(.plain).disabled(store.models == nil)
                    HStack(spacing: 16) {
                        Button { store.feedback.play(.selection); setup = .transcription } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                Text("Transcribe voice notes").font(.headline)
                                Text(store.speechSummary(.transcription)).font(.subheadline).foregroundStyle(Palette.muted)
                            }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
                        }.buttonStyle(.plain)
                        Toggle("Transcribe voice notes", isOn: Binding(get: { store.transcriptionRequested }, set: { enabled in
                            store.feedback.play(.selection)
                            if !enabled { Task { await store.selectTranscription("off") } }
                            else if let pack = store.models?.ready(capability: "asr")?.0 { store.startSpeechSetup(pack) }
                            else { setup = .transcription }
                        })).labelsHidden().fixedSize().accessibilityValue(store.speechSummary(.transcription))
                    }.padding(18).background(Palette.soft, in: RoundedRectangle(cornerRadius: 20)).disabled(store.models == nil)
                    ReaderSheetCard(title: "Playback speed") {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 84), spacing: 8)], spacing: 8) {
                            ForEach(speeds, id: \.self) { multiplier in
                                Button { Task { await audio.changePace(multiplier) } } label: {
                                    Text("\(multiplier.formatted(.number.precision(.fractionLength(0...2))))×").font(BrandFont.mono(14, relativeTo: .subheadline))
                                        .frame(maxWidth: .infinity, minHeight: 44)
                                        .foregroundStyle(audio.playbackSpeed == multiplier ? Palette.onAccent : Palette.accent)
                                        .background(audio.playbackSpeed == multiplier ? Palette.accent : Palette.container, in: Capsule())
                                }.buttonStyle(.plain).accessibilityLabel("\(multiplier.formatted(.number.precision(.fractionLength(0...2)))) times normal speed")
                                    .accessibilityAddTraits(audio.playbackSpeed == multiplier ? .isSelected : [])
                            }
                        }.disabled(transportUnavailable || audio.replayingNote != nil)
                    }
                    ReaderSheetCard(title: "Appearance") {
                        Picker("Theme", selection: $appearance) { Text("System").tag("system"); Text("Light").tag("light"); Text("Dark").tag("dark") }.pickerStyle(.segmented)
                    }
                    ReaderSheetCard(title: "Documents") {
                        if let document = store.document {
                            Text(document.document.title).font(.subheadline).foregroundStyle(Palette.muted).lineLimit(2)
                        }
                        Button("Export document archive", systemImage: "square.and.arrow.up") {
                            exportTask = Task { await store.exportSelected() }
                        }.frame(minHeight: 44).disabled(!store.canExportArchive)
                        Text("Save the selected PDF, voice notes, transcripts and listening position.").font(.caption).foregroundStyle(Palette.muted)
                        if store.document == nil {
                            Text("Open a document to export its archive.").font(.caption).foregroundStyle(Palette.muted)
                        } else if store.document?.document.extractionStatus == "preparing" {
                            Text("Finish preparing this document before exporting its archive.").font(.caption).foregroundStyle(Palette.muted)
                        } else if store.currentNotes.contains(where: \.isDraft) {
                            Text("Save or discard the unfinished thought before exporting this document.").font(.caption).foregroundStyle(Palette.muted)
                        }
                        if store.busy && store.preparation == "Preparing document archive" {
                            ProgressView("Preparing and saving your archive…").font(.caption)
                        }
                        Button("Restore a document archive", systemImage: "square.and.arrow.down") { restorePicker = true }
                            .frame(minHeight: 44).disabled(transportUnavailable)
                    }
                }.padding(20).frame(maxWidth: 640).frame(maxWidth: .infinity)
            }
        }.foregroundStyle(Palette.ink).background(Palette.paper)
            .presentationDetents([.medium, .large], selection: $detent).presentationDragIndicator(.visible)
            .sheet(isPresented: $gallery) {
                if let models = store.models { SpeechSetupSheet(capability: nil, models: models) }
            }
            .sheet(item: $setup) { capability in
                if let models = store.models { SpeechSetupSheet(capability: capability, models: models) }
            }
            .sheet(item: Binding(get: { store.preparedArchive }, set: { if $0 == nil { store.finishArchiveExport() } })) { archive in
                ArchiveExportPicker(url: archive.url) { saved in store.finishArchiveExport(archive.id, saved: saved) }
                    .ignoresSafeArea()
            }
            .interactiveDismissDisabled(store.busy || store.preparedArchive != nil)
            .fileImporter(isPresented: $restorePicker, allowedContentTypes: [.rhapsodos, .zip, .archive]) { result in
                dismiss()
                switch result {
                case .success(let url): Task { await store.restoreArchive(url) }
                case .failure(let error): store.errorMessage = error.localizedDescription
                }
            }
            .onDisappear { exportTask?.cancel() }
    }
}

private struct CaptureSheet: View {
    let draft: VoiceNote
    @EnvironmentObject private var store: ReaderStore
    @EnvironmentObject private var audio: AudioCoordinator
    @State private var discard = false
    @State private var detent: PresentationDetent = .large
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 16) {
                Text("Keep the thought").font(BrandFont.display(25, relativeTo: .title2)).accessibilityAddTraits(.isHeader)
                Spacer(minLength: 0)
                if store.captureDraft?.isDraft == true { Button("Discard") { discard = true }.frame(minWidth: 44, minHeight: 44).disabled(store.busy) }
                else { Button("Done") { Task { await store.keepDraft() } }.frame(minWidth: 44, minHeight: 44).disabled(store.busy) }
            }.padding(.horizontal, 20).padding(.top, 12).padding(.bottom, 8)
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    ReaderSheetCard(title: "Attached to page \(draft.pageIndex + 1)") {
                        Text(draft.quote).font(BrandFont.body(21, relativeTo: .title3)).lineSpacing(6).lineLimit(6)
                    }
                    VStack(spacing: 12) {
                        HStack(spacing: 7) {
                            ForEach(0..<9) { index in
                                Capsule().fill(audio.recording ? Palette.accent : Color.secondary.opacity(0.3))
                                    .frame(width: 7, height: 8 + CGFloat(audio.level) * CGFloat(22 + (index % 4) * 12))
                            }
                        }.frame(height: 64).accessibilityHidden(true)
                        Text(audio.recording ? "Recording your thought" : "Your thought, safely attached").font(.headline)
                        if audio.recording { Text(Duration.seconds(audio.recordingSeconds).formatted(.time(pattern: .minuteSecond))).font(.title2.monospacedDigit()) }
                        Text(store.captureMessage).font(.subheadline).foregroundStyle(Palette.muted)
                    }.frame(maxWidth: .infinity).padding(18).background(Palette.soft, in: RoundedRectangle(cornerRadius: 20))
                    if !audio.recording {
                        TextField("Or write your thought…", text: $store.typedThought, axis: .vertical).lineLimit(4...10)
                            .padding(16).background(Palette.soft, in: RoundedRectangle(cornerRadius: 16)).accessibilityLabel("Typed note")
                    }
                    Button { Task { await store.saveNote() } } label: {
                        HStack { if store.busy { ProgressView().tint(Palette.onAccent) }; Text("Save & resume").font(.headline).foregroundStyle(Palette.onAccent); Image(systemName: "arrow.right").foregroundStyle(Palette.onAccent) }
                            .frame(maxWidth: .infinity).padding(.vertical, 10)
                    }.buttonStyle(.borderedProminent).buttonBorderShape(.capsule).disabled(store.busy)
                    Button("Save and stay paused") { Task { await store.saveNote(resume: false) } }.frame(maxWidth: .infinity, minHeight: 44).disabled(store.busy)
                    Button(store.captureDraft?.isDraft == false ? "Close saved thought" : "Keep draft for later") { Task { await store.keepDraft() } }.font(.footnote).frame(maxWidth: .infinity, minHeight: 44).disabled(store.busy)
                    Text("This note stays at the frozen passage. Transcription can finish after you resume.").font(.caption).foregroundStyle(.secondary)
                }.padding(24).frame(maxWidth: 640).frame(maxWidth: .infinity)
            }
        }.background(Palette.paper).foregroundStyle(Palette.ink)
            .presentationDetents([.medium, .large], selection: $detent).presentationDragIndicator(.visible)
            .confirmationDialog("Discard this unsaved thought?", isPresented: $discard, titleVisibility: .visible) {
                Button("Discard draft", role: .destructive) { Task { await store.discardDraft() } }
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
    @State private var saving = false
    @State private var editError: String?
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Anchored notes").font(BrandFont.display(25, relativeTo: .title2)).accessibilityAddTraits(.isHeader)
                Spacer()
                Menu {
                    Button("Import saved audio", systemImage: "waveform.badge.plus") { importedAnchor = store.currentPassage; audio.pause(); audioImporter = importedAnchor != nil }
                } label: { Image(systemName: "plus").frame(minWidth: 44, minHeight: 44) }.accessibilityLabel("Attach a saved recording").disabled(store.busy || store.currentPassage == nil)
                Button("Done") { store.inspector = false }.frame(minWidth: 44, minHeight: 44)
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
                                        if !(note.recordingPath ?? "").isEmpty { Button("Transcribe saved recording") { store.retry(note) } }
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
                        TextEditor(text: $text).scrollContentBackground(.hidden).padding(20).background(Palette.paper).navigationTitle("Edit thought").disabled(saving)
                            .toolbar {
                                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { editing = nil }.frame(minWidth: 44, minHeight: 44).disabled(saving) }
                                ToolbarItem(placement: .confirmationAction) {
                                    Button("Save") {
                                        saving = true
                                        Task {
                                            do { try await store.edit(note, text: text); editing = nil }
                                            catch { editError = error.localizedDescription; store.feedback.play(.failure) }
                                            saving = false
                                        }
                                    }.frame(minWidth: 44, minHeight: 44).disabled(saving)
                                }
                            }
                    }.interactiveDismissDisabled(saving)
                        .alert("Could not save your note", isPresented: Binding(get: { editError != nil }, set: { if !$0 { editError = nil } })) {
                            Button("OK") { editError = nil }
                        } message: { Text(editError ?? "") }
                }
        }.background(Palette.paper).foregroundStyle(Palette.ink)
            .modifier(ImportNavigationBlocker(blocked: editing != nil || deleting != nil || audioImporter))
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
