package dev.ewoxej.gallerylens.work

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.ewoxej.gallerylens.data.AppDatabase
import dev.ewoxej.gallerylens.data.PhotoStatus
import dev.ewoxej.gallerylens.ocr.OcrEngine
import dev.ewoxej.gallerylens.ocr.OcrLayout

class IndexingWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val dao = db.photoDao()

        runCatching { MediaScanner(applicationContext, dao).scan() }
            .onFailure { Log.w(TAG, "scan failed", it) }

        val ocr = OcrEngine(applicationContext)
        try {
            // Drain the whole pending backlog in this single run, one batch at a
            // time. (Self-enqueuing the next batch would be a no-op: the unique
            // work is still "running" here, so ExistingWorkPolicy.KEEP ignores
            // it and photos past the first batch would strand until an external
            // trigger.) isStopped keeps it cancellable between photos.
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

    companion object {
        private const val TAG = "IndexingWorker"
        private const val BATCH = 20
        const val WORK_NAME = "photo-indexing"

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
