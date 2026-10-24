package com.osuji.ereaderapp.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import androidx.media3.common.Player

internal class ListeningAudioFocus(
    context: Context,
    private val player: Player,
    private val changed: (Long, Change) -> Unit,
) {
    enum class Change { GAIN, TRANSIENT_LOSS, LOSS }
    enum class Permission { READY, WAITING, DENIED }

    private val manager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(player.applicationLooper)
    private var request: AudioFocusRequest? = null
    private var epoch: Long? = null
    private var generation = 0L
    private var permission = Permission.DENIED
    private var multiplier = 1f
    private var volume = player.volume
    private val expectedVolumeChanges = ArrayDeque<Float>()
    private var released = false
    private val listener = object : Player.Listener {
        override fun onVolumeChanged(volume: Float) {
            if (expectedVolumeChanges.firstOrNull() == volume) {
                expectedVolumeChanges.removeFirst()
                return
            }
            this@ListeningAudioFocus.volume = volume
            applyVolume()
        }
    }

    init { player.addListener(listener) }

    val isWaiting: Boolean get() = request != null && permission == Permission.WAITING

    fun request(epoch: Long): Permission {
        if (released) return Permission.DENIED
        if (this.epoch == epoch && request != null) return permission
        abandon()
        this.epoch = epoch
        val token = ++generation
        val next = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setWillPauseWhenDucked(false)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener({ change ->
                if (!released && generation == token && this.epoch == epoch) focusChanged(epoch, change)
            }, handler)
            .build()
        request = next
        permission = Permission.WAITING
        val result = runCatching { manager.requestAudioFocus(next) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        permission = when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> Permission.READY
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> Permission.WAITING
            else -> Permission.DENIED
        }
        if (permission == Permission.DENIED) abandon()
        return permission
    }

    fun abandon() {
        generation++
        epoch = null
        permission = Permission.DENIED
        val previous = request
        request = null
        if (previous != null) runCatching { manager.abandonAudioFocusRequest(previous) }
        multiplier = 1f
        applyVolume()
    }

    fun release() {
        abandon()
        released = true
        player.removeListener(listener)
    }

    private fun focusChanged(epoch: Long, change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                permission = Permission.READY
                multiplier = 1f
                applyVolume()
                changed(epoch, Change.GAIN)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (permission == Permission.READY) {
                    multiplier = 0.2f
                    applyVolume()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                permission = Permission.WAITING
                changed(epoch, Change.TRANSIENT_LOSS)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                permission = Permission.DENIED
                changed(epoch, Change.LOSS)
            }
        }
    }

    private fun applyVolume() {
        val adjusted = volume * multiplier
        if (player.volume == adjusted) return
        expectedVolumeChanges.addLast(adjusted)
        player.volume = adjusted
    }
}
