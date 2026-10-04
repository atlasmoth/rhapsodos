package com.osuji.ereaderapp.speech

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipFile

internal class KittenEngine(directory: File) : Closeable {
    private val styles = readStyles(File(directory, "voices.npz"))
    private val graphs = ArrayList<InterpreterGraph>(3)
    private val frontend: EnglishFrontend

    init {
        try {
            for (name in listOf("kitten_predictor_fp16.tflite", "kitten_prosody_fp16.tflite", "kitten_vocoder_fp16.tflite")) {
                graphs += InterpreterGraph(File(directory, name))
            }
            frontend = EnglishFrontend(directory)
        } catch (error: Throwable) {
            graphs.forEach { it.close() }
            throw error
        }
    }

    fun synthesize(text: String, voiceId: String, checkCancellation: () -> Unit): FloatArray {
        require(text.isNotBlank() && text.length <= 2_000) { "Speak a shorter non-empty passage" }
        val voice = VOICE_ALIASES[voiceId.lowercase(Locale.ROOT)] ?: voiceId
        val voiceStyle = checkNotNull(styles[voice]) { "This voice is not in the installed pack" }
        val audio = ArrayList<FloatArray>()
        var samples = 0
        for (sentence in chunks(text)) {
            checkCancellation()
            val symbols = frontend.phonemize(sentence, checkCancellation)
            require(symbols.isNotEmpty()) { "This English voice cannot pronounce this passage" }
            val row = minOf(sentence.length, 399)
            val style = voiceStyle.copyOfRange(row * 256, (row + 1) * 256)
            for (part in synthesizePhonemes(symbols, style, checkCancellation)) {
                samples += part.size
                check(samples <= 24_000 * 180) { "Speak a shorter passage" }
                audio += part
            }
        }
        return FloatArray(samples).also { result ->
            var offset = 0
            for (part in audio) { part.copyInto(result, offset); offset += part.size }
        }
    }

    private fun synthesizePhonemes(symbols: IntArray, style: FloatArray, checkCancellation: () -> Unit): List<FloatArray> {
        checkCancellation()
        if (symbols.size > MAX_PHONEMES) return splitPhonemes(symbols, style, checkCancellation)
        val ids = IntArray(symbols.size + 2)
        symbols.copyInto(ids, 1)
        val predictor = graphs[0]
        predictor.run(mapOf(
            "input_ids" to InterpreterGraph.ints(intArrayOf(1, ids.size), ids),
            "style" to InterpreterGraph.floats(intArrayOf(1, 256), style),
            "speed" to InterpreterGraph.floats(intArrayOf(1), floatArrayOf(1f)),
        ))
        val durations = predictor.ints("StatefulPartitionedCall:1")
        check(durations.size == ids.size && durations.all { it in 0..4_000 }) { "Invalid speech duration" }
        val frames = durations.sum()
        check(frames > 0) { "Invalid speech duration" }
        if (frames > MAX_FRAMES) {
            require(symbols.size > 1) { "This voice cannot safely synthesize this segment" }
            return splitPhonemes(symbols, style, checkCancellation)
        }
        val en = repeatRows(predictor.floats("StatefulPartitionedCall:2"), durations, 256)
        val asr = repeatRows(predictor.floats("StatefulPartitionedCall:0"), durations, 128)
        checkCancellation()
        val prosody = graphs[1]
        prosody.run(mapOf(
            "en" to InterpreterGraph.floats(intArrayOf(1, frames, 256), en),
            "style" to InterpreterGraph.floats(intArrayOf(1, 256), style),
        ))
        val f0 = prosody.floats("StatefulPartitionedCall:0")
        val noise = prosody.floats("StatefulPartitionedCall:1")
        val harmonics = prosody.floats("StatefulPartitionedCall:2")
        check(f0.size == frames * 2 && noise.size == f0.size && harmonics.size == (frames * 120 + 1) * 22) { "Invalid speech prosody shape" }
        checkCancellation()
        val vocoder = graphs[2]
        vocoder.run(mapOf(
            "asr" to InterpreterGraph.floats(intArrayOf(1, frames, 128), asr),
            "f0" to InterpreterGraph.floats(intArrayOf(1, f0.size), f0),
            "n" to InterpreterGraph.floats(intArrayOf(1, noise.size), noise),
            "har" to InterpreterGraph.floats(intArrayOf(1, harmonics.size / 22, 22), harmonics),
            "style" to InterpreterGraph.floats(intArrayOf(1, 256), style),
        ))
        val waveform = vocoder.floats("Identity")
        check(waveform.size == frames * 600 && waveform.all { it.isFinite() }) { "Invalid speech waveform" }
        val trimmed = waveform.copyOf(maxOf(waveform.size - 5_000, minOf(1_200, waveform.size)))
        for (index in trimmed.indices) trimmed[index] = trimmed[index].coerceIn(-1f, 1f)
        return listOf(trimmed)
    }

    private fun splitPhonemes(symbols: IntArray, style: FloatArray, checkCancellation: () -> Unit): List<FloatArray> {
        val midpoint = symbols.size / 2
        val middle = (1 until symbols.size).filter { frontend.isWordBoundary(symbols[it - 1]) }
            .minByOrNull { kotlin.math.abs(it - midpoint) } ?: midpoint
        return synthesizePhonemes(symbols.copyOfRange(0, middle), style, checkCancellation) +
            synthesizePhonemes(symbols.copyOfRange(middle, symbols.size), style, checkCancellation)
    }

    private fun repeatRows(values: FloatArray, durations: IntArray, width: Int): FloatArray {
        check(values.size == durations.size * width) { "Invalid speech feature shape" }
        val result = FloatArray(durations.sum() * width)
        var offset = 0
        for (row in durations.indices) repeat(durations[row]) {
            values.copyInto(result, offset, row * width, (row + 1) * width)
            offset += width
        }
        return result
    }

    private fun chunks(text: String): List<String> {
        val result = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            while (start < text.length && text[start].isWhitespace()) start++
            if (start == text.length) break
            var end = minOf(start + 100, text.length)
            val punctuation = (start until end).firstOrNull { text[it] in ".!?" && it + 1 < text.length && text[it + 1].isWhitespace() }
            if (punctuation != null) end = punctuation + 1
            else if (end < text.length) {
                val space = text.lastIndexOf(' ', end - 1)
                if (space > start) end = space + 1
                else if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            }
            result += text.substring(start, end).trim()
            start = end
        }
        return result
    }

    override fun close() { frontend.close(); graphs.forEach { it.close() } }

    companion object {
        private const val MAX_PHONEMES = 110
        private const val MAX_FRAMES = 240
        val VOICE_IDS = listOf("expr-voice-2-m", "expr-voice-2-f", "expr-voice-3-m", "expr-voice-3-f", "expr-voice-4-m", "expr-voice-4-f", "expr-voice-5-m", "expr-voice-5-f")
        private val VOICE_ALIASES = mapOf("jasper" to VOICE_IDS[0], "bella" to VOICE_IDS[1], "bruno" to VOICE_IDS[2], "luna" to VOICE_IDS[3], "hugo" to VOICE_IDS[4], "rosie" to VOICE_IDS[5], "leo" to VOICE_IDS[6], "kiki" to VOICE_IDS[7])

        private fun readStyles(file: File): Map<String, FloatArray> = ZipFile(file).use { zip ->
            VOICE_IDS.associateWith { voice ->
                val entry = checkNotNull(zip.getEntry("$voice.npy")) { "Missing voice embedding" }
                require(entry.size in 409_610..410_000) { "Invalid voice embedding size" }
                val data = zip.getInputStream(entry).use { it.readBytes() }
                check(data.take(6).toByteArray().contentEquals(byteArrayOf(0x93.toByte(), 0x4e, 0x55, 0x4d, 0x50, 0x59))) { "Invalid voice embedding format" }
                val version = data[6].toInt()
                val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                val headerStart = if (version == 1) 10 else 12
                val headerLength = if (version == 1) buffer.getShort(8).toInt() and 0xffff else buffer.getInt(8)
                require(version in 1..2 && headerLength in 1..1_024 && headerStart + headerLength + 409_600 == data.size) { "Invalid voice embedding header" }
                val header = data.copyOfRange(headerStart, headerStart + headerLength).toString(Charsets.ISO_8859_1)
                require("<f4" in header && "False" in header && Regex("\\(400,\\s*256\\)").containsMatchIn(header)) { "Incompatible voice embedding" }
                buffer.position(headerStart + headerLength)
                FloatArray(400 * 256).also { buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
            }
        }
    }
}
