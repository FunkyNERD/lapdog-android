package io.hazaar.lapdog.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.hazaar.lapdog.service.HeatStore

/** A background [WebSync.sync], queued by [WebSync.request]: retried while offline or the website is busy. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        HeatStore.init(applicationContext)
        WebSync.init(applicationContext)
        return when (WebSync.sync()) {
            WebSync.Outcome.OK -> Result.success()
            WebSync.Outcome.RETRY -> Result.retry()
            WebSync.Outcome.FAILED -> Result.failure()
        }
    }
}
