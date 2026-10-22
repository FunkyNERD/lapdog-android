package io.hazaar.lapdog.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.IOException

/** The daily update check, scheduled by [AppUpdates.start]: notifies once per new version. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AppUpdates.init(applicationContext)
        return try {
            AppUpdates.check()?.let { AppUpdates.notify(applicationContext, it) }
            Result.success()
        } catch (e: IOException) {
            Result.retry()
        }
    }
}
