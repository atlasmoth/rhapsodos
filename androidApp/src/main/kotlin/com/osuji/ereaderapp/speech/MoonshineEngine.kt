package com.osuji.ereaderapp.speech

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import org.json.JSONObject

internal class MoonshineEngine(directory: File) : Closeable {
    private val vocabulary: Map<Int, String>
    private val specialIds: Set<Int>
    private val model: CompiledModel

    init {
        val tokenizer = JSONObject(File(directory, "tokenizer.json").readText())
        val vocab = tokenizer.getJSONObject("model").getJSONObject("vocab")
        vocabulary = vocab.keys().asSequence().associate { it.let { token -> vocab.getInt(token) to token } }
        val added = tokenizer.getJSONArray("added_tokens")
        specialIds = (0 until added.length()).map { added.getJSONObject(it) }.filter { it.optBoolean("special") }.map { it.getInt("id") }.toSet()
        val options = CompiledModel.Options(Accelerator.CPU).apply { cpuOptions = CompiledModel.CpuOptions(numThreads = 2) }
        model = CompiledModel.create(File(directory, "moonshine_tiny_5s_i8.tflite").absolutePath, options, null)
        try {
            check(elements("args_0", "encode") == 80_000) { "Incompatible transcription window" }
        } catch (error: Throwable) {
            model.close()
            throw error
        }
    }

    fun recognize(audio: FloatArray, checkCancellation: () -> Unit): String {
        require(audio.size == 80_000 && audio.all { it.isFinite() }) { "Invalid transcription audio" }
        val encoderInputs = model.createInputBuffers("encode")
        val encoderOutputs = model.createOutputBuffers("encode")
        val decoderInputs = model.createInputBuffers("decode")
        val decoderOutputs = model.createOutputBuffers("decode")
        try {
            checkCancellation()
            encoderInputs[0].writeFloat(audio)
            model.run(encoderInputs, encoderOutputs, "encode")
            val tokenBuffer = decoderInputs[decoderInputs.size - 2]
            val maskBuffer = decoderInputs.last()
            val capacity = elements("args_${decoderInputs.size - 2}", "decode")
            val maskElements = elements("args_${decoderInputs.size - 1}", "decode")
            val maskSide = kotlin.math.sqrt(maskElements.toDouble()).toInt()
            check(capacity in 2..512 && maskSide * maskSide == maskElements) { "Incompatible transcription decoder" }
            val mask = FloatArray(maskElements) { -0.7f * Float.MAX_VALUE }
            for (row in 0 until maskSide) for (column in 0..row) mask[row * maskSide + column] = 0f
            maskBuffer.writeFloat(mask)
            val tokens = IntArray(capacity)
            tokens[0] = 1
            val decoded = ArrayList<Int>()
            val inputs = encoderOutputs + listOf(tokenBuffer, maskBuffer)
            for (position in 0 until capacity - 1) {
                checkCancellation()
                tokenBuffer.writeInt(tokens)
                model.run(inputs, decoderOutputs, "decode")
                val logits = decoderOutputs[0].readFloat()
                check(logits.size % capacity == 0) { "Incompatible transcription logits" }
                val width = logits.size / capacity
                val offset = position * width
                var best = 0
                for (candidate in 1 until width) if (logits[offset + candidate] > logits[offset + best]) best = candidate
                if (best == 2) break
                decoded += best
                tokens[position + 1] = best
            }
            return decodeTokens(decoded)
        } finally {
            encoderInputs.forEach { it.close() }
            encoderOutputs.forEach { it.close() }
            decoderInputs.forEach { it.close() }
            decoderOutputs.forEach { it.close() }
        }
    }

    private fun decodeTokens(ids: List<Int>): String {
        val result = StringBuilder()
        val bytes = ByteArrayOutputStream()
        fun flush() { if (bytes.size() > 0) { result.append(bytes.toByteArray().toString(Charsets.UTF_8)); bytes.reset() } }
        for (id in ids) {
            if (id in specialIds) continue
            val token = vocabulary[id] ?: continue
            val fallback = BYTE_TOKEN.matchEntire(token)
            if (fallback != null) bytes.write(fallback.groupValues[1].toInt(16))
            else { flush(); result.append(token.replace('▁', ' ')) }
        }
        flush()
        return result.toString().trim()
    }

    override fun close() = model.close()

    private fun elements(name: String, signature: String): Int =
        checkNotNull(model.getInputTensorType(name, signature).layout) { "Missing transcription tensor layout" }
            .dimensions.fold(1) { total, size -> Math.multiplyExact(total, size) }

    companion object { private val BYTE_TOKEN = Regex("<0x([0-9A-Fa-f]{2})>") }
}
