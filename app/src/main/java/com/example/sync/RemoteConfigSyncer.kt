package com.example.sync

import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import com.example.BuildConfig
import com.example.data.StorePreferencesRepository
import com.example.location.OnDemandGpsCapturer
import com.example.model.AlertDeliveryMode
import com.example.model.DEFAULT_ATTENDANCE_URL
import com.example.model.DEFAULT_BASE_URL
import com.example.model.DEFAULT_TASKS_URL
import com.example.model.GpsCollectionMode
import com.example.model.GpsUploadTrigger
import com.example.model.PortalConfig
import com.example.model.RemoteBottomTab
import com.example.model.RemoteControlSettings
import com.example.model.RemoteTopTab
import com.example.model.StoreBrandingUpdate
import com.example.model.TaskRefreshMode
import com.example.model.defaultBottomTabs
import com.example.model.defaultTopTabs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class RemoteConfigSyncResult(
    val success: Boolean,
    val updated: Boolean,
    val gpsRequestFulfilled: Boolean = false,
    val deviceDisconnected: Boolean = false,
    val settings: RemoteControlSettings? = null,
    val statusMessage: String = ""
)

class RemoteConfigSyncer(
    private val context: Context,
    private val repository: StorePreferencesRepository
) {
    private val httpClient: OkHttpClient
        get() = NetworkClientProvider.getClient(context)

    suspend fun syncConfig(): RemoteConfigSyncResult = withContext(Dispatchers.IO) {
        val config = repository.getConfigSnapshot()
        if (!config.isCustomStoreBound || config.configApiUrl.isBlank()) {
            return@withContext RemoteConfigSyncResult(
                success = false,
                updated = false,
                statusMessage = "কোনো শপ বাইন্ড করা নেই"
            )
        }
        val deviceId = repository.getOrCreateUniqueDeviceId()
        val requestUrl = buildConfigUrl(config)
        val cookie = runCatching { CookieManager.getInstance().getCookie(config.baseUrl) }
            .getOrNull().orEmpty()
            .ifBlank { repository.getSessionCookie() }

        val requestBuilder = Request.Builder()
            .url(requestUrl)
            .header("Accept", "application/json")
        DeviceTelemetryHelper.buildRequestHeaders(context, deviceId).forEach { (k, v) ->
            requestBuilder.header(k, v)
        }
        if (cookie.isNotBlank()) {
            requestBuilder.header("Cookie", cookie)
        }

        return@withContext try {
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                val bodyString = response.body?.string().orEmpty().trim()
                val parsedJson = if (bodyString.startsWith("{")) {
                    runCatching { JSONObject(bodyString) }.getOrNull()
                } else {
                    null
                }

                if (DeviceDisconnectManager.isRemoteDisconnectSignal(response.code, parsedJson)) {
                    val reason = parsedJson?.optString("message", "").orEmpty().trim()
                        .ifBlank { "অ্যাডমিন প্যানেল থেকে এই ফোনটি ডিসকানেক্ট করা হয়েছে — অ্যাপ রিসেট ও ডেটা ক্লিয়ার করা হয়েছে।" }
                    DeviceDisconnectManager.executeFullResetAndWipe(
                        context = context,
                        repository = repository,
                        reasonMessage = reason,
                        showNotification = true
                    )
                    return@withContext RemoteConfigSyncResult(
                        success = false,
                        updated = false,
                        deviceDisconnected = true,
                        statusMessage = reason
                    )
                }

                if (response.code == 404) {
                    return@withContext RemoteConfigSyncResult(
                        success = false,
                        updated = false,
                        statusMessage = "ডিফল্ট কনফিগ সক্রিয় (app_config.php অপেক্ষমাণ)"
                    )
                }
                if (!response.isSuccessful) {
                    return@withContext RemoteConfigSyncResult(
                        success = false,
                        updated = false,
                        statusMessage = "কনফিগ HTTP ${response.code}"
                    )
                }

                val rootObj = parsedJson
                    ?: return@withContext RemoteConfigSyncResult(
                        success = false,
                        updated = false,
                        statusMessage = "অবৈধ কনফিগ JSON"
                    )
                val responsePanelId = rootObj.optString("panel_id", "").trim()

                // Strict multi-panel isolation: never apply another panel's config
                if (config.panelId.isNotBlank() &&
                    responsePanelId.isNotBlank() &&
                    !responsePanelId.equals(config.panelId, ignoreCase = true)
                ) {
                    return@withContext RemoteConfigSyncResult(
                        success = false,
                        updated = false,
                        statusMessage = "প্যানেল আইডি অমিল হওয়ায় কনফিগ উপেক্ষিত"
                    )
                }

                if (config.panelId.isBlank() && responsePanelId.isNotBlank()) {
                    repository.updateSessionFromWeb(
                        role = null,
                        userId = null,
                        userName = null,
                        panelId = responsePanelId
                    )
                }

                val boundRoot = config.boundStoreUrl.trim().trimEnd('/')
                val parsed = parseRemoteControlSettings(
                    sourceObj = rootObj,
                    fallbackBaseUrl = config.baseUrl,
                    fallbackAttendanceUrl = config.attendanceUrl,
                    fallbackTasksUrl = config.tasksUrl,
                    fallbackPrimaryLabel = config.effectiveStoreDisplayName,
                    includeDefaultIsp = !config.isCustomStoreBound,
                    boundStoreRootUrl = boundRoot
                )
                    ?: return@withContext RemoteConfigSyncResult(
                        success = false,
                        updated = false,
                        statusMessage = "কনফিগ পার্স করা যায়নি"
                    )

                val rawOverrides = extractUrlOverrides(rootObj, boundRoot.ifBlank { config.baseUrl })
                val urlOverrides = rawOverrides.filter { (k, v) ->
                    if (k == "base_url" && boundRoot.isNotBlank()) {
                        !isGlobalPortalUrlForBoundStore(v, boundRoot)
                    } else {
                        true
                    }
                }
                val brandingUpdate = extractBrandingUpdate(rootObj, boundRoot.ifBlank { config.baseUrl })
                repository.saveRemoteControl(parsed, urlOverrides, brandingUpdate)

                val latestConfig = repository.getConfigSnapshot()
                val gpsFulfilled = handleRemoteGpsRequestObject(
                    sourceObj = rootObj,
                    config = latestConfig,
                    context = context,
                    repository = repository
                )

                RemoteConfigSyncResult(
                    success = true,
                    updated = parsed.configVersion != config.remoteControl.configVersion ||
                        parsed != config.remoteControl,
                    gpsRequestFulfilled = gpsFulfilled,
                    settings = parsed,
                    statusMessage = "প্যানেল কনফিগ আপডেট হয়েছে"
                )
            }
        } catch (_: Exception) {
            RemoteConfigSyncResult(
                success = false,
                updated = false,
                statusMessage = "কনফিগ সিঙ্ক অফলাইন"
            )
        }
    }

    private fun buildConfigUrl(config: PortalConfig): String {
        return Uri.parse(config.configApiUrl)
            .buildUpon()
            .appendQueryParameter("role", config.userRole.key)
            .apply {
                if (config.panelId.isNotBlank()) {
                    appendQueryParameter("panel_id", config.panelId)
                }
                if (config.storeCode.isNotBlank()) {
                    appendQueryParameter("store_code", config.storeCode)
                }
                if (config.userId.isNotBlank()) {
                    appendQueryParameter("user_id", config.userId)
                }
            }
            .build()
            .toString()
    }

    companion object {
        suspend fun handleRemoteGpsRequestObject(
            sourceObj: JSONObject,
            config: PortalConfig,
            context: Context,
            repository: StorePreferencesRepository
        ): Boolean {
            val containerObj = sourceObj.optJSONObject("app_config")
                ?: sourceObj.optJSONObject("config")
                ?: sourceObj
            val gpsSettingsObj = containerObj.optJSONObject("gps_settings") ?: sourceObj.optJSONObject("gps_settings")
            val gpsReqObj = sourceObj.optJSONObject("gps_request")
                ?: containerObj.optJSONObject("gps_request")
                ?: gpsSettingsObj?.optJSONObject("gps_request")

            var requestId = gpsReqObj?.optString("request_id", "").orEmpty().trim()
                .ifBlank { gpsReqObj?.optString("id", "").orEmpty().trim() }
                .ifBlank { gpsSettingsObj?.optString("gps_request_id", "").orEmpty().trim() }
                .ifBlank { containerObj.optString("gps_request_id", "").trim() }
                .ifBlank { sourceObj.optString("gps_request_id", "").trim() }

            val requestFlag = gpsReqObj?.optBoolean("requested", true)
                ?: gpsSettingsObj?.optBoolean("request_gps_now", false)
                ?: containerObj.optBoolean("request_gps_now", false)

            if (requestId.isBlank() && requestFlag) {
                requestId = "req_flag_${containerObj.optString("config_version", System.currentTimeMillis().toString())}"
            }
            if (requestId.isBlank()) return false

            if (gpsReqObj != null) {
                val reqPanelId = gpsReqObj.optString("panel_id", "").trim()
                if (config.panelId.isNotBlank() &&
                    reqPanelId.isNotBlank() &&
                    !reqPanelId.equals(config.panelId, ignoreCase = true)
                ) {
                    return false
                }
                val reqRole = gpsReqObj.optString("target_role", "all").trim().lowercase()
                if (reqRole.isNotBlank() && reqRole != "all" && !reqRole.equals(config.userRole.key, ignoreCase = true)) {
                    return false
                }
                val reqUserId = gpsReqObj.optString("target_user_id", "").trim()
                if (reqUserId.isNotBlank() && config.userId.isNotBlank() && !reqUserId.equals(config.userId, ignoreCase = true)) {
                    return false
                }
            }

            if (repository.isGpsRequestFulfilled(requestId)) {
                return false
            }

            val snapshot = OnDemandGpsCapturer(context, repository).captureAndUploadIfAllowed(
                trigger = GpsUploadTrigger.PANEL_REQUEST,
                requestId = requestId,
                isBackground = true
            )
            return snapshot != null
        }

        fun extractUrlOverrides(
            rootObj: JSONObject,
            baseUrlForRelative: String = DEFAULT_BASE_URL
        ): Map<String, String> {
            val urlsObj = rootObj.optJSONObject("urls")
                ?: rootObj.optJSONObject("app_config")?.optJSONObject("urls")
                ?: rootObj
            val overrides = mutableMapOf<String, String>()
            listOf(
                "base_url",
                "attendance_url",
                "tasks_url",
                "notification_api_url",
                "location_api_url",
                "sync_gps_api_url",
                "config_api_url"
            ).forEach { key ->
                val valStr = urlsObj.optString(key, "").trim()
                if (valStr.startsWith("https://", ignoreCase = true) ||
                    valStr.startsWith("http://", ignoreCase = true) ||
                    valStr.startsWith("/")
                ) {
                    overrides[key] = resolveUrlAgainstBase(valStr, baseUrlForRelative)
                }
            }
            return overrides
        }

        fun extractBrandingUpdate(
            rootObj: JSONObject,
            baseUrlForRelative: String = DEFAULT_BASE_URL
        ): StoreBrandingUpdate? {
            val obj = rootObj.optJSONObject("app_config")
                ?: rootObj.optJSONObject("config")
                ?: rootObj
            val storeCode = obj.optString("store_code", rootObj.optString("store_code", "")).trim()
            val storeName = obj.optString("store_name", rootObj.optString("store_name", "")).trim()
            val appTitle = obj.optString("app_title", rootObj.optString("app_title", "")).trim()
            val rawLogo = obj.optString("logo_url", rootObj.optString("logo_url", "")).trim()
            val logoUrl = if (rawLogo.isNotBlank()) resolveUrlAgainstBase(rawLogo, baseUrlForRelative) else ""
            val themeColor = obj.optString("theme_color", rootObj.optString("theme_color", "")).trim()

            if (storeCode.isBlank() &&
                storeName.isBlank() &&
                appTitle.isBlank() &&
                logoUrl.isBlank() &&
                themeColor.isBlank()
            ) {
                return null
            }
            return StoreBrandingUpdate(
                storeCode = storeCode.ifBlank { null },
                storeName = storeName.ifBlank { null },
                appTitle = appTitle.ifBlank { null },
                logoUrl = logoUrl.ifBlank { null },
                themeColorHex = themeColor.ifBlank { null }
            )
        }

        fun parseRemoteControlSettings(
            sourceObj: JSONObject,
            fallbackBaseUrl: String = DEFAULT_BASE_URL,
            fallbackAttendanceUrl: String = DEFAULT_ATTENDANCE_URL,
            fallbackTasksUrl: String = DEFAULT_TASKS_URL,
            fallbackPrimaryLabel: String = "বাংলা স্টোর",
            includeDefaultIsp: Boolean = true,
            boundStoreRootUrl: String = ""
        ): RemoteControlSettings? {
            val obj = sourceObj.optJSONObject("app_config")
                ?: sourceObj.optJSONObject("config")
                ?: sourceObj

            val hasAnyControlField = obj.has("top_tabs") ||
                obj.has("bottom_tabs") ||
                obj.has("bottom_nav") ||
                obj.has("notification_settings") ||
                obj.has("gps_settings") ||
                obj.has("task_settings") ||
                obj.has("config_version") ||
                obj.has("panel_name") ||
                obj.has("store_name") ||
                obj.has("app_title")
            if (!hasAnyControlField) return null

            val notifObj = obj.optJSONObject("notification_settings") ?: obj
            val gpsObj = obj.optJSONObject("gps_settings") ?: obj
            val taskObj = obj.optJSONObject("task_settings") ?: obj

            val defaultTop = defaultTopTabs(
                primaryUrl = fallbackBaseUrl,
                primaryLabel = fallbackPrimaryLabel,
                includeDefaultIsp = includeDefaultIsp
            )
            val defaultBottom = defaultBottomTabs(
                baseUrl = fallbackBaseUrl,
                attendanceUrl = fallbackAttendanceUrl,
                tasksUrl = fallbackTasksUrl
            )

            val relativeBase = boundStoreRootUrl.trim().trimEnd('/').ifBlank { fallbackBaseUrl }
            val parsedTopTabs = parseTopTabs(obj.optJSONArray("top_tabs"), relativeBase)
                .ifEmpty { defaultTop }
                .let { list ->
                    val withPrimary = if (list.any { it.isPrimaryStore }) {
                        list
                    } else {
                        list.mapIndexed { idx, tab ->
                            if (idx == 0) tab.copy(isPrimaryStore = true) else tab
                        }
                    }
                    if (boundStoreRootUrl.isNotBlank()) {
                        withPrimary.map { tab ->
                            if (tab.isPrimaryStore && isGlobalPortalUrlForBoundStore(tab.url, boundStoreRootUrl)) {
                                tab.copy(url = fallbackBaseUrl)
                            } else {
                                tab
                            }
                        }
                    } else {
                        withPrimary
                    }
                }

            val bottomJsonArray = obj.optJSONArray("bottom_tabs") ?: obj.optJSONArray("bottom_nav")
            val parsedBottomTabs = parseBottomTabs(bottomJsonArray, relativeBase)
                .ifEmpty { defaultBottom }
                .let { list ->
                    if (boundStoreRootUrl.isNotBlank()) {
                        list.map { tab ->
                            if (tab.id.equals("portal", ignoreCase = true) &&
                                isGlobalPortalUrlForBoundStore(tab.url, boundStoreRootUrl)
                            ) {
                                tab.copy(url = fallbackBaseUrl)
                            } else {
                                tab
                            }
                        }
                    } else {
                        list
                    }
                }

            val allowedEvents = parseStringList(
                notifObj.optJSONArray("allowed_event_types")
                    ?: obj.optJSONArray("allowed_event_types")
            )

            val refreshTabs = parseStringList(
                taskObj.optJSONArray("refresh_only_on_tabs")
                    ?: obj.optJSONArray("refresh_only_on_tabs")
            )

            val gpsMode = GpsCollectionMode.fromKey(
                gpsObj.optString("mode", gpsObj.optString("gps_mode", "on_demand_or_hourly"))
            )
            val defaultGpsInterval = if (gpsMode == GpsCollectionMode.ON_DEMAND_OR_HOURLY) 3600 else 30

            val resolvedPanelName = obj.optString("panel_name", sourceObj.optString("panel_name", ""))
                .ifBlank { obj.optString("store_name", sourceObj.optString("store_name", "")) }

            return RemoteControlSettings(
                configVersion = obj.optString("config_version", sourceObj.optString("config_version", "")),
                panelName = resolvedPanelName,
                topTabs = parsedTopTabs,
                bottomTabs = parsedBottomTabs,
                notificationsEnabled = notifObj.optBoolean("enabled", notifObj.optBoolean("notifications_enabled", true)),
                alertDeliveryMode = AlertDeliveryMode.fromKey(
                    notifObj.optString("delivery_mode", notifObj.optString("alert_delivery_mode", "popup_sound"))
                ),
                syncIntervalSeconds = notifObj.optInt("sync_interval_seconds", 30).coerceIn(10, 3600),
                backgroundSyncEnabled = notifObj.optBoolean("background_sync_enabled", true),
                allowedEventTypes = allowedEvents,
                gpsEnabled = gpsObj.optBoolean("enabled", gpsObj.optBoolean("gps_enabled", true)),
                gpsMode = gpsMode,
                gpsIntervalSeconds = gpsObj.optInt(
                    "interval_seconds",
                    gpsObj.optInt("gps_interval_seconds", defaultGpsInterval)
                ).coerceIn(10, 86400),
                gpsMinDistanceMeters = gpsObj.optDouble(
                    "min_distance_meters",
                    gpsObj.optDouble("gps_min_distance_meters", 10.0)
                ).toFloat().coerceIn(0f, 5000f),
                gpsBackgroundEnabled = gpsObj.optBoolean("background_enabled", gpsObj.optBoolean("gps_background_enabled", true)),
                taskAutoRefreshEnabled = taskObj.optBoolean("auto_refresh_on_update", taskObj.optBoolean("task_auto_refresh_enabled", true)),
                taskRefreshMode = TaskRefreshMode.fromKey(
                    taskObj.optString("refresh_mode", taskObj.optString("task_refresh_mode", "version_and_events"))
                ),
                refreshCooldownSeconds = taskObj.optInt(
                    "refresh_cooldown_seconds",
                    taskObj.optInt("task_refresh_cooldown_seconds", 20)
                ).coerceIn(5, 3600),
                refreshOnlyOnTabs = refreshTabs
            )
        }

        private fun parseTopTabs(
            array: JSONArray?,
            baseUrlForRelative: String
        ): List<RemoteTopTab> {
            if (array == null || array.length() == 0) return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id").trim()
                        .ifBlank { item.optString("key").trim() }
                        .ifBlank { "tab_$i" }
                    val label = item.optString("label").trim()
                        .ifBlank { item.optString("title").trim() }
                        .ifBlank { item.optString("name").trim() }
                    val url = item.optString("url").trim()
                        .ifBlank { item.optString("link").trim() }
                    if (id.isBlank() || label.isBlank() || url.isBlank()) continue
                    add(
                        RemoteTopTab(
                            id = id,
                            label = label,
                            url = resolveUrlAgainstBase(url, baseUrlForRelative),
                            icon = item.optString("icon", "storefront").trim().ifBlank { "storefront" },
                            roles = parseStringList(item.optJSONArray("roles")).ifEmpty { listOf("all") },
                            enabled = item.optBoolean("enabled", true),
                            isPrimaryStore = item.optBoolean(
                                "is_primary_store",
                                id == "bangla_store" || i == 0
                            )
                        )
                    )
                }
            }
        }

        private fun parseBottomTabs(
            array: JSONArray?,
            baseUrlForRelative: String
        ): List<RemoteBottomTab> {
            if (array == null || array.length() == 0) return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id").trim()
                        .ifBlank { item.optString("key").trim() }
                        .ifBlank { item.optString("route").trim() }
                        .ifBlank { "nav_$i" }
                    val label = item.optString("label").trim()
                        .ifBlank { item.optString("title").trim() }
                        .ifBlank { item.optString("name").trim() }
                    if (id.isBlank() || label.isBlank()) continue
                    val type = item.optString("type", if (id == "alerts") "alerts" else "web")
                        .trim()
                        .lowercase()
                        .ifBlank { "web" }
                    val url = item.optString("url").trim()
                        .ifBlank { item.optString("link").trim() }
                    if (type == "web" && url.isBlank()) continue
                    add(
                        RemoteBottomTab(
                            id = id,
                            label = label,
                            url = if (url.isBlank()) "" else resolveUrlAgainstBase(url, baseUrlForRelative),
                            icon = item.optString("icon", id).trim().ifBlank { id },
                            type = type,
                            roles = parseStringList(item.optJSONArray("roles")).ifEmpty { listOf("all") },
                            enabled = item.optBoolean("enabled", true)
                        )
                    )
                }
            }
        }

        private fun parseStringList(array: JSONArray?): List<String> {
            if (array == null || array.length() == 0) return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val value = array.optString(i).trim().lowercase()
                    if (value.isNotBlank()) add(value)
                }
            }
        }

        /**
         * Returns true if [candidateUrl] points to the global portal/login root (e.g. `https://banglait.net/shop`,
         * `https://banglait.net/shop/`, `https://banglait.net/shop/login`, `https://banglait.net/shop/login.php`)
         * when the app is bound to a specific tenant store subpath like `https://banglait.net/shop/store1`.
         */
        fun isGlobalPortalUrlForBoundStore(candidateUrl: String, boundStoreRootUrl: String): Boolean {
            val cleanBound = boundStoreRootUrl.trim().trimEnd('/')
            val cleanCandidate = candidateUrl.trim().trimEnd('/')
            if (cleanBound.isBlank() || cleanCandidate.isBlank()) return false

            val boundUri = runCatching { Uri.parse(cleanBound) }.getOrNull() ?: return false
            val candUri = runCatching { Uri.parse(cleanCandidate) }.getOrNull() ?: return false
            val boundHost = boundUri.host.orEmpty().lowercase()
            val candHost = candUri.host.orEmpty().lowercase()
            if (boundHost.isBlank() || candHost != boundHost) return false

            val boundPath = boundUri.path.orEmpty().trimEnd('/').lowercase()
            val candPath = candUri.path.orEmpty().trimEnd('/').lowercase()

            // If bound to a subpath (e.g., /shop/store1), a candidate path that is a strict parent/global login
            // without the bound subpath (e.g. "", "/shop", "/shop/login", "/shop/login.php", "/login", "/login.php")
            // must be rewritten to the bound store's login URL.
            if (boundPath.isNotBlank() && !candPath.startsWith(boundPath)) {
                val parentPath = boundPath.substringBeforeLast('/', missingDelimiterValue = "")
                val isParentRoot = candPath.isBlank() ||
                    candPath == parentPath ||
                    candPath == "$parentPath/index.php" ||
                    candPath == "$parentPath/login" ||
                    candPath == "$parentPath/login.php" ||
                    candPath == "/login" ||
                    candPath == "/login.php"
                if (isParentRoot) return true
            }

            return false
        }

        fun resolveUrlAgainstBase(raw: String, baseUrl: String): String {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return baseUrl
            return when {
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                trimmed.startsWith("http://", ignoreCase = true) ->
                    "https://" + trimmed.removePrefix("http://").removePrefix("HTTP://")
                trimmed.startsWith("/") -> {
                    val parsedBase = runCatching { Uri.parse(baseUrl) }.getOrNull()
                    val scheme = parsedBase?.scheme?.ifBlank { "https" } ?: "https"
                    val authority = parsedBase?.encodedAuthority.orEmpty()
                    if (authority.isNotBlank()) "$scheme://$authority$trimmed" else "${baseUrl.trimEnd('/')}$trimmed"
                }
                !trimmed.contains('.') || trimmed.contains(".php", ignoreCase = true) -> {
                    "${baseUrl.trimEnd('/')}/${trimmed.trimStart('/')}"
                }
                else -> "https://$trimmed"
            }
        }
    }
}
