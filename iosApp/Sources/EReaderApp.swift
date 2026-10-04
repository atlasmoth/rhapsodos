import SwiftUI

@main
struct EReaderApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var store = ReaderStore()
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("appearance") private var appearance = "system"
    var body: some Scene {
        WindowGroup {
            ReaderRoot()
                .environmentObject(store)
                .environmentObject(store.audio)
                .environmentObject(store.feedback)
                .tint(Palette.accent)
                .font(BrandFont.body())
                .preferredColorScheme(appearance == "dark" ? .dark : appearance == "light" ? .light : nil)
                .overlay { LaunchIdentity().environmentObject(store.feedback) }
                .onChange(of: scenePhase) { _, phase in store.sceneChanged(phase) }
                .onOpenURL { url in if url.isFileURL { Task { if url.pathExtension.lowercased() == "rhapsodos" { await store.restoreArchive(url) } else { await store.importPDF(url) } } } }
        }
    }
}
