package com.ereader.shared

internal data class SpokenText(val text: String, val mapping: List<SpokenMapping>) {
    companion object {
        fun build(source: String, pageStart: Int, rules: List<PronunciationRule>): SpokenText {
            val normalized = PassageBuilder.normalize(source)
            val mapping = PassageBuilder.mapping(source, pageStart)
            if (rules.isEmpty()) return SpokenText(normalized, mapping)
            val ordered = rules.sortedByDescending { it.term.length }
                .groupBy { it.term.first().lowercaseChar() }
            val output = StringBuilder()
            val result = mutableListOf<SpokenMapping>()
            fun append(start: Int, end: Int, replacement: String? = null) {
                val overlaps = mapping.filter { it.spokenStart < end && it.spokenEnd > start }
                if (replacement != null) {
                    val first = overlaps.first()
                    val last = overlaps.last()
                    val sourceStart = first.sourceBoundary(start.coerceAtLeast(first.spokenStart), false)
                    val sourceEnd = last.sourceBoundary(end.coerceAtMost(last.spokenEnd), true)
                    result.add(SpokenMapping(output.length, output.length + replacement.length, sourceStart, sourceEnd, "replacement"))
                    output.append(replacement)
                } else overlaps.forEach { interval ->
                    val from = maxOf(start, interval.spokenStart)
                    val to = minOf(end, interval.spokenEnd)
                    result.add(SpokenMapping(output.length, output.length + to - from, interval.sourceBoundary(from, false), interval.sourceBoundary(to, true), interval.kind))
                    output.append(normalized, from, to)
                }
            }
            var position = 0
            var unchangedStart = 0
            while (position < normalized.length) {
                val candidates = if (position == 0 || !normalized[position - 1].isLetterOrDigit()) ordered[normalized[position].lowercaseChar()].orEmpty() else emptyList()
                val rule = candidates.firstOrNull {
                    position + it.term.length <= normalized.length && normalized.regionMatches(position, it.term, 0, it.term.length, ignoreCase = true) &&
                        (position == 0 || !normalized[position - 1].isLetterOrDigit()) &&
                        (position + it.term.length == normalized.length || !normalized[position + it.term.length].isLetterOrDigit())
                }
                if (rule == null) {
                    position += if (normalized[position].isHighSurrogate() && position + 1 < normalized.length && normalized[position + 1].isLowSurrogate()) 2 else 1
                } else {
                    if (unchangedStart < position) append(unchangedStart, position)
                    append(position, position + rule.term.length, rule.replacement)
                    position += rule.term.length
                    unchangedStart = position
                }
            }
            if (unchangedStart < normalized.length) append(unchangedStart, normalized.length)
            return SpokenText(output.toString(), result)
        }
    }
}

private fun SpokenMapping.sourceBoundary(offset: Int, end: Boolean): Int =
    if (kind == "linear" && sourceEnd - sourceStart == spokenEnd - spokenStart) sourceStart + offset - spokenStart else if (end) sourceEnd else sourceStart
