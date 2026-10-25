package com.osuji.ereaderapp

import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.osuji.ereaderapp.ui.ReaderApp
import com.osuji.ereaderapp.data.ExternalPdfOpener
import org.koin.android.ext.android.inject
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val externalPdfs: ExternalPdfOpener by inject()
    private var externalRequestId: String? = null
    private var externalRequestConsumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        externalRequestId = savedInstanceState?.getString("externalPdfRequest")
        externalRequestConsumed = savedInstanceState?.getBoolean("externalPdfConsumed") ?: false
        if (!externalRequestConsumed) receivePdf(intent)
        volumeControlStream = AudioManager.STREAM_MUSIC
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 31) splashScreen.setOnExitAnimationListener { splash ->
            val reduced = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            if (!reduced) {
                splash.iconView?.apply { scaleX = 0.88f; scaleY = 0.75f; animate().scaleX(1f).scaleY(1f).setDuration(260).start() }
            }
            splash.animate().alpha(0f).setDuration(if (reduced) 100 else 320).withEndAction { splash.remove() }.start()
        }
        setContent { ReaderApp(externalPdfConsumed = ::externalPdfConsumed) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalRequestId = null
        externalRequestConsumed = false
        receivePdf(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("externalPdfRequest", externalRequestId)
        outState.putBoolean("externalPdfConsumed", externalRequestConsumed)
        super.onSaveInstanceState(outState)
    }

    private fun receivePdf(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val request = externalRequestId ?: UUID.randomUUID().toString().also { externalRequestId = it }
        externalPdfs.submit(intent, request)
    }

    private fun externalPdfConsumed(requestId: String) {
        externalPdfs.acknowledge(requestId)
        if (externalRequestId == requestId) {
            externalRequestId = null
            externalRequestConsumed = true
            setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        }
    }

    override fun onResume() {
        super.onResume()
        org.koin.core.context.GlobalContext.get().get<com.osuji.ereaderapp.audio.TranscriptionCoordinator>().resumeAfterMemoryPressure()
    }
}
