package com.example.location

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import com.example.data.StorePreferencesRepository
import com.example.notifications.NotificationHelper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LocationTrackingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var repository: StorePreferencesRepository
    private lateinit var uploader: LocationUploader
    private var isTrackingStarted = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            updateOngoingNotification(location.latitude, location.longitude)
            serviceScope.launch {
                uploader.recordAndUpload(location = location, isBackground = true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        repository = StorePreferencesRepository(applicationContext)
        uploader = LocationUploader(applicationContext, repository)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        if (!LocationPermissionHelper.hasForegroundLocationPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = NotificationHelper.buildLocationTrackingNotification(this)
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)

        if (!isTrackingStarted) {
            isTrackingStarted = true
            startLocationUpdates()
            fetchImmediateFix()
        }

        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!LocationPermissionHelper.hasForegroundLocationPermission(this)) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 30_000L)
            .setMinUpdateIntervalMillis(15_000L)
            .setMinUpdateDistanceMeters(10f)
            .build()

        runCatching {
            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchImmediateFix() {
        if (!LocationPermissionHelper.hasForegroundLocationPermission(this)) return
        val cancellationSource = CancellationTokenSource()
        runCatching {
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationSource.token
            ).addOnSuccessListener { location ->
                if (location != null) {
                    updateOngoingNotification(location.latitude, location.longitude)
                    serviceScope.launch {
                        uploader.recordAndUpload(location = location, isBackground = false)
                    }
                }
            }
        }
    }

    private fun updateOngoingNotification(latitude: Double, longitude: Double) {
        if (!NotificationHelper.hasNotificationPermission(this)) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID,
            NotificationHelper.buildLocationTrackingNotification(this, latitude, longitude)
        )
    }

    private fun stopSelfCleanly() {
        runCatching { fusedLocationClient.removeLocationUpdates(locationCallback) }
        isTrackingStarted = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { fusedLocationClient.removeLocationUpdates(locationCallback) }
        isTrackingStarted = false
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.location.ACTION_START"
        const val ACTION_STOP = "com.example.location.ACTION_STOP"
        private const val NOTIFICATION_ID = 9001
    }
}
