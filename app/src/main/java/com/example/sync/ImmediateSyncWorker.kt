package com.example.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.StorePreferencesRepository
import com.example.location.LocationPermissionHelper
import com.example.location.LocationUploader
import com.example.location.OnDemandGpsCapturer
import com.example.model.GpsCollectionMode
import com.example.model.GpsUploadTrigger
import java.util.concurrent.TimeUnit

class ImmediateSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val repository = StorePreferencesRepository(applicationContext)
        val initial = repository.getConfigSnapshot()
        if (!initial.isCustomStoreBound) {
            return Result.success()
        }
        // 1. Fetch panel config & fulfill any explicit panel GPS request right away
        RemoteConfigSyncer(applicationContext, repository).syncConfig()
        val config = repository.getConfigSnapshot()
        if (!config.isCustomStoreBound) return Result.success()
        val uploader = LocationUploader(applicationContext, repository)

        // 2. Calculate elapsed time since previous server upload (or permission anchor):
        //    If >= 1 hour has passed while offline / app closed, flush & send GPS immediately upon reconnect!
        val refTime = repository.getGpsReferenceUploadOrAnchorTime()
        val intervalMs = config.remoteControl.gpsIntervalSeconds.coerceAtLeast(3600) * 1000L
        val hourlyDue = refTime > 0L && (System.currentTimeMillis() - refTime) >= (intervalMs - 90_000L)

        // Flush panel_request items always, and all offline-collected GPS items if >= 1 hour elapsed
        val flushed = uploader.flushPendingLocations(forceAll = hourlyDue)

        if (hourlyDue &&
            flushed == 0 &&
            config.gpsTrackingEnabled &&
            config.remoteControl.gpsEnabled &&
            config.remoteControl.gpsBackgroundEnabled &&
            config.remoteControl.gpsMode != GpsCollectionMode.OFF &&
            LocationPermissionHelper.hasForegroundLocationPermission(applicationContext) &&
            LocationPermissionHelper.isDeviceLocationEnabled(applicationContext)
        ) {
            OnDemandGpsCapturer(applicationContext, repository, uploader)
                .captureAndUploadIfAllowed(
                    trigger = GpsUploadTrigger.HOURLY_SCHEDULE,
                    isBackground = true
                )
        }

        // 3. Immediately poll server notifications (also catches gps_request notifications)
        if (config.backgroundSyncEnabled && config.remoteControl.backgroundSyncEnabled) {
            val poller = NotificationPoller(applicationContext, repository)
            poller.pollOnce(showSystemNotifications = true)
        }

        return Result.success()
    }

    companion object {
        private const val UNIQUE_RECONNECT_WORK = "bangla_store_immediate_online_sync"

        /**
         * Enqueues a WorkManager job constrained to NetworkType.CONNECTED.
         * Even if the app is completely closed/killed, Android OS triggers this worker
         * immediately as soon as internet connectivity is restored.
         */
        fun scheduleOnReconnect(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<ImmediateSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_RECONNECT_WORK,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
