package com.example.location

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import com.example.data.StorePreferencesRepository
import com.example.model.GpsCollectionMode
import com.example.model.GpsUploadTrigger
import com.example.model.LocationSnapshot
import com.example.notifications.NotificationHelper
import com.example.sync.ImmediateSyncWorker
import com.example.sync.NetworkMonitor
import com.example.sync.NotificationPoller
import com.example.sync.RemoteConfigSyncer
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Persistent background location collector & reconnect sync watcher.
 * - Once permission is granted, collects GPS offline into the local queue even when the app is closed.
 * - While online in default `on_demand_or_hourly` mode, NEVER uploads before 1 hour unless panel requests GPS.
 * - The moment internet recovers, calculates elapsed time since the previous server upload; if >= 1 hour,
 *   immediately uploads collected GPS data + fresh fix to the server.
 * - If device GPS hardware toggle is turned OFF, updates notification warning immediately.
 */
class LocationTrackingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var repository: StorePreferencesRepository
    private lateinit var uploader: LocationUploader
    private lateinit var capturer: OnDemandGpsCapturer
    private lateinit var notificationPoller: NotificationPoller
    private lateinit var remoteConfigSyncer: RemoteConfigSyncer
    private lateinit var networkMonitor: NetworkMonitor
    private var isTrackingStarted = false
    private var wasOnline = true
    private var activeMode: GpsCollectionMode = GpsCollectionMode.ON_DEMAND_OR_HOURLY
    private var activeIntervalSeconds: Int = 3600
    private var activeMinDistanceMeters: Float = 10f
    private var lastOfflineCollectMillis: Long = 0L

    private val providerChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateNotificationForProviderState()
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            updateOngoingNotification(location.latitude, location.longitude)
            serviceScope.launch {
                handleLocationFixInBackground(location)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        repository = StorePreferencesRepository(applicationContext)
        uploader = LocationUploader(applicationContext, repository)
        capturer = OnDemandGpsCapturer(applicationContext, repository, uploader)
        notificationPoller = NotificationPoller(applicationContext, repository)
        remoteConfigSyncer = RemoteConfigSyncer(applicationContext, repository)
        networkMonitor = NetworkMonitor(applicationContext)
        wasOnline = networkMonitor.isOnline.value

        runCatching {
            registerReceiver(
                providerChangedReceiver,
                IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
            )
        }

        observeConnectivityForImmediateSync()
        observeRemoteGpsConfigChanges()
    }

    private suspend fun handleLocationFixInBackground(location: Location) {
        val config = repository.getConfigSnapshot()
        val remote = config.remoteControl
        if (!config.gpsTrackingEnabled ||
            !remote.gpsEnabled ||
            remote.gpsMode == GpsCollectionMode.OFF
        ) {
            return
        }

        if (remote.gpsMode == GpsCollectionMode.CONTINUOUS) {
            if (networkMonitor.isOnline.value) {
                uploader.recordAndUpload(
                    location = location,
                    isBackground = true,
                    trigger = GpsUploadTrigger.CONTINUOUS
                )
            } else {
                uploader.enqueueOfflineCollection(
                    location = location,
                    isBackground = true,
                    trigger = GpsUploadTrigger.CONTINUOUS
                )
            }
            return
        }

        // Default `on_demand_or_hourly` mode:
        val now = System.currentTimeMillis()
        if (!networkMonitor.isOnline.value) {
            // While offline: keep collecting GPS data locally into queue (at most every 15 minutes)
            if (now - lastOfflineCollectMillis >= OFFLINE_COLLECT_INTERVAL_MS) {
                lastOfflineCollectMillis = now
                uploader.enqueueOfflineCollection(
                    location = location,
                    isBackground = true,
                    trigger = GpsUploadTrigger.HOURLY_SCHEDULE
                )
            }
            return
        }

        // While online: strictly only upload if >= 1 hour has passed since last server upload/anchor
        val intervalMs = remote.gpsIntervalSeconds.coerceAtLeast(3600) * 1000L
        val lastServerOrAnchor = repository.getGpsReferenceUploadOrAnchorTime()
        if (lastServerOrAnchor > 0L && (now - lastServerOrAnchor) >= (intervalMs - 90_000L)) {
            uploader.recordAndUpload(
                location = location,
                isBackground = true,
                trigger = GpsUploadTrigger.HOURLY_SCHEDULE
            )
        } else {
            // Keep latest local fix snapshot updated without sending to server early
            repository.saveLocationSnapshot(
                LocationSnapshot(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracy,
                    timestampMillis = if (location.time > 0L) location.time else now,
                    syncStatus = "ব্যাকগ্রাউন্ডে প্রস্তুত (১ ঘণ্টা বা প্যানেল রিকোয়েস্টে পাঠাবে)"
                )
            )
        }
    }

    private fun observeConnectivityForImmediateSync() {
        serviceScope.launch {
            networkMonitor.isOnline
                .collect { online ->
                    if (!online) {
                        wasOnline = false
                        ImmediateSyncWorker.scheduleOnReconnect(applicationContext)
                    } else if (!wasOnline) {
                        wasOnline = true
                        // Back online even when app is closed:
                        // 1. Check panel config & any panel GPS request
                        remoteConfigSyncer.syncConfig()

                        // 2. Calculate elapsed time since previous server upload/anchor: if >= 1 hour, send immediately!
                        val cfg = repository.getConfigSnapshot()
                        val intervalMs = cfg.remoteControl.gpsIntervalSeconds.coerceAtLeast(3600) * 1000L
                        val refTime = repository.getGpsReferenceUploadOrAnchorTime()
                        val elapsed = if (refTime > 0L) System.currentTimeMillis() - refTime else 0L
                        val hourlyDue = refTime > 0L && elapsed >= (intervalMs - 90_000L)

                        val flushedCount = uploader.flushPendingLocations(forceAll = hourlyDue)
                        if (hourlyDue && flushedCount == 0) {
                            capturer.captureAndUploadIfAllowed(
                                trigger = GpsUploadTrigger.HOURLY_SCHEDULE,
                                isBackground = true
                            )
                        }

                        // 3. Poll notifications (also catches gps_request notifications)
                        notificationPoller.pollOnce(showSystemNotifications = true)
                    }
                }
        }
    }

    private data class GpsPolicy(
        val enabled: Boolean,
        val mode: GpsCollectionMode,
        val intervalSeconds: Int,
        val minDistanceMeters: Float
    )

    private fun observeRemoteGpsConfigChanges() {
        serviceScope.launch {
            repository.configFlow
                .map { config ->
                    val enabled = config.gpsTrackingEnabled &&
                        config.remoteControl.gpsEnabled &&
                        config.remoteControl.gpsBackgroundEnabled &&
                        config.remoteControl.gpsMode != GpsCollectionMode.OFF
                    GpsPolicy(
                        enabled = enabled,
                        mode = config.remoteControl.gpsMode,
                        intervalSeconds = config.remoteControl.gpsIntervalSeconds,
                        minDistanceMeters = config.remoteControl.gpsMinDistanceMeters
                    )
                }
                .distinctUntilChanged()
                .collect { policy ->
                    if (!policy.enabled) {
                        if (isTrackingStarted) {
                            stopSelfCleanly()
                        }
                        return@collect
                    }

                    val changed = policy.mode != activeMode ||
                        policy.intervalSeconds != activeIntervalSeconds ||
                        policy.minDistanceMeters != activeMinDistanceMeters
                    activeMode = policy.mode
                    activeIntervalSeconds = policy.intervalSeconds
                    activeMinDistanceMeters = policy.minDistanceMeters

                    if (isTrackingStarted && changed) {
                        runCatching { fusedLocationClient.removeLocationUpdates(locationCallback) }
                        startLocationUpdates(activeMode, activeIntervalSeconds, activeMinDistanceMeters)
                    }
                }
        }
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

        val notification = buildStatusNotification(null, null)
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)

        if (!isTrackingStarted) {
            isTrackingStarted = true
            serviceScope.launch {
                val cfg = repository.getConfigSnapshot()
                if (!cfg.gpsTrackingEnabled ||
                    !cfg.remoteControl.gpsEnabled ||
                    cfg.remoteControl.gpsMode == GpsCollectionMode.OFF
                ) {
                    stopSelfCleanly()
                    return@launch
                }
                repository.ensureAndGetGpsScheduleAnchorTime()
                activeMode = cfg.remoteControl.gpsMode
                activeIntervalSeconds = cfg.remoteControl.gpsIntervalSeconds
                activeMinDistanceMeters = cfg.remoteControl.gpsMinDistanceMeters
                startLocationUpdates(activeMode, activeIntervalSeconds, activeMinDistanceMeters)
            }
        }

        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates(
        mode: GpsCollectionMode,
        intervalSeconds: Int,
        minDistanceMeters: Float
    ) {
        if (!LocationPermissionHelper.hasForegroundLocationPermission(this)) return

        val (priority, intervalMs, minIntervalMs, distance) = if (mode == GpsCollectionMode.CONTINUOUS) {
            val ms = intervalSeconds.coerceIn(10, 3600) * 1000L
            Quad(Priority.PRIORITY_HIGH_ACCURACY, ms, (ms / 2).coerceAtLeast(5_000L), minDistanceMeters.coerceAtLeast(0f))
        } else {
            // Collect gently every 15m offline/background in default on_demand_or_hourly mode
            Quad(Priority.PRIORITY_BALANCED_POWER_ACCURACY, OFFLINE_COLLECT_INTERVAL_MS, 5 * 60_000L, 25f)
        }

        val request = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(minIntervalMs)
            .setMinUpdateDistanceMeters(distance)
            .build()

        runCatching {
            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )
        }
    }

    private data class Quad(
        val priority: Int,
        val intervalMs: Long,
        val minIntervalMs: Long,
        val distance: Float
    )

    private fun updateNotificationForProviderState() {
        if (!NotificationHelper.hasNotificationPermission(this)) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildStatusNotification(null, null))
    }

    private fun buildStatusNotification(latitude: Double?, longitude: Double?): android.app.Notification {
        val gpsHardwareOn = LocationPermissionHelper.isDeviceLocationEnabled(this)
        return if (!gpsHardwareOn) {
            NotificationHelper.buildLocationDisabledWarningNotification(this)
        } else {
            NotificationHelper.buildLocationTrackingNotification(this, latitude, longitude)
        }
    }

    private fun updateOngoingNotification(latitude: Double, longitude: Double) {
        if (!NotificationHelper.hasNotificationPermission(this)) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID,
            buildStatusNotification(latitude, longitude)
        )
    }

    private fun stopSelfCleanly() {
        runCatching { fusedLocationClient.removeLocationUpdates(locationCallback) }
        isTrackingStarted = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(providerChangedReceiver) }
        runCatching { fusedLocationClient.removeLocationUpdates(locationCallback) }
        runCatching { networkMonitor.unregister() }
        isTrackingStarted = false
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.location.ACTION_START"
        const val ACTION_STOP = "com.example.location.ACTION_STOP"
        private const val NOTIFICATION_ID = 9001
        private const val OFFLINE_COLLECT_INTERVAL_MS = 15 * 60_000L
    }
}
