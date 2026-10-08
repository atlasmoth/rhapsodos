package com.osuji.ereaderapp

import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.osuji.ereaderapp.ui.ReaderApp

class MainActivity : ComponentActivity() {
    private val preferences by lazy { getSharedPreferences("presentation", MODE_PRIVATE) }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "appearance") runOnUiThread { syncSystemBars() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        syncSystemBars()
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
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

    private fun syncSystemBars() {
        val dark = when (preferences.getString("appearance", "system")) {
            "dark" -> true
            "light" -> false
            else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    override fun onDestroy() { preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener); super.onDestroy() }
}
