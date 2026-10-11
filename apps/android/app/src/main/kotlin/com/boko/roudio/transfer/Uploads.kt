package com.boko.roudio.transfer

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.boko.roudio.RoudioApp
import java.util.concurrent.TimeUnit

/**
 * The upload queue (spec §2, "Upload worker"): WorkManager runs [UploadWorker] whenever there's a
 * network, retries with growing gaps, and survives restarts. Ask for a run whenever a recording
 * reaches the phone; WorkManager keeps one chain, so runs never overlap.
 */
object Uploads {
    private const val WORK = "uploads"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE: a run already going finishes, then this one picks up anything new.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as RoudioApp
        if (!app.session.signedIn.value) return Result.success() // nothing can go up signed out
        return when (app.uploader.uploadAll()) {
            Uploader.Outcome.Done -> Result.success()
            Uploader.Outcome.Retry -> Result.retry()
        }
    }
}
