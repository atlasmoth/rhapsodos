package com.osuji.ereaderapp.speech

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.ceil
import kotlin.math.floor

internal class WavPcmReader(path: File) : Closeable {
    private val file = RandomAccessFile(path, "r")
    private val header = try { readHeader() } catch (error: Throwable) { file.close(); throw error }
    val targetSamples = ceil(header.frames.toDouble() * 16_000 / header.rate).toLong()

    fun window(start: Long, length: Int): FloatArray {
        require(start >= 0 && length in 1..80_000)
        val result = FloatArray(length)
        val ratio = header.rate.toDouble() / 16_000
        val firstFrame = floor(start * ratio).toLong()
        val endFrame = minOf(header.frames, ceil((start + length) * ratio).toLong() + 1)
        if (firstFrame >= endFrame) return result
        val data = ByteArray(((endFrame - firstFrame) * header.blockSize).toInt())
        file.seek(header.dataOffset + firstFrame * header.blockSize)
        file.readFully(data)
        fun sample(frame: Int): Float {
            val bounded = frame.coerceIn(0, ((endFrame - firstFrame) - 1).toInt())
            var value = 0f
            for (channel in 0 until header.channels) {
                val offset = bounded * header.blockSize + channel * (header.bits / 8)
                val raw = if (header.bits == 16) ((data[offset].toInt() and 0xff) or (data[offset + 1].toInt() shl 8)).toShort().toInt()
                else int32(data, offset)
                value += if (header.format == 3) Float.fromBits(raw) else if (header.bits == 16) raw / 32_768f else raw / 2_147_483_648f
            }
            value /= header.channels
            require(value.isFinite()) { "Recording contains invalid audio samples" }
            return value.coerceIn(-1f, 1f)
        }
        for (index in 0 until minOf(length.toLong(), targetSamples - start).toInt()) {
            val position = (start + index) * ratio - firstFrame
            val left = floor(position).toInt()
            val fraction = (position - left).toFloat()
            result[index] = sample(left) * (1f - fraction) + sample(left + 1) * fraction
        }
        return result
    }

    private fun readHeader(): Header {
        require(file.length() in 44..134_217_728L) { "Recording is empty or exceeds the transcription limit" }
        val start = ByteArray(12).also { file.readFully(it) }
        require(start.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && start.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE") { "Transcription needs a finalized WAV recording" }
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var block = 0
        var dataOffset = -1L
        var dataSize = 0L
        while (file.filePointer + 8 <= file.length()) {
            val chunk = ByteArray(8).also { file.readFully(it) }
            val name = chunk.copyOfRange(0, 4).toString(Charsets.US_ASCII)
            val size = int32(chunk, 4).toLong() and 0xffffffffL
            val offset = file.filePointer
            require(offset + size <= file.length()) { "Recording has an unfinished WAV chunk" }
            if (name == "fmt ") {
                require(size >= 16) { "Invalid recording format" }
                val bytes = ByteArray(16).also { file.readFully(it) }
                format = uint16(bytes, 0); channels = uint16(bytes, 2); rate = int32(bytes, 4)
                block = uint16(bytes, 12); bits = uint16(bytes, 14)
            } else if (name == "data") { dataOffset = offset; dataSize = size }
            file.seek(offset + size + size % 2)
        }
        require(channels in 1..2 && rate in 8_000..96_000 && bits in listOf(16, 32) && (format == 1 || format == 3 && bits == 32)) { "Unsupported recording audio format" }
        require(block == channels * bits / 8 && dataOffset >= 0 && dataSize > 0 && dataSize % block == 0L) { "Recording is not finalized" }
        return Header(format, channels, rate, bits, block, dataOffset, dataSize / block)
    }

    override fun close() = file.close()

    private data class Header(val format: Int, val channels: Int, val rate: Int, val bits: Int, val blockSize: Int, val dataOffset: Long, val frames: Long)

    companion object {
        private fun uint16(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)
        private fun int32(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8) or ((bytes[at + 2].toInt() and 0xff) shl 16) or (bytes[at + 3].toInt() shl 24)
    }
}
