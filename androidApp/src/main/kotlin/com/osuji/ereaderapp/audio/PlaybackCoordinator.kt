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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import com.osuji.ereaderapp.models.ModelManager
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val passageFraction: Double = 0.0,
    val sourceRevision: String? = null,
    val sourceProgress: Double? = null,
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
    private var transportEpoch = 0L
    private var sourceSeekRequest = 0L
    private var resumeOffset = 0L
    private var pendingCursor: PlaybackSnapshot? = null
    private val progressWrites = Mutex()
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
                if (player.playbackState == Player.STATE_ENDED && transportEpoch == epoch && !state.value.preview && state.value.noteId == null &&
                    state.value.passageId == player.currentMediaItem?.mediaId && state.value.passageFraction == 1.0) {
                    val completed = state.value
                    scope.launch { persistSnapshot(completed) }
                }
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
        publish()
        val snapshot = state.value
        scope.launch { persistSnapshot(snapshot) }
        generation?.cancel()
        monitoring?.cancel()
        player = null
        playerReady = CompletableDeferred()
        mutableState.value = mutableState.value.copy(playing = false, preparing = false)
    }

    fun openSaved(document: ReaderContents) {
        if (capturing) return
        if (state.value.documentId == document.document.id && !state.value.preview && state.value.noteId == null) {
            updateContents(document)
            return
        }
        if (document.passages.isEmpty()) {
            publish()
            val previous = state.value
            player?.pause()
            val currentEpoch = ++epoch
            generation?.cancel()
            mutableState.value = state.value.copy(playing = false, preparing = true)
            generation = scope.launch {
                if (persistSnapshot(previous) && currentEpoch == epoch) stop()
            }
            return
        }
        val saved = store.snapshot.value.positions.firstOrNull { it.documentId == document.document.id }
        val index = document.passages.indexOfFirst { it.id == saved?.passageId }.coerceAtLeast(0)
        listen(document, index, saved?.offsetMillis ?: 0, false, saved?.passageFraction ?: 0.0)
    }

    fun listen(document: ReaderContents, passageIndex: Int = 0, offsetMillis: Long = 0, startPlaying: Boolean = true, passageFraction: Double = 0.0, saveTarget: Boolean = false) {
        if (capturing) return
        if (document.passages.isEmpty()) return
        publish()
        val previous = state.value
        player?.pause()
        val start = passageIndex.coerceIn(document.passages.indices)
        desiredPlaying = startPlaying
        playingNote = null
        previewReturn = null
        val currentEpoch = ++epoch
        generation?.cancel()
        contents = document
        resumeOffset = offsetMillis
        mutableState.value = PlaybackSnapshot(document.document.id, document.document.title, document.passages[start].id, document.passages[start].pageIndex, positionMillis = offsetMillis, preparing = true, speed = mutableState.value.speed, passageFraction = passageFraction.coerceIn(0.0, 1.0), sourceRevision = document.document.sourceRevision, sourceProgress = sourceProgress(document, document.passages[start].id, passageFraction))
        pendingCursor = state.value
        val target = state.value
        context.startService(Intent(context, ListeningService::class.java))
        generation = scope.launch {
            try {
                check(persistSnapshot(previous)) { "Your listening position could not be saved. Please retry." }
                if (currentEpoch != epoch) return@launch
                if (saveTarget) {
                    check(persistSnapshot(target)) { "Your listening position could not be saved. Please retry." }
                    if (currentEpoch != epoch) return@launch
                }
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
                    if (nextIndex == start) transportEpoch = currentEpoch
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
            mutableState.value = state.value.copy(title = document.document.title, sourceRevision = document.document.sourceRevision,
                sourceProgress = if (state.value.preview || state.value.noteId != null) state.value.sourceProgress else sourceProgress(document, state.value.passageId, state.value.passageFraction))
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
        pendingCursor = null
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
        publish()
        if (!transport.isPlaying && completedReading(state.value)) {
            contents?.let { listen(it) }
            return
        }
        if (!transport.isPlaying && state.value.noteId == null && !state.value.preview && state.value.passageFraction == 1.0) {
            val document = contents
            val index = document?.passages?.indexOfFirst { it.id == state.value.passageId } ?: -1
            if (document != null && index in 0 until document.passages.lastIndex) {
                listen(document, index + 1)
                return
            }
        }
        if (transportEpoch != epoch) {
            desiredPlaying = true
            if (generation?.isActive != true) contents?.let { document ->
                val current = state.value
                val index = document.passages.indexOfFirst { it.id == current.passageId }.coerceAtLeast(0)
                listen(document, index, current.positionMillis, true, current.passageFraction)
            }
            return
        }
        if (transport.isPlaying || transport.playWhenReady) { desiredPlaying = false; transport.pause(); scope.launch { persist() } }
        else {
            if (transport.playbackState == Player.STATE_ENDED) {
                val document = contents
                if (playingNote == null && document != null) {
                    val index = document.passages.indexOfFirst { it.id == state.value.passageId }.coerceAtLeast(0)
                    if (document.document.extractionStatus == "preparing" && index == document.passages.lastIndex) {
                        desiredPlaying = true
                        transport.play()
                        publish()
                        return
                    }
                    listen(document, if (state.value.passageFraction == 1.0 && index < document.passages.lastIndex) index + 1 else index)
                    return
                }
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
        scope.launch { persistSnapshot(snapshot) }
        return FrozenPlayback(documentId, passageId, playingNote?.offsetMillis ?: snapshot.positionMillis)
    }

    fun resume() { if (!capturing) { desiredPlaying = true; if (transportEpoch == epoch) player?.play() } }

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
        sourceSeekRequest++
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
            listen(document, index, current.positionMillis, false, current.passageFraction)
        }
    }

    suspend fun awaitQuietPreparation() { generation?.join() }

    fun beginTextCapture(documentId: String, passageId: String): FrozenPlayback? {
        captureAnchor?.let { return it }
        if (capturing) return null
        pause()
        return FrozenPlayback(documentId, passageId, if (state.value.documentId == documentId && state.value.passageId == passageId) state.value.positionMillis else 0).also {
            captureAnchor = it
            sourceSeekRequest++
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
        pendingCursor = null
        mutableState.value = current.copy(title = "Voice preview", preview = true, noteId = null, preparing = true, playing = false)
        context.startService(Intent(context, ListeningService::class.java))
        generation = scope.launch {
            try {
                check(persistSnapshot(current)) { "Your listening position could not be saved. Please retry." }
                val audio = synthesizer.synthesize("Welcome to Rhapsodos. Take your time, find your pace, and let the words come to you.", packId)
                val transport = withTimeoutOrNull(15_000) { playerReady.await() } ?: error("The audio service did not start.")
                if (currentEpoch != epoch) return@launch
                transportEpoch = currentEpoch
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
        if (document != null) restoreAfterPreview(document, current, note, true)
        else stop()
    }

    private fun restoreAfterPreview(document: ReaderContents, snapshot: PlaybackSnapshot, note: VoiceNote?, changedEdition: Boolean = false) {
        if (note != null) playSavedNote(note, snapshot.positionMillis, snapshot.playing, document)
        else {
            val index = document.passages.indexOfFirst { it.id == snapshot.passageId }.coerceAtLeast(0)
            listen(document, index, if (changedEdition) 0 else snapshot.positionMillis, snapshot.playing, snapshot.passageFraction)
        }
    }

    suspend fun stopAndWait() { generation?.cancelAndJoin(); stop() }

    suspend fun reloadSpokenEdition(document: ReaderContents) {
        if (state.value.documentId != document.document.id) return
        publish()
        val current = state.value
        player?.pause()
        check(persistSnapshot(current)) { "Your listening position could not be saved. Please retry." }
        generation?.cancelAndJoin()
        val index = document.passages.indexOfFirst { it.id == current.passageId }.coerceAtLeast(0)
        listen(document, index, 0, false, current.passageFraction)
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
        publish()
        val previous = state.value
        player?.pause()
        val currentEpoch = ++epoch
        generation?.cancel()
        playingNote = note
        previewReturn = null
        pendingCursor = null
        desiredPlaying = startPlaying
        mutableState.value = PlaybackSnapshot(note.documentId, savedDocument?.document?.title ?: state.value.title, note.passageId, note.pageIndex, positionMillis = offsetMillis, preparing = true, voice = "Your recorded thought", noteId = note.id,
            sourceRevision = savedDocument?.document?.sourceRevision ?: previous.sourceRevision, sourceProgress = previous.sourceProgress.takeIf { previous.documentId == note.documentId })
        context.startService(Intent(context, ListeningService::class.java))
        generation = scope.launch {
            try {
                check(persistSnapshot(previous)) { "Your listening position could not be saved. Please retry." }
                if (currentEpoch != epoch) return@launch
                val document = savedDocument ?: store.contents(note.documentId)
                val transport = withTimeoutOrNull(15_000) { playerReady.await() }
                    ?: error("The audio service did not start. Please try playing the thought again.")
                if (currentEpoch != epoch) return@launch
                contents = document
                val savedPosition = store.snapshot.value.positions.firstOrNull { it.documentId == note.documentId }
                mutableState.value = PlaybackSnapshot(note.documentId, document.document.title, note.passageId, note.pageIndex, positionMillis = offsetMillis, voice = "Your recorded thought", noteId = note.id,
                    sourceRevision = document.document.sourceRevision, sourceProgress = sourceProgress(document, savedPosition?.passageId ?: document.passages.firstOrNull()?.id, savedPosition?.passageFraction ?: 0.0))
                transportEpoch = currentEpoch
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

    fun seek(positionMillis: Long) {
        if (capturing || transportEpoch != epoch) return
        val transport = player ?: return
        sourceSeekRequest++
        val position = positionMillis.coerceAtLeast(0)
        if (!state.value.preview && state.value.noteId == null && position < transport.duration) {
            pendingCursor = null
            mutableState.value = state.value.copy(passageFraction = 0.0)
        }
        transport.seekTo(position)
        publish()
    }

    fun seekSource(documentId: String, sourceRevision: String, fraction: Double) {
        if (!fraction.isFinite() || fraction !in 0.0..1.0 || !canSeekSource(documentId, sourceRevision)) return
        val request = ++sourceSeekRequest
        val expectedEpoch = epoch
        scope.launch {
            try {
                val target = store.resolveSourceSeek(documentId, sourceRevision, fraction)
                if (request != sourceSeekRequest || epoch != expectedEpoch || !canSeekSource(documentId, sourceRevision)) return@launch
                if (target.documentId != documentId || target.sourceRevision != sourceRevision) return@launch
                publish()
                val document = contents ?: return@launch
                val index = document.passages.indexOfFirst { it.id == target.passageId }
                if (index < 0) return@launch
                val shouldPlay = fraction < 1.0 && (desiredPlaying || player?.playWhenReady == true || state.value.playing)
                listen(document, index, 0, shouldPlay, target.passageFraction, saveTarget = true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (request == sourceSeekRequest && epoch == expectedEpoch && canSeekSource(documentId, sourceRevision)) {
                    mutableState.value = state.value.copy(error = "This document position could not be opened. Please retry.")
                }
            }
        }
    }

    private fun canSeekSource(documentId: String, sourceRevision: String): Boolean = !capturing && !state.value.preview && state.value.noteId == null &&
        state.value.documentId == documentId && contents?.document?.id == documentId && contents?.document?.sourceRevision == sourceRevision &&
        contents?.document?.extractionStatus != "preparing" && contents?.passages?.isNotEmpty() == true

    private fun sourceProgress(document: ReaderContents?, passageId: String?, fraction: Double): Double? {
        if (document == null || document.document.extractionStatus == "preparing") return null
        val total = document.passages.lastOrNull()?.sourceEnd?.takeIf { it > 0 } ?: return null
        val passage = document.passages.firstOrNull { it.id == passageId } ?: return null
        return ((passage.sourceStart + (passage.sourceEnd - passage.sourceStart) * fraction.coerceIn(0.0, 1.0)) / total).coerceIn(0.0, 1.0)
    }

    fun setSpeed(speed: Float) {
        val value = speed.coerceIn(0.75f, 2f)
        player?.playbackParameters = PlaybackParameters(value)
        mutableState.value = mutableState.value.copy(speed = value)
    }

    suspend fun persist(): Boolean {
        publish()
        return persistSnapshot(if (state.value.preview) previewReturn?.snapshot ?: state.value else state.value)
    }

    private suspend fun persistSnapshot(snapshot: PlaybackSnapshot): Boolean {
        if (snapshot.noteId != null || snapshot.preview) return true
        val documentId = snapshot.documentId ?: return true
        val passageId = snapshot.passageId ?: return true
        return try {
            withContext(NonCancellable) { progressWrites.withLock { store.saveProgress(documentId, passageId, snapshot.positionMillis, snapshot.passageFraction) } }
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (state.value.documentId == documentId) mutableState.value = state.value.copy(error = "Your listening position could not be saved.")
            false
        }
    }

    private fun completedReading(snapshot: PlaybackSnapshot): Boolean = snapshot.documentId != null && snapshot.noteId == null && !snapshot.preview &&
        snapshot.passageFraction == 1.0 && contents?.document?.extractionStatus != "preparing" && contents?.passages?.lastOrNull()?.id == snapshot.passageId

    private fun publish() {
        val transport = player ?: return
        val passage = contents?.passages?.firstOrNull { it.id == transport.currentMediaItem?.mediaId }
        val current = state.value
        val currentMedia = transportEpoch == epoch && when {
            current.preview -> transport.currentMediaItem?.mediaId == "voice-preview"
            current.noteId != null -> transport.currentMediaItem?.mediaId == "note:${current.noteId}"
            else -> passage != null
        }
        val duration = transport.duration.takeIf { it > 0 } ?: 0
        val pending = pendingCursor
        if (currentMedia && current.noteId == null && !current.preview && pending != null && pending.passageId == passage?.id && duration > 0 &&
            transport.playbackState in setOf(Player.STATE_READY, Player.STATE_ENDED)) {
            pendingCursor = null
            val position = if (pending.positionMillis == 0L || pending.passageFraction == 1.0) (duration * pending.passageFraction).toLong() else pending.positionMillis
            transport.seekTo(position.coerceIn(0, duration))
        }
        val fraction = when {
            !currentMedia || current.preview || current.noteId != null -> current.passageFraction
            pendingCursor?.passageId == passage?.id -> pendingCursor!!.passageFraction
            transport.playbackState == Player.STATE_ENDED && duration > 0 -> 1.0
            // Media3 clamps a paused end seek one millisecond before the duration.
            duration > 0 && current.passageId == passage?.id && current.passageFraction == 1.0 && !transport.isPlaying && transport.currentPosition >= duration - 1 -> 1.0
            duration > 0 -> (transport.currentPosition.toDouble() / duration).coerceIn(0.0, 1.0)
            passage?.id == current.passageId -> current.passageFraction
            else -> 0.0
        }
        mutableState.value = mutableState.value.copy(
            passageId = if (currentMedia && !current.preview && current.noteId == null) passage?.id ?: current.passageId else current.passageId,
            pageIndex = if (currentMedia && !current.preview && current.noteId == null) passage?.pageIndex ?: current.pageIndex else current.pageIndex,
            positionMillis = if (currentMedia) transport.currentPosition.coerceAtLeast(0) else current.positionMillis,
            durationMillis = if (currentMedia) duration else current.durationMillis,
            passageFraction = fraction,
            sourceRevision = contents?.document?.sourceRevision ?: current.sourceRevision,
            sourceProgress = if (current.preview || current.noteId != null) current.sourceProgress else sourceProgress(contents, if (currentMedia) passage?.id else current.passageId, fraction),
            playing = currentMedia && transport.isPlaying,
            preparing = if (currentMedia) transport.playbackState == Player.STATE_BUFFERING ||
                (transport.playbackState == Player.STATE_ENDED && contents?.document?.extractionStatus == "preparing" && desiredPlaying && generation?.isActive == true) else generation?.isActive == true,
            voice = if (playingNote != null) "Your recorded thought" else synthesizer.voiceLabel,
        )
    }
}
