package com.osuji.ereaderapp.audio

object CaptureLimits {
    const val MAXIMUM_DURATION_MILLIS = 30L * 60 * 1000
    const val MAXIMUM_BYTES = 16_000L * 2 * 30 * 60
    const val STORAGE_RESERVE_BYTES = 10L * 1024 * 1024

    fun writableBytes(written: Long, requested: Int, freeBytes: Long): Int {
        require(freeBytes >= STORAGE_RESERVE_BYTES + requested) { "Recording stopped because storage is running low. Your draft is retained; save or discard it." }
        require(written in 0 until MAXIMUM_BYTES) { "Recording stopped at 30 minutes. Your draft is retained; save or discard it." }
        return minOf(requested.toLong(), MAXIMUM_BYTES - written).toInt() / 2 * 2
    }
}
