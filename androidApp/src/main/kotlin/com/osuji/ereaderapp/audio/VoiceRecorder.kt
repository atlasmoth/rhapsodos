package com.osuji.ereaderapp.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.osuji.ereaderapp.data.syncDirectory
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
    var onInterrupted: (() -> Unit)? = null

    fun draftPath(noteId: String): String = File(context.filesDir, "recordings/$noteId.recording.wav").absolutePath

    @SuppressLint("MissingPermission")
    fun start(noteId: String) {
        check(!running) { "A recording is already in progress." }
        captureFile = null
        require(android.os.StatFs(context.filesDir.absolutePath).availableBytes >= CaptureLimits.STORAGE_RESERVE_BYTES + 8192) { "Free at least 10 MB of storage before recording a thought." }
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
                    var lastSpaceCheck = 0L
                    var freeBytes = android.os.StatFs(context.filesDir.absolutePath).availableBytes
                    while (running) {
                        val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (count < 0 && running) error("The microphone was interrupted.")
                        if (count <= 0) continue
                        if (bytes - lastSpaceCheck >= SAMPLE_RATE * 2) { freeBytes = android.os.StatFs(context.filesDir.absolutePath).availableBytes; lastSpaceCheck = bytes }
                        val writable = CaptureLimits.writableBytes(bytes, count, freeBytes)
                        output.write(buffer, 0, writable)
                        bytes += writable
                        var amplitude = 0
                        for (index in 0 until writable - 1 step 2) {
                            val sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 255)).toShort().toInt()
                            amplitude = maxOf(amplitude, abs(sample))
                        }
                        mutableState.value = CaptureState(true, bytes * 1000 / (SAMPLE_RATE * 2), amplitude / 32768f)
                        if (bytes - lastSync >= SAMPLE_RATE * 2) {
                            output.fd.sync()
                            lastSync = bytes
                        }
                        if (bytes >= CaptureLimits.MAXIMUM_BYTES) { output.fd.sync(); error("Recording stopped at 30 minutes. Your draft is retained; save or discard it.") }
                    }
                    output.fd.sync()
                }
            } catch (failure: Exception) {
                running = false
                runCatching { recorder.stop() }
                mutableState.value = mutableState.value.copy(recording = false, error = failure.message ?: "Recording was interrupted.")
                scope.launch(Dispatchers.Main.immediate) { onInterrupted?.invoke() }
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

    suspend fun cancel() = withContext(Dispatchers.IO) {
        running = false
        runCatching { audioRecord?.stop() }
        captureJob?.join()
        audioRecord?.release()
        audioRecord = null
        captureJob = null
        val file = captureFile
        check(file == null || !file.exists() || file.delete()) { "The recording could not be discarded." }
        captureFile = null
        mutableState.value = CaptureState()
    }

    fun acknowledgeSaved() { captureFile = null; mutableState.value = CaptureState() }
    fun hasAudio(): Boolean = (captureFile?.length() ?: 0) > 44

    fun duration(path: String): Long = (File(path).length() - 44).coerceAtLeast(0) * 1000 / (SAMPLE_RATE * 2)

    suspend fun recover(path: String): String? = withContext(Dispatchers.IO) {
        val draft = File(path)
        val final = File(path.replace(".recording.wav", ".wav"))
        val file = if (draft.exists() && draft.length() > 44) draft else final
        if (!file.exists() || file.length() <= 44) null else finalize(file).absolutePath
    }

    private fun finalize(file: File): File {
        require(file.length() > 44) { "No audio was captured. Please record again." }
        val dataBytes = (file.length() - 44) / 2 * 2
        require(dataBytes <= CaptureLimits.MAXIMUM_BYTES) { "This draft exceeds the recording limit. The original audio is retained." }
        val dataSize = dataBytes.toInt()
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(44L + dataSize)
            output.seek(0)
            output.write(header(dataSize))
            output.fd.sync()
        }
        val destination = File(file.parentFile, file.name.replace(".recording.wav", ".wav"))
        if (file != destination) check(file.renameTo(destination)) { "The recording could not be finalized." }
        syncDirectory(checkNotNull(destination.parentFile))
        return destination
    }

    private fun header(dataSize: Int): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(dataSize)
    }.array()

    companion object { const val SAMPLE_RATE = 16_000 }
}
