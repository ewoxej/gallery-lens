package dev.ewoxej.gallerylens.work

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.ewoxej.gallerylens.R
import dev.ewoxej.gallerylens.data.AppDatabase
import dev.ewoxej.gallerylens.data.PhotoStatus
import dev.ewoxej.gallerylens.ocr.OcrEngine
import dev.ewoxej.gallerylens.ocr.OcrLayout

/**
 * Runs as a foreground service (ongoing notification) so OCR keeps going with
 * the screen off / app in the background, and isn't cut at WorkManager's 10-min
 * limit. Drains the whole pending backlog in one run.
 */
class IndexingWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val dao = db.photoDao()

        runCatching { MediaScanner(applicationContext, dao).scan() }
            .onFailure { Log.w(TAG, "scan failed", it) }

        // Promote to a foreground service so the OS keeps us alive with the
        // screen off. Best-effort: if it can't (e.g. notifications blocked) we
        // still index while the app stays alive.
        runCatching { setForeground(foregroundInfo()) }
            .onFailure { Log.w(TAG, "setForeground failed; indexing without it", it) }

        val ocr = OcrEngine(applicationContext)
        try {
            // Drain the whole pending backlog in this single run, one batch at a
            // time. (Self-enqueuing the next batch would be a no-op: the unique
            // work is still "running" here, so ExistingWorkPolicy.KEEP ignores
            // it and photos past the first batch would strand.) isStopped keeps
            // it cancellable between photos.
            while (!isStopped) {
                val batch = dao.nextPending(BATCH)
                if (batch.isEmpty()) break
                for (photo in batch) {
                    if (isStopped) break
                    val result = ocr.recognize(Uri.parse(photo.uri))
                    if (result == null) {
                        dao.setStatus(photo.id, PhotoStatus.FAILED, null, System.currentTimeMillis())
                    } else {
                        dao.applyOcrResult(
                            id = photo.id,
                            text = result.text,
                            searchText = result.searchText,
                            blocksJson = OcrLayout.toJson(result.blocks),
                            w = result.width,
                            h = result.height,
                            atMs = System.currentTimeMillis(),
                        )
                    }
                }
            }
        } finally {
            ocr.close()
        }
        return Result.success()
    }

    private fun foregroundInfo(): ForegroundInfo {
        val notification: Notification =
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setContentTitle(applicationContext.getString(R.string.notif_indexing_title))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setSilent(true)
                .setProgress(0, 0, true) // indeterminate; live counts are in-app
                .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val TAG = "IndexingWorker"
        private const val BATCH = 20
        const val WORK_NAME = "photo-indexing"
        const val CHANNEL_ID = "indexing"
        private const val NOTIF_ID = 1

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<IndexingWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
