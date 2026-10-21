import AVFoundation
import Foundation

final class SavedAudioChunks {
    private let file: AVAudioFile
    private let converter: AVAudioConverter
    private let input: AVAudioPCMBuffer
    private let output: AVAudioPCMBuffer
    private var pending: [Float] = []
    private var ended = false
    private var frames = 0

    init(_ url: URL) throws {
        file = try AVAudioFile(forReading: url, commonFormat: .pcmFormatFloat32, interleaved: false)
        let source = file.processingFormat
        guard source.sampleRate.isFinite, (8_000...192_000).contains(source.sampleRate),
              (1...2).contains(source.channelCount), file.length > 0,
              Double(file.length) / source.sampleRate <= 1800,
              let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1, interleaved: false),
              let converter = AVAudioConverter(from: source, to: format),
              let input = AVAudioPCMBuffer(pcmFormat: source, frameCapacity: 32_768),
              let output = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 8_000) else { throw TranscriptionFailure.audio }
        self.converter = converter; self.input = input; self.output = output
        converter.downmix = true
        pending.reserveCapacity(488_000)
    }

    func next() throws -> [Float]? {
        while !ended && pending.count <= 480_000 { try read() }
        guard !pending.isEmpty else { return nil }
        let count = pending.count <= 480_000 ? pending.count : Self.boundary(pending)
        let result = Array(pending.prefix(count))
        pending.removeFirst(count)
        return result
    }

    private func read() throws {
        try Task.checkCancellation()
        var readFailure: Error?
        var conversionError: NSError?
        output.frameLength = 0
        let status = converter.convert(to: output, error: &conversionError) { requested, state in
            do {
                try Task.checkCancellation()
                guard self.file.framePosition < self.file.length else { state.pointee = .endOfStream; return nil }
                try self.file.read(into: self.input, frameCount: min(requested, self.input.frameCapacity))
                guard self.input.frameLength > 0, let channels = self.input.floatChannelData else { throw TranscriptionFailure.audio }
                for channel in 0..<Int(self.input.format.channelCount) {
                    guard UnsafeBufferPointer(start: channels[channel], count: Int(self.input.frameLength)).allSatisfy(\.isFinite) else { throw TranscriptionFailure.audio }
                }
                state.pointee = .haveData
                return self.input
            } catch {
                readFailure = error; state.pointee = .endOfStream; return nil
            }
        }
        if let readFailure { throw readFailure }
        guard conversionError == nil, status != .error else { throw TranscriptionFailure.audio }
        ended = status == .endOfStream
        if output.frameLength > 0, let channel = output.floatChannelData?[0] {
            let samples = UnsafeBufferPointer(start: channel, count: Int(output.frameLength))
            guard samples.allSatisfy(\.isFinite) else { throw TranscriptionFailure.audio }
            frames += samples.count
            guard frames <= 28_800_000 else { throw TranscriptionFailure.audio }
            pending.append(contentsOf: samples)
        } else if !ended { throw TranscriptionFailure.audio }
    }

    private static func boundary(_ samples: [Float]) -> Int {
        var bestStart = 0
        var bestCount = 0
        var quietStart = 0
        var quietCount = 0
        for frame in 0..<150 {
            let start = 240_000 + frame * 1600
            let power = samples[start..<(start + 1600)].reduce(0.0) { $0 + Double($1) * Double($1) }
            if sqrt(power / 1600) <= 0.02 {
                if quietCount == 0 { quietStart = frame }
                quietCount += 1
                if quietCount > bestCount { bestStart = quietStart; bestCount = quietCount }
            } else { quietCount = 0 }
        }
        return bestCount == 0 ? 480_000 : 240_000 + (bestStart + bestCount / 2) * 1600
    }
}
