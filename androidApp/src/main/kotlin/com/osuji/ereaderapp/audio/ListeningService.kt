package com.osuji.ereaderapp.audio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.osuji.ereaderapp.MainActivity
import org.koin.android.ext.android.inject

class ListeningService : MediaSessionService() {
    private val coordinator: PlaybackCoordinator by inject()
    private var session: MediaSession? = null
    private var audioFocus: ListeningAudioFocus? = null
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) coordinator.pause()
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).setWakeMode(C.WAKE_MODE_LOCAL).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), false)
            setHandleAudioBecomingNoisy(false)
        }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val guarded = object : ForwardingPlayer(player) {
            override fun play() { coordinator.resume() }
            override fun pause() { coordinator.pause() }
            override fun stop() { coordinator.pause(); coordinator.stop() }
            override fun setPlayWhenReady(playWhenReady: Boolean) { if (playWhenReady) coordinator.resume() else coordinator.pause() }
            override fun seekTo(positionMs: Long) { if (!coordinator.capturing) super.seekTo(positionMs) }
            override fun seekTo(mediaItemIndex: Int, positionMs: Long) { if (!coordinator.capturing) super.seekTo(mediaItemIndex, positionMs) }
            override fun seekToDefaultPosition() { if (!coordinator.capturing) super.seekToDefaultPosition() }
            override fun seekToDefaultPosition(mediaItemIndex: Int) { if (!coordinator.capturing) super.seekToDefaultPosition(mediaItemIndex) }
            override fun seekBack() { if (!coordinator.capturing) super.seekBack() }
            override fun seekForward() { if (!coordinator.capturing) super.seekForward() }
            override fun seekToPrevious() {
                if (!coordinator.capturing) {
                    if (coordinator.state.value.noteId == null) coordinator.skip(-1, preservePlaybackState = true) else super.seekToPrevious()
                }
            }
            override fun seekToPreviousMediaItem() {
                if (!coordinator.capturing) {
                    if (coordinator.state.value.noteId == null) coordinator.skip(-1, preservePlaybackState = true) else super.seekToPreviousMediaItem()
                }
            }
            override fun seekToNext() {
                if (!coordinator.capturing) {
                    if (coordinator.state.value.noteId == null) coordinator.skip(1, preservePlaybackState = true) else super.seekToNext()
                }
            }
            override fun seekToNextMediaItem() {
                if (!coordinator.capturing) {
                    if (coordinator.state.value.noteId == null) coordinator.skip(1, preservePlaybackState = true) else super.seekToNextMediaItem()
                }
            }
        }
        session = MediaSession.Builder(this, guarded).setSessionActivity(activity).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                if (controller.isTrusted || controller.packageName == packageName) super.onConnect(session, controller)
                else MediaSession.ConnectionResult.reject()

            override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
                val event = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
                if (event.keyCode != KeyEvent.KEYCODE_MEDIA_PAUSE) return false
                val trusted = controllerInfo.isTrusted || controllerInfo.packageName == packageName ||
                    getSystemService(MediaSessionManager::class.java).isTrustedForMediaControl(
                        MediaSessionManager.RemoteUserInfo(controllerInfo.packageName, -1, controllerInfo.uid))
                if (!trusted) return false
                if (event.action == KeyEvent.ACTION_DOWN) coordinator.pause()
                return event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
            }
        }).build()
        val focus = ListeningAudioFocus(this, player, coordinator::audioFocusChanged)
        audioFocus = focus
        coordinator.attach(player, focus)
        ContextCompat.registerReceiver(this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        coordinator.detach()
        unregisterReceiver(noisyReceiver)
        audioFocus?.release()
        audioFocus = null
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
