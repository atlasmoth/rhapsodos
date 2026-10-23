package com.osuji.ereaderapp

import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.osuji.ereaderapp.ui.ReaderApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 31) splashScreen.setOnExitAnimationListener { splash ->
            val reduced = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            if (!reduced) {
                splash.iconView?.apply { scaleX = 0.88f; scaleY = 0.75f; animate().scaleX(1f).scaleY(1f).setDuration(260).start() }
            }
            splash.animate().alpha(0f).setDuration(if (reduced) 100 else 320).withEndAction { splash.remove() }.start()
        }
        setContent { ReaderApp() }
    }

    override fun onResume() {
        super.onResume()
        org.koin.core.context.GlobalContext.get().get<com.osuji.ereaderapp.audio.TranscriptionCoordinator>().resumeAfterMemoryPressure()
    }
}
