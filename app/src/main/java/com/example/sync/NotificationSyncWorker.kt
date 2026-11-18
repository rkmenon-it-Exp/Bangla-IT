package com.example.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.StorePreferencesRepository
import java.util.concurrent.TimeUnit

class NotificationSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val repository = StorePreferencesRepository(applicationContext)
        RemoteConfigSyncer(applicationContext, repository).syncConfig()
        val config = repository.getConfigSnapshot()
        if (!config.backgroundSyncEnabled || !config.remoteControl.backgroundSyncEnabled) {
            return Result.success()
        }
        val poller = NotificationPoller(applicationContext, repository)
        val outcome = poller.pollOnce(showSystemNotifications = true)
        return if (outcome.success) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE_PERIODIC_WORK = "bangla_store_periodic_notification_sync"

        fun schedulePeriodicSync(context: Context, enabled: Boolean) {
            val workManager = WorkManager.getInstance(context)
            if (!enabled) {
                workManager.cancelUniqueWork(UNIQUE_PERIODIC_WORK)
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<NotificationSyncWorker>(
                15,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            workManager.enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun triggerImmediateSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<NotificationSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
