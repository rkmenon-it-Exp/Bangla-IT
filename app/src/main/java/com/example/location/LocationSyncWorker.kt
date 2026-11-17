package com.example.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.StorePreferencesRepository
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class LocationSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val repository = StorePreferencesRepository(applicationContext)
        val config = repository.getConfigSnapshot()
        if (!config.gpsTrackingEnabled) {
            return Result.success()
        }
        if (!LocationPermissionHelper.hasForegroundLocationPermission(applicationContext)) {
            return Result.success()
        }

        val location = fetchCurrentOrLastLocation() ?: return Result.retry()
        val uploader = LocationUploader(applicationContext, repository)
        uploader.recordAndUpload(location = location, isBackground = true)
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
                .addOnSuccessListener { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
        }
        if (fresh != null) return fresh

        return suspendCancellableCoroutine { cont ->
            client.lastLocation
                .addOnSuccessListener { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
        }
    }

    companion object {
        private const val UNIQUE_LOCATION_WORK = "bangla_store_periodic_gps_sync"

        fun schedulePeriodicLocationSync(context: Context, enabled: Boolean) {
            val workManager = WorkManager.getInstance(context)
            if (!enabled) {
                workManager.cancelUniqueWork(UNIQUE_LOCATION_WORK)
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<LocationSyncWorker>(
                15,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            workManager.enqueueUniquePeriodicWork(
                UNIQUE_LOCATION_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
