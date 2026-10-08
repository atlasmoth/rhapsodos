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
    private val transcription: TranscriptionCoordinator,
) {
    private val mutableState = MutableStateFlow(NoteCaptureSnapshot())
    val state = mutableState.asStateFlow()
    private val saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val savedEvents = saved.asSharedFlow()
    private var starting = false
    private var interruptedDraft: String? = null
    private var reservationId: String? = null
    private var startableDraft: String? = null

    init { recorder.onInterrupted = { interrupted(recorder.state.value.error) } }

    fun begin(captureId: String) {
        if (starting || state.value.draft != null) return
        val reservation = playback.captureReservation(captureId) ?: return
        val frozen = reservation.attachment
        starting = true
        reservationId = captureId
        scope.launch {
            try {
                check(playback.persistCaptureReading(captureId)) { "Your listening position could not be saved. Please retry." }
                val noteId = UUID.randomUUID().toString()
                val draft = store.createDraft(noteId, frozen.documentId, frozen.passageId, recorder.draftPath(noteId), frozen.offsetMillis)
                mutableState.value = NoteCaptureSnapshot(draft)
                startableDraft = noteId
                playback.awaitQuietPreparation()
                if (state.value.draft?.id != noteId || state.value.saving || startableDraft != noteId) return@launch
                check(playback.isCaptureReservationValid(captureId, requireQuiet = true)) { "The recording attachment changed. Your draft is retained." }
                context.startForegroundService(Intent(context, CaptureService::class.java).putExtra("noteId", noteId))
            } catch (failure: Exception) {
                playback.endCapture(captureId)
                mutableState.value = state.value.copy(error = failure.message ?: "Recording could not start.")
            } finally { starting = false }
        }
    }

    fun startCapture(noteId: String) {
        if (state.value.draft?.id != noteId || state.value.saving || startableDraft != noteId) {
            context.stopService(Intent(context, CaptureService::class.java))
            return
        }
        try {
            check(playback.isCaptureReservationValid(reservationId, requireQuiet = true)) { "The recording attachment changed. Your draft is retained." }
            recorder.start(noteId)
        } catch (failure: Exception) {
            playback.endCapture(reservationId)
            mutableState.value = state.value.copy(error = failure.message ?: "The microphone is unavailable.")
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }

    fun save(resume: Boolean) {
        val draft = state.value.draft ?: return
        if (state.value.saving) return
        startableDraft = null
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
                transcription.enqueue(draft.id)
                if (resume) playback.resume()
            } catch (failure: Exception) {
                mutableState.value = state.value.copy(saving = false, error = failure.message ?: "This recording has not been saved. Please retry.")
            }
        }
    }

    fun cancel() {
        val draft = state.value.draft ?: return
        if (state.value.saving) return
        startableDraft = null
        mutableState.value = state.value.copy(saving = true)
        scope.launch {
            try {
                recorder.cancel()
                store.discardDraft(draft.id)
                playback.endCapture()
                mutableState.value = NoteCaptureSnapshot()
                context.stopService(Intent(context, CaptureService::class.java))
            } catch (failure: Exception) {
                mutableState.value = state.value.copy(saving = false, error = "The recording could not be discarded. Its draft is retained; retry saving.")
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
                        transcription.enqueue(note.id)
                    } else store.markStatus(note.id, "interrupted")
                }.onFailure {
                    mutableState.value = state.value.copy(error = "An interrupted thought is retained. Reopen the app to retry saving it.")
                }
            }
        }
    }

    fun interrupted(reason: String? = null) {
        val draft = state.value.draft ?: return
        if (state.value.saving || interruptedDraft == draft.id || (!recorder.state.value.recording && !recorder.hasAudio() && reason == null)) return
        interruptedDraft = draft.id
        scope.launch {
            runCatching { recorder.stop() }
            runCatching { store.markStatus(draft.id, "interrupted") }
            playback.endCapture()
            mutableState.value = state.value.copy(error = reason ?: "Recording was interrupted. Your draft is retained; save it when you are ready.")
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }
}
