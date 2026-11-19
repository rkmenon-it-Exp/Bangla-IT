package com.example.sync

import android.content.Context
import android.net.Uri
import android.os.Build
import com.example.BuildConfig
import com.example.data.StorePreferencesRepository
import com.example.model.RemoteControlSettings
import com.example.model.defaultBottomTabs
import com.example.model.defaultTopTabs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

sealed interface ShopHandshakeResult {
    data class Success(
        val boundStoreUrl: String,
        val storeCode: String,
        val storeName: String,
        val appTitle: String,
        val logoUrl: String,
        val themeColorHex: String,
        val panelId: String,
        val baseUrl: String,
        val attendanceUrl: String,
        val tasksUrl: String,
        val notificationApiUrl: String,
        val locationApiUrl: String,
        val syncGpsApiUrl: String,
        val configApiUrl: String,
        val remoteControl: RemoteControlSettings
    ) : ShopHandshakeResult

    data class Failure(val errorMessageBn: String) : ShopHandshakeResult
}

/**
 * Performs Zero-Credential Dynamic URL Handshake against `{store_url}/api/app_handshake.php`
 * (with graceful fallback to `{store_url}/api/app_config.php`), downloads store branding,
 * tabs, and control panel settings, and prepares the app for clean tenant re-binding.
 */
class ShopHandshakeManager(
    private val context: Context,
    private val repository: StorePreferencesRepository
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun performHandshake(rawStoreUrl: String): ShopHandshakeResult = withContext(Dispatchers.IO) {
        val normalizedBase = normalizeStoreBaseUrl(rawStoreUrl)
            ?: return@withContext ShopHandshakeResult.Failure(
                "সঠিক শপের URL দিন (যেমন: https://banglait.net/shop/store1)"
            )

        val deviceId = repository.getOrCreateUniqueDeviceId()
        val handshakeUrl = "$normalizedBase/api/app_handshake.php"
        val configUrl = "$normalizedBase/api/app_config.php"

        // 1. Try primary Antigravity endpoint: {store_url}/api/app_handshake.php
        val handshakeFetch = fetchJsonObject(handshakeUrl, deviceId)
        val primaryObj: JSONObject = when (handshakeFetch) {
            is FetchOutcome.Ok -> {
                if (handshakeFetch.json.has("success") && !handshakeFetch.json.optBoolean("success", true)) {
                    val serverMsg = handshakeFetch.json.optString("message")
                        .ifBlank { handshakeFetch.json.optString("error") }
                        .trim()
                    return@withContext ShopHandshakeResult.Failure(
                        serverMsg.ifBlank { "শপ সার্ভার এই URL-এর জন্য বাইন্ডিং অনুমোদন করেনি।" }
                    )
                }
                handshakeFetch.json
            }
            is FetchOutcome.NotFound -> {
                // Graceful fallback: try {store_url}/api/app_config.php directly
                when (val cfgFallback = fetchJsonObject(configUrl, deviceId)) {
                    is FetchOutcome.Ok -> cfgFallback.json
                    is FetchOutcome.NotFound -> return@withContext ShopHandshakeResult.Failure(
                        "এই URL-এ শপ API (app_handshake.php / app_config.php) পাওয়া যায়নি (404)। URL টি যাচাই করুন।"
                    )
                    is FetchOutcome.Error -> return@withContext ShopHandshakeResult.Failure(cfgFallback.messageBn)
                }
            }
            is FetchOutcome.Error -> return@withContext ShopHandshakeResult.Failure(handshakeFetch.messageBn)
        }

        // 2. If handshake succeeded, optionally fetch full control panel settings from app_config.php
        val panelIdInitial = primaryObj.optString("panel_id").trim()
            .ifBlank { primaryObj.optString("store_code").trim() }
        val extraConfigObj: JSONObject? = if (handshakeFetch is FetchOutcome.Ok) {
            val fullCfgUrl = Uri.parse(configUrl)
                .buildUpon()
                .apply {
                    if (panelIdInitial.isNotBlank()) {
                        appendQueryParameter("panel_id", panelIdInitial)
                        appendQueryParameter("store_code", panelIdInitial)
                    }
                }
                .build()
                .toString()
            when (val extra = fetchJsonObject(fullCfgUrl, deviceId)) {
                is FetchOutcome.Ok -> extra.json
                else -> null
            }
        } else {
            null
        }

        val mergedSource = mergeHandshakeAndConfig(primaryObj, extraConfigObj)
        val urlOverrides = RemoteConfigSyncer.extractUrlOverrides(mergedSource, normalizedBase)

        val storeCode = primaryObj.optString("store_code").trim()
            .ifBlank { extraConfigObj?.optString("store_code").orEmpty().trim() }
            .ifBlank { panelIdInitial }
        val storeName = primaryObj.optString("store_name").trim()
            .ifBlank { extraConfigObj?.optString("store_name").orEmpty().trim() }
            .ifBlank { primaryObj.optString("panel_name").trim() }
            .ifBlank { extraConfigObj?.optString("panel_name").orEmpty().trim() }
        val appTitle = primaryObj.optString("app_title").trim()
            .ifBlank { extraConfigObj?.optString("app_title").orEmpty().trim() }
            .ifBlank { storeName }
        val rawLogoUrl = primaryObj.optString("logo_url").trim()
            .ifBlank { extraConfigObj?.optString("logo_url").orEmpty().trim() }
        val logoUrl = if (rawLogoUrl.isNotBlank()) {
            RemoteConfigSyncer.resolveUrlAgainstBase(rawLogoUrl, normalizedBase)
        } else {
            ""
        }
        val themeColorHex = primaryObj.optString("theme_color").trim()
            .ifBlank { extraConfigObj?.optString("theme_color").orEmpty().trim() }
        val panelId = primaryObj.optString("panel_id").trim()
            .ifBlank { extraConfigObj?.optString("panel_id").orEmpty().trim() }
            .ifBlank { storeCode }

        val resolvedBaseUrl = urlOverrides["base_url"] ?: normalizedBase
        val resolvedAttendanceUrl = urlOverrides["attendance_url"]
            ?: "$resolvedBaseUrl/dashboard.php?staff_view=attendance"
        val resolvedTasksUrl = urlOverrides["tasks_url"]
            ?: "$resolvedBaseUrl/tasks.php"
        val resolvedNotifApiUrl = urlOverrides["notification_api_url"]
            ?: "$resolvedBaseUrl/api/notifications.php"
        val resolvedLocApiUrl = urlOverrides["location_api_url"]
            ?: "$resolvedBaseUrl/api/location.php"
        val resolvedSyncGpsApiUrl = urlOverrides["sync_gps_api_url"]
            ?: "$resolvedBaseUrl/api/sync_gps.php"
        val resolvedConfigApiUrl = urlOverrides["config_api_url"]
            ?: "$resolvedBaseUrl/api/app_config.php"

        val primaryLabel = appTitle.ifBlank { storeName.ifBlank { "বাংলা স্টোর" } }
        val parsedControl = RemoteConfigSyncer.parseRemoteControlSettings(
            sourceObj = mergedSource,
            fallbackBaseUrl = resolvedBaseUrl,
            fallbackAttendanceUrl = resolvedAttendanceUrl,
            fallbackTasksUrl = resolvedTasksUrl,
            fallbackPrimaryLabel = primaryLabel,
            includeDefaultIsp = false
        ) ?: RemoteControlSettings(
            panelName = storeName.ifBlank { appTitle },
            topTabs = defaultTopTabs(
                primaryUrl = resolvedBaseUrl,
                primaryLabel = primaryLabel,
                includeDefaultIsp = false
            ),
            bottomTabs = defaultBottomTabs(
                baseUrl = resolvedBaseUrl,
                attendanceUrl = resolvedAttendanceUrl,
                tasksUrl = resolvedTasksUrl
            )
        )

        val finalControl = if (parsedControl.panelName.isBlank() && storeName.isNotBlank()) {
            parsedControl.copy(panelName = storeName)
        } else {
            parsedControl
        }

        ShopHandshakeResult.Success(
            boundStoreUrl = normalizedBase,
            storeCode = storeCode,
            storeName = storeName,
            appTitle = appTitle,
            logoUrl = logoUrl,
            themeColorHex = themeColorHex,
            panelId = panelId,
            baseUrl = resolvedBaseUrl,
            attendanceUrl = resolvedAttendanceUrl,
            tasksUrl = resolvedTasksUrl,
            notificationApiUrl = resolvedNotifApiUrl,
            locationApiUrl = resolvedLocApiUrl,
            syncGpsApiUrl = resolvedSyncGpsApiUrl,
            configApiUrl = resolvedConfigApiUrl,
            remoteControl = finalControl
        )
    }

    private fun mergeHandshakeAndConfig(
        handshake: JSONObject,
        extraConfig: JSONObject?
    ): JSONObject {
        val merged = JSONObject(handshake.toString())
        if (extraConfig == null) return merged

        val cfgRoot = extraConfig.optJSONObject("app_config")
            ?: extraConfig.optJSONObject("config")
            ?: extraConfig

        val keys = cfgRoot.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = cfgRoot.opt(key) ?: continue
            // Keep handshake tabs/branding if config omitted them; otherwise let admin app_config take precedence for controls
            val preferConfig = key in setOf(
                "notification_settings",
                "gps_settings",
                "task_settings",
                "config_version",
                "urls"
            )
            if (preferConfig || !merged.has(key) || merged.isNull(key)) {
                merged.put(key, value)
            }
        }
        return merged
    }

    private sealed interface FetchOutcome {
        data class Ok(val json: JSONObject) : FetchOutcome
        data object NotFound : FetchOutcome
        data class Error(val messageBn: String) : FetchOutcome
    }

    private fun fetchJsonObject(url: String, deviceId: String): FetchOutcome {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Device-Id", deviceId)
            .header("X-Device-Model", Build.MODEL.orEmpty())
            .header("X-OS-Version", "Android ${Build.VERSION.RELEASE.orEmpty()}")
            .header("X-App-Version", BuildConfig.VERSION_NAME)
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    return FetchOutcome.NotFound
                }
                if (!response.isSuccessful) {
                    return FetchOutcome.Error(
                        "সার্ভার থেকে সঠিক উত্তর পাওয়া যায়নি (HTTP ${response.code})। শপের URL ও API যাচাই করুন।"
                    )
                }
                val body = response.body?.string().orEmpty().trim()
                if (!body.startsWith("{")) {
                    return FetchOutcome.Error(
                        "সার্ভার সঠিক JSON ডেটা পাঠায়নি। এই শপের `api/app_handshake.php` চালু আছে কি না যাচাই করুন।"
                    )
                }
                FetchOutcome.Ok(JSONObject(body))
            }
        } catch (_: UnknownHostException) {
            FetchOutcome.Error("ডোমেইন বা শপ সার্ভার খুঁজে পাওয়া যায়নি। ইন্টারনেট সংযোগ ও URL যাচাই করুন।")
        } catch (_: SocketTimeoutException) {
            FetchOutcome.Error("সার্ভার সাড়া দিতে অনেক সময় নিচ্ছে (Timeout)। পরে আবার চেষ্টা করুন।")
        } catch (_: IOException) {
            FetchOutcome.Error("শপ সার্ভারের সাথে সংযোগ করা যায়নি। ইন্টারনেট সংযোগ ও HTTPS URL যাচাই করুন।")
        } catch (ex: Exception) {
            FetchOutcome.Error("শপ বাইন্ডিং ত্রুটি: ${ex.localizedMessage ?: "অজানা সমস্যা"}")
        }
    }

    companion object {
        fun normalizeStoreBaseUrl(rawInput: String): String? {
            var cleaned = rawInput.trim()
            if (cleaned.isBlank()) return null

            if (!cleaned.startsWith("http://", ignoreCase = true) &&
                !cleaned.startsWith("https://", ignoreCase = true)
            ) {
                cleaned = "https://$cleaned"
            } else if (cleaned.startsWith("http://", ignoreCase = true)) {
                cleaned = "https://" + cleaned.substring(7)
            }

            // If user accidentally pasted a full API path, trim it back to the store root
            val suffixPatterns = listOf(
                "/api/app_handshake.php",
                "/api/app_config.php",
                "/index.php",
                "/login.php"
            )
            for (suffix in suffixPatterns) {
                if (cleaned.endsWith(suffix, ignoreCase = true)) {
                    cleaned = cleaned.dropLast(suffix.length)
                }
            }
            cleaned = cleaned.trimEnd('/')

            val uri = runCatching { Uri.parse(cleaned) }.getOrNull() ?: return null
            val host = uri.host.orEmpty().trim()
            if (host.isBlank() || !host.contains('.')) return null
            return cleaned
        }
    }
}
