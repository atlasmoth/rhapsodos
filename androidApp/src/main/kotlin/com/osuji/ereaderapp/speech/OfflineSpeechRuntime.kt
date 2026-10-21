package com.osuji.ereaderapp.speech

import android.content.Context
import java.io.File
import java.util.Locale
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
    private var kitten: Pair<String, KittenEngine>? = null
    private var phonon: Pair<String, PhononEngine>? = null
    private var moonshine: Pair<String, MoonshineEngine>? = null

    suspend fun synthesize(adapter: String, modelDirectory: File, text: String, voiceId: String): SpeechAudio =
        withContext(worker) {
            val job = currentCoroutineContext()
            mutex.withLock {
                job.ensureActive()
                moonshine?.second?.close()
                moonshine = null
                val samples = when (adapter) {
                    "android-kitten-english" -> {
                        phonon?.second?.close()
                        phonon = null
                        kitten(modelDirectory).synthesize(text, voiceId) { job.ensureActive() }
                    }
                    "android-phonon-english" -> {
                        kitten?.second?.close()
                        kitten = null
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

    suspend fun transcribe(modelDirectory: File, wav: File, adapter: String = "android-moonshine-english"): String = withContext(worker) {
        require(adapter == "android-moonshine-english") { "Unsupported transcription adapter" }
        val job = currentCoroutineContext()
        try {
            WavPcmReader(wav).use { reader ->
                require(reader.targetSamples <= 16_000L * 1_800) { "This transcription pack supports notes up to 30 minutes" }
                var position = 0L
                var transcript = ""
                var previousWindowWords = 0
                var overlapPending = false
                while (position < reader.targetSamples) {
                    job.ensureActive()
                    val audio = reader.window(position, 80_000)
                    val finalWindow = position + 80_000 >= reader.targetSamples
                    val pause = if (finalWindow) null else silenceBoundary(audio)
                    if (pause != null) audio.fill(0f, pause)
                    val next = mutex.withLock {
                        job.ensureActive()
                        if (audio.none { kotlin.math.abs(it) > 0.001f }) ""
                        else {
                            kitten?.second?.close()
                            kitten = null
                            phonon?.second?.close()
                            phonon = null
                            moonshine(modelDirectory).recognize(audio) { job.ensureActive() }
                        }
                    }
                    // A natural pause needs no overlap. Otherwise only one fifth of consecutive windows overlaps.
                    val overlapLimit = if (overlapPending) minOf(8, (previousWindowWords + 4) / 5 + 1) else 0
                    transcript = joinOverlapping(transcript, next, overlapLimit)
                    previousWindowWords = if (next.isBlank()) 0 else next.trim().split(Regex("\\s+")).size
                    if (finalWindow) break
                    overlapPending = pause == null
                    position += pause ?: 64_000
                    yield()
                }
                transcript
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                mutex.withLock { moonshine?.second?.close(); moonshine = null }
            }
        }
    }

    suspend fun smoke(adapter: String, modelDirectory: File): Unit = withContext(worker) {
        val job = currentCoroutineContext()
        mutex.withLock {
            job.ensureActive()
            // Verification runs while playback is idle; retain no second model arena beside its fresh engine.
            kitten?.second?.close()
            kitten = null
            phonon?.second?.close()
            phonon = null
            moonshine?.second?.close()
            moonshine = null
            when (adapter) {
                "android-kitten-english" -> KittenEngine(modelDirectory).use { engine ->
                    val samples = engine.synthesize("Hello. This voice works offline.", "bella") { job.ensureActive() }
                    check(samples.size >= 2_400 && samples.all { it.isFinite() }) { "Voice validation failed" }
                }
                "android-phonon-english" -> PhononEngine(modelDirectory).use { engine ->
                    val samples = engine.synthesize("Hello. This voice works offline.", "default") { job.ensureActive() }
                    check(samples.size >= 2_400 && samples.all { it.isFinite() }) { "Voice validation failed" }
                }
                "android-moonshine-english" -> MoonshineEngine(modelDirectory).use { engine ->
                    WavPcmReader(File(modelDirectory, "golden.wav")).use { reader ->
                        check(reader.targetSamples in 16_000..80_000) { "Invalid transcription validation clip" }
                        val audio = reader.window(0, 80_000)
                        check(audio.any { kotlin.math.abs(it) > 0.001f }) { "The validation clip contains no speech" }
                        val result = engine.recognize(audio) { job.ensureActive() }
                        check(result.isNotBlank() && result.length <= 1_024) { "Transcription validation failed" }
                    }
                }
                else -> error("Unsupported offline speech pack")
            }
        }
    }

    suspend fun close(adapter: String): Unit = withContext(worker) {
        mutex.withLock {
            when (adapter) {
                "android-kitten-english" -> { kitten?.second?.close(); kitten = null }
                "android-phonon-english" -> { phonon?.second?.close(); phonon = null }
                "android-moonshine-english" -> { moonshine?.second?.close(); moonshine = null }
            }
        }
    }

    private fun kitten(directory: File): KittenEngine {
        val path = directory.canonicalPath
        if (kitten?.first != path) {
            kitten?.second?.close()
            kitten = null
            kitten = path to KittenEngine(directory)
        }
        return checkNotNull(kitten).second
    }

    private fun moonshine(directory: File): MoonshineEngine {
        val path = directory.canonicalPath
        if (moonshine?.first != path) {
            moonshine?.second?.close()
            moonshine = null
            moonshine = path to MoonshineEngine(directory)
        }
        return checkNotNull(moonshine).second
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

    private fun silenceBoundary(audio: FloatArray): Int? {
        var quiet = 0
        var boundary: Int? = null
        for (start in 0 until 76_800 step 320) {
            var energy = 0.0
            var peak = 0f
            for (index in start until start + 320) {
                energy += audio[index] * audio[index]
                peak = maxOf(peak, kotlin.math.abs(audio[index]))
            }
            if (energy / 320 < 0.000004 && peak < 0.015f) quiet += 320 else quiet = 0
            if (start >= 32_000 && quiet >= 1_280) boundary = start + 320
        }
        return boundary
    }

    private fun joinOverlapping(previous: String, next: String, overlapLimit: Int): String {
        if (previous.isBlank()) return next.trim()
        if (next.isBlank()) return previous
        val left = previous.split(Regex("\\s+"))
        val right = next.trim().split(Regex("\\s+"))
        fun key(word: String) = word.lowercase(Locale.ROOT).trim('.', ',', '!', '?', ';', ':')
        var overlap = 0
        for (count in 1..minOf(overlapLimit, left.size, right.size)) {
            if ((0 until count).all { key(left[left.size - count + it]) == key(right[it]) }) overlap = count
        }
        return (left + right.drop(overlap)).joinToString(" ")
    }
}
