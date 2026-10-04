package com.osuji.ereaderapp.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.osuji.ereaderapp.MainActivity
import com.osuji.ereaderapp.R
import org.koin.android.ext.android.inject

class CaptureService : Service() {
    private val coordinator: VoiceNoteCoordinator by inject()

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Voice-note recording", NotificationManager.IMPORTANCE_LOW))
        val activity = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_reader)
            .setContentTitle("Recording your thought")
            .setContentText("Open eReader to save or cancel your voice note.")
            .setContentIntent(activity)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(201, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(201, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("noteId")?.let { coordinator.startCapture(it) }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        coordinator.interrupted()
        super.onDestroy()
    }

    companion object { private const val CHANNEL = "voice-notes" }
}
