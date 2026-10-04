import SwiftUI
import CoreText

enum Palette {
    static let onAccent = adaptive(0xFFFFFF, 0x241C15)
    static let accent = adaptive(0x9F3800, 0xFFB58B)
    static let paper = adaptive(0xFFF8EE, 0x161412)
    static let soft = adaptive(0xFFFDF8, 0x211D19)
    static let ink = adaptive(0x241C15, 0xFFF5E8)
    static let muted = adaptive(0x756657, 0xC6B5A3)
    static let container = adaptive(0xFFE3CC, 0x693116)
    static let ember = Color(red: 244 / 255, green: 122 / 255, blue: 54 / 255)
    private static func adaptive(_ light: Int, _ dark: Int) -> Color {
        Color(uiColor: UIColor { traits in
            let value = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((value >> 16) & 255) / 255, green: CGFloat((value >> 8) & 255) / 255, blue: CGFloat(value & 255) / 255, alpha: 1)
        })
    }
}

enum BrandFont {
    static func display(_ size: CGFloat, relativeTo style: Font.TextStyle = .title) -> Font {
        Font(displayUIFont(size, relativeTo: style))
    }
    static func displayUIFont(_ size: CGFloat, relativeTo style: Font.TextStyle = .title, compatibleWith traits: UITraitCollection? = nil) -> UIFont {
        let base = UIFont(name: "SpaceGrotesk-Light", size: size) ?? UIFont.systemFont(ofSize: size)
        let variation = UIFontDescriptor.AttributeName(rawValue: kCTFontVariationAttribute as String)
        let descriptor = base.fontDescriptor.addingAttributes([variation: [NSNumber(value: 0x77676874): NSNumber(value: 400)]])
        let textStyle: UIFont.TextStyle
        switch style {
        case .largeTitle: textStyle = .largeTitle
        case .title: textStyle = .title1
        case .title2: textStyle = .title2
        case .title3: textStyle = .title3
        case .headline: textStyle = .headline
        case .subheadline: textStyle = .subheadline
        case .callout: textStyle = .callout
        case .footnote: textStyle = .footnote
        case .caption: textStyle = .caption1
        case .caption2: textStyle = .caption2
        default: textStyle = .body
        }
        return UIFontMetrics(forTextStyle: textStyle).scaledFont(for: UIFont(descriptor: descriptor, size: size), compatibleWith: traits)
    }
    static func body(_ size: CGFloat = 17, relativeTo style: Font.TextStyle = .body) -> Font { .custom("Inter-Regular", size: size, relativeTo: style) }
    static func mono(_ size: CGFloat = 12, relativeTo style: Font.TextStyle = .caption) -> Font { .custom("SpaceMono-Regular", size: size, relativeTo: style) }
}

struct RhapsodosMark: View {
    var reveal = 1.0
    var wave = 0.0
    var body: some View {
        GeometryReader { geometry in
            let side = min(geometry.size.width, geometry.size.height)
            ZStack {
                ForEach(0..<7) { index in
                    let x = [56,77,98,119,140,161,182][index]
                    let y = [108,88,65,49,65,88,108][index]
                    let height = [40,80,126,158,126,80,40][index]
                    RoundedRectangle(cornerRadius: side * 7 / 256)
                        .fill(LinearGradient(colors: [.orange, Palette.ember], startPoint: .top, endPoint: .bottom))
                        .frame(width: side * 14 / 256, height: side * CGFloat(height) / 256)
                        .scaleEffect(y: min(1, max(0, reveal * 2.2 - Double(index) * 0.2)) * (1 + 0.05 * sin(wave - Double(index) * 0.6)), anchor: .center)
                        .position(x: side * CGFloat(x + 7) / 256, y: side * CGFloat(y + height / 2) / 256)
                }
            }.frame(width: side, height: side)
        }.accessibilityHidden(true)
    }
}

struct LaunchIdentity: View {
    @Environment(\.accessibilityReduceMotion) private var reduced
    @Environment(\.accessibilityReduceTransparency) private var still
    @EnvironmentObject private var feedback: Feedback
    @State private var wave = 0.0
    @State private var reveal = 0.1
    @State private var opacity = 1.0
    @State private var finished = false
    var body: some View {
        if !finished {
            ZStack {
                Color(red: 22/255, green: 20/255, blue: 18/255).ignoresSafeArea()
                VStack(spacing: 12) {
                    RhapsodosMark(reveal: reduced || still || feedback.restrained ? 1 : reveal, wave: reduced || still || feedback.restrained ? 0 : wave).frame(width: 160, height: 160).modifier(QuietGlow(active: !reduced && !still && !feedback.restrained, restrained: feedback.restrained))
                    Text("Rhapsodos").font(BrandFont.display(32)).foregroundStyle(Color(red: 1, green: 245/255, blue: 232/255))
                }
            }.opacity(opacity).allowsHitTesting(false).accessibilityHidden(true)
                .task {
                    withAnimation(.easeOut(duration: reduced || still || feedback.restrained ? 0 : 0.35)) { reveal = 1; wave = 5 }
                    try? await Task.sleep(for: .milliseconds(reduced || still || feedback.restrained ? 80 : 350))
                    withAnimation(.easeOut(duration: 0.15)) { opacity = 0 }
                    try? await Task.sleep(for: .milliseconds(150)); finished = true
                }
        }
    }
}
