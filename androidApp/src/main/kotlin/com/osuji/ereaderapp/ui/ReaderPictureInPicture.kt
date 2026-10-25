package com.osuji.ereaderapp.ui

import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.PictureInPictureUiState
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Process
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.osuji.ereaderapp.audio.PlaybackCoordinator
import com.osuji.ereaderapp.audio.PlaybackSnapshot
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

class ReaderPictureInPicture(
    private val activity: ComponentActivity,
    private val playback: PlaybackCoordinator,
) {
    val supported = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    var presenting by mutableStateOf(activity.isInPictureInPictureMode)
        private set
    var canEnter by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private data class PlaybackGate(val documentId: String?, val hasPassage: Boolean, val playing: Boolean, val capturing: Boolean, val replayingNote: Boolean)
    private data class AppliedParams(val autoEnter: Boolean, val sourceRect: Rect?)

    private var playbackGate = playback.state.value.gate()
    private var selectedDocumentId: String? = null
    private var readerDocumentId: String? = null
    private var sourceDocumentId: String? = null
    private var uiBlocked = true
    private var readerBlocked = true
    private var suspended = false
    private var sourceRect: Rect? = null
    private var appliedParams: AppliedParams? = null
    private var entered = activity.isInPictureInPictureMode
    private var disposed = false
    private val closeActionName = "${activity.packageName}.pip.CLOSE.${UUID.randomUUID()}"
    private var closeIntent: PendingIntent? = null
    private var receiverRegistered = false
    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != closeActionName || disposed || !activity.isInPictureInPictureMode) return
            entered = false
            playback.pause()
            activity.finish()
        }
    }
    private val playbackJob = activity.lifecycleScope.launch {
        playback.state.map { it.gate() }.distinctUntilChanged().collect {
            playbackGate = it
            refresh()
        }
    }

    init {
        if (supported && Build.VERSION.SDK_INT >= 33) {
            ContextCompat.registerReceiver(activity, closeReceiver, IntentFilter(closeActionName), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            closeIntent = PendingIntent.getBroadcast(activity, 0, Intent(closeActionName).setPackage(activity.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        appliedParams = null
        refresh()
    }

    fun clearError() { error = null }

    fun updateUi(selectedDocumentId: String?, blocked: Boolean) {
        if (this.selectedDocumentId == selectedDocumentId && uiBlocked == blocked && (blocked || !suspended)) return
        this.selectedDocumentId = selectedDocumentId
        uiBlocked = blocked
        if (!blocked) suspended = false
        refresh()
    }

    fun updateReader(documentId: String, blocked: Boolean) {
        if (readerDocumentId == documentId && readerBlocked == blocked) return
        readerDocumentId = documentId
        readerBlocked = blocked
        refresh()
    }

    fun clearReader(documentId: String) {
        if (readerDocumentId != documentId && sourceDocumentId != documentId) return
        if (readerDocumentId == documentId) {
            readerDocumentId = null
            readerBlocked = true
        }
        if (sourceDocumentId == documentId) {
            sourceDocumentId = null
            sourceRect = null
        }
        refresh()
    }

    fun updateSourceRect(documentId: String, rect: Rect) {
        if (presenting || rect.isEmpty) return
        val decor = activity.window.decorView
        val clipped = Rect(rect)
        if (decor.width <= 0 || decor.height <= 0 || !clipped.intersect(0, 0, decor.width, decor.height) || clipped.isEmpty) return
        if (sourceDocumentId == documentId && sourceRect == clipped) return
        sourceDocumentId = documentId
        sourceRect = clipped
        refresh()
    }

    fun suspendAutoEntry() {
        suspended = true
        refresh()
    }

    fun enter(): Boolean = enter(reportError = true)

    fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT < 31 && canEnter && permissionAllowed()) enter(reportError = false)
    }

    fun onModeChanged(inPip: Boolean) {
        // Older Android versions stop and reparent a dismissed PiP activity without finishing it.
        if (!inPip && entered && !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            entered = false
            playback.pause()
        }
        presenting = inPip
        if (inPip) entered = true
        refresh()
    }

    @RequiresApi(31)
    fun onUiStateChanged(state: PictureInPictureUiState) {
        if (Build.VERSION.SDK_INT >= 35) {
            if (state.isTransitioningToPip) presenting = true
            else if (!activity.isInPictureInPictureMode && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) presenting = false
        }
    }

    fun onResume() {
        if (!activity.isInPictureInPictureMode) {
            entered = false
            presenting = false
        }
        refresh()
    }

    fun dispose(finishing: Boolean) {
        if (disposed) return
        disposed = true
        playbackJob.cancel()
        if (receiverRegistered) activity.unregisterReceiver(closeReceiver)
        closeIntent?.cancel()
        if (finishing && entered && !activity.isChangingConfigurations) playback.pause()
    }

    private fun enter(reportError: Boolean): Boolean {
        if (disposed || activity.isFinishing || activity.isDestroyed) return false
        if (activity.isInPictureInPictureMode) return true
        refresh()
        val problem = when {
            !supported -> "Picture-in-picture is unavailable on this device."
            !permissionAllowed() -> "Allow picture-in-picture for Rhapsodos in Android Settings."
            !canEnter -> "Start listening in the reader to use picture-in-picture."
            else -> null
        }
        if (problem != null) {
            if (reportError) error = problem
            return false
        }
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
        val params = parameters(canEnter && permissionAllowed(), validSourceRect())
        presenting = true
        return try {
            activity.enterPictureInPictureMode(params).also { accepted ->
                if (!accepted) {
                    presenting = false
                    if (reportError) error = "Picture-in-picture could not start. Please try again."
                }
            }
        } catch (_: Exception) {
            presenting = false
            if (reportError) error = "Picture-in-picture could not start. Please try again."
            false
        }
    }

    private fun refresh() {
        if (disposed) return
        val source = validSourceRect()
        canEnter = supported && !suspended && !uiBlocked && !readerBlocked &&
            selectedDocumentId != null && selectedDocumentId == readerDocumentId &&
            selectedDocumentId == sourceDocumentId && selectedDocumentId == playbackGate.documentId &&
            playbackGate.hasPassage && playbackGate.playing && !playbackGate.capturing && !playbackGate.replayingNote && source != null
        if (!supported || activity.isFinishing || activity.isDestroyed) return
        val next = AppliedParams(canEnter && permissionAllowed(), source)
        if (next == appliedParams) return
        try {
            activity.setPictureInPictureParams(parameters(next.autoEnter, source))
            appliedParams = next
        } catch (_: Exception) { }
    }

    private fun parameters(autoEnter: Boolean, source: Rect?): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (source != null) builder.setSourceRectHint(source)
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(autoEnter).setSeamlessResizeEnabled(false)
        if (Build.VERSION.SDK_INT >= 33) closeIntent?.let {
            builder.setCloseAction(RemoteAction(Icon.createWithResource(activity, android.R.drawable.ic_menu_close_clear_cancel), "Close", "Close picture-in-picture", it))
        }
        return builder.build()
    }

    private fun validSourceRect(): Rect? {
        val source = sourceRect ?: return null
        if (presenting) return Rect(source)
        val decor = activity.window.decorView
        if (decor.width <= 0 || decor.height <= 0) return null
        return Rect(source).takeIf { it.intersect(0, 0, decor.width, decor.height) && !it.isEmpty }
    }

    private fun permissionAllowed(): Boolean = try {
        val mode = activity.getSystemService(AppOpsManager::class.java).checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), activity.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    private fun PlaybackSnapshot.gate() = PlaybackGate(documentId, passageId != null, playing, capturing, noteId != null)
}
