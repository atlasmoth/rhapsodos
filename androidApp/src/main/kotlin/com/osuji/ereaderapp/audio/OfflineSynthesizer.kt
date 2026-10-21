package com.osuji.ereaderapp.audio

import android.content.Context
import com.osuji.ereaderapp.models.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OfflineSynthesizer(private val context: Context, private val models: ModelManager) {
    private val serial = Mutex()
    private val pinned = ConcurrentHashMap.newKeySet<String>()
    var voiceLabel: String = "Downloaded voice"
        private set

    suspend fun synthesize(text: String): File = serial.withLock {
        val pack = models.selectedVoicePack()
        val directory = checkNotNull(models.installed(pack.id)) { "The selected offline voice is unavailable." }
        val voiceId = models.state.value.voiceId
        voiceLabel = "${models.voiceName(pack.id, voiceId)} · offline"
        val key = MessageDigest.getInstance("SHA-256").digest("narration-v4:${pack.id}:${pack.version}:${pack.runtime}:${pack.adapter}:$voiceId:$text".toByteArray()).joinToString("") { "%02x".format(it) }
        val cache = File(context.cacheDir, "narration").apply { mkdirs() }
        val destination = File(cache, "$key.wav")
        if (usableCache(destination)) return@withLock destination
        val audio = models.runtime.synthesize(pack.adapter, directory, text, voiceId)
        require(audio.samples.isNotEmpty() && audio.sampleRate in 8000..48000) { "This offline voice produced no playable audio." }
        val temporary = File(cache, "$key.preparing.wav")
        try {
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                temporary.outputStream().use { output ->
                    val size = audio.samples.size * 2
                    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                    header.put("RIFF".toByteArray()).putInt(size + 36).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                    header.putInt(audio.sampleRate).putInt(audio.sampleRate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(size)
                    output.write(header.array())
                    val buffer = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
                    audio.samples.forEach { sample ->
                        buffer.putShort((sample.takeIf { it.isFinite() }?.coerceIn(-1f, 1f)?.times(32767) ?: 0f).toInt().toShort())
                        if (!buffer.hasRemaining()) { output.write(buffer.array()); buffer.clear() }
                    }
                    if (buffer.position() > 0) output.write(buffer.array(), 0, buffer.position())
                    output.fd.sync()
                }
                currentCoroutineContext().ensureActive()
                validateRecording(temporary)
                check(temporary.renameTo(destination)) { "Prepared speech could not be saved." }
                trimCache(cache, destination)
            }
        } finally { temporary.delete() }
        return@withLock destination
    }

    fun requireVoice() { models.selectedVoicePack() }

    fun pin(file: File) { pinned += file.absolutePath }
    fun releasePins() { pinned.clear() }
    fun replacePins(paths: List<String>) { pinned.clear(); pinned.addAll(paths) }

    private suspend fun usableCache(file: File): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists()) false
        else if (runCatching { validateRecording(file) }.isSuccess) { file.setLastModified(System.currentTimeMillis()); true }
        else { check(file.delete()) { "Damaged prepared audio could not be removed. Free storage and retry." }; false }
    }

    private fun trimCache(directory: File, current: File) {
        val files = directory.listFiles().orEmpty().filter { it.name.endsWith(".wav") && !it.name.contains(".preparing.") }.sortedBy { it.lastModified() }
        var size = files.sumOf { it.length() }
        for (file in files) {
            if (size <= 256L * 1024 * 1024) break
            val bytes = file.length()
            if (file != current && file.absolutePath !in pinned && file.delete()) size -= bytes
        }
    }
}
