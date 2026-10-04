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
    var capturing: Boolean = false
        private set

    fun attach(transport: ExoPlayer) {
        player = transport
        if (!playerReady.isCompleted) playerReady.complete(transport)
        transport.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (capturing && player.playWhenReady) player.pause()
                publish()
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

    fun listen(document: ReaderContents, passageIndex: Int = 0, offsetMillis: Long = 0) {
        if (capturing) return
        if (document.passages.isEmpty()) return
        val start = passageIndex.coerceIn(document.passages.indices)
        playingNote = null
        val currentEpoch = ++epoch
        generation?.cancel()
        contents = document
        resumeOffset = offsetMillis
        mutableState.value = PlaybackSnapshot(document.document.id, document.document.title, document.passages[start].id, document.passages[start].pageIndex, preparing = true, speed = mutableState.value.speed)
        context.startService(Intent(context, ListeningService::class.java))
        generation = scope.launch {
            try {
                val transport = withTimeoutOrNull(15_000) { playerReady.await() }
                    ?: error("The audio service did not start. Please try listening again.")
                transport.stop()
                transport.clearMediaItems()
                transport.playbackParameters = PlaybackParameters(mutableState.value.speed)
                document.passages.drop(start).forEachIndexed { queueIndex, passage ->
                    while (queueIndex > transport.currentMediaItemIndex.coerceAtLeast(0) + 3) delay(250)
                    val audio = synthesizer.synthesize(passage.spokenText)
                    if (currentEpoch != epoch) return@launch
                    transport.addMediaItem(item(document, passage, audio))
                    if (queueIndex == 0) {
                        transport.seekTo(0, resumeOffset)
                        transport.prepare()
                        if (!capturing) transport.play()
                    }
                    publish()
                }
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (currentEpoch == epoch) mutableState.value = mutableState.value.copy(preparing = false, playing = false, error = failure.message ?: "This passage could not be prepared.")
            }
        }
    }

    private fun item(document: ReaderContents, passage: ReaderPassage, file: File): MediaItem = MediaItem.Builder()
        .setMediaId(passage.id)
        .setUri(android.net.Uri.fromFile(file))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(document.document.title).setArtist("Page ${passage.pageIndex + 1} · ${synthesizer.voiceLabel}").build())
        .build()

    fun toggle() {
        if (capturing) return
        val transport = player ?: return
        if (transport.isPlaying || transport.playWhenReady) { transport.pause(); scope.launch { persist() } }
        else { if (transport.playbackState == Player.STATE_ENDED) transport.seekTo(0); transport.play() }
        publish()
    }

    fun pause(): FrozenPlayback? {
        player?.pause()
        publish()
        val snapshot = state.value
        val documentId = snapshot.documentId ?: return null
        val passageId = snapshot.passageId ?: return null
        scope.launch { persist() }
        return FrozenPlayback(documentId, passageId, playingNote?.offsetMillis ?: snapshot.positionMillis)
    }

    fun resume() { if (!capturing) player?.play() }

    fun beginCapture(): FrozenPlayback? {
        captureAnchor?.let { return it }
        if (playingNote != null) return null
        val frozen = pause() ?: return null
        captureAnchor = frozen
        capturing = true
        return frozen
    }

    fun endCapture() { capturing = false; captureAnchor = null }

    fun skip(direction: Int) {
        val document = contents ?: return
        val index = document.passages.indexOfFirst { it.id == state.value.passageId }
        listen(document, (index + direction).coerceIn(document.passages.indices))
    }

    fun playNote(note: VoiceNote) {
        if (capturing) return
        if (state.value.noteId == note.id) { toggle(); return }
        val currentEpoch = ++epoch
        generation?.cancel()
        playingNote = note
        context.startService(Intent(context, ListeningService::class.java))
        scope.launch {
            try {
                val document = store.contents(note.documentId)
                val transport = withTimeoutOrNull(15_000) { playerReady.await() }
                    ?: error("The audio service did not start. Please try playing the thought again.")
                if (currentEpoch != epoch) return@launch
                contents = document
                mutableState.value = PlaybackSnapshot(note.documentId, contents!!.document.title, note.passageId, note.pageIndex, voice = "Your recorded thought", noteId = note.id)
                transport.setMediaItem(MediaItem.Builder().setMediaId("note:${note.id}").setUri(android.net.Uri.fromFile(File(note.recordingPath))).setMediaMetadata(MediaMetadata.Builder().setTitle("Your thought · page ${note.pageIndex + 1}").setArtist(contents!!.document.title).build()).build())
                transport.playbackParameters = PlaybackParameters(1f)
                transport.prepare()
                transport.play()
            } catch (failure: Exception) {
                mutableState.value = mutableState.value.copy(error = "This saved recording could not be played.")
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
        if (snapshot.noteId != null) return
        val documentId = snapshot.documentId ?: return
        val passageId = snapshot.passageId ?: return
        runCatching { store.saveProgress(documentId, passageId, snapshot.positionMillis) }.onFailure {
            mutableState.value = state.value.copy(error = "Your listening position could not be saved.")
        }
    }

    private fun publish() {
        val transport = player ?: return
        val passage = contents?.passages?.firstOrNull { it.id == transport.currentMediaItem?.mediaId }
        mutableState.value = mutableState.value.copy(
            passageId = passage?.id ?: mutableState.value.passageId,
            pageIndex = passage?.pageIndex ?: mutableState.value.pageIndex,
            positionMillis = transport.currentPosition.coerceAtLeast(0),
            durationMillis = transport.duration.takeIf { it > 0 } ?: 0,
            playing = transport.isPlaying,
            preparing = transport.playbackState == Player.STATE_BUFFERING || (transport.mediaItemCount == 0 && generation?.isActive == true),
            voice = if (playingNote != null) "Your recorded thought" else synthesizer.voiceLabel,
        )
    }
}
