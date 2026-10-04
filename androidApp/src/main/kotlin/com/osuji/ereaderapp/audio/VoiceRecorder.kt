package com.osuji.ereaderapp.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

data class CaptureState(val recording: Boolean = false, val elapsedMs: Long = 0, val level: Float = 0f, val error: String? = null)

class VoiceRecorder(private val context: Context, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(CaptureState())
    val state = mutableState.asStateFlow()
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var captureFile: File? = null
    @Volatile private var running = false

    fun draftPath(noteId: String): String = File(context.filesDir, "recordings/$noteId.recording.wav").absolutePath

    @SuppressLint("MissingPermission")
    fun start(noteId: String) {
        check(!running) { "A recording is already in progress." }
        captureFile = null
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "This microphone does not support the recording format." }
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, 8192))
            .build()
        val file = File(draftPath(noteId)).apply { parentFile?.mkdirs() }
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not be initialized." }
            RandomAccessFile(file, "rw").use { output ->
                output.setLength(0)
                output.write(header(0))
                output.fd.sync()
            }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "The microphone could not start." }
        } catch (failure: Exception) {
            runCatching { recorder.stop() }
            recorder.release()
            if (file.length() <= 44) file.delete()
            throw failure
        }
        captureFile = file
        audioRecord = recorder
        running = true
        mutableState.value = CaptureState(recording = true)
        captureJob = scope.launch(Dispatchers.IO) {
            try {
                RandomAccessFile(file, "rw").use { output ->
                    output.seek(44)
                    val buffer = ByteArray(8192)
                    var bytes = 0L
                    var lastSync = 0L
                    while (running) {
                        val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (count < 0 && running) error("The microphone was interrupted.")
                        if (count <= 0) continue
                        output.write(buffer, 0, count)
                        bytes += count
                        var amplitude = 0
                        for (index in 0 until count - 1 step 2) {
                            val sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 255)).toShort().toInt()
                            amplitude = maxOf(amplitude, abs(sample))
                        }
                        mutableState.value = CaptureState(true, bytes * 1000 / (SAMPLE_RATE * 2), amplitude / 32768f)
                        if (bytes - lastSync >= SAMPLE_RATE * 2) {
                            output.fd.sync()
                            lastSync = bytes
                        }
                    }
                    output.fd.sync()
                }
            } catch (failure: Exception) {
                running = false
                mutableState.value = mutableState.value.copy(recording = false, error = failure.message ?: "Recording was interrupted.")
            }
        }
    }

    suspend fun stop(): String = withContext(Dispatchers.IO) {
        running = false
        runCatching { audioRecord?.stop() }
        captureJob?.join()
        audioRecord?.release()
        audioRecord = null
        captureJob = null
        val file = checkNotNull(captureFile)
        val result = finalize(file)
        captureFile = result
        mutableState.value = CaptureState(elapsedMs = duration(result.absolutePath))
        result.absolutePath
    }

    suspend fun cancel() {
        val file = captureFile
        if (file == null) return
        if (file.length() <= 44 && !running) {
            withContext(Dispatchers.IO) { file.delete() }
            captureFile = null
            return
        }
        val result = stop()
        withContext(Dispatchers.IO) { check(File(result).delete()) { "The recording could not be discarded." } }
        captureFile = null
    }

    fun acknowledgeSaved() { captureFile = null; mutableState.value = CaptureState() }

    fun duration(path: String): Long = (File(path).length() - 44).coerceAtLeast(0) * 1000 / (SAMPLE_RATE * 2)

    suspend fun recover(path: String): String? = withContext(Dispatchers.IO) {
        val draft = File(path)
        val final = File(path.replace(".recording.wav", ".wav"))
        val file = if (draft.exists() && draft.length() > 44) draft else final
        if (!file.exists() || file.length() <= 44) null else finalize(file).absolutePath
    }

    private fun finalize(file: File): File {
        require(file.length() > 44) { "No audio was captured. Please record again." }
        val dataSize = ((file.length() - 44) / 2 * 2).toInt()
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(44L + dataSize)
            output.seek(0)
            output.write(header(dataSize))
            output.fd.sync()
        }
        val destination = File(file.parentFile, file.name.replace(".recording.wav", ".wav"))
        if (file != destination) check(file.renameTo(destination)) { "The recording could not be finalized." }
        return destination
    }

    private fun header(dataSize: Int): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(dataSize)
    }.array()

    companion object { const val SAMPLE_RATE = 16_000 }
}
