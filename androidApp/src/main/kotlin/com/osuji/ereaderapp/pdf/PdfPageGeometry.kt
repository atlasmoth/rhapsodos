package com.osuji.ereaderapp.pdf

import kotlinx.serialization.Serializable

@Serializable
data class PdfPageGeometry(val rotation: Int, val cropWidth: Float, val cropHeight: Float, val displayWidth: Float, val displayHeight: Float)

internal object PdfPageCoordinates {
    fun displayed(region: SourceRegion, rotation: Int): SourceRegion = when (rotation) {
        90 -> bounds(region, 1 - region.y - region.height, region.x, region.height, region.width)
        180 -> bounds(region, 1 - region.x - region.width, 1 - region.y - region.height, region.width, region.height)
        270 -> bounds(region, region.y, 1 - region.x - region.width, region.height, region.width)
        else -> region
    }

    fun canonical(region: SourceRegion, rotation: Int): SourceRegion = displayed(region, (360 - rotation) % 360)

    private fun bounds(region: SourceRegion, x: Float, y: Float, width: Float, height: Float): SourceRegion {
        val left = x.coerceIn(0f, 1f)
        val top = y.coerceIn(0f, 1f)
        val right = (x + width).coerceIn(left, 1f)
        val bottom = (y + height).coerceIn(top, 1f)
        return region.copy(x = left, y = top, width = right - left, height = bottom - top)
    }
}
