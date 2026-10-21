import Foundation
import PhononCore

public enum PhononFailure: LocalizedError {
    case runtime, incomplete, invalidAudio
    public var errorDescription: String? {
        switch self {
        case .runtime: "This voice could not prepare the passage. Try again, or choose another voice."
        case .incomplete: "This voice could not finish the passage. Choose another voice."
        case .invalidAudio: "This voice produced invalid audio. Choose another voice."
        }
    }
}

public final class PhononEngine: @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.osuji.rhapsodos.phonon", qos: .utility)
    private var handle: OpaquePointer?

    public init() {}

    public func load(directory: URL, neuralEngine: Bool) async throws {
        try await perform {
            if let handle = self.handle { ptts_free(handle); self.handle = nil }
            guard let handle = directory.path.withCString({ path in
                "en".withCString { ptts_new(path, neuralEngine ? UInt32(PTTS_UNIT_ANE) : UInt32(PTTS_UNIT_CPU), $0) }
            }) else { throw PhononFailure.runtime }
            self.handle = handle
            guard "default".withCString({ ptts_set_voice(handle, $0) }) else { throw PhononFailure.runtime }
        }
        try Task.checkCancellation()
    }

    public func synthesize(_ text: String, deadline: ContinuousClock.Instant) async throws -> [Float] {
        let sink = Sink(deadline: deadline)
        let samples = try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await perform {
                guard let handle = self.handle else { throw PhononFailure.runtime }
                guard !sink.stopped else { throw CancellationError() }
                let pointer = Unmanaged.passUnretained(sink).toOpaque()
                var result = PttsResult()
                let completed = text.withCString { source in
                    ptts_speak(handle, source, { pcm, count, opaque in
                        guard let pcm, let opaque else { return false }
                        let sink = Unmanaged<Sink>.fromOpaque(opaque).takeUnretainedValue()
                        guard !sink.stopped, ContinuousClock.now < sink.deadline else { return false }
                        guard count > 0, count <= 24_000, sink.samples.count <= 24_000 * 120 - count else { sink.invalid = true; return false }
                        let values = UnsafeBufferPointer(start: pcm, count: count)
                        guard values.allSatisfy(\.isFinite) else { sink.invalid = true; return false }
                        sink.samples.append(contentsOf: values)
                        return true
                    }, pointer, &result)
                }
                guard !sink.stopped else { throw CancellationError() }
                guard !sink.invalid else { throw PhononFailure.invalidAudio }
                guard completed, ContinuousClock.now < sink.deadline else {
                    if let error = ptts_last_error(handle), String(cString: error).contains("speech did not reach EOS before the frame limit") { throw PhononFailure.incomplete }
                    throw PhononFailure.runtime
                }
                guard !sink.samples.isEmpty, sink.samples.count == result.samples else { throw PhononFailure.invalidAudio }
                return sink.samples
            }
        } onCancel: { sink.cancel() }
        // Native cancellation returns partial PCM successfully; it must never become a cached passage.
        try Task.checkCancellation()
        return samples
    }

    public func close() async {
        try? await perform { if let handle = self.handle { ptts_free(handle); self.handle = nil } }
    }

    private func perform<T>(_ operation: @escaping () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            queue.async { continuation.resume(with: Result(catching: operation)) }
        }
    }

    deinit { if let handle { ptts_free(handle) } }

    private final class Sink: @unchecked Sendable {
        let deadline: ContinuousClock.Instant
        var samples: [Float] = []
        var invalid = false
        private let lock = NSLock()
        private var cancelled = false
        init(deadline: ContinuousClock.Instant) { self.deadline = deadline }
        var stopped: Bool { lock.lock(); defer { lock.unlock() }; return cancelled }
        func cancel() { lock.lock(); cancelled = true; lock.unlock() }
    }
}
