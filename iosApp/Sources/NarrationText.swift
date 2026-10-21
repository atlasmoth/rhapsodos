import Foundation

enum NarrationFailure: LocalizedError {
    case unsupportedText, timedOut, voiceFailed
    var errorDescription: String? {
        switch self {
        case .unsupportedText: "This English preview voice cannot pronounce part of this passage safely. Try another passage."
        case .timedOut: "This passage took too long to prepare. Try again when the device is cool."
        case .voiceFailed: "This voice could not finish the passage. Try again, or choose another voice."
        }
    }
}

enum NarrationText {
    static let version = "english-literals-v2"
    static func prepare(_ text: String) throws -> String {
        let names: [Character: String] = ["*": "asterisk", "<": "less than", ">": "greater than", "≤": "less than or equal to", "≥": "greater than or equal to", "≠": "not equal to", "×": "times", "÷": "divided by", "=": "equals", "+": "plus", "−": "minus", "±": "plus or minus", "%": "percent", "&": "and", "^": "caret", "|": "vertical bar", "~": "tilde", "∞": "infinity", "√": "square root", "α": "alpha", "β": "beta", "γ": "gamma", "δ": "delta", "θ": "theta", "λ": "lambda", "μ": "mu", "π": "pi", "σ": "sigma", "φ": "phi", "ω": "omega", "©": "copyright", "®": "registered", "™": "trademark"]
        var result = ""
        let characters = Array(text)
        for (index, character) in characters.enumerated() {
            if let name = names[character] { result += " " + name + " " }
            else if character == "-" {
                let left = characters[..<index].reversed().prefix(while: \.isLetter).count
                let right = characters[(index + 1)...].prefix(while: \.isLetter).count
                if left > 0 && right > 0 && (left > 1 || right > 1) { result.append(character) }
                else { result += " minus " }
            }
            else {
                guard !character.unicodeScalars.contains(where: { $0.properties.generalCategory == .mathSymbol || $0.properties.generalCategory == .otherSymbol || ($0.properties.generalCategory == .currencySymbol && !"$£€".contains(character)) }) else { throw NarrationFailure.unsupportedText }
                result.append(character)
            }
        }
        return result
    }
}
