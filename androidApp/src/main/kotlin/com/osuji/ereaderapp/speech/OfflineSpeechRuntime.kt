package com.osuji.ereaderapp.speech

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

data class SpeechAudio(val samples: FloatArray, val sampleRate: Int)

class OfflineSpeechRuntime(@Suppress("UNUSED_PARAMETER") context: Context) {
    private val worker = Dispatchers.Default.limitedParallelism(1)
    private val mutex = Mutex()
    private var phonon: Pair<String, PhononEngine>? = null
    private var whisper: Pair<String, WhisperEngine>? = null

    suspend fun synthesize(adapter: String, modelDirectory: File, text: String, voiceId: String): SpeechAudio =
        withContext(worker) {
            val job = currentCoroutineContext()
            mutex.withLock {
                job.ensureActive()
                whisper?.second?.close()
                whisper = null
                val samples = when (adapter) {
                    "android-phonon-english" -> {
                        try {
                            phonon(modelDirectory).synthesize(text, voiceId) { job.ensureActive() }
                        } catch (failure: Throwable) {
                            phonon?.second?.close()
                            phonon = null
                            throw failure
                        }
                    }
                    else -> error("Unsupported offline voice")
                }
                SpeechAudio(samples, 24_000)
            }
        }

    suspend fun transcribe(modelDirectory: File, wav: File, adapter: String = "android-whisper-base-en"): String = withContext(worker) {
        require(adapter == "android-whisper-base-en") { "Unsupported transcription adapter" }
        val job = currentCoroutineContext()
        try {
            WavPcmReader(wav).use { reader ->
                require(reader.targetSamples <= 16_000L * 1_800) { "This transcription pack supports notes up to 30 minutes" }
                var position = 0L
                val transcript = StringBuilder()
                while (position < reader.targetSamples) {
                    job.ensureActive()
                    val remaining = reader.targetSamples - position
                    val audio = reader.window(position, minOf(remaining, 480_000L).toInt())
                    val count = if (remaining > audio.size) transcriptionBoundary(audio) else audio.size
                    val window = if (count == audio.size) audio else audio.copyOf(count)
                    val next = mutex.withLock {
                        job.ensureActive()
                        if (window.all { it == 0f }) ""
                        else {
                            phonon?.second?.close()
                            phonon = null
                            whisper(modelDirectory).recognize(window) { job.ensureActive() }
                        }
                    }.trim()
                    job.ensureActive()
                    if (next.isNotEmpty()) {
                        if (transcript.isNotEmpty()) transcript.append(' ')
                        transcript.append(next)
                    }
                    position += count
                    yield()
                }
                transcript.toString()
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                mutex.withLock { whisper?.second?.close(); whisper = null }
            }
        }
    }

    suspend fun smoke(adapter: String, modelDirectory: File): Unit = withContext(worker) {
        val job = currentCoroutineContext()
        mutex.withLock {
            job.ensureActive()
            // Verification runs while playback is idle; retain no second model arena beside its fresh engine.
            phonon?.second?.close()
            phonon = null
            whisper?.second?.close()
            whisper = null
            when (adapter) {
                "android-phonon-english" -> PhononEngine(modelDirectory).use { engine ->
                    val samples = engine.synthesize("Hello. This voice works offline.", "default") { job.ensureActive() }
                    check(samples.size >= 2_400 && samples.all { it.isFinite() }) { "Voice validation failed" }
                }
                "android-whisper-base-en" -> WhisperEngine(modelDirectory).use { engine ->
                    val result = engine.recognize(FloatArray(32_000)) { job.ensureActive() }
                    check(result.length <= 16_384) { "Transcription validation failed" }
                }
                else -> error("Unsupported offline speech pack")
            }
        }
    }

    suspend fun close(adapter: String): Unit = withContext(worker) {
        mutex.withLock {
            when (adapter) {
                "android-phonon-english" -> { phonon?.second?.close(); phonon = null }
                "android-whisper-base-en" -> { whisper?.second?.close(); whisper = null }
            }
        }
    }

    private fun whisper(directory: File): WhisperEngine {
        val path = directory.canonicalPath
        if (whisper?.first != path) {
            whisper?.second?.close()
            whisper = null
            whisper = path to WhisperEngine(directory)
        }
        return checkNotNull(whisper).second
    }

    private fun phonon(directory: File): PhononEngine {
        val path = directory.canonicalPath
        if (phonon?.first != path) {
            phonon?.second?.close()
            phonon = null
            phonon = path to PhononEngine(directory)
        }
        return checkNotNull(phonon).second
    }

    private fun transcriptionBoundary(audio: FloatArray): Int {
        var start = 0
        var count = 0
        var bestStart = 0
        var bestCount = 0
        for (offset in 240_000 until 480_000 step 1_600) {
            var energy = 0.0
            for (index in offset until offset + 1_600) energy += audio[index] * audio[index]
            if (kotlin.math.sqrt(energy / 1_600) <= 0.02) {
                if (count == 0) start = offset
                count++
                if (count > bestCount) { bestStart = start; bestCount = count }
            } else count = 0
        }
        return if (bestCount == 0) audio.size else bestStart + bestCount / 2 * 1_600
    }
}
