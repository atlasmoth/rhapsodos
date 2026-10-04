package com.osuji.ereaderapp.audio

import java.io.File
import java.io.RandomAccessFile

fun validateRecording(file: File): Long = RandomAccessFile(file, "r").use { input ->
    require(input.length() in 44..134_217_728L) { "A recording is empty or too large." }
    fun unsignedInt(): Long = java.lang.Integer.toUnsignedLong(java.lang.Integer.reverseBytes(input.readInt()))
    fun unsignedShort(): Int = java.lang.Short.toUnsignedInt(java.lang.Short.reverseBytes(input.readShort()))
    fun tag() = ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII)
    require(tag() == "RIFF" && unsignedInt() == input.length() - 8 && tag() == "WAVE") { "A recording is not a finalized WAV file." }
    var rate = 0L
    var block = 0
    var bytes = -1L
    var formatSeen = false
    while (input.filePointer + 8 <= input.length()) {
        val name = tag()
        val size = unsignedInt()
        val start = input.filePointer
        require(size <= input.length() - start) { "A recording contains an incomplete WAV chunk." }
        when (name) {
            "fmt " -> {
                require(!formatSeen && size >= 16) { "A recording contains an invalid audio format." }
                formatSeen = true
                val format = unsignedShort()
                val channels = unsignedShort()
                rate = unsignedInt()
                val byteRate = unsignedInt()
                block = unsignedShort()
                val bits = unsignedShort()
                require(format == 1 && channels in 1..2 && rate in 8_000..96_000 && bits == 16 && block == channels * 2 && byteRate == rate * block) { "Archives require mono or stereo PCM16 WAV recordings." }
            }
            "data" -> { require(bytes < 0) { "A recording contains duplicate audio data." }; bytes = size }
        }
        input.seek(start + size + size % 2)
    }
    require(input.filePointer == input.length() && formatSeen && bytes > 0 && bytes % block == 0L) { "A recording is not finalized." }
    bytes / block * 1000 / rate
}
