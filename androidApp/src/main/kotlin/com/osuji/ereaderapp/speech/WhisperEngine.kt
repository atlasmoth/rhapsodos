package com.osuji.ereaderapp.speech

import java.io.Closeable
import java.io.File

internal class WhisperEngine(directory: File) : Closeable {
    private var handle = WhisperNative.open(File(directory, "ggml-base.en-q8_0.bin").canonicalPath.toByteArray(Charsets.UTF_8))

    fun recognize(audio: FloatArray, checkCancellation: () -> Unit): String {
        check(handle != 0L) { "This transcription model is closed." }
        require(audio.size in 1..480_000 && audio.all { it.isFinite() && it in -1f..1f }) { "Invalid transcription audio." }
        checkCancellation()
        val samples = if (audio.size < 16_000) audio.copyOf(16_000) else audio
        val result = WhisperNative.recognize(handle, samples, Runnable(checkCancellation)).toString(Charsets.UTF_8)
        checkCancellation()
        return result
    }

    override fun close() {
        if (handle != 0L) WhisperNative.close(handle)
        handle = 0
    }
}

internal object WhisperNative {
    init { System.loadLibrary("rhapsodos_whisper") }
    external fun open(path: ByteArray): Long
    external fun recognize(handle: Long, audio: FloatArray, cancellation: Runnable): ByteArray
    external fun close(handle: Long)
}
