package com.example.location

import android.content.Context
import android.location.Location
import android.webkit.CookieManager
import com.example.data.StorePreferencesRepository
import com.example.model.LocationSnapshot
import kotlinx.coroutines.Dispatchers
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

        val webViewCookie = runCatching {
            CookieManager.getInstance().getCookie(config.baseUrl)
        }.getOrNull().orEmpty()
        val storedCookie = repository.getSessionCookie()
        val effectiveCookie = webViewCookie.ifBlank { storedCookie }
        if (webViewCookie.isNotBlank() && webViewCookie != storedCookie) {
            repository.saveSessionCookie(webViewCookie)
        }

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

        val status = try {
            val requestBuilder = Request.Builder()
                .url(config.locationApiUrl)
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

            if (effectiveCookie.isNotBlank()) {
                requestBuilder.header("Cookie", effectiveCookie)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                when {
                    response.isSuccessful -> "সার্ভারে সিঙ্ক হয়েছে (${config.userId.ifBlank { "Session" }})"
                    response.code == 404 -> "লোকেশন সংরক্ষিত (API 404 — api/location.php অপেক্ষমাণ)"
                    else -> "সার্ভার রেসপন্স HTTP ${response.code}"
                }
            }
        } catch (ex: Exception) {
            "লোকেশন সংরক্ষিত (নেটওয়ার্ক ত্রুটি: ${ex.localizedMessage ?: "Offline"})"
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
}
