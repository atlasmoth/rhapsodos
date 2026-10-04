import SwiftUI

@main
struct EReaderApp: App {
    @StateObject private var store = ReaderStore()
    var body: some Scene {
        WindowGroup {
            ReaderRoot()
                .environmentObject(store)
                .environmentObject(store.audio)
                .environmentObject(store.feedback)
                .tint(Palette.accent)
                .onOpenURL { url in if url.isFileURL { Task { await store.importPDF(url) } } }
        }
    }
}
