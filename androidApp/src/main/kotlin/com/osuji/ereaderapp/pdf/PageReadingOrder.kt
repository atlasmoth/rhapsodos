package com.osuji.ereaderapp.pdf

import kotlin.math.abs

data class PositionedText(val text: String, val x: Float, val y: Float, val width: Float, val height: Float, val displayX: Float = x, val displayY: Float = y, val displayWidth: Float = width, val displayHeight: Float = height, val separatedWord: Boolean = false)

object PageReadingOrder {
    fun source(items: List<PositionedText>, readingWidth: Float, displayWidth: Float, displayHeight: Float, pageIndex: Int): PdfSourcePage {
        val text = StringBuilder()
        val regions = mutableListOf<SourceRegion>()
        for (row in divide(items, readingWidth, 0)) {
            val line = buildString {
                var previous: PositionedText? = null
                row.sortedBy { it.x }.forEach { item ->
                    val left = previous
                    if (left != null && isNotEmpty() && !last().isWhitespace() && !item.text.first().isWhitespace() && (item.separatedWord || item.x - (left.x + left.width) > minOf(left.height, item.height) * 0.16f)) append(' ')
                    append(item.text)
                    previous = item
                }
            }.trim()
            if (line.isBlank()) continue
            val start = text.length
            text.append(line)
            val left = row.minOf { it.displayX }.coerceIn(0f, displayWidth)
            val top = row.minOf { it.displayY }.coerceIn(0f, displayHeight)
            val right = row.maxOf { it.displayX + it.displayWidth }.coerceIn(left, displayWidth)
            val bottom = row.maxOf { it.displayY + it.displayHeight }.coerceIn(top, displayHeight)
            regions += SourceRegion(start, text.length, left / displayWidth, top / displayHeight, (right - left) / displayWidth, (bottom - top) / displayHeight)
            text.append('\n')
        }
        return PdfSourcePage(pageIndex, text.toString(), regions = regions)
    }

    private fun divide(items: List<PositionedText>, pageWidth: Float, depth: Int): List<List<PositionedText>> {
        if (items.size < 4 || depth > 16) return rows(items)
        fun largestGap(horizontal: Boolean): Pair<Float, Float>? {
            val spans = items.map { if (horizontal) it.y to it.y + it.height else it.x to it.x + it.width }.sortedBy { it.first }
            var end = spans.first().second
            var best: Pair<Float, Float>? = null
            for ((start, nextEnd) in spans.drop(1)) {
                if (start > end && (best == null || start - end > best.second - best.first)) best = end to start
                end = maxOf(end, nextEnd)
            }
            return best
        }
        val typicalHeight = items.map { it.height }.sorted()[items.size / 2]
        val vertical = largestGap(false)
        if (vertical != null && vertical.second - vertical.first >= maxOf(6f, pageWidth * 0.015f, typicalHeight * 0.9f)) {
            val cut = (vertical.first + vertical.second) / 2
            val left = items.filter { it.x + it.width / 2 < cut }
            val right = items.filter { it.x + it.width / 2 >= cut }
            fun extent(group: List<PositionedText>) = group.maxOf { it.y + it.height } - group.minOf { it.y }
            if (left.size >= 4 && right.size >= 4 && minOf(extent(left), extent(right)) >= items.maxOf { it.height } * 2) return divide(left, pageWidth, depth + 1) + divide(right, pageWidth, depth + 1)
        }
        val horizontal = largestGap(true)
        if (horizontal != null && horizontal.second - horizontal.first >= maxOf(12f, typicalHeight * 1.2f)) {
            val cut = (horizontal.first + horizontal.second) / 2
            val top = items.filter { it.y + it.height / 2 < cut }
            val bottom = items.filter { it.y + it.height / 2 >= cut }
            if (top.isNotEmpty() && bottom.isNotEmpty()) return divide(top, pageWidth, depth + 1) + divide(bottom, pageWidth, depth + 1)
        }
        return rows(items)
    }

    private fun rows(items: List<PositionedText>): List<List<PositionedText>> {
        val result = mutableListOf<MutableList<PositionedText>>()
        for (item in items.sortedWith(compareBy<PositionedText> { it.y }.thenBy { it.x })) {
            val row = result.lastOrNull()
            val reference = row?.firstOrNull()
            if (reference != null && abs(item.y - reference.y) <= maxOf(item.height, reference.height) * 0.45f) row += item
            else result += mutableListOf(item)
        }
        return result
    }
}
