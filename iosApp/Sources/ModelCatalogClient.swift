import CryptoKit
import Foundation
import SharedCore

struct ModelCatalogStatus: Codable {
    let revision: Int64
    let acceptedAt: Int64
    let source: String
}

enum ModelCatalogFailure: LocalizedError {
    case configuration, transport, size, signature, payload, busy
    var errorDescription: String? {
        switch self {
        case .configuration: "Live catalogue updates have not been configured. The bundled catalogue remains available."
        case .transport: "The catalogue could not be reached. Your existing downloads and choices are unchanged."
        case .size: "The catalogue exceeds the supported size. The existing catalogue remains available."
        case .signature: "The catalogue could not be authenticated. The existing catalogue remains available."
        case .payload: "The catalogue is invalid or older than the installed catalogue."
        case .busy: "Pause playback and wait for downloads and checks to finish before refreshing."
        }
    }
}

enum ModelCatalogClient {
    static let maximumEnvelopeBytes = 3 * 1_048_576
    static let maximumPayloadBytes = 2 * 1_048_576
    static var configured: Bool {
        !ModelCatalogSource.shared.httpsUrl.isEmpty && !ModelCatalogSource.shared.publicKeyX963.isEmpty
    }

    static func verifiedPayload(_ envelope: Data, publicKeyX963: String) throws -> String {
        guard envelope.count <= maximumEnvelopeBytes else { throw ModelCatalogFailure.size }
        struct Envelope: Decodable { let payload: String; let signature: String }
        guard let object = try? JSONSerialization.jsonObject(with: envelope) as? [String: Any],
              Set(object.keys) == Set(["payload", "signature"]),
              let document = try? JSONDecoder().decode(Envelope.self, from: envelope),
              let payload = Data(base64Encoded: document.payload), !payload.isEmpty,
              payload.count <= maximumPayloadBytes,
              let text = String(data: payload, encoding: .utf8) else { throw ModelCatalogFailure.payload }
        guard let keyBytes = Data(base64Encoded: publicKeyX963), keyBytes.count == 65,
              let key = try? P256.Signing.PublicKey(x963Representation: keyBytes),
              let signatureBytes = Data(base64Encoded: document.signature), signatureBytes.count <= 80,
              let signature = try? P256.Signing.ECDSASignature(derRepresentation: signatureBytes),
              key.isValidSignature(signature, for: payload) else { throw ModelCatalogFailure.signature }
        return text
    }

    static func fetch(_ endpoint: String = ModelCatalogSource.shared.httpsUrl,
                      publicKeyX963: String = ModelCatalogSource.shared.publicKeyX963) async throws -> String {
        guard ModelDownloadUrl.shared.isAllowed(url: endpoint),
              let url = URL(string: endpoint, encodingInvalidCharacters: false), !publicKeyX963.isEmpty else { throw ModelCatalogFailure.configuration }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCredentialStorage = nil
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 30
        let delegate = CatalogueRedirects()
        let session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200,
              let finalURL = response.url, valid(finalURL) else { throw ModelCatalogFailure.transport }
        guard response.expectedContentLength <= maximumEnvelopeBytes else { throw ModelCatalogFailure.size }
        var envelope = Data()
        for try await byte in bytes {
            try Task.checkCancellation()
            guard envelope.count < maximumEnvelopeBytes else { throw ModelCatalogFailure.size }
            envelope.append(byte)
        }
        return try verifiedPayload(envelope, publicKeyX963: publicKeyX963)
    }

    static func valid(_ url: URL) -> Bool {
        ModelDownloadUrl.shared.isAllowed(url: url.absoluteString)
    }
}

private final class CatalogueRedirects: NSObject, URLSessionTaskDelegate {
    private var redirects = 0
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        guard redirects < 5, let url = request.url, ModelCatalogClient.valid(url) else { completionHandler(nil); return }
        redirects += 1
        var clean = URLRequest(url: url)
        clean.httpMethod = "GET"
        clean.setValue("application/json", forHTTPHeaderField: "Accept")
        completionHandler(clean)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        completionHandler(challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust ? .performDefaultHandling : .cancelAuthenticationChallenge, nil)
    }
}

struct DownloadedVoice {
    let packId: String
    let voiceId: String
    var identifier: String { "pack:" + packId + ":" + voiceId }
    init(packId: String, voiceId: String) { self.packId = packId; self.voiceId = voiceId }
    init?(_ identifier: String) {
        let parts = identifier.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0] == "pack", !parts[1].isEmpty, !parts[2].isEmpty else { return nil }
        packId = String(parts[1]); voiceId = String(parts[2])
    }
}
