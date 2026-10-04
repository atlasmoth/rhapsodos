package com.ereader.shared

internal object PassageBuilder {
    private const val MAX_SEGMENT_LENGTH = 1200

    fun build(documentId: String, pages: List<SourcePage>): List<Passage> {
        var sourceOffset = 0
        return buildList {
            pages.forEach { page ->
                var start = 0
                val text = page.text
                while (start < text.length) {
                    while (start < text.length && text[start].isWhitespace()) start++
                    if (start == text.length) break
                    val end = findEnd(text, start)
                    val raw = text.substring(start, end)
                    val spoken = normalize(raw)
                    if (spoken.isNotEmpty()) {
                        add(Passage(
                            id = "$documentId:1:${page.pageIndex}:$start",
                            documentId = documentId,
                            pageIndex = page.pageIndex,
                            pageStart = start,
                            pageEnd = end,
                            sourceStart = sourceOffset + start,
                            sourceEnd = sourceOffset + end,
                            sourceText = raw,
                            spokenText = spoken,
                            languageTag = page.languageTag,
                            spokenMapping = mapping(raw, start),
                            provenance = page.provenance,
                        ))
                    }
                    start = end
                }
                sourceOffset += text.length + 3
            }
        }
    }

    fun normalize(source: String): String = buildString {
        var separator = false
        source.forEach { character ->
            if (character.isWhitespace()) separator = isNotEmpty()
            else {
                if (separator) append(' ')
                append(character)
                separator = false
            }
        }
    }

    fun mapping(source: String, pageStart: Int): List<SpokenMapping> {
        val mappings = mutableListOf<SpokenMapping>()
        fun append(mapping: SpokenMapping) {
            val previous = mappings.lastOrNull()
            if (previous != null && previous.spokenEnd == mapping.spokenStart && previous.sourceEnd == mapping.sourceStart &&
                previous.kind == "linear" && mapping.kind == "linear" && previous.spokenEnd - previous.spokenStart == previous.sourceEnd - previous.sourceStart &&
                mapping.spokenEnd - mapping.spokenStart == mapping.sourceEnd - mapping.sourceStart) {
                mappings[mappings.lastIndex] = previous.copy(spokenEnd = mapping.spokenEnd, sourceEnd = mapping.sourceEnd)
            } else mappings.add(mapping)
        }
        var sourceIndex = 0
        var spokenIndex = 0
        while (sourceIndex < source.length) {
            val start = sourceIndex
            val whitespaceRun = source[sourceIndex].isWhitespace()
            while (sourceIndex < source.length && source[sourceIndex].isWhitespace() == whitespaceRun) sourceIndex++
            if (!whitespaceRun) {
                val length = sourceIndex - start
                append(SpokenMapping(spokenIndex, spokenIndex + length, pageStart + start, pageStart + sourceIndex))
                spokenIndex += length
            } else if (start > 0 && sourceIndex < source.length) {
                append(SpokenMapping(spokenIndex, spokenIndex + 1, pageStart + start, pageStart + sourceIndex, if (sourceIndex - start == 1) "linear" else "collapsed"))
                spokenIndex++
            }
        }
        return mappings
    }

    private fun findEnd(text: String, start: Int): Int {
        val limit = minOf(text.length, start + MAX_SEGMENT_LENGTH)
        var i = start
        var lastWhitespace = -1
        while (i < limit) {
            val current = text[i]
            if (current.isWhitespace()) lastWhitespace = i
            if (current == '\n' && i + 1 < text.length && text[i + 1] == '\n') {
                return i + 2
            }
            if (current in ".!?" && i > start && i + 1 < text.length && text[i + 1].isWhitespace()) {
                val previousWord = text.substring(start, i).takeLastWhile { it.isLetter() }
                if (previousWord.lowercase() !in abbreviations && previousWord.length != 1) return i + 1
            }
            i++
        }
        if (limit == text.length) return limit
        if (lastWhitespace > start) return lastWhitespace + 1
        // Persisted UTF-16 ranges must never split a surrogate pair.
        return if (text[limit - 1].isHighSurrogate() && text[limit].isLowSurrogate()) limit - 1 else limit
    }

    private val abbreviations = setOf("mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "fig", "no")
}
