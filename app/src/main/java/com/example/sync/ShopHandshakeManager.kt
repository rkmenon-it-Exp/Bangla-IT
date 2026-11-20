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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    private val httpClient: OkHttpClient
        get() = NetworkClientProvider.getClientWithTimeouts(
            context = context,
            connectSeconds = 12L,
            readSeconds = 15L
        )

    suspend fun performHandshake(rawStoreUrl: String): ShopHandshakeResult = withContext(Dispatchers.IO) {
        val normalizedBase = normalizeStoreBaseUrl(rawStoreUrl)
            ?: return@withContext ShopHandshakeResult.Failure(
                "সঠিক শপের URL দিন (যেমন: https://banglait.net/shop/store1)"
            )

        val deviceId = repository.getOrCreateUniqueDeviceId()
        val handshakeUrl = "$normalizedBase/api/app_handshake.php"
        val altHandshakeUrl = "$normalizedBase/api/api_handshake.php"
        val configUrl = "$normalizedBase/api/app_config.php"

        // 1. Try primary endpoint: {store_url}/api/app_handshake.php
        //    Fallback 1: {store_url}/api/api_handshake.php
        //    Fallback 2: {store_url}/api/app_config.php
        var handshakeFetch = fetchJsonObject(handshakeUrl, deviceId)
        if (handshakeFetch is FetchOutcome.NotFound) {
            handshakeFetch = fetchJsonObject(altHandshakeUrl, deviceId)
        }
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
                when (val cfgFallback = fetchJsonObject(configUrl, deviceId)) {
                    is FetchOutcome.Ok -> cfgFallback.json
                    is FetchOutcome.NotFound -> return@withContext ShopHandshakeResult.Failure(
                        "এই URL-এ শপ API (app_handshake.php / api_handshake.php / app_config.php) পাওয়া যায়নি (404)। URL টি যাচাই করুন।"
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

        val effectiveStoreRoot = if (storeCode.isNotBlank() &&
            !normalizedBase.endsWith("/$storeCode", ignoreCase = true) &&
            normalizedBase.trimEnd('/').endsWith("/shop", ignoreCase = true)
        ) {
            "${normalizedBase.trimEnd('/')}/$storeCode"
        } else {
            normalizedBase
        }

        val defaultBoundLoginUrl = "$effectiveStoreRoot/login"
        val explicitLoginUrl = primaryObj.optString("login_url").trim()
            .ifBlank { extraConfigObj?.optString("login_url").orEmpty().trim() }
            .takeIf { it.isNotBlank() }
            ?.let { RemoteConfigSyncer.resolveUrlAgainstBase(it, effectiveStoreRoot) }
            ?.takeUnless { RemoteConfigSyncer.isGlobalPortalUrlForBoundStore(it, effectiveStoreRoot) }

        val candidateBaseOverride = urlOverrides["base_url"]
            ?.takeUnless { RemoteConfigSyncer.isGlobalPortalUrlForBoundStore(it, effectiveStoreRoot) }

        val resolvedBaseUrl = explicitLoginUrl ?: candidateBaseOverride ?: defaultBoundLoginUrl
        val resolvedAttendanceUrl = urlOverrides["attendance_url"]
            ?: "$effectiveStoreRoot/dashboard.php?staff_view=attendance"
        val resolvedTasksUrl = urlOverrides["tasks_url"]
            ?: "$effectiveStoreRoot/tasks.php"
        val resolvedNotifApiUrl = urlOverrides["notification_api_url"]
            ?: "$effectiveStoreRoot/api/notifications.php"
        val resolvedLocApiUrl = urlOverrides["location_api_url"]
            ?: "$effectiveStoreRoot/api/location.php"
        val resolvedSyncGpsApiUrl = urlOverrides["sync_gps_api_url"]
            ?: "$effectiveStoreRoot/api/sync_gps.php"
        val resolvedConfigApiUrl = urlOverrides["config_api_url"]
            ?: "$effectiveStoreRoot/api/app_config.php"

        // 3. Register device against {store_url}/api/register_device.php (non-blocking if backend endpoint not yet deployed)
        registerDeviceWithStore(
            storeRootUrl = effectiveStoreRoot,
            deviceId = deviceId,
            storeCode = storeCode,
            panelId = panelId
        )

        val primaryLabel = appTitle.ifBlank { storeName.ifBlank { "বাংলা স্টোর" } }
        val parsedControl = RemoteConfigSyncer.parseRemoteControlSettings(
            sourceObj = mergedSource,
            fallbackBaseUrl = resolvedBaseUrl,
            fallbackAttendanceUrl = resolvedAttendanceUrl,
            fallbackTasksUrl = resolvedTasksUrl,
            fallbackPrimaryLabel = primaryLabel,
            includeDefaultIsp = false,
            boundStoreRootUrl = effectiveStoreRoot
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
            boundStoreUrl = effectiveStoreRoot,
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

    private fun registerDeviceWithStore(
        storeRootUrl: String,
        deviceId: String,
        storeCode: String,
        panelId: String
    ) {
        runCatching {
            val registerUrl = "$storeRootUrl/api/register_device.php"
            val battery = DeviceTelemetryHelper.getBatteryLevel(context)
            val payload = JSONObject().apply {
                put("device_id", deviceId)
                put("unique_device_id", deviceId)
                put("device_model", Build.MODEL.orEmpty())
                put("os_version", Build.VERSION.RELEASE.orEmpty())
                put("os_version_full", DeviceTelemetryHelper.getOsVersion())
                put("app_version", BuildConfig.VERSION_NAME)
                if (battery in 0..100) put("battery_level", battery)
                put("store_code", storeCode)
                put("panel_id", panelId)
            }
            val requestBuilder = Request.Builder()
                .url(registerUrl)
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            DeviceTelemetryHelper.buildRequestHeaders(context, deviceId).forEach { (k, v) ->
                requestBuilder.header(k, v)
            }
            httpClient.newCall(requestBuilder.build()).execute().use { _ -> }
        }
    }

    private fun fetchJsonObject(url: String, deviceId: String): FetchOutcome {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
        DeviceTelemetryHelper.buildRequestHeaders(context, deviceId).forEach { (k, v) ->
            requestBuilder.header(k, v)
        }
        val request = requestBuilder.build()

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
        /**
         * Extracts a store binding URL from any QR code payload format:
         * - Plain URL: `https://banglait.net/shop/store1` or `https://banglait.net/shop/store1/login`
         * - JSON object: `{"store_url":"..."}`, `{"url":"..."}`, `{"base_url":"..."}`, `{"shop_url":"..."}`
         * - Deep link URI: `banglastore://bind?url=https://...`
         */
        fun extractStoreUrlFromQr(rawQrText: String): String? {
            val trimmed = rawQrText.trim()
            if (trimmed.isBlank()) return null

            if (trimmed.startsWith("{")) {
                val parsedUrl = runCatching {
                    val obj = JSONObject(trimmed)
                    val directUrl = obj.optString("store_url").trim()
                        .ifBlank { obj.optString("shop_url").trim() }
                        .ifBlank { obj.optString("panel_url").trim() }
                        .ifBlank { obj.optString("url").trim() }
                        .ifBlank { obj.optString("base_url").trim() }
                    val code = obj.optString("store_code").trim()
                    when {
                        directUrl.isNotBlank() && code.isNotBlank() &&
                            !directUrl.trimEnd('/').endsWith("/$code", ignoreCase = true) &&
                            !directUrl.contains(code, ignoreCase = true) ->
                            "${directUrl.trimEnd('/')}/$code"
                        directUrl.isNotBlank() -> directUrl
                        else -> ""
                    }
                }.getOrNull().orEmpty()
                if (parsedUrl.isNotBlank()) {
                    return normalizeStoreBaseUrl(parsedUrl)
                }
            }

            if (trimmed.contains("://") &&
                !trimmed.startsWith("http://", ignoreCase = true) &&
                !trimmed.startsWith("https://", ignoreCase = true)
            ) {
                val queryUrl = runCatching {
                    val uri = Uri.parse(trimmed)
                    uri.getQueryParameter("store_url")
                        ?: uri.getQueryParameter("url")
                        ?: uri.getQueryParameter("shop_url")
                        ?: uri.getQueryParameter("base_url")
                }.getOrNull().orEmpty()
                if (queryUrl.isNotBlank()) {
                    return normalizeStoreBaseUrl(queryUrl)
                }
            }

            return normalizeStoreBaseUrl(trimmed)
        }

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

            cleaned = cleaned.trimEnd('/')

            // If user pasted a full API or login path, trim back to the store root
            val suffixPatterns = listOf(
                "/api/app_handshake.php",
                "/api/api_handshake.php",
                "/api/app_config.php",
                "/index.php",
                "/login.php",
                "/login"
            )
            for (suffix in suffixPatterns) {
                if (cleaned.endsWith(suffix, ignoreCase = true)) {
                    cleaned = cleaned.dropLast(suffix.length).trimEnd('/')
                }
            }

            val uri = runCatching { Uri.parse(cleaned) }.getOrNull() ?: return null
            val host = uri.host.orEmpty().trim()
            if (host.isBlank() || !host.contains('.')) return null
            return cleaned
        }
    }
}
