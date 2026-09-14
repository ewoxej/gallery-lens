package dev.ewoxej.gallerylens.work

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.ewoxej.gallerylens.R
import dev.ewoxej.gallerylens.data.AppDatabase
import dev.ewoxej.gallerylens.data.PhotoDao
import dev.ewoxej.gallerylens.data.PhotoStatus
import dev.ewoxej.gallerylens.data.Settings
import dev.ewoxej.gallerylens.ocr.OcrLayout
import dev.ewoxej.gallerylens.ocr.OcrOutcome
import dev.ewoxej.gallerylens.ocr.OcrSpace
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Recognises text on gallery photos via the OCR.space API. Runs as a foreground
 * service so it survives the screen off. Photos are sent several at a time (there
 * is no batch endpoint) and the run stops when the free tier's daily request
 * budget is spent, then reschedules itself for the next day.
 */
class IndexingWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    override suspend fun doWork(): Result {
        val dao = AppDatabase.get(applicationContext).photoDao()

        runCatching { MediaScanner(applicationContext, dao).scan() }
            .onFailure { Log.w(TAG, "scan failed", it) }

        val apiKey = Settings.apiKey(applicationContext)
        if (apiKey.isBlank()) return Result.success() // no key -> nothing to recognise

        runCatching { setForeground(foregroundInfo()) }
            .onFailure { Log.w(TAG, "setForeground failed; indexing without it", it) }

        // Engine 3's free plan permits only ONE request at a time and 60/hour, so
        // recognise serially and pace ourselves off the API's retryAfter. Each
        // processed request counts against the 500/day budget.
        var quotaHit = false
        var consecutiveFails = 0
        drain@ while (!isStopped) {
            if (Settings.ocrRemainingToday(applicationContext) <= 0) { quotaHit = true; break }
            val batch = dao.nextPending(BATCH)
            if (batch.isEmpty()) break
            for (photo in batch) {
                if (isStopped) break@drain
                if (Settings.ocrRemainingToday(applicationContext) <= 0) { quotaHit = true; break@drain }
                // Retry the SAME photo while the API throttles us (E552/E553).
                photo@ while (!isStopped) {
                    when (val o = OcrSpace.recognize(applicationContext, photo.uri, apiKey)) {
                        is OcrOutcome.Ok -> {
                            consecutiveFails = 0
                            Settings.incrementOcrToday(applicationContext)
                            dao.applyOcrResult(
                                id = photo.id,
                                text = o.result.text,
                                searchText = o.result.searchText,
                                blocksJson = OcrLayout.toJson(o.result.blocks),
                                w = o.result.width,
                                h = o.result.height,
                                atMs = System.currentTimeMillis(),
                            )
                            break@photo
                        }
                        // Rate-limited: wait the API's retryAfter (capped) and re-try
                        // this photo — the request wasn't processed, so nothing counts.
                        is OcrOutcome.Throttled -> delay(o.retryAfterSec.coerceAtMost(MAX_WAIT_SEC) * 1000L)
                        OcrOutcome.DailyLimit -> { Settings.markQuotaExhausted(applicationContext); quotaHit = true; break@drain }
                        OcrOutcome.Undecodable -> { dao.setStatus(photo.id, PhotoStatus.FAILED, null, System.currentTimeMillis()); break@photo }
                        // A timeout/hiccup: skip this photo (stays PENDING) and move
                        // on. Only stop once many fail in a row (network down).
                        OcrOutcome.Transient -> { if (++consecutiveFails >= MAX_CONSECUTIVE_FAILS) break@drain else break@photo }
                    }
                }
            }
        }

        if (quotaHit) scheduleDailyResume(applicationContext)
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
        // Photos pulled from the DB per loop (recognised one at a time).
        private const val BATCH = 40
        // Stop the run after this many back-to-back failures (network likely down).
        private const val MAX_CONSECUTIVE_FAILS = 5
        // Cap a single in-run wait for a throttle backoff (keeps it responsive).
        private const val MAX_WAIT_SEC = 120
        const val WORK_NAME = "photo-indexing"
        private const val RESUME_WORK_NAME = "photo-indexing-resume"
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

        /** After the daily limit is hit, wake up just after the next local midnight. */
        private fun scheduleDailyResume(context: Context) {
            val nextMidnight = LocalDate.now().plusDays(1).atStartOfDay().plusMinutes(5)
            val delay = Duration.between(LocalDateTime.now(), nextMidnight).toMillis().coerceAtLeast(60_000)
            val request = OneTimeWorkRequestBuilder<IndexingWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                RESUME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
