package com.osuji.ereaderapp.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class OfflineSynthesizer(private val context: Context) {
    private val serial = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var engine: TextToSpeech? = null
    private var initialized = false
    var voiceLabel: String = "Installed English voice"
        private set

    private suspend fun initialize() {
        if (initialized) return
        val ready = CompletableDeferred<Int>()
        withContext(Dispatchers.Main.immediate) {
            engine = TextToSpeech(context) { ready.complete(it) }
        }
        try {
            val result = withTimeoutOrNull(15_000) { ready.await() }
                ?: error("The installed speech engine did not start. Please try listening again.")
            check(result == TextToSpeech.SUCCESS) { "System speech is unavailable. Install an offline English voice in Android's text-to-speech settings." }
        } catch (failure: Exception) {
            engine?.shutdown()
            engine = null
            throw failure
        }
        val speaker = checkNotNull(engine)
        val voice = speaker.voices.orEmpty().filter {
            it.locale.language == Locale.ENGLISH.language && !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features
        }.sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.name }).firstOrNull()
        if (voice == null || speaker.setVoice(voice) != TextToSpeech.SUCCESS) {
            speaker.shutdown()
            engine = null
            error(if (voice == null) "No installed offline English voice was found. Download one in Android's text-to-speech settings." else "The selected offline voice could not be loaded.")
        }
        speaker.setSpeechRate(1f)
        speaker.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) { pending.remove(utteranceId)?.complete(Unit) }
            override fun onError(utteranceId: String) { pending.remove(utteranceId)?.completeExceptionally(IllegalStateException("The installed voice could not prepare this passage.")) }
            override fun onStop(utteranceId: String, interrupted: Boolean) { pending.remove(utteranceId)?.completeExceptionally(IllegalStateException("Voice preparation was interrupted. Please try listening again.")) }
        })
        voiceLabel = "${voice.locale.getDisplayLanguage(Locale.ENGLISH)} · offline"
        initialized = true
    }

    suspend fun synthesize(text: String): File = serial.withLock {
        initialize()
        require(text.length <= TextToSpeech.getMaxSpeechInputLength()) { "This passage is too long for the installed speech engine." }
        val speaker = checkNotNull(engine)
        val key = MessageDigest.getInstance("SHA-256").digest("v1:${speaker.defaultEngine}:${speaker.voice.name}:rate1:$text".toByteArray()).joinToString("") { "%02x".format(it) }
        val directory = File(context.cacheDir, "narration").apply { mkdirs() }
        val destination = File(directory, "$key.wav")
        if (destination.length() > 44) {
            destination.setLastModified(System.currentTimeMillis())
            return@withLock destination
        }
        val temporary = File(directory, "$key.preparing.wav")
        val complete = CompletableDeferred<Unit>()
        pending[key] = complete
        try {
            withContext(Dispatchers.Main.immediate) {
                check(speaker.synthesizeToFile(text, Bundle(), temporary, key) == TextToSpeech.SUCCESS) { "The installed voice rejected this passage." }
            }
            check(withTimeoutOrNull(60_000) { complete.await(); true } == true) { "The installed voice did not finish preparing this passage. Please try again." }
            withContext(Dispatchers.IO) {
                require(temporary.length() > 44) { "The speech engine produced no playable audio." }
                check(temporary.renameTo(destination)) { "Prepared speech could not be saved." }
                trimCache(directory, destination)
            }
            destination
        } catch (failure: Exception) {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) { speaker.stop() }
            throw failure
        } finally {
            pending.remove(key)
            temporary.delete()
        }
    }

    fun close() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        initialized = false
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    private fun trimCache(directory: File, current: File) {
        val files = directory.listFiles().orEmpty().filter { it.name.endsWith(".wav") && !it.name.contains(".preparing.") }.sortedBy { it.lastModified() }
        var size = files.sumOf { it.length() }
        for (file in files) {
            if (size <= 256L * 1024 * 1024) break
            val bytes = file.length()
            if (file != current && file.delete()) size -= bytes
        }
    }
}
