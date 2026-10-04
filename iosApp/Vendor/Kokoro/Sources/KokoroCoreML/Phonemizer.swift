public final class EnglishPhonemizer {
    private let g2p = EnglishG2P(british: false)
    public init() {}
    public func phonemize(_ text: String) -> String { g2p.phonemize(text: text).0 }
}
