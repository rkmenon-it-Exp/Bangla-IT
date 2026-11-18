package com.example.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.data.StorePreferencesRepository
import com.example.model.GpsCollectionMode
import com.example.model.GpsUploadTrigger
import com.example.model.LocationSnapshot
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

class OnDemandGpsCapturer(
    private val context: Context,
    private val repository: StorePreferencesRepository,
    private val uploader: LocationUploader = LocationUploader(context, repository)
) {
    suspend fun captureAndUploadIfAllowed(
        trigger: GpsUploadTrigger,
        requestId: String = "",
        isBackground: Boolean = true
    ): LocationSnapshot? = captureMutex.withLock {
        val config = repository.getConfigSnapshot()
        val remote = config.remoteControl

        if (!config.gpsTrackingEnabled ||
            !remote.gpsEnabled ||
            remote.gpsMode == GpsCollectionMode.OFF
        ) {
            return@withLock null
        }

        if (!LocationPermissionHelper.hasForegroundLocationPermission(context) ||
            !LocationPermissionHelper.isDeviceLocationEnabled(context)
        ) {
            return@withLock null
        }

        val cleanRequestId = requestId.trim()
        val isOnline = isNetworkAvailable()

        when (trigger) {
            GpsUploadTrigger.PANEL_REQUEST -> {
                if (cleanRequestId.isNotBlank() && repository.isGpsRequestFulfilled(cleanRequestId)) {
                    return@withLock null
                }
            }
            GpsUploadTrigger.HOURLY_SCHEDULE -> {
                val intervalMs = remote.gpsIntervalSeconds.coerceAtLeast(3600) * 1000L
                val now = System.currentTimeMillis()
                if (isOnline) {
                    val lastServerOrAnchor = repository.getGpsReferenceUploadOrAnchorTime()
                    if (lastServerOrAnchor > 0L && (now - lastServerOrAnchor) < (intervalMs - 90_000L)) {
                        return@withLock null
                    }
                } else {
                    // While offline, still collect offline GPS data on schedule
                    val lastCollect = repository.getLastGpsUploadTime()
                    if (lastCollect > 0L && (now - lastCollect) < (intervalMs - 90_000L)) {
                        return@withLock null
                    }
                }
            }
            GpsUploadTrigger.CONTINUOUS -> {
                if (remote.gpsMode != GpsCollectionMode.CONTINUOUS) {
                    return@withLock null
                }
            }
        }

        val location = fetchCurrentOrLastLocation(
            highAccuracy = trigger == GpsUploadTrigger.PANEL_REQUEST
        ) ?: return@withLock null

        val snapshot = if (!isOnline) {
            uploader.enqueueOfflineCollection(
                location = location,
                isBackground = isBackground,
                trigger = trigger
            )
        } else {
            uploader.recordAndUpload(
                location = location,
                isBackground = isBackground,
                trigger = trigger,
                requestId = cleanRequestId
            )
        }

        if (trigger == GpsUploadTrigger.PANEL_REQUEST && cleanRequestId.isNotBlank()) {
            repository.markGpsRequestFulfilled(cleanRequestId)
        }
        snapshot
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @SuppressLint("MissingPermission")
    private suspend fun fetchCurrentOrLastLocation(highAccuracy: Boolean): Location? {
        if (!LocationPermissionHelper.hasForegroundLocationPermission(context)) {
            return null
        }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val priority = if (highAccuracy) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val fresh = suspendCancellableCoroutine<Location?> { cont ->
            val tokenSource = CancellationTokenSource()
            cont.invokeOnCancellation { tokenSource.cancel() }
            client.getCurrentLocation(priority, tokenSource.token)
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
        private val captureMutex = Mutex()
    }
}
