package com.example.sync

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
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
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class ImmediateSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val repository = StorePreferencesRepository(applicationContext)
        val config = repository.getConfigSnapshot()
        val uploader = LocationUploader(applicationContext, repository)

        // 1. Immediately flush any queued offline GPS locations
        uploader.flushPendingLocations()

        // 2. Capture & send a fresh GPS fix if tracking is enabled
        if (config.gpsTrackingEnabled &&
            LocationPermissionHelper.hasForegroundLocationPermission(applicationContext)
        ) {
            fetchCurrentOrLastLocation()?.let { loc ->
                uploader.recordAndUpload(location = loc, isBackground = true)
            }
        }

        // 3. Immediately poll server notifications so missed admin/staff alerts arrive right away
        if (config.backgroundSyncEnabled) {
            val poller = NotificationPoller(applicationContext, repository)
            poller.pollOnce(showSystemNotifications = true)
        }

        return Result.success()
    }

    @SuppressLint("MissingPermission")
    private suspend fun fetchCurrentOrLastLocation(): Location? {
        if (!LocationPermissionHelper.hasForegroundLocationPermission(applicationContext)) {
            return null
        }
        val client = LocationServices.getFusedLocationProviderClient(applicationContext)
        val fresh = suspendCancellableCoroutine<Location?> { cont ->
            val tokenSource = CancellationTokenSource()
            cont.invokeOnCancellation { tokenSource.cancel() }
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, tokenSource.token)
                .addOnSuccessListener { loc -> if (cont.isActive) cont.resume(loc) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
        if (fresh != null) return fresh

        return suspendCancellableCoroutine { cont ->
            client.lastLocation
                .addOnSuccessListener { loc -> if (cont.isActive) cont.resume(loc) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
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
