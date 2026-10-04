package com.osuji.ereaderapp.audio

import android.content.Context
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.osuji.ereaderapp.data.ReaderContents
import com.osuji.ereaderapp.data.ReaderPassage
import com.osuji.ereaderapp.data.ReaderStore
import com.osuji.ereaderapp.data.VoiceNote
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import com.osuji.ereaderapp.models.ModelManager
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

data class PlaybackSnapshot(
    val documentId: String? = null,
    val title: String = "Nothing playing yet",
    val passageId: String? = null,
    val pageIndex: Int = 0,
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val playing: Boolean = false,
    val preparing: Boolean = false,
    val speed: Float = 1f,
    val voice: String = "Installed offline voice",
    val error: String? = null,
    val noteId: String? = null,
    val sleepRemainingMillis: Long = 0,
    val capturing: Boolean = false,
    val preview: Boolean = false,
)

data class FrozenPlayback(val documentId: String, val passageId: String, val offsetMillis: Long)

class PlaybackCoordinator(
    private val context: Context,
    private val store: ReaderStore,
    private val synthesizer: OfflineSynthesizer,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(PlaybackSnapshot())
    val state = mutableState.asStateFlow()
    private var player: ExoPlayer? = null
    private var playerReady = CompletableDeferred<ExoPlayer>()
    private var contents: ReaderContents? = null
    private var generation: Job? = null
    private var monitoring: Job? = null
    private var epoch = 0L
    private var resumeOffset = 0L
    private var playingNote: VoiceNote? = null
    private var captureAnchor: FrozenPlayback? = null
    private var sleepJob: Job? = null
    private var sleepDeadline = 0L
    private data class PreviewReturn(val document: ReaderContents, val snapshot: PlaybackSnapshot, val note: VoiceNote?)
    private var previewReturn: PreviewReturn? = null
    private var desiredPlaying = false
    private var restartAfterCapture = false
    var capturing: Boolean = false
        private set

    fun attach(transport: ExoPlayer) {
        player = transport
        if (!playerReady.isCompleted) playerReady.complete(transport)
        transport.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (capturing && player.playWhenReady) player.pause()
                publish()
                if (state.value.preview && player.currentMediaItem?.mediaId == "voice-preview" && player.playbackState == Player.STATE_ENDED) returnFromPreview()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                mutableState.value = mutableState.value.copy(playing = false, preparing = false, error = "Audio playback was interrupted. You can prepare this passage again.")
            }
        })
        monitoring?.cancel()
        monitoring = scope.launch {
            var lastSaved = 0L
            while (player === transport) {
                publish()
                if (transport.currentPosition - lastSaved >= 2000 || transport.currentPosition < lastSaved) {
                    persist()
                    lastSaved = transport.currentPosition
                }
                delay(250)
            }
        }
    }

    fun detach() {
        scope.launch { persist() }
        generation?.cancel()
        monitoring?.cancel()
        player = null
        playerReady = CompletableDeferred()
        mutableState.value = mutableState.value.copy(playing = false, preparing = false)
    }

    fun listen(document: ReaderContents, passageIndex: Int = 0, offsetMillis: Long = 0, startPlaying: Boolean = true) {
        if (capturing) return
        if (document.passages.isEmpty()) return
        val start = passageIndex.coerceIn(document.passages.indices)
        desiredPlaying = startPlaying
        playingNote = null
        previewReturn = null
        val currentEpoch = ++epoch
        generation?.cancel()
        contents = document
        resumeOffset = offsetMillis
        mutableState.value = PlaybackSnapshot(document.document.id, document.document.title, document.passages[start].id, document.passages[start].pageIndex, positionMillis = offsetMillis, preparing = true, speed = mutableState.value.speed)
        context.startService(Intent(context, ListeningService::class.java))
        generation = scope.launch {
            try {
                val transport = withTimeoutOrNull(15_000) { playerReady.await() }
                    ?: error("The audio service did not start. Please try listening again.")
                transport.stop()
                transport.clearMediaItems()
                synthesizer.releasePins()
                transport.playbackParameters = PlaybackParameters(mutableState.value.speed)
                var nextIndex = start
                var queuedFirst = start
                while (currentEpoch == epoch) {
                    while (capturing) delay(100)
                    val latest = contents ?: break
                    if (nextIndex >= latest.passages.size) {
                        if (latest.document.extractionStatus == "preparing") { delay(250); continue }
                        break
                    }
                    val passage = latest.passages[nextIndex]
                    while (nextIndex > queuedFirst + transport.currentMediaItemIndex.coerceAtLeast(0) + 3 || capturing) delay(250)
                    val removeCount = (transport.currentMediaItemIndex - 1).coerceAtLeast(0)
                    if (removeCount > 0) {
                        transport.removeMediaItems(0, removeCount)
                        queuedFirst += removeCount
                        synthesizer.replacePins((0 until transport.mediaItemCount).mapNotNull { transport.getMediaItemAt(it).localConfiguration?.uri?.path })
                    }
                    val audio = synthesizer.synthesize(passage.spokenText)
                    if (currentEpoch != epoch) return@launch
                    synthesizer.pin(audio)
                    transport.addMediaItem(item(latest, passage, audio))
                    if (nextIndex == start) {
                        transport.seekTo(0, resumeOffset)
                        transport.prepare()
                        if (!capturing && desiredPlaying) transport.play()
                    }
                    publish()
                    nextIndex++
                }
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (currentEpoch == epoch) mutableState.value = mutableState.value.copy(preparing = false, playing = false, error = failure.message ?: "This passage could not be prepared.")
            }
        }
    }

    fun updateContents(document: ReaderContents) {
        if (contents?.document?.id == document.document.id) {
            contents = document
            mutableState.value = state.value.copy(title = document.document.title)
        }
    }

    fun stop(documentId: String? = null) {
        if (documentId != null && state.value.documentId != documentId) return
        if (capturing) return
        epoch++
        generation?.cancel()
        player?.stop()
        player?.clearMediaItems()
        synthesizer.releasePins()
        contents = null
        playingNote = null
        previewReturn = null
        cancelSleepTimer()
        mutableState.value = PlaybackSnapshot(speed = state.value.speed)
    }

    fun setSleepTimer(minutes: Int) {
        cancelSleepTimer()
        if (minutes <= 0) return
        sleepDeadline = android.os.SystemClock.elapsedRealtime() + minutes * 60_000L
        sleepJob = scope.launch {
            while (true) {
                val remaining = (sleepDeadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0)
                mutableState.value = state.value.copy(sleepRemainingMillis = remaining)
                if (remaining == 0L) { desiredPlaying = false; player?.pause(); persist(); break }
                delay(1000)
            }
        }
    }

    fun cancelSleepTimer() { sleepJob?.cancel(); sleepJob = null; sleepDeadline = 0; mutableState.value = state.value.copy(sleepRemainingMillis = 0) }

    private fun item(document: ReaderContents, passage: ReaderPassage, file: File): MediaItem = MediaItem.Builder()
        .setMediaId(passage.id)
        .setUri(android.net.Uri.fromFile(file))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(document.document.title).setArtist("Page ${passage.pageIndex + 1} · ${synthesizer.voiceLabel}").build())
        .build()

    fun toggle() {
        if (capturing) return
        val transport = player ?: return
        if (transport.isPlaying || transport.playWhenReady) { desiredPlaying = false; transport.pause(); scope.launch { persist() } }
        else {
            if (transport.playbackState == Player.STATE_ENDED) {
                val document = contents
                if (playingNote == null && document != null) { listen(document); return }
                transport.seekTo(0)
            }
            desiredPlaying = true
            transport.play()
        }
        publish()
    }

    fun pause(): FrozenPlayback? {
        desiredPlaying = false
        player?.pause()
        publish()
        val snapshot = state.value
        val documentId = snapshot.documentId ?: return null
        val passageId = snapshot.passageId ?: return null
        scope.launch { persist() }
        return FrozenPlayback(documentId, passageId, playingNote?.offsetMillis ?: snapshot.positionMillis)
    }

    fun resume() { if (!capturing) { desiredPlaying = true; player?.play() } }

    fun beginCapture(expectedDocumentId: String? = null, expectedPassageId: String? = null, expectedSourceRevision: String? = null): FrozenPlayback? {
        if (expectedDocumentId != null || expectedPassageId != null || expectedSourceRevision != null) {
            if (capturing || playingNote != null || state.value.preview) return null
            publish()
            val current = state.value
            if (current.documentId != expectedDocumentId || current.passageId != expectedPassageId ||
                contents?.document?.sourceRevision != expectedSourceRevision || player?.currentMediaItem?.mediaId != expectedPassageId ||
                current.preparing || current.durationMillis <= 0) return null
        }
        captureAnchor?.let { return it }
        if (playingNote != null || state.value.preview) return null
        val frozen = pause() ?: return null
        if (expectedDocumentId != null && (frozen.documentId != expectedDocumentId || frozen.passageId != expectedPassageId)) return null
        captureAnchor = frozen
        capturing = true
        restartAfterCapture = generation?.isActive == true
        generation?.cancel()
        mutableState.value = state.value.copy(capturing = true)
        return frozen
    }

    fun endCapture() {
        capturing = false
        captureAnchor = null
        mutableState.value = state.value.copy(capturing = false)
        if (restartAfterCapture) {
            restartAfterCapture = false
            val document = contents ?: return
            val current = state.value
            val index = document.passages.indexOfFirst { it.id == current.passageId }.coerceAtLeast(0)
            listen(document, index, current.positionMillis, false)
        }
    }

    suspend fun awaitQuietPreparation() { generation?.join() }

    fun beginTextCapture(documentId: String, passageId: String): FrozenPlayback? {
        captureAnchor?.let { return it }
        if (capturing) return null
        pause()
        return FrozenPlayback(documentId, passageId, if (state.value.documentId == documentId && state.value.passageId == passageId) state.value.positionMillis else 0).also {
            captureAnchor = it
            capturing = true
            restartAfterCapture = generation?.isActive == true
            generation?.cancel()
            mutableState.value = state.value.copy(capturing = true)
        }
    }

    fun previewVoice(packId: String) {
        if (capturing) return
        publish()
        val current = state.value
        val document = contents
        if (!current.preview && document != null) previewReturn = PreviewReturn(document, current, playingNote)
        player?.pause()
        val currentEpoch = ++epoch
        generation?.cancel()
        playingNote = null
        mutableState.value = current.copy(title = "Voice preview", preview = true, noteId = null, preparing = true, playing = false)
        context.startService(Intent(context, ListeningService::class.java))
        generation = scope.launch {
            try {
                val audio = synthesizer.synthesize("Welcome to Rhapsodos. Take your time, find your pace, and let the words come to you.", packId)
                val transport = withTimeoutOrNull(15_000) { playerReady.await() } ?: error("The audio service did not start.")
                if (currentEpoch != epoch) return@launch
                transport.setMediaItem(MediaItem.Builder().setMediaId("voice-preview").setUri(android.net.Uri.fromFile(audio)).setMediaMetadata(MediaMetadata.Builder().setTitle("Rhapsodos voice preview").build()).build())
                transport.playbackParameters = PlaybackParameters(1f)
                transport.prepare()
                transport.play()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { if (currentEpoch == epoch) mutableState.value = state.value.copy(preparing = false, error = failure.message ?: "The voice preview could not be prepared.") }
        }
    }

    fun returnFromPreview() {
        val saved = previewReturn
        previewReturn = null
        if (saved == null) { stop(); return }
        restoreAfterPreview(saved.document, saved.snapshot, saved.note)
    }

    suspend fun selectVoice(models: ModelManager, packId: String, voiceId: String = models.state.value.voiceId) {
        check(!capturing) { "Finish your recording before changing voices." }
        publish()
        val saved = if (state.value.preview) previewReturn else null
        val current = saved?.snapshot ?: state.value
        val document = saved?.document ?: contents
        val note = saved?.note ?: playingNote
        player?.pause()
        epoch++
        generation?.cancelAndJoin()
        models.selectVoice(packId, voiceId)
        if (document != null) restoreAfterPreview(document, current, note)
        else stop()
    }

    private fun restoreAfterPreview(document: ReaderContents, snapshot: PlaybackSnapshot, note: VoiceNote?) {
        if (note != null) playSavedNote(note, snapshot.positionMillis, snapshot.playing, document)
        else {
            val index = document.passages.indexOfFirst { it.id == snapshot.passageId }.coerceAtLeast(0)
            listen(document, index, snapshot.positionMillis, snapshot.playing)
        }
    }

    suspend fun stopAndWait() { generation?.cancelAndJoin(); stop() }

    suspend fun reloadSpokenEdition(document: ReaderContents) {
        if (state.value.documentId != document.document.id) return
        val passageId = state.value.passageId
        player?.pause()
        generation?.cancelAndJoin()
        val index = document.passages.indexOfFirst { it.id == passageId }.coerceAtLeast(0)
        listen(document, index, 0, false)
    }

    fun skip(direction: Int) {
        val document = contents ?: return
        val index = document.passages.indexOfFirst { it.id == state.value.passageId }
        listen(document, (index + direction).coerceIn(document.passages.indices))
    }

    fun playNote(note: VoiceNote) {
        if (capturing) return
        if (!state.value.preview && state.value.noteId == note.id) { toggle(); return }
        playSavedNote(note, 0, true)
    }

    private fun playSavedNote(note: VoiceNote, offsetMillis: Long, startPlaying: Boolean, savedDocument: ReaderContents? = null) {
        if (capturing) return
        player?.pause()
        val currentEpoch = ++epoch
        generation?.cancel()
        playingNote = note
        previewReturn = null
        desiredPlaying = startPlaying
        mutableState.value = PlaybackSnapshot(note.documentId, savedDocument?.document?.title ?: state.value.title, note.passageId, note.pageIndex, positionMillis = offsetMillis, preparing = true, voice = "Your recorded thought", noteId = note.id)
        context.startService(Intent(context, ListeningService::class.java))
        generation = scope.launch {
            try {
                val document = savedDocument ?: store.contents(note.documentId)
                val transport = withTimeoutOrNull(15_000) { playerReady.await() }
                    ?: error("The audio service did not start. Please try playing the thought again.")
                if (currentEpoch != epoch) return@launch
                contents = document
                mutableState.value = PlaybackSnapshot(note.documentId, document.document.title, note.passageId, note.pageIndex, positionMillis = offsetMillis, voice = "Your recorded thought", noteId = note.id)
                transport.setMediaItem(MediaItem.Builder().setMediaId("note:${note.id}").setUri(android.net.Uri.fromFile(File(note.recordingPath))).setMediaMetadata(MediaMetadata.Builder().setTitle("Your thought · page ${note.pageIndex + 1}").setArtist(contents!!.document.title).build()).build())
                transport.playbackParameters = PlaybackParameters(1f)
                transport.prepare()
                transport.seekTo(offsetMillis.coerceAtLeast(0))
                if (startPlaying && !capturing) transport.play()
                publish()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (currentEpoch == epoch) mutableState.value = state.value.copy(preparing = false, playing = false, error = "This saved recording could not be played.")
            }
        }
    }

    fun returnToReading() {
        val note = playingNote ?: return
        val document = contents ?: return
        val index = document.passages.indexOfFirst { it.id == note.passageId }.coerceAtLeast(0)
        listen(document, index, note.offsetMillis)
    }

    fun seek(positionMillis: Long) { if (!capturing) player?.seekTo(positionMillis.coerceAtLeast(0)); publish() }

    fun setSpeed(speed: Float) {
        val value = speed.coerceIn(0.75f, 2f)
        player?.playbackParameters = PlaybackParameters(value)
        mutableState.value = mutableState.value.copy(speed = value)
    }

    suspend fun persist() {
        val snapshot = state.value
        if (snapshot.noteId != null || snapshot.preview) return
        val documentId = snapshot.documentId ?: return
        val passageId = snapshot.passageId ?: return
        runCatching { store.saveProgress(documentId, passageId, snapshot.positionMillis) }.onFailure {
            mutableState.value = state.value.copy(error = "Your listening position could not be saved.")
        }
    }

    private fun publish() {
        val transport = player ?: return
        val passage = contents?.passages?.firstOrNull { it.id == transport.currentMediaItem?.mediaId }
        val current = state.value
        val currentMedia = when {
            current.preview -> transport.currentMediaItem?.mediaId == "voice-preview"
            current.noteId != null -> transport.currentMediaItem?.mediaId == "note:${current.noteId}"
            else -> passage != null
        }
        mutableState.value = mutableState.value.copy(
            passageId = if (currentMedia && !current.preview && current.noteId == null) passage?.id ?: current.passageId else current.passageId,
            pageIndex = if (currentMedia && !current.preview && current.noteId == null) passage?.pageIndex ?: current.pageIndex else current.pageIndex,
            positionMillis = if (currentMedia) transport.currentPosition.coerceAtLeast(0) else current.positionMillis,
            durationMillis = if (currentMedia) transport.duration.takeIf { it > 0 } ?: 0 else current.durationMillis,
            playing = currentMedia && transport.isPlaying,
            preparing = if (currentMedia) transport.playbackState == Player.STATE_BUFFERING else generation?.isActive == true,
            voice = if (playingNote != null) "Your recorded thought" else synthesizer.voiceLabel,
        )
    }
}
