package com.osuji.ereaderapp.audio

import android.content.Context
import android.content.Intent
import com.osuji.ereaderapp.data.ReaderStore
import com.osuji.ereaderapp.data.VoiceNote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class NoteCaptureSnapshot(val draft: VoiceNote? = null, val saving: Boolean = false, val error: String? = null)

class VoiceNoteCoordinator(
    private val context: Context,
    private val store: ReaderStore,
    private val playback: PlaybackCoordinator,
    val recorder: VoiceRecorder,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(NoteCaptureSnapshot())
    val state = mutableState.asStateFlow()
    private val saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val savedEvents = saved.asSharedFlow()
    private var starting = false

    fun begin() {
        if (starting || state.value.draft != null) return
        val frozen = playback.beginCapture() ?: return
        starting = true
        scope.launch {
            try {
                val noteId = UUID.randomUUID().toString()
                val draft = store.createDraft(noteId, frozen.documentId, frozen.passageId, recorder.draftPath(noteId), frozen.offsetMillis)
                mutableState.value = NoteCaptureSnapshot(draft)
                context.startForegroundService(Intent(context, CaptureService::class.java).putExtra("noteId", noteId))
            } catch (failure: Exception) {
                playback.endCapture()
                mutableState.value = NoteCaptureSnapshot(error = failure.message ?: "Recording could not start.")
            } finally { starting = false }
        }
    }

    fun startCapture(noteId: String) {
        try {
            recorder.start(noteId)
        } catch (failure: Exception) {
            playback.endCapture()
            mutableState.value = state.value.copy(error = failure.message ?: "The microphone is unavailable.")
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }

    fun save(resume: Boolean) {
        val draft = state.value.draft ?: return
        if (state.value.saving) return
        mutableState.value = state.value.copy(saving = true)
        scope.launch {
            try {
                val path = recorder.stop()
                store.commit(draft.id, path, recorder.duration(path))
                runCatching { store.markStatus(draft.id, "pendingModel") }
                recorder.acknowledgeSaved()
                mutableState.value = NoteCaptureSnapshot()
                playback.endCapture()
                context.stopService(Intent(context, CaptureService::class.java))
                saved.tryEmit(Unit)
                if (resume) playback.resume()
            } catch (failure: Exception) {
                mutableState.value = state.value.copy(saving = false, error = failure.message ?: "This recording has not been saved. Please retry.")
            }
        }
    }

    fun cancel() {
        val draft = state.value.draft ?: return
        scope.launch {
            try {
                recorder.cancel()
                store.discardDraft(draft.id)
                playback.endCapture()
                mutableState.value = NoteCaptureSnapshot()
                context.stopService(Intent(context, CaptureService::class.java))
            } catch (failure: Exception) {
                mutableState.value = state.value.copy(error = "The recording could not be discarded. Its draft is retained; retry saving.")
            }
        }
    }

    fun recoverDrafts() {
        scope.launch {
            if (runCatching { store.refresh() }.isFailure) {
                mutableState.value = state.value.copy(error = "Saved thoughts could not be checked. Please reopen the app to retry.")
                return@launch
            }
            store.snapshot.value.notes.filter { it.status in setOf("draft", "interrupted") && it.durationMillis == 0L }.forEach { note ->
                runCatching {
                    val recovered = recorder.recover(note.recordingPath)
                    if (recovered != null) {
                        store.commit(note.id, recovered, recorder.duration(recovered))
                        store.markStatus(note.id, "pendingModel")
                    } else store.markStatus(note.id, "interrupted")
                }.onFailure {
                    mutableState.value = state.value.copy(error = "An interrupted thought is retained. Reopen the app to retry saving it.")
                }
            }
        }
    }

    fun interrupted() {
        val draft = state.value.draft ?: return
        if (state.value.saving || !recorder.state.value.recording) return
        scope.launch {
            runCatching { recorder.stop() }
            runCatching { store.markStatus(draft.id, "interrupted") }
            playback.endCapture()
            mutableState.value = state.value.copy(error = "Recording was interrupted. Your draft is retained; save it when you are ready.")
        }
    }
}
