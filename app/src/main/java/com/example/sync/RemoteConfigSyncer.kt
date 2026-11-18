package com.example.sync

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import com.example.data.StorePreferencesRepository
import com.example.location.OnDemandGpsCapturer
import com.example.model.AlertDeliveryMode
import com.example.model.GpsCollectionMode
import com.example.model.GpsUploadTrigger
import com.example.model.PortalConfig
import com.example.model.RemoteBottomTab
import com.example.model.RemoteControlSettings
import com.example.model.RemoteTopTab
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
    val settings: RemoteControlSettings? = null,
    val statusMessage: String = ""
)

class RemoteConfigSyncer(
    private val context: Context,
    private val repository: StorePreferencesRepository
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun syncConfig(): RemoteConfigSyncResult = withContext(Dispatchers.IO) {
        val config = repository.getConfigSnapshot()
        val requestUrl = buildConfigUrl(config)
        val cookie = runCatching { CookieManager.getInstance().getCookie(config.baseUrl) }
            .getOrNull().orEmpty()
            .ifBlank { repository.getSessionCookie() }

        val requestBuilder = Request.Builder()
            .url(requestUrl)
            .header("Accept", "application/json")
        if (cookie.isNotBlank()) {
            requestBuilder.header("Cookie", cookie)
        }

        return@withContext try {
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
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

                val bodyString = response.body?.string().orEmpty().trim()
                if (!bodyString.startsWith("{")) {
                    return@withContext RemoteConfigSyncResult(
                        success = false,
                        updated = false,
                        statusMessage = "অবৈধ কনফিগ JSON"
                    )
                }

                val rootObj = JSONObject(bodyString)
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

                val parsed = parseRemoteControlSettings(rootObj)
                    ?: return@withContext RemoteConfigSyncResult(
                        success = false,
                        updated = false,
                        statusMessage = "কনফিগ পার্স করা যায়নি"
                    )

                val urlOverrides = extractUrlOverrides(rootObj)
                repository.saveRemoteControl(parsed, urlOverrides)

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

        fun extractUrlOverrides(rootObj: JSONObject): Map<String, String> {
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
                "config_api_url"
            ).forEach { key ->
                val valStr = urlsObj.optString(key, "").trim()
                if (valStr.startsWith("https://", ignoreCase = true) ||
                    valStr.startsWith("http://", ignoreCase = true)
                ) {
                    overrides[key] = valStr
                }
            }
            return overrides
        }

        fun parseRemoteControlSettings(sourceObj: JSONObject): RemoteControlSettings? {
            val obj = sourceObj.optJSONObject("app_config")
                ?: sourceObj.optJSONObject("config")
                ?: sourceObj

            val hasAnyControlField = obj.has("top_tabs") ||
                obj.has("bottom_tabs") ||
                obj.has("notification_settings") ||
                obj.has("gps_settings") ||
                obj.has("task_settings") ||
                obj.has("config_version") ||
                obj.has("panel_name")
            if (!hasAnyControlField) return null

            val notifObj = obj.optJSONObject("notification_settings") ?: obj
            val gpsObj = obj.optJSONObject("gps_settings") ?: obj
            val taskObj = obj.optJSONObject("task_settings") ?: obj

            val parsedTopTabs = parseTopTabs(obj.optJSONArray("top_tabs"))
                .ifEmpty { defaultTopTabs() }
                .let { list ->
                    if (list.any { it.isPrimaryStore }) {
                        list
                    } else {
                        list.mapIndexed { idx, tab ->
                            if (idx == 0) tab.copy(isPrimaryStore = true) else tab
                        }
                    }
                }

            val parsedBottomTabs = parseBottomTabs(obj.optJSONArray("bottom_tabs"))
                .ifEmpty { defaultBottomTabs() }

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

            return RemoteControlSettings(
                configVersion = obj.optString("config_version", sourceObj.optString("config_version", "")),
                panelName = obj.optString("panel_name", sourceObj.optString("panel_name", "")),
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

        private fun parseTopTabs(array: JSONArray?): List<RemoteTopTab> {
            if (array == null || array.length() == 0) return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id").trim()
                    val label = item.optString("label").trim()
                    val url = item.optString("url").trim()
                    if (id.isBlank() || label.isBlank() || url.isBlank()) continue
                    add(
                        RemoteTopTab(
                            id = id,
                            label = label,
                            url = normalizeUrl(url),
                            icon = item.optString("icon", "storefront").trim().ifBlank { "storefront" },
                            roles = parseStringList(item.optJSONArray("roles")).ifEmpty { listOf("all") },
                            enabled = item.optBoolean("enabled", true),
                            isPrimaryStore = item.optBoolean("is_primary_store", id == "bangla_store")
                        )
                    )
                }
            }
        }

        private fun parseBottomTabs(array: JSONArray?): List<RemoteBottomTab> {
            if (array == null || array.length() == 0) return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id").trim()
                    val label = item.optString("label").trim()
                    if (id.isBlank() || label.isBlank()) continue
                    val type = item.optString("type", if (id == "alerts") "alerts" else "web")
                        .trim()
                        .lowercase()
                        .ifBlank { "web" }
                    val url = item.optString("url").trim()
                    if (type == "web" && url.isBlank()) continue
                    add(
                        RemoteBottomTab(
                            id = id,
                            label = label,
                            url = if (url.isBlank()) "" else normalizeUrl(url),
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

        private fun normalizeUrl(raw: String): String {
            val trimmed = raw.trim()
            return when {
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                trimmed.startsWith("http://", ignoreCase = true) ->
                    "https://" + trimmed.removePrefix("http://").removePrefix("HTTP://")
                else -> "https://$trimmed"
            }
        }
    }
}
