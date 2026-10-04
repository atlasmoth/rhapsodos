package com.osuji.ereaderapp.speech

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import java.io.Closeable
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.zip.GZIPInputStream
import org.json.JSONObject

internal class EnglishFrontend(directory: File) : Closeable {
    private val symbols = HashMap<Char, Int>()
    private val dictionary = HashMap<String, String>(300_000)
    private val metadata = JSONObject(File(directory, "g2p_meta.json").readText())
    private val characters = metadata.getJSONObject("char2idx")
    private val phonemes = metadata.getJSONObject("idx2ph")
    private val maxTokens = metadata.getInt("MAXT")
    private val repeats = metadata.getInt("char_repeats")
    private val phonemeCount = metadata.getInt("n_phonemes")
    private val special = metadata.getJSONArray("special").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
    private val model: CompiledModel

    init {
        require(maxTokens == 96 && repeats == 3 && phonemeCount == 64) { "Incompatible English pronunciation frontend" }
        val alphabet = JSONObject(File(directory, "config.json").readText()).getJSONArray("symbols")
        for (index in 0 until alphabet.length()) {
            val value = alphabet.getString(index)
            if (value.length == 1) symbols[value[0]] = index
        }
        GZIPInputStream(File(directory, "g2p_dict.txt.gz").inputStream()).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val separator = line.indexOf('\t')
                if (separator > 0) dictionary[line.substring(0, separator)] = line.substring(separator + 1)
            }
        }
        require(dictionary.size > 100_000) { "The pronunciation dictionary is incomplete" }
        val options = CompiledModel.Options(Accelerator.CPU).apply { cpuOptions = CompiledModel.CpuOptions(numThreads = 2) }
        model = CompiledModel.create(File(directory, "dp_g2p_matcha_fp16.tflite").absolutePath, options, null)
    }

    fun phonemize(text: String, checkCancellation: () -> Unit): IntArray {
        val expanded = LITERAL_OPERATOR_PATTERN.replace(text) { " ${LITERAL_OPERATORS.getValue(it.value)} " }
        require(expanded.none { (Character.getType(it) == Character.MATH_SYMBOL.toInt() && it !in "+−") || it in UNHANDLED_OPERATORS }) {
            "This English voice cannot safely read this symbol. Use an installed voice and check the source preview."
        }
        val normalized = Normalizer.normalize(expanded.replace('’', '\''), Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
            .replace('−', '-').replace(Regex("(?<=\\p{L})-(?=\\p{L})"), " ")
            .replace(Regex("([$£€])([0-9][0-9,]*(?:\\.[0-9]+)?)")) { match ->
                match.groupValues[2] + " " + when (match.groupValues[1]) { "$" -> "dollars"; "£" -> "pounds"; else -> "euros" }
            }.replace("$", " dollar sign ").replace("£", " pound sign ").replace("€", " euro sign ")
            .replace("%", " percent ").replace("&", " and ")
        val pieces = ArrayList<String>()
        for (match in TOKEN.findAll(normalized)) {
            checkCancellation()
            val token = match.value
            when {
                token[0].isDigit() -> numberWords(token).forEach { pieces += pronunciation(it, checkCancellation) }
                token[0].isLetter() -> {
                    require(token.all { it in 'a'..'z' || it in 'A'..'Z' || it == '\'' }) { "This voice supports English text" }
                    if (token.length >= 2 && token.all { it in 'A'..'Z' }) {
                        pieces += token.map { LETTERS.getValue(it.lowercaseChar()) }.joinToString("")
                    } else pieces += pronunciation(token.lowercase(Locale.ROOT), checkCancellation)
                }
                token[0] in ".,!?;:" -> pieces += token
                token in OPERATORS -> pieces += pronunciation(OPERATORS.getValue(token), checkCancellation)
            }
        }
        return pieces.joinToString(" ").mapNotNull { symbols[it] }.toIntArray()
    }

    fun isWordBoundary(symbol: Int): Boolean = symbols[' '] == symbol || symbols[','] == symbol || symbols[';'] == symbol

    private fun pronunciation(word: String, checkCancellation: () -> Unit): String {
        dictionary[word]?.let { return it }
        if (word.length > 30) return word.filter { it in 'a'..'z' }.map { LETTERS.getValue(it) }.joinToString("")
        val ids = ArrayList<Int>(maxTokens)
        ids += metadata.getInt("start")
        for (character in word) if (characters.has(character.toString())) repeat(repeats) { ids += characters.getInt(character.toString()) }
        ids += metadata.getInt("end")
        val input = FloatArray(maxTokens) { if (it < ids.size) ids[it].toFloat() else 0f }
        checkCancellation()
        val inputs = model.createInputBuffers()
        val outputs = model.createOutputBuffers()
        try {
            inputs[0].writeFloat(input)
            model.run(inputs, outputs)
            val logits = outputs[0].readFloat()
            check(logits.size == maxTokens * phonemeCount) { "Incompatible pronunciation model output" }
            val result = StringBuilder()
            var previous = -1
            for (position in ids.indices) {
                var best = 0
                for (candidate in 1 until phonemeCount) if (logits[position * phonemeCount + candidate] > logits[position * phonemeCount + best]) best = candidate
                if (best == previous) continue
                previous = best
                val phoneme = phonemes.optString(best.toString(), "")
                if (best != 0 && phoneme !in special) result.append(phoneme.replace("-", ""))
            }
            return result.toString()
        } finally {
            inputs.forEach { it.close() }
            outputs.forEach { it.close() }
        }
    }

    private fun numberWords(token: String): List<String> {
        val parts = token.replace(",", "").split('.', limit = 2)
        val value = parts[0].toLongOrNull()
        val integer = if (value == null) parts[0].map { ONES[it - '0'] } else integerWords(value)
        return if (parts.size == 1) integer else integer + "point" + parts[1].map { ONES[it - '0'] }
    }

    private fun integerWords(value: Long): List<String> {
        if (value == 0L) return listOf("zero")
        if (value >= 1_000_000_000_000_000L) return value.toString().map { ONES[it - '0'] }
        val groups = ArrayList<Int>()
        var remaining = value
        while (remaining > 0) { groups += (remaining % 1_000).toInt(); remaining /= 1_000 }
        return buildList {
            for (group in groups.indices.reversed()) {
                var amount = groups[group]
                if (amount == 0) continue
                if (amount >= 100) { add(ONES[amount / 100]); add("hundred"); amount %= 100 }
                if (amount >= 20) { add(TENS[amount / 10]); amount %= 10 }
                if (amount > 0) add(ONES[amount])
                if (group > 0) add(SCALES[group])
            }
        }
    }

    override fun close() = model.close()

    companion object {
        private val LITERAL_OPERATORS = mapOf("*" to "asterisk", "<" to "less than", ">" to "greater than", "≤" to "less than or equal to", "≥" to "greater than or equal to", "≠" to "not equal to", "×" to "times", "÷" to "divided by", "=" to "equals")
        private val LITERAL_OPERATOR_PATTERN = Regex("[*<>≤≥≠×÷=]")
        private val UNHANDLED_OPERATORS = "^~\\→←↔↦⇒⇐⇔"
        private val TOKEN = Regex("[0-9][0-9,]*(?:\\.[0-9]+)?|\\p{L}+(?:'\\p{L}+)*|[.,!?;:+/=-]")
        private val OPERATORS = mapOf("+" to "plus", "-" to "minus", "/" to "slash", "=" to "equals")
        private val ONES = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
        private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
        private val SCALES = listOf("", "thousand", "million", "billion", "trillion")
        private val LETTERS = mapOf('a' to "ˈeɪ", 'b' to "bˈiː", 'c' to "sˈiː", 'd' to "dˈiː", 'e' to "ˈiː", 'f' to "ˈɛf", 'g' to "dʒˈiː", 'h' to "ˈeɪtʃ", 'i' to "ˈaɪ", 'j' to "dʒˈeɪ", 'k' to "kˈeɪ", 'l' to "ˈɛl", 'm' to "ˈɛm", 'n' to "ˈɛn", 'o' to "ˈoʊ", 'p' to "pˈiː", 'q' to "kjˈuː", 'r' to "ˈɑːɹ", 's' to "ˈɛs", 't' to "tˈiː", 'u' to "jˈuː", 'v' to "vˈiː", 'w' to "dˈʌbəljˌuː", 'x' to "ˈɛks", 'y' to "wˈaɪ", 'z' to "zˈiː")
    }
}
