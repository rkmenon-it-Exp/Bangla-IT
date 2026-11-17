package com.example.location

import android.content.Context
import android.location.Location
import android.webkit.CookieManager
import com.example.data.StorePreferencesRepository
import com.example.model.LocationSnapshot
import com.example.sync.ImmediateSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    suspend fun recordAndUpload(
        location: Location,
        isBackground: Boolean
    ): LocationSnapshot = withContext(Dispatchers.IO) {
        val config = repository.getConfigSnapshot()
        val now = if (location.time > 0L) location.time else System.currentTimeMillis()
        val effectiveCookie = resolveEffectiveCookie(config.baseUrl)

        val payload = JSONObject().apply {
            put("user_id", config.userId)
            put("user_name", config.userName)
            put("role", config.userRole.key)
            put("is_logged_in", config.isLoggedIn || config.userId.isNotBlank() || effectiveCookie.isNotBlank())
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("accuracy", location.accuracy.toDouble())
            put("speed", if (location.hasSpeed()) location.speed.toDouble() else 0.0)
            put("bearing", if (location.hasBearing()) location.bearing.toDouble() else 0.0)
            put("timestamp", now)
            put("is_background", isBackground)
        }

        // First flush any previously queued offline points if possible
        flushPendingLocations()

        val uploadResult = postPayload(config.locationApiUrl, effectiveCookie, payload)
        val status = when {
            uploadResult.success -> "সার্ভারে সিঙ্ক হয়েছে (${config.userId.ifBlank { "Session" }})"
            uploadResult.httpCode == 404 -> "লোকেশন সংরক্ষিত (API 404 — api/location.php অপেক্ষমাণ)"
            else -> {
                repository.enqueuePendingLocation(payload)
                ImmediateSyncWorker.scheduleOnReconnect(context)
                "অফলাইনে সংরক্ষিত — অনলাইনে গেলেই সাথে সাথে সিঙ্ক হবে"
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

    suspend fun flushPendingLocations(): Int = withContext(Dispatchers.IO) {
        flushMutex.withLock {
            val pending = repository.getPendingLocations()
            if (pending.isEmpty()) return@withLock 0

            val config = repository.getConfigSnapshot()
            val cookie = resolveEffectiveCookie(config.baseUrl)
            val remaining = mutableListOf<JSONObject>()
            var uploadedCount = 0

            for ((index, item) in pending.withIndex()) {
                // Ensure latest user_id/cookie is attached if staff logged in before reconnect
                if (item.optString("user_id").isBlank() && config.userId.isNotBlank()) {
                    item.put("user_id", config.userId)
                }
                val result = postPayload(config.locationApiUrl, cookie, item)
                if (result.success || result.httpCode == 404) {
                    if (result.success) uploadedCount++
                } else {
                    // Network still down or server unreachable: keep this and subsequent items
                    for (j in index until pending.size) {
                        remaining.add(pending[j])
                    }
                    break
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
        payload: JSONObject
    ): UploadResponse {
        return try {
            val requestBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

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
