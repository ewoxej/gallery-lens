package dev.ewoxej.gallerylens

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.ewoxej.gallerylens.work.IndexingWorker

class PhotoSearchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Low-importance channel for the ongoing indexing foreground notification.
        val channel = NotificationChannel(
            IndexingWorker.CHANNEL_ID,
            getString(R.string.notif_channel_indexing),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
