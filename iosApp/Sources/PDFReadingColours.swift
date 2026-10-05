import PDFKit
import SwiftUI

struct PDFReadingPalette: Equatable {
    let paper: [Int]
    let ink: [Int]

    init(traits: UITraitCollection) {
        func bytes(_ colour: Color) -> [Int] {
            var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
            UIColor(colour).resolvedColor(with: traits).getRed(&red, green: &green, blue: &blue, alpha: &alpha)
            return [red, green, blue].map { Int(($0 * 255).rounded()).clamped(to: 0...255) }
        }
        paper = bytes(Palette.paper)
        ink = bytes(Palette.ink)
    }

    func remap(_ pixels: UnsafeMutablePointer<UInt8>, width: Int, height: Int, rowBytes: Int) {
        for row in 0..<height {
            let line = pixels.advanced(by: row * rowBytes)
            for column in 0..<width {
                let pixel = line.advanced(by: column * 4)
                let alpha = Int(pixel[3])
                guard alpha > 0 else { continue }
                let red = min(255, (Int(pixel[0]) * 255 + alpha / 2) / alpha)
                let green = min(255, (Int(pixel[1]) * 255 + alpha / 2) / alpha)
                let blue = min(255, (Int(pixel[2]) * 255 + alpha / 2) / alpha)
                let chroma = max(red, green, blue) - min(red, green, blue)
                guard chroma < 64 else { continue }
                let weight = chroma <= 24 ? 40 : 64 - chroma
                let luminance = (2126 * red + 7152 * green + 722 * blue + 5000) / 10_000
                for channel in 0..<3 {
                    let original = channel == 0 ? red : channel == 1 ? green : blue
                    let neutral = (ink[channel] * (255 - luminance) + paper[channel] * luminance + 127) / 255
                    let result = (neutral * weight + original * (40 - weight) + 20) / 40
                    pixel[channel] = UInt8((result * alpha + 127) / 255)
                }
            }
        }
    }
}

private extension Int {
    func clamped(to range: ClosedRange<Int>) -> Int { Swift.min(range.upperBound, Swift.max(range.lowerBound, self)) }
}

struct PDFReadingConfiguration: Equatable {
    let original: Bool
    let palette: PDFReadingPalette
}

final class PDFReadingState {
    private let lock = NSLock()
    private var configuration: PDFReadingConfiguration?
    func update(_ value: PDFReadingConfiguration?) {
        lock.lock(); configuration = value; lock.unlock()
    }
    func snapshot() -> PDFReadingConfiguration? {
        lock.lock(); defer { lock.unlock() }; return configuration
    }
}

private final class ReadingPDFPageDelegate: NSObject, PDFDocumentDelegate {
    func classForPage() -> AnyClass { ReadingPDFPage.self }
}

final class ReadingPDFDocument: PDFDocument {
    let readingState = PDFReadingState()
    private let pageFactory = ReadingPDFPageDelegate()
    override init?(url: URL) {
        super.init(url: url)
        delegate = pageFactory
    }
}

final class ThemedPDFView: PDFView {
    private let colourLock = NSLock()
    private var configuration: PDFReadingConfiguration?
    var onColourChange: (() -> Void)?
    var narrationTraits: UITraitCollection {
        colourLock.lock()
        let original = configuration?.original ?? false
        colourLock.unlock()
        return original ? traitCollection.modifyingTraits { $0.userInterfaceStyle = .light } : traitCollection
    }

    override var document: PDFDocument? {
        willSet {
            colourLock.lock()
            let current = configuration
            colourLock.unlock()
            (newValue as? ReadingPDFDocument)?.readingState.update(current)
        }
    }

    func configureColours(original: Bool) {
        let next = PDFReadingConfiguration(original: original, palette: PDFReadingPalette(traits: traitCollection))
        colourLock.lock()
        let changed = next != configuration
        configuration = next
        colourLock.unlock()
        (document as? ReadingPDFDocument)?.readingState.update(next)
        backgroundColor = UIColor(Palette.paper).resolvedColor(with: traitCollection)
        guard changed else { return }
        invalidateReadingPages()
        onColourChange?()
    }

    private func invalidateReadingPages() {
        func invalidate(_ view: UIView) {
            view.setNeedsDisplay()
            view.layer.setNeedsDisplay()
            view.subviews.forEach(invalidate)
        }
        invalidate(self)
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        observeColourTraits()
    }
    required init?(coder: NSCoder) {
        super.init(coder: coder)
        observeColourTraits()
    }
    private func observeColourTraits() {
        registerForTraitChanges([UITraitUserInterfaceStyle.self]) { (view: ThemedPDFView, _: UITraitCollection) in
            view.refreshColourTraits()
        }
    }
    private func refreshColourTraits() {
        colourLock.lock()
        let original = configuration?.original ?? false
        colourLock.unlock()
        configureColours(original: original)
    }

}

final class ReadingPDFPage: PDFPage {
    static let maximumTileDimension = 1024

    override func draw(with box: PDFDisplayBox, to context: CGContext) {
        let transform = context.ctm
        guard [transform.a, transform.b, transform.c, transform.d, transform.tx, transform.ty].allSatisfy(\.isFinite),
              abs(transform.a * transform.d - transform.b * transform.c) > 0.000_000_1 else { return }
        let clip = context.boundingBoxOfClipPath
        guard !clip.isNull, !clip.isInfinite, !clip.isEmpty,
              [clip.minX, clip.minY, clip.width, clip.height].allSatisfy(\.isFinite) else { return }
        guard let colours = (document as? ReadingPDFDocument)?.readingState.snapshot(), !colours.original else {
            super.draw(with: box, to: context)
            return
        }
        if !drawReadingColours(box, in: context, palette: colours.palette) {
            fillOriginalPaper(box, in: context)
            super.draw(with: box, to: context)
        }
    }

    private func fillOriginalPaper(_ box: PDFDisplayBox, in context: CGContext) {
        context.saveGState()
        transform(context, for: box)
        context.setFillColor(UIColor.white.cgColor)
        context.fill(bounds(for: box))
        context.restoreGState()
    }

    private func drawReadingColours(_ box: PDFDisplayBox, in context: CGContext, palette: PDFReadingPalette) -> Bool {
        // PDFKit's dynamic page construction can bypass Swift stored-property initialization.
        guard let colourSpace = CGColorSpace(name: CGColorSpace.sRGB) else { return false }
        let transform = context.ctm
        let values = [transform.a, transform.b, transform.c, transform.d, transform.tx, transform.ty]
        guard values.allSatisfy(\.isFinite), abs(transform.a * transform.d - transform.b * transform.c) > 0.000_000_1 else { return false }
        let clip = context.boundingBoxOfClipPath
        guard !clip.isNull, !clip.isInfinite, !clip.isEmpty else { return false }
        let device = clip.applying(transform).integral
        guard [device.minX, device.minY, device.width, device.height].allSatisfy(\.isFinite),
              device.width <= 16_384, device.height <= 16_384, device.width * device.height <= 16_777_216 else { return false }
        let dimension = Self.maximumTileDimension
        for y in stride(from: 0, to: Int(device.height), by: dimension) {
            for x in stride(from: 0, to: Int(device.width), by: dimension) {
                let width = min(dimension, Int(device.width) - x), height = min(dimension, Int(device.height) - y)
                let rect = CGRect(x: device.minX + CGFloat(x), y: device.minY + CGFloat(y), width: CGFloat(width), height: CGFloat(height))
                let success: Bool = autoreleasepool {
                    guard let tile = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
                        bytesPerRow: width * 4, space: colourSpace,
                        bitmapInfo: CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue),
                        let pixels = tile.data else { return false }
                    tile.translateBy(x: -rect.minX, y: -rect.minY)
                    tile.concatenate(transform)
                    tile.clip(to: clip)
                    // PDFPage does not clear paper; isolate its display-box transform before the original draw.
                    fillOriginalPaper(box, in: tile)
                    super.draw(with: box, to: tile)
                    palette.remap(pixels.assumingMemoryBound(to: UInt8.self), width: width, height: height, rowBytes: tile.bytesPerRow)
                    guard let image = tile.makeImage() else { return false }
                    // PDFKit tiles arrive at the current zoom; copy in device space without resampling text.
                    context.saveGState()
                    context.concatenate(transform.inverted())
                    context.interpolationQuality = .none
                    context.draw(image, in: rect)
                    context.restoreGState()
                    return true
                }
                guard success else { return false }
            }
        }
        return true
    }
}

struct PDFNarrationRect {
    let bounds: CGRect
    let colour: UIColor
}

final class PDFNarrationOverlay: UIView {
    var rectangles: (() -> [PDFNarrationRect])?
    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        isUserInteractionEnabled = false
        accessibilityElementsHidden = true
        clipsToBounds = true
    }
    required init?(coder: NSCoder) {
        super.init(coder: coder)
        backgroundColor = .clear
        isOpaque = false
        isUserInteractionEnabled = false
        accessibilityElementsHidden = true
        clipsToBounds = true
    }
    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        for rectangle in rectangles?() ?? [] {
            context.setFillColor(rectangle.colour.cgColor)
            context.fill(rectangle.bounds)
        }
    }
}
