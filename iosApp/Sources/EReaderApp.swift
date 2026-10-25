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
                .background { WindowAppearance(appearance: appearance).frame(width: 0, height: 0) }
                .overlay { LaunchIdentity().environmentObject(store.feedback) }
                .onChange(of: scenePhase) { _, phase in store.sceneChanged(phase) }
                .onOpenURL { url in
                    guard url.isFileURL else { return }
                    if url.pathExtension.lowercased() == "rhapsodos" { Task { await store.restoreArchive(url) } }
                    else { store.receivePDF(url) }
                }
        }
    }
}

struct WindowAppearance: UIViewRepresentable {
    let appearance: String

    func makeUIView(context: Context) -> AppearanceView { AppearanceView() }

    func updateUIView(_ view: AppearanceView, context: Context) {
        view.style = appearance == "dark" ? .dark : appearance == "light" ? .light : .unspecified
        view.applyStyle()
    }

    final class AppearanceView: UIView {
        var style: UIUserInterfaceStyle = .unspecified

        override func didMoveToWindow() {
            super.didMoveToWindow()
            applyStyle()
        }

        func applyStyle() {
            guard let window, window.overrideUserInterfaceStyle != style else { return }
            window.overrideUserInterfaceStyle = style
        }
    }
}
