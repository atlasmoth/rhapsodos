import AppKit

let root = URL(fileURLWithPath: CommandLine.arguments.dropFirst().first ?? FileManager.default.currentDirectoryPath)
let size = 1024
let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: size * 4, bitsPerPixel: 32)!
let context = NSGraphicsContext(bitmapImageRep: bitmap)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = context
NSColor(red: 22 / 255, green: 20 / 255, blue: 18 / 255, alpha: 1).setFill()
NSBezierPath(rect: NSRect(x: 0, y: 0, width: size, height: size)).fill()
let glow = NSGradient(colors: [NSColor(red: 0.39, green: 0.18, blue: 0.06, alpha: 1), NSColor(red: 22 / 255, green: 20 / 255, blue: 18 / 255, alpha: 1)])!
glow.draw(in: NSBezierPath(ovalIn: NSRect(x: 140, y: 140, width: 744, height: 744)), relativeCenterPosition: .zero)
let xs: [CGFloat] = [56, 77, 98, 119, 140, 161, 182]
let ys: [CGFloat] = [108, 88, 65, 49, 65, 88, 108]
let heights: [CGFloat] = [40, 80, 126, 158, 126, 80, 40]
let gradient = NSGradient(starting: NSColor(red: 1, green: 0.78, blue: 0.38, alpha: 1), ending: NSColor(red: 0.95, green: 0.34, blue: 0.16, alpha: 1))!
for index in xs.indices {
    let rect = NSRect(x: xs[index] * 4, y: (256 - ys[index] - heights[index]) * 4, width: 56, height: heights[index] * 4)
    gradient.draw(in: NSBezierPath(roundedRect: rect, xRadius: 28, yRadius: 28), angle: -90)
}
NSGraphicsContext.restoreGraphicsState()
let output = root.appendingPathComponent("iosApp/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png")
try bitmap.representation(using: .png, properties: [:])!.write(to: output)
print(output.path)
