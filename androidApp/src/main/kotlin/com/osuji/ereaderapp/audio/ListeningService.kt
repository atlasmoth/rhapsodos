package com.osuji.ereaderapp.audio

import android.app.PendingIntent
import android.content.Intent
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

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).setWakeMode(C.WAKE_MODE_LOCAL).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
        }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val guarded = object : ForwardingPlayer(player) {
            override fun play() { if (!coordinator.capturing) super.play() }
            override fun setPlayWhenReady(playWhenReady: Boolean) { super.setPlayWhenReady(playWhenReady && !coordinator.capturing) }
            override fun seekTo(positionMs: Long) { if (!coordinator.capturing) super.seekTo(positionMs) }
            override fun seekTo(mediaItemIndex: Int, positionMs: Long) { if (!coordinator.capturing) super.seekTo(mediaItemIndex, positionMs) }
            override fun seekToDefaultPosition() { if (!coordinator.capturing) super.seekToDefaultPosition() }
            override fun seekToDefaultPosition(mediaItemIndex: Int) { if (!coordinator.capturing) super.seekToDefaultPosition(mediaItemIndex) }
            override fun seekBack() { if (!coordinator.capturing) super.seekBack() }
            override fun seekForward() { if (!coordinator.capturing) super.seekForward() }
            override fun seekToPrevious() { if (!coordinator.capturing) super.seekToPrevious() }
            override fun seekToPreviousMediaItem() { if (!coordinator.capturing) super.seekToPreviousMediaItem() }
            override fun seekToNext() { if (!coordinator.capturing) super.seekToNext() }
            override fun seekToNextMediaItem() { if (!coordinator.capturing) super.seekToNextMediaItem() }
        }
        session = MediaSession.Builder(this, guarded).setSessionActivity(activity).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                if (controller.isTrusted || controller.packageName == packageName) super.onConnect(session, controller)
                else MediaSession.ConnectionResult.reject()
        }).build()
        coordinator.attach(player)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        coordinator.detach()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
