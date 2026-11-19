package com.example.location

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.webkit.CookieManager
import com.example.BuildConfig
import com.example.data.StorePreferencesRepository
import com.example.model.GpsCollectionMode
import com.example.model.GpsUploadTrigger
import com.example.model.LocationSnapshot
import com.example.sync.DeviceTelemetryHelper
import com.example.sync.ImmediateSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LocationUploader(
    private val context: Context,
    private val repository: StorePreferencesRepository
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /**
     * Collects GPS data locally when offline (or app closed & offline) without attempting network I/O,
     * queuing it for automatic sync when online + eligible (panel request or >= 1 hour elapsed).
     */
    suspend fun enqueueOfflineCollection(
        location: Location,
        isBackground: Boolean,
        trigger: GpsUploadTrigger = GpsUploadTrigger.HOURLY_SCHEDULE
    ): LocationSnapshot = withContext(Dispatchers.IO) {
        val config = repository.getConfigSnapshot()
        if (!config.isCustomStoreBound || config.locationApiUrl.isBlank()) {
            return@withContext repository.getLatestLocationSnapshot()
        }
        val deviceId = repository.getOrCreateUniqueDeviceId()
        val now = if (location.time > 0L) location.time else System.currentTimeMillis()
        val payload = buildPayload(
            location = location,
            isBackground = isBackground,
            trigger = trigger,
            requestId = "",
            timestampMillis = now,
            isLoggedIn = config.isLoggedIn || config.userId.isNotBlank(),
            panelId = config.panelId,
            storeCode = config.storeCode,
            deviceId = deviceId,
            userId = config.userId,
            userName = config.userName,
            roleKey = config.userRole.key
        )
        repository.markGpsUploadTime(System.currentTimeMillis())
        repository.enqueuePendingLocation(payload)
        ImmediateSyncWorker.scheduleOnReconnect(context)

        val snapshot = LocationSnapshot(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy,
            timestampMillis = now,
            syncStatus = "অফলাইনে সংরক্ষিত — অনলাইনে গেলেই নিয়মানুযায়ী সিঙ্ক হবে"
        )
        repository.saveLocationSnapshot(snapshot)
        snapshot
    }

    suspend fun recordAndUpload(
        location: Location,
        isBackground: Boolean,
        trigger: GpsUploadTrigger = GpsUploadTrigger.PANEL_REQUEST,
        requestId: String = ""
    ): LocationSnapshot = withContext(Dispatchers.IO) {
        val config = repository.getConfigSnapshot()
        val remote = config.remoteControl
        if (!config.isCustomStoreBound ||
            config.locationApiUrl.isBlank() ||
            !config.gpsTrackingEnabled ||
            !remote.gpsEnabled ||
            remote.gpsMode == GpsCollectionMode.OFF
        ) {
            return@withContext repository.getLatestLocationSnapshot()
        }

        // Strict guard: do not upload continuous unsolicited fixes unless panel opted into CONTINUOUS mode
        if (trigger == GpsUploadTrigger.CONTINUOUS && remote.gpsMode != GpsCollectionMode.CONTINUOUS) {
            return@withContext repository.getLatestLocationSnapshot()
        }

        val now = if (location.time > 0L) location.time else System.currentTimeMillis()
        val effectiveCookie = resolveEffectiveCookie(config.baseUrl)
        val deviceId = repository.getOrCreateUniqueDeviceId()

        val payload = buildPayload(
            location = location,
            isBackground = isBackground,
            trigger = trigger,
            requestId = requestId,
            timestampMillis = now,
            isLoggedIn = config.isLoggedIn || config.userId.isNotBlank() || effectiveCookie.isNotBlank(),
            panelId = config.panelId,
            storeCode = config.storeCode,
            deviceId = deviceId,
            userId = config.userId,
            userName = config.userName,
            roleKey = config.userRole.key
        )

        repository.markGpsUploadTime(System.currentTimeMillis())

        // Flush any previously queued offline points first if eligible
        flushPendingLocations(forceAll = trigger != GpsUploadTrigger.CONTINUOUS)

        val uploadResult = postPayload(config.locationApiUrl, effectiveCookie, payload)
        if (uploadResult.success) {
            repository.markGpsServerUploadTime(System.currentTimeMillis())
        }

        val triggerLabel = when (trigger) {
            GpsUploadTrigger.PANEL_REQUEST -> "প্যানেল রিকোয়েস্টে GPS পাঠানো হয়েছে"
            GpsUploadTrigger.HOURLY_SCHEDULE -> "১ ঘণ্টার নিয়মিত GPS পাঠানো হয়েছে"
            GpsUploadTrigger.CONTINUOUS -> "সার্ভারে সিঙ্ক হয়েছে"
        }
        val status = when {
            uploadResult.success -> "$triggerLabel (${config.userId.ifBlank { "Session" }})"
            uploadResult.httpCode == 404 -> "লোকেশন সংরক্ষিত (API 404 — api/location.php অপেক্ষমাণ)"
            else -> {
                repository.enqueuePendingLocation(payload)
                ImmediateSyncWorker.scheduleOnReconnect(context)
                "অফলাইনে সংরক্ষিত — অনলাইনে গেলেই সিঙ্ক হবে"
            }
        }

        val snapshot = LocationSnapshot(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy,
            timestampMillis = now,
            syncStatus = status
        )
        repository.saveLocationSnapshot(snapshot)
        snapshot
    }

    suspend fun flushPendingLocations(forceAll: Boolean = false): Int = withContext(Dispatchers.IO) {
        flushMutex.withLock {
            val pending = repository.getPendingLocations()
            if (pending.isEmpty()) return@withLock 0

            val config = repository.getConfigSnapshot()
            if (!config.isCustomStoreBound || config.locationApiUrl.isBlank()) {
                return@withLock 0
            }
            val deviceId = repository.getOrCreateUniqueDeviceId()
            val cookie = resolveEffectiveCookie(config.baseUrl)
            val remaining = mutableListOf<JSONObject>()
            val eligible = mutableListOf<JSONObject>()
            var uploadedCount = 0

            val now = System.currentTimeMillis()
            val lastServerUpload = repository.getGpsReferenceUploadOrAnchorTime()
            val intervalMs = config.remoteControl.gpsIntervalSeconds.coerceAtLeast(3600) * 1000L
            val hourlyWindowElapsed = lastServerUpload > 0L && (now - lastServerUpload) >= (intervalMs - 90_000L)
            val allowAllItems = forceAll ||
                hourlyWindowElapsed ||
                config.remoteControl.gpsMode == GpsCollectionMode.CONTINUOUS

            for (item in pending) {
                val itemTrigger = item.optString("trigger", GpsUploadTrigger.HOURLY_SCHEDULE.key)
                val canUploadThisItem = allowAllItems ||
                    itemTrigger.equals(GpsUploadTrigger.PANEL_REQUEST.key, ignoreCase = true)

                if (!canUploadThisItem) {
                    remaining.add(item)
                    continue
                }

                if (item.optString("panel_id").isBlank() && config.panelId.isNotBlank()) {
                    item.put("panel_id", config.panelId)
                }
                if (item.optString("store_code").isBlank() && config.storeCode.isNotBlank()) {
                    item.put("store_code", config.storeCode)
                }
                if (item.optString("user_id").isBlank() && config.userId.isNotBlank()) {
                    item.put("user_id", config.userId)
                }
                if (item.optString("device_id").isBlank()) {
                    item.put("device_id", deviceId)
                }
                eligible.add(item)
            }

            if (eligible.isNotEmpty()) {
                // Prefer Antigravity batch endpoint {store_url}/api/sync_gps.php first
                val batchPayload = JSONObject().apply {
                    put("panel_id", config.panelId)
                    put("store_code", config.storeCode)
                    put("user_id", config.userId)
                    put("role", config.userRole.key)
                    put("device_id", deviceId)
                    val arr = JSONArray()
                    eligible.forEach { arr.put(it) }
                    put("locations", arr)
                }
                val batchResult = postPayload(config.syncGpsApiUrl, cookie, batchPayload, deviceId)
                if (batchResult.success) {
                    uploadedCount = eligible.size
                    repository.markGpsServerUploadTime(System.currentTimeMillis())
                } else if (batchResult.httpCode == 404 || batchResult.httpCode == 405) {
                    // Fallback to individual POSTs to {store_url}/api/location.php for older backends
                    for ((index, item) in eligible.withIndex()) {
                        val result = postPayload(config.locationApiUrl, cookie, item, deviceId)
                        if (result.success || result.httpCode == 404) {
                            if (result.success) {
                                uploadedCount++
                                repository.markGpsServerUploadTime(System.currentTimeMillis())
                            }
                        } else {
                            for (j in index until eligible.size) {
                                remaining.add(eligible[j])
                            }
                            break
                        }
                    }
                } else {
                    // Transient network failure: keep all eligible items in queue
                    remaining.addAll(eligible)
                }
            }

            repository.setPendingLocations(remaining)
            if (uploadedCount > 0) {
                val current = repository.getLatestLocationSnapshot()
                if (current.hasFix) {
                    repository.saveLocationSnapshot(
                        current.copy(syncStatus = "$uploadedCount টি অফলাইন লোকেশন সিঙ্ক সম্পন্ন")
                    )
                }
            }
            uploadedCount
        }
    }

    private fun buildPayload(
        location: Location,
        isBackground: Boolean,
        trigger: GpsUploadTrigger,
        requestId: String,
        timestampMillis: Long,
        isLoggedIn: Boolean,
        panelId: String,
        storeCode: String,
        deviceId: String,
        userId: String,
        userName: String,
        roleKey: String
    ): JSONObject {
        val batteryPct = readBatteryLevelPercent()
        return JSONObject().apply {
            put("panel_id", panelId)
            if (storeCode.isNotBlank()) put("store_code", storeCode)
            put("device_id", deviceId)
            put("user_id", userId)
            put("user_name", userName)
            put("role", roleKey)
            put("is_logged_in", isLoggedIn)
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("accuracy", location.accuracy.toDouble())
            put("speed", if (location.hasSpeed()) location.speed.toDouble() else 0.0)
            put("bearing", if (location.hasBearing()) location.bearing.toDouble() else 0.0)
            put("battery_level", batteryPct)
            put("timestamp", timestampMillis)
            put("is_background", isBackground)
            put("trigger", trigger.key)
            put("trigger_type", trigger.key)
            if (requestId.isNotBlank()) {
                put("request_id", requestId.trim())
            }
        }
    }

    private fun readBatteryLevelPercent(): Int {
        return runCatching {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else -1
        }.getOrDefault(-1)
    }

    private suspend fun resolveEffectiveCookie(baseUrl: String): String {
        val webViewCookie = runCatching {
            CookieManager.getInstance().getCookie(baseUrl)
        }.getOrNull().orEmpty()
        val storedCookie = repository.getSessionCookie()
        if (webViewCookie.isNotBlank() && webViewCookie != storedCookie) {
            repository.saveSessionCookie(webViewCookie)
        }
        return webViewCookie.ifBlank { storedCookie }
    }

    private fun postPayload(
        url: String,
        cookie: String,
        payload: JSONObject,
        deviceId: String = ""
    ): UploadResponse {
        return try {
            val requestBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

            DeviceTelemetryHelper.buildRequestHeaders(context, deviceId).forEach { (k, v) ->
                requestBuilder.header(k, v)
            }
            if (cookie.isNotBlank()) {
                requestBuilder.header("Cookie", cookie)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                UploadResponse(success = response.isSuccessful, httpCode = response.code)
            }
        } catch (_: Exception) {
            UploadResponse(success = false, httpCode = -1)
        }
    }

    private data class UploadResponse(val success: Boolean, val httpCode: Int)

    companion object {
        private val flushMutex = Mutex()
    }
}
