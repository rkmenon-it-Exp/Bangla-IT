package com.example.sync

import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import com.example.BuildConfig
import com.example.data.StorePreferencesRepository
import com.example.location.OnDemandGpsCapturer
import com.example.model.AlertDeliveryMode
import com.example.model.GpsUploadTrigger
import com.example.model.NotificationEventType
import com.example.model.PortalConfig
import com.example.model.StoreNotification
import com.example.model.TaskRefreshMode
import com.example.notifications.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PollSyncResult(
    val success: Boolean,
    val newNotificationsCount: Int,
    val shouldAutoRefreshTasks: Boolean,
    val gpsRequestFulfilled: Boolean = false,
    val statusMessage: String
)

class NotificationPoller(
    private val context: Context,
    private val repository: StorePreferencesRepository
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun pollOnce(showSystemNotifications: Boolean = true): PollSyncResult = withContext(Dispatchers.IO) {
        val config = repository.getConfigSnapshot()
        if (!config.isCustomStoreBound || config.notificationApiUrl.isBlank()) {
            return@withContext PollSyncResult(
                success = false,
                newNotificationsCount = 0,
                shouldAutoRefreshTasks = false,
                statusMessage = "কোনো শপ বাইন্ড করা নেই"
            )
        }
        val lastVersion = repository.getLastTaskVersion()
        val lastSyncMillis = repository.getLastSyncTimeMillis()
        val deviceId = repository.getOrCreateUniqueDeviceId()

        val requestUrl = buildPollUrl(config, lastVersion, lastSyncMillis)
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
        val request = requestBuilder.build()

        return@withContext try {
            httpClient.newCall(request).execute().use { response ->
                val now = System.currentTimeMillis()
                if (!response.isSuccessful) {
                    val msg = if (response.code == 404) {
                        "PHP API পাওয়া যায়নি (404) — সেটআপ গাইড দেখুন"
                    } else {
                        "সার্ভার রেসপন্স HTTP ${response.code}"
                    }
                    repository.updateLastSync(now, msg)
                    return@withContext PollSyncResult(
                        success = false,
                        newNotificationsCount = 0,
                        shouldAutoRefreshTasks = false,
                        statusMessage = msg
                    )
                }

                val bodyString = response.body?.string().orEmpty().trim()
                if (bodyString.isEmpty() || (!bodyString.startsWith("{") && !bodyString.startsWith("["))) {
                    val msg = "অবৈধ JSON রেসপন্স — PHP API ফরম্যাট যাচাই করুন"
                    repository.updateLastSync(now, msg)
                    return@withContext PollSyncResult(
                        success = false,
                        newNotificationsCount = 0,
                        shouldAutoRefreshTasks = false,
                        statusMessage = msg
                    )
                }

                val rootObj = if (bodyString.startsWith("[")) {
                    JSONObject().put("notifications", JSONArray(bodyString))
                } else {
                    JSONObject(bodyString)
                }

                // Strict multi-panel root guard
                val rootPanelId = rootObj.optString("panel_id", "").trim()
                if (config.panelId.isNotBlank() &&
                    rootPanelId.isNotBlank() &&
                    !rootPanelId.equals(config.panelId, ignoreCase = true)
                ) {
                    val msg = "প্যানেল আইডি অমিল — অন্য প্যানেলের ডেটা ব্লক করা হয়েছে"
                    repository.updateLastSync(now, msg)
                    return@withContext PollSyncResult(
                        success = false,
                        newNotificationsCount = 0,
                        shouldAutoRefreshTasks = false,
                        statusMessage = msg
                    )
                }

                if (config.panelId.isBlank() && rootPanelId.isNotBlank()) {
                    repository.updateSessionFromWeb(
                        role = null,
                        userId = null,
                        userName = null,
                        panelId = rootPanelId
                    )
                }

                // If server bundles inline app_config inside notifications.php, apply it immediately
                val boundRoot = config.boundStoreUrl.trim().trimEnd('/')
                val bundledConfig = if (rootObj.has("app_config") || rootObj.has("config")) {
                    RemoteConfigSyncer.parseRemoteControlSettings(
                        sourceObj = rootObj,
                        fallbackBaseUrl = config.baseUrl,
                        fallbackAttendanceUrl = config.attendanceUrl,
                        fallbackTasksUrl = config.tasksUrl,
                        fallbackPrimaryLabel = config.effectiveStoreDisplayName,
                        includeDefaultIsp = !config.isCustomStoreBound,
                        boundStoreRootUrl = boundRoot
                    )
                } else {
                    null
                }
                if (bundledConfig != null) {
                    val rawOverrides = RemoteConfigSyncer.extractUrlOverrides(
                        rootObj,
                        boundRoot.ifBlank { config.baseUrl }
                    )
                    val safeOverrides = rawOverrides.filter { (k, v) ->
                        if (k == "base_url" && boundRoot.isNotBlank()) {
                            !RemoteConfigSyncer.isGlobalPortalUrlForBoundStore(v, boundRoot)
                        } else {
                            true
                        }
                    }
                    repository.saveRemoteControl(
                        bundledConfig,
                        safeOverrides,
                        RemoteConfigSyncer.extractBrandingUpdate(
                            rootObj,
                            boundRoot.ifBlank { config.baseUrl }
                        )
                    )
                }
                val latestConfig = if (bundledConfig != null) {
                    repository.getConfigSnapshot()
                } else {
                    config
                }
                val remoteControl = latestConfig.remoteControl

                var gpsFulfilled = RemoteConfigSyncer.handleRemoteGpsRequestObject(
                    sourceObj = rootObj,
                    config = latestConfig,
                    context = context,
                    repository = repository
                )

                val incomingVersion = rootObj.optString("task_version", "")
                var shouldRefresh = false
                if (incomingVersion.isNotBlank() && incomingVersion != lastVersion) {
                    if (lastVersion.isNotBlank() &&
                        remoteControl.taskRefreshMode == TaskRefreshMode.VERSION_AND_EVENTS
                    ) {
                        shouldRefresh = true
                    }
                    repository.setLastTaskVersion(incomingVersion)
                }

                val itemsArray = rootObj.optJSONArray("notifications") ?: JSONArray()
                var newCount = 0

                val canDeliverAlerts = remoteControl.notificationsEnabled &&
                    remoteControl.alertDeliveryMode != AlertDeliveryMode.OFF

                for (i in 0 until itemsArray.length()) {
                    val obj = itemsArray.optJSONObject(i) ?: continue
                    val notif = parseNotification(obj, latestConfig) ?: continue
                    if (!matchesPanelRoleAndUser(notif, latestConfig)) continue

                    if (notif.type == NotificationEventType.GPS_REQUEST) {
                        val snap = OnDemandGpsCapturer(context, repository).captureAndUploadIfAllowed(
                            trigger = GpsUploadTrigger.PANEL_REQUEST,
                            requestId = notif.id,
                            isBackground = true
                        )
                        if (snap != null) {
                            gpsFulfilled = true
                        }
                        continue
                    }

                    if (remoteControl.allowedEventTypes.isNotEmpty() &&
                        notif.type.key.lowercase() !in remoteControl.allowedEventTypes
                    ) {
                        continue
                    }

                    val isNew = repository.recordIncomingNotification(notif, deduplicate = true)
                    if (isNew) {
                        newCount++
                        if (remoteControl.taskRefreshMode != TaskRefreshMode.OFF &&
                            (notif.type == NotificationEventType.TASK_COMPLETED ||
                                notif.type == NotificationEventType.TASK_ASSIGNED)
                        ) {
                            shouldRefresh = true
                        }
                        if (showSystemNotifications && canDeliverAlerts) {
                            NotificationHelper.showNotification(
                                context,
                                notif,
                                remoteControl.alertDeliveryMode
                            )
                        }
                    }
                }

                val effectiveAutoRefresh = shouldRefresh &&
                    latestConfig.autoRefreshOnTaskComplete &&
                    remoteControl.taskAutoRefreshEnabled &&
                    remoteControl.taskRefreshMode != TaskRefreshMode.OFF

                val statusMsg = when {
                    gpsFulfilled -> "প্যানেল রিকোয়েস্টে GPS পাঠানো হয়েছে"
                    newCount > 0 -> "$newCount টি নতুন নোটিফিকেশন পাওয়া গেছে"
                    else -> "সার্ভারের সাথে সিঙ্ক সম্পন্ন (Synced)"
                }
                repository.updateLastSync(now, statusMsg)

                PollSyncResult(
                    success = true,
                    newNotificationsCount = newCount,
                    shouldAutoRefreshTasks = effectiveAutoRefresh,
                    gpsRequestFulfilled = gpsFulfilled,
                    statusMessage = statusMsg
                )
            }
        } catch (ex: Exception) {
            val now = System.currentTimeMillis()
            val msg = "সংযোগ ত্রুটি: ${ex.localizedMessage ?: "Network unavailable"}"
            repository.updateLastSync(now, msg)
            PollSyncResult(
                success = false,
                newNotificationsCount = 0,
                shouldAutoRefreshTasks = false,
                statusMessage = msg
            )
        }
    }

    private fun buildPollUrl(
        config: PortalConfig,
        lastTaskVersion: String,
        lastSyncMillis: Long
    ): String {
        return Uri.parse(config.notificationApiUrl)
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
                if (lastTaskVersion.isNotBlank()) {
                    appendQueryParameter("since_version", lastTaskVersion)
                }
                if (lastSyncMillis > 0L) {
                    appendQueryParameter("since", (lastSyncMillis / 1000L).toString())
                }
            }
            .build()
            .toString()
    }

    private fun parseNotification(obj: JSONObject, config: PortalConfig): StoreNotification? {
        val id = obj.optString("id").ifBlank {
            "${obj.optString("type")}_${obj.optLong("timestamp", System.currentTimeMillis())}_${obj.optString("title").hashCode()}"
        }
        val type = NotificationEventType.fromKey(obj.optString("type"))
        val rawTitle = obj.optString("title").trim()
        val title = if (rawTitle.isBlank()) {
            if (type == NotificationEventType.GPS_REQUEST) "GPS লোকেশন অনুরোধ" else return null
        } else {
            rawTitle
        }
        val message = obj.optString("message").ifBlank { title }
        val defaultTarget = when (type) {
            NotificationEventType.ATTENDANCE_SUBMITTED -> config.attendanceUrl
            NotificationEventType.TASK_ASSIGNED, NotificationEventType.TASK_COMPLETED -> config.tasksUrl
            NotificationEventType.GPS_REQUEST, NotificationEventType.GENERAL -> config.baseUrl
        }
        val targetUrl = obj.optString("target_url").ifBlank { defaultTarget }
        val rawTimestamp = obj.optLong("timestamp", System.currentTimeMillis())
        val millis = if (rawTimestamp in 1L..9_999_999_999L) rawTimestamp * 1000L else rawTimestamp

        return StoreNotification(
            id = id,
            type = type,
            title = title,
            message = message,
            actorName = obj.optString("actor_name"),
            panelId = obj.optString("panel_id").trim(),
            targetRole = obj.optString("target_role", "all").lowercase(),
            targetUserId = obj.optString("target_user_id"),
            targetUrl = targetUrl,
            timestampMillis = millis,
            isRead = false
        )
    }

    private fun matchesPanelRoleAndUser(notif: StoreNotification, config: PortalConfig): Boolean {
        // Strict panel isolation: reject any notification belonging to a different panel
        if (config.panelId.isNotBlank() &&
            notif.panelId.isNotBlank() &&
            !notif.panelId.equals(config.panelId, ignoreCase = true)
        ) {
            return false
        }

        val roleMatch = notif.targetRole.isBlank() ||
            notif.targetRole == "all" ||
            notif.targetRole.equals(config.userRole.key, ignoreCase = true)
        if (!roleMatch) return false

        return notif.targetUserId.isBlank() ||
            config.userId.isBlank() ||
            notif.targetUserId.equals(config.userId, ignoreCase = true)
    }
}
