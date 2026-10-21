package com.osuji.ereaderapp.speech

import java.io.Closeable
import java.io.File

internal class PhononEngine(directory: File) : Closeable {
    private var handle = PhononNative.open(directory.canonicalPath.toByteArray(Charsets.UTF_8))

    fun synthesize(text: String, voiceId: String, checkCancellation: () -> Unit): FloatArray {
        require(text.isNotBlank() && text.length <= 2_000 && '\u0000' !in text) { "Speak a shorter non-empty passage." }
        require(voiceId == "default") { "This voice is not in the installed pack." }
        check(handle != 0L) { "This offline voice is closed." }
        checkCancellation()
        val samples = PhononNative.synthesize(handle, text.toByteArray(Charsets.UTF_8), Runnable(checkCancellation))
        checkCancellation()
        return samples
    }

    override fun close() {
        if (handle != 0L) PhononNative.close(handle)
        handle = 0
    }
}

internal object PhononNative {
    init { System.loadLibrary("rhapsodos_phonon") }
    external fun open(path: ByteArray): Long
    external fun synthesize(handle: Long, text: ByteArray, cancellation: Runnable): FloatArray
    external fun close(handle: Long)
}
