import CoreHaptics
import SwiftUI
import UIKit

@MainActor
final class Feedback: ObservableObject {
    enum Event { case selection, playback, capture, saved, failure }
    @Published private(set) var restrained = false
    @Published var enabled = UserDefaults.standard.object(forKey: "touchFeedback") as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(enabled, forKey: "touchFeedback")
            if !enabled { engine?.stop(completionHandler: nil) }
        }
    }
    private var engine: CHHapticEngine?
    private var observers: [NSObjectProtocol] = []

    init() {
        updatePolicy()
        for name in [Notification.Name.NSProcessInfoPowerStateDidChange, ProcessInfo.thermalStateDidChangeNotification,
                     UIAccessibility.reduceMotionStatusDidChangeNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.updatePolicy() }
            })
        }
    }

    deinit { observers.forEach(NotificationCenter.default.removeObserver) }

    private func updatePolicy() {
        restrained = UIAccessibility.isReduceMotionEnabled || ProcessInfo.processInfo.isLowPowerModeEnabled ||
            ProcessInfo.processInfo.thermalState == .serious || ProcessInfo.processInfo.thermalState == .critical
        if restrained { engine?.stop(completionHandler: nil) }
    }

    func play(_ event: Event) {
        guard enabled else { return }
        if event == .selection {
            UISelectionFeedbackGenerator().selectionChanged()
            return
        }
        guard !restrained, CHHapticEngine.capabilitiesForHardware().supportsHaptics else {
            switch event {
            case .saved: UINotificationFeedbackGenerator().notificationOccurred(.success)
            case .failure: UINotificationFeedbackGenerator().notificationOccurred(.error)
            default: UIImpactFeedbackGenerator(style: .soft).impactOccurred(intensity: 0.55)
            }
            return
        }
        do {
            if engine == nil {
                let newEngine = try CHHapticEngine()
                newEngine.isAutoShutdownEnabled = true
                newEngine.playsHapticsOnly = true
                newEngine.resetHandler = { [weak self] in Task { @MainActor in self?.engine = nil } }
                engine = newEngine
            }
            try engine?.start()
            let intensity: Float = event == .capture ? 0.8 : 0.55
            let pulse = CHHapticEvent(eventType: .hapticTransient, parameters: [
                CHHapticEventParameter(parameterID: .hapticIntensity, value: intensity),
                CHHapticEventParameter(parameterID: .hapticSharpness, value: event == .saved ? 0.65 : 0.2)
            ], relativeTime: 0)
            var events = [pulse]
            if event == .saved || event == .capture {
                events.append(CHHapticEvent(eventType: .hapticTransient, parameters: [
                    CHHapticEventParameter(parameterID: .hapticIntensity, value: 0.35)
                ], relativeTime: 0.09))
            }
            try engine?.makePlayer(with: CHHapticPattern(events: events, parameters: [])).start(atTime: CHHapticTimeImmediate)
        } catch {
            UIImpactFeedbackGenerator(style: .soft).impactOccurred(intensity: 0.4)
        }
    }
}

struct QuietGlow: ViewModifier {
    let active: Bool
    let restrained: Bool
    @Environment(\.colorSchemeContrast) private var contrast
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        if active && !restrained && contrast != .increased && scenePhase == .active {
            TimelineView(.animation(minimumInterval: 1.0 / 24.0)) { context in
                content.visualEffect { effect, geometry in
                    effect.colorEffect(ShaderLibrary.passageGlow(.float2(Float(geometry.size.width), Float(geometry.size.height)),
                        .float(Float(context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 120))), .float(1)))
                }
            }
        } else { content }
    }
}
