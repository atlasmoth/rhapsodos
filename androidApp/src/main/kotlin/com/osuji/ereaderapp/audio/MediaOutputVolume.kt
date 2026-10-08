package com.osuji.ereaderapp.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive

data class MediaVolumeSnapshot(
    val current: Int = 0,
    val minimum: Int = 0,
    val maximum: Int = 0,
    val fixed: Boolean = false,
    val available: Boolean = false,
    val error: String? = null,
) {
    val adjustable: Boolean get() = available && !fixed && maximum > minimum
    val fraction: Float get() = if (maximum > minimum) ((current - minimum).toFloat() / (maximum - minimum)).coerceIn(0f, 1f) else 0f
}

class MediaOutputVolume(context: Context) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val mutableState = MutableStateFlow(MediaVolumeSnapshot())
    val state = mutableState.asStateFlow()
    private var changeError: String? = null

    fun refresh(): MediaVolumeSnapshot {
        val value = try {
            val audio = manager ?: error("Audio service unavailable")
            MediaVolumeSnapshot(
                current = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                minimum = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC),
                maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                fixed = audio.isVolumeFixed,
                available = true,
                error = changeError,
            )
        } catch (_: Exception) { mutableState.value.copy(available = false, error = "Media volume is unavailable on this device.") }
        mutableState.value = value
        return value
    }

    fun setIndex(index: Int): Boolean {
        val before = refresh()
        if (!before.adjustable) return false
        val target = index.coerceIn(before.minimum, before.maximum)
        changeError = null
        try {
            if (target != before.current) manager?.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (_: Exception) {
            changeError = "This device could not change media volume."
            refresh()
            return false
        }
        val applied = refresh()
        if (applied.current != target) {
            changeError = "This output limits media volume."
            refresh()
            return false
        }
        return true
    }

    suspend fun observe() {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { refresh() }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { refresh() }
        }
        val registered = try { manager?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper())); manager != null } catch (_: Exception) { false }
        try {
            while (currentCoroutineContext().isActive) { refresh(); delay(500) }
        } finally {
            if (registered) runCatching { manager?.unregisterAudioDeviceCallback(callback) }
        }
    }
}
