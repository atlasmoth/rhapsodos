package com.osuji.ereaderapp.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.osuji.ereaderapp.MainActivity
import com.osuji.ereaderapp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class ModelDownloadService : Service() {
    private val models: ModelManager by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var finishedNormally = false

    override fun onCreate() {
        super.onCreate()
        models.downloadService = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("offline-downloads", "Offline speech downloads", NotificationManager.IMPORTANCE_LOW))
        startForeground(41, notification(0, 0))
        scope.launch {
            models.state.collect { state ->
                val active = state.installs.filter { it.status in setOf("downloading", "verifying") }
                getSystemService(NotificationManager::class.java).notify(41, notification(active.sumOf { it.bytesDownloaded }, active.sumOf { it.totalBytes }))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "pause") { models.pauseAll(); finishedNormally = true; stopSelfResult(startId) }
        return START_NOT_STICKY
    }

    private fun notification(downloaded: Long, total: Long): Notification {
        val open = PendingIntent.getActivity(this, 41, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 42, Intent(this, ModelDownloadService::class.java).setAction("pause"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "offline-downloads").setSmallIcon(R.drawable.ic_rhapsodos).setContentTitle("Rhapsodos offline speech")
            .setContentText(if (total > 0) "${downloaded / (1024 * 1024)} of ${total / (1024 * 1024)} MB · tap to view" else "Preparing your requested download")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setProgress(100, if (total > 0) (downloaded * 100 / total).toInt() else 0, total == 0L)
            .addAction(Notification.Action.Builder(null, "Pause downloads", pause).build()).build()
    }

    internal fun finishIfIdle() { if (!models.hasDownloads()) { finishedNormally = true; stopSelf() } }
    override fun onTimeout(startId: Int, fgsType: Int) { models.pauseAll(); finishedNormally = true; stopSelfResult(startId) }
    override fun onDestroy() {
        if (models.downloadService === this) models.downloadService = null
        if (!finishedNormally) models.pauseAll()
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
