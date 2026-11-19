package com.example.location

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.StorePreferencesRepository
import com.example.model.GpsCollectionMode
import com.example.model.GpsUploadTrigger
import java.util.concurrent.TimeUnit

class LocationSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val repository = StorePreferencesRepository(applicationContext)
        val config = repository.getConfigSnapshot()
        if (!config.isCustomStoreBound ||
            !config.gpsTrackingEnabled ||
            !config.remoteControl.gpsEnabled ||
            !config.remoteControl.gpsBackgroundEnabled ||
            config.remoteControl.gpsMode == GpsCollectionMode.OFF
        ) {
            return Result.success()
        }
        if (!LocationPermissionHelper.hasForegroundLocationPermission(applicationContext) ||
            !LocationPermissionHelper.isDeviceLocationEnabled(applicationContext)
        ) {
            return Result.success()
        }

        // Works even offline when app is closed (queues locally until online + eligible)
        val capturer = OnDemandGpsCapturer(applicationContext, repository)
        capturer.captureAndUploadIfAllowed(
            trigger = GpsUploadTrigger.HOURLY_SCHEDULE,
            isBackground = true
        )
        return Result.success()
    }

    companion object {
        private const val UNIQUE_LOCATION_WORK = "bangla_store_hourly_gps_sync"

        fun schedulePeriodicLocationSync(
            context: Context,
            enabled: Boolean,
            intervalHours: Long = 1L
        ) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork("bangla_store_periodic_gps_sync")

            if (!enabled) {
                workManager.cancelUniqueWork(UNIQUE_LOCATION_WORK)
                return
            }

            val safeHours = intervalHours.coerceAtLeast(1L)
            // Network NOT_REQUIRED so offline GPS collection continues even when app is closed & offline!
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val request = PeriodicWorkRequestBuilder<LocationSyncWorker>(
                safeHours,
                TimeUnit.HOURS
            )
                .setInitialDelay(safeHours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            workManager.enqueueUniquePeriodicWork(
                UNIQUE_LOCATION_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
