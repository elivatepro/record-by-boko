package com.boko.roudio.data

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.boko.roudio.RoudioApp
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

class PrefsSyncCursor(context: Context) : SyncCursor {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    override var since: OffsetDateTime?
        get() = prefs.getString(KEY, null)?.let(OffsetDateTime::parse)
        set(value) = prefs.edit { putString(KEY, value?.toString()) }

    private companion object {
        const val KEY = "since"
    }
}

/** Runs [Sync] every 15 minutes with a network, and right away when asked. */
object SyncWork {
    private const val PERIODIC = "sync-periodic"
    private const val NOW = "sync-now"
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).build(),
        )
    }

    fun now(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(online).build(),
        )
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as RoudioApp
        if (!app.session.signedIn.value) return Result.success()
        return if (app.sync.run()) Result.success() else Result.retry()
    }
}
