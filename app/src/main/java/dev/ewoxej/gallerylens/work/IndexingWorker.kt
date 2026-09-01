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
import dev.ewoxej.gallerylens.data.PhotoDao
import dev.ewoxej.gallerylens.data.PhotoStatus
import dev.ewoxej.gallerylens.data.Settings
import dev.ewoxej.gallerylens.ocr.BatchState
import dev.ewoxej.gallerylens.ocr.CloudBatch
import dev.ewoxej.gallerylens.ocr.CloudItem
import dev.ewoxej.gallerylens.ocr.OcrEngine
import dev.ewoxej.gallerylens.ocr.OcrLayout
import dev.ewoxej.gallerylens.ocr.cloudWanted
import kotlinx.coroutines.delay

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

        // "Send every photo to Claude" mode: local OCR would just be discarded, so
        // skip it and push fresh photos straight to the cloud queue — the batch then
        // starts right away instead of after an hours-long local pass on a big library.
        val cloudAll = Settings.cloudReady(applicationContext) && Settings.cloudAlways(applicationContext)
        if (cloudAll) {
            runCatching { dao.movePendingToCloud() }.onFailure { Log.w(TAG, "movePendingToCloud failed", it) }
        } else {
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
                                // Queue for a cloud batch re-read if wanted (kept
                                // searchable by the local text meanwhile).
                                pendingCloud = cloudWanted(applicationContext, result.text),
                            )
                        }
                    }
                }
            } finally {
                ocr.close()
            }
        }

        // Cloud (Claude Batch API) pass for the photos flagged above.
        runCatching { runCloudPass(dao) }.onFailure { Log.w(TAG, "cloud pass failed", it) }
        return Result.success()
    }

    /**
     * Resume an in-flight batch, then submit the cloud-pending backlog in chunks,
     * polling each to completion. A batch that hasn't finished when we stop is left
     * in-flight (its id persisted) and picked up on the next run — never resubmitted.
     */
    private suspend fun runCloudPass(dao: PhotoDao) {
        if (!Settings.cloudReady(applicationContext)) return
        val apiKey = Settings.apiKey(applicationContext)

        // 1. Resume a batch from a previous run.
        Settings.pendingBatchId(applicationContext)?.let { id ->
            when (pollUntilDone(apiKey, id)) {
                BatchState.ENDED -> applyBatch(dao, apiKey, id)
                BatchState.FAILED -> {
                    dao.resetSubmittedToPending(); Settings.setPendingBatchId(applicationContext, null)
                }
                BatchState.PROCESSING -> return // still running; try again next run
            }
        }

        // 2. Submit the cloud-pending backlog in chunks.
        while (!isStopped) {
            val chunk = dao.nextCloudPending(CLOUD_BATCH)
            if (chunk.isEmpty()) break
            val items = chunk.mapNotNull { p ->
                CloudBatch.encode(applicationContext, p.uri)?.let { CloudItem(p.id, it) }
            }
            // Photos we couldn't decode can't be cloud-read — finalise them with
            // their local text so they don't come back in the next nextCloudPending.
            val encoded = items.map { it.id }.toHashSet()
            chunk.filter { it.id !in encoded }.forEach { dao.finalizeCloudFailed(it.id) }
            if (items.isEmpty()) continue
            val batchId = CloudBatch.submit(apiKey, items) ?: break
            dao.markSubmitted(items.map { it.id })
            Settings.setPendingBatchId(applicationContext, batchId)
            when (pollUntilDone(apiKey, batchId)) {
                BatchState.ENDED -> applyBatch(dao, apiKey, batchId)
                BatchState.FAILED -> {
                    dao.resetSubmittedToPending(); Settings.setPendingBatchId(applicationContext, null)
                }
                BatchState.PROCESSING -> return // left in-flight for the next run
            }
        }
    }

    private suspend fun applyBatch(dao: PhotoDao, apiKey: String, batchId: String) {
        val results = CloudBatch.fetchResults(apiKey, batchId)
        val submitted = dao.submittedPhotos()
        val now = System.currentTimeMillis()
        for (photo in submitted) {
            val text = results[photo.id]
            if (!text.isNullOrBlank()) dao.applyCloudResult(photo.id, text, now)
            else dao.finalizeCloudFailed(photo.id) // errored / no text -> keep local
        }
        Settings.setPendingBatchId(applicationContext, null)
    }

    /** Poll a batch until it ends, we're stopped, or the time cap is hit. */
    private suspend fun pollUntilDone(apiKey: String, batchId: String): BatchState {
        val deadline = System.currentTimeMillis() + CLOUD_POLL_CAP_MS
        while (!isStopped && System.currentTimeMillis() < deadline) {
            when (val state = CloudBatch.pollStatus(apiKey, batchId)) {
                BatchState.ENDED, BatchState.FAILED -> return state
                BatchState.PROCESSING -> delay(CLOUD_POLL_INTERVAL_MS)
            }
        }
        return BatchState.PROCESSING
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
        // Photos per Claude batch (keeps the request body a sane size), and how
        // long we wait in-run for a batch before leaving it to resume next run.
        private const val CLOUD_BATCH = 50
        private const val CLOUD_POLL_INTERVAL_MS = 8_000L
        private const val CLOUD_POLL_CAP_MS = 15 * 60_000L
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
