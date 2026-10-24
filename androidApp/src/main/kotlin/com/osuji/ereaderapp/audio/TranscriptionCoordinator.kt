package com.osuji.ereaderapp.audio

import android.content.Context
import com.osuji.ereaderapp.data.ReaderStore
import com.osuji.ereaderapp.models.ModelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class TranscriptionCoordinator(private val context: Context, private val store: ReaderStore, private val models: ModelManager, private val playback: PlaybackCoordinator, private val scope: CoroutineScope) {
    private val jobs = mutableMapOf<String, Job>()
    private val generations = mutableMapOf<String, Long>()
    private val queue = Mutex()
    private var memoryPaused = false
    private val mutableInferenceActive = MutableStateFlow(false)
    val inferenceActive = mutableInferenceActive.asStateFlow()

    fun initialize() {
        scope.launch { models.readyEvents.collect { if (it == models.state.value.selectedTranscription) processPending() } }
        scope.launch { store.refresh(); processPending() }
    }

    fun processPending() {
        if (memoryPaused || models.state.value.changingTranscription) return
        store.snapshot.value.notes.filter { it.durationMillis > 0 && it.status in setOf("pending", "pendingModel", "transcribing") }.forEach { enqueue(it.id) }
    }

    fun enqueue(noteId: String) {
        if (memoryPaused || models.state.value.changingTranscription) return
        if (jobs[noteId]?.isActive == true) return
        val generation = (generations[noteId] ?: 0) + 1
        generations[noteId] = generation
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val note = store.snapshot.value.notes.firstOrNull { it.id == noteId } ?: return@launch
                if (note.durationMillis <= 0) return@launch
                val directory = models.asrDirectory()
                val packId = models.state.value.selectedTranscription
                val adapter = models.asrAdapter()
                if (directory == null || adapter == null) { store.markStatus(noteId, "pendingModel"); return@launch }
                val root = context.filesDir.canonicalFile
                val audio = File(note.recordingPath).canonicalFile
                require(audio.path.startsWith(root.path + File.separator) && audio.isFile && audio.length() > 44) { "The saved recording is unavailable." }
                queue.withLock {
                    while (currentCoroutineContext().isActive) {
                        models.state.first { it.checking.isEmpty() && it.installs.none { install -> install.status == "verifying" } }
                        playback.state.first { !it.playing && !it.capturing && !it.preparing }
                        if (models.state.value.selectedTranscription != packId || models.asrDirectory() != directory) { store.markStatus(noteId, "pendingModel"); break }
                        store.markStatus(noteId, "transcribing")
                        try {
                            val transcript = coroutineScope {
                                val inference = async {
                                    mutableInferenceActive.value = true
                                    try { models.runtime.transcribe(directory, audio, adapter) }
                                    finally { mutableInferenceActive.value = false }
                                }
                                val interruption = launch {
                                    playback.state.first { it.playing || it.capturing || it.preparing }
                                    inference.cancel()
                                }
                                try { inference.await() } finally { interruption.cancel() }
                            }
                            require(transcript.isNotBlank()) { "No speech was recognized." }
                            if (generations[noteId] == generation && models.state.value.selectedTranscription == packId && store.snapshot.value.notes.any { it.id == noteId }) store.updateTranscript(noteId, transcript, "transcribed")
                            break
                        } catch (interrupted: CancellationException) {
                            if (!currentCoroutineContext().isActive) throw interrupted
                            store.markStatus(noteId, "pending")
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { if (generations[noteId] == generation && store.snapshot.value.notes.any { it.id == noteId }) runCatching { store.markStatus(noteId, "pending") } }
                throw cancelled
            }
            catch (failure: Exception) { runCatching { store.markStatus(noteId, "failed") } }
            finally { if (generations[noteId] == generation) jobs.remove(noteId) }
        }
        jobs[noteId] = task
        task.start()
    }

    fun retry(noteId: String) = scope.launch { memoryPaused = false; cancel(noteId); enqueue(noteId) }
    suspend fun cancel(noteId: String) {
        generations[noteId] = (generations[noteId] ?: 0) + 1
        jobs.remove(noteId)?.cancelAndJoin()
        withContext(NonCancellable) { if (store.snapshot.value.notes.any { it.id == noteId && it.durationMillis > 0 && it.status in setOf("pending", "pendingModel", "transcribing") }) runCatching { store.markStatus(noteId, "pending") } }
    }

    suspend fun pauseForModelRemoval() {
        jobs.keys.toList().forEach { cancel(it) }
        store.snapshot.value.notes.filter { it.durationMillis > 0 && it.status in setOf("pending", "pendingModel", "transcribing") }.forEach { store.markStatus(it.id, "pendingModel") }
    }

    suspend fun pauseForMemoryPressure() { memoryPaused = true; jobs.keys.toList().forEach { cancel(it) } }
    fun resumeAfterMemoryPressure() { if (memoryPaused) { memoryPaused = false; processPending() } }
}
