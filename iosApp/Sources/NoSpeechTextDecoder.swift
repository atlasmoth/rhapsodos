import CoreML
import Foundation
import WhisperKit

// WhisperKit 0.18 leaves no-speech probability unset; read it from the unsuppressed start-token logits.
final class NoSpeechTextDecoder: TextDecoding, WhisperMLModel {
    private let base = TextDecoder()
    var model: MLModel? { get { base.model } set { base.model = newValue } }
    var tokenizer: WhisperTokenizer? { get { base.tokenizer } set { base.tokenizer = newValue } }
    var prefillData: WhisperMLModel? { get { base.prefillData } set { base.prefillData = newValue } }
    var isModelMultilingual: Bool { get { base.isModelMultilingual } set { base.isModelMultilingual = newValue } }
    var logitsFilters: [any LogitsFiltering]? { get { base.logitsFilters } set { base.logitsFilters = newValue } }
    var supportsWordTimestamps: Bool { base.supportsWordTimestamps }
    var logitsSize: Int? { base.logitsSize }
    var kvCacheEmbedDim: Int? { base.kvCacheEmbedDim }
    var kvCacheMaxSequenceLength: Int? { base.kvCacheMaxSequenceLength }
    var windowSize: Int? { base.windowSize }
    var embedSize: Int? { base.embedSize }
    func unloadModel() { base.unloadModel() }

    func predictLogits(_ inputs: any TextDecoderInputType) async throws -> (any TextDecoderOutputType)? {
        try await base.predictLogits(inputs)
    }

    func decodeText(from encoderOutput: any AudioEncoderOutputType, using decoderInputs: any DecodingInputsType,
                    sampler tokenSampler: any TokenSampling, options: DecodingOptions,
                    callback: TranscriptionCallback) async throws -> DecodingResult {
        let probability = try await noSpeechProbability(encoderOutput)
        try Task.checkCancellation()
        var result = try await base.decodeText(from: encoderOutput, using: decoderInputs, sampler: tokenSampler, options: options, callback: callback)
        result.noSpeechProb = probability
        return result
    }

    private func noSpeechProbability(_ output: any AudioEncoderOutputType) async throws -> Float {
        try Task.checkCancellation()
        guard let tokenizer, let encoder = output as? MLMultiArray,
              let input = try prepareDecoderInputs(withPrompt: [tokenizer.specialTokens.startOfTranscriptToken]) as? DecodingInputs else { throw TranscriptionFailure.runtime }
        input.inputIds[0] = NSNumber(value: tokenizer.specialTokens.startOfTranscriptToken)
        input.cacheLength[0] = 0
        let result = try await base.predictLogits(TextDecoderMLMultiArrayInputType(
            inputIds: input.inputIds, cacheLength: input.cacheLength,
            keyCache: input.keyCache, valueCache: input.valueCache, kvCacheUpdateMask: input.kvCacheUpdateMask,
            encoderOutputEmbeds: encoder, decoderKeyPaddingMask: input.decoderKeyPaddingMask)) as? TextDecoderMLMultiArrayOutputType
        guard let logits = result?.logits, tokenizer.specialTokens.noSpeechToken < logits.count else { throw TranscriptionFailure.runtime }
        let values = (0..<logits.count).map { logits[$0].doubleValue }
        guard let maximum = values.max(), maximum.isFinite else { throw TranscriptionFailure.runtime }
        let denominator = values.reduce(0) { $0 + exp($1 - maximum) }
        let probability = exp(values[tokenizer.specialTokens.noSpeechToken] - maximum) / denominator
        guard probability.isFinite else { throw TranscriptionFailure.runtime }
        return Float(probability)
    }
}
