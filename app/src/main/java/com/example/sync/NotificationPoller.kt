package com.example.sync

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import com.example.data.StorePreferencesRepository
import com.example.model.NotificationEventType
import com.example.model.PortalConfig
import com.example.model.StoreNotification
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
        val lastVersion = repository.getLastTaskVersion()

        val requestUrl = buildPollUrl(config, lastVersion)
        val cookie = runCatching { CookieManager.getInstance().getCookie(config.baseUrl) }
            .getOrNull().orEmpty()
            .ifBlank { repository.getSessionCookie() }

        val requestBuilder = Request.Builder()
            .url(requestUrl)
            .header("Accept", "application/json")
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
                if (bodyString.isEmpty() || !bodyString.startsWith("{") && !bodyString.startsWith("[")) {
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

                val incomingVersion = rootObj.optString("task_version", "")
                var shouldRefresh = false
                if (incomingVersion.isNotBlank() && incomingVersion != lastVersion) {
                    if (lastVersion.isNotBlank()) {
                        shouldRefresh = true
                    }
                    repository.setLastTaskVersion(incomingVersion)
                }

                val itemsArray = rootObj.optJSONArray("notifications") ?: JSONArray()
                var newCount = 0

                for (i in 0 until itemsArray.length()) {
                    val obj = itemsArray.optJSONObject(i) ?: continue
                    val notif = parseNotification(obj, config) ?: continue
                    if (!matchesRoleAndUser(notif, config)) continue

                    val isNew = repository.recordIncomingNotification(notif, deduplicate = true)
                    if (isNew) {
                        newCount++
                        if (notif.type == NotificationEventType.TASK_COMPLETED ||
                            notif.type == NotificationEventType.TASK_ASSIGNED
                        ) {
                            shouldRefresh = true
                        }
                        if (showSystemNotifications) {
                            NotificationHelper.showNotification(context, notif)
                        }
                    }
                }

                val statusMsg = if (newCount > 0) {
                    "$newCount টি নতুন নোটিফিকেশন পাওয়া গেছে"
                } else {
                    "সার্ভারের সাথে সিঙ্ক সম্পন্ন (Synced)"
                }
                repository.updateLastSync(now, statusMsg)

                PollSyncResult(
                    success = true,
                    newNotificationsCount = newCount,
                    shouldAutoRefreshTasks = shouldRefresh && config.autoRefreshOnTaskComplete,
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

    private fun buildPollUrl(config: PortalConfig, lastTaskVersion: String): String {
        return Uri.parse(config.notificationApiUrl)
            .buildUpon()
            .appendQueryParameter("role", config.userRole.key)
            .apply {
                if (config.userId.isNotBlank()) {
                    appendQueryParameter("user_id", config.userId)
                }
                if (lastTaskVersion.isNotBlank()) {
                    appendQueryParameter("since_version", lastTaskVersion)
                }
            }
            .build()
            .toString()
    }

    private fun parseNotification(obj: JSONObject, config: PortalConfig): StoreNotification? {
        val id = obj.optString("id").ifBlank {
            "${obj.optString("type")}_${obj.optLong("timestamp", System.currentTimeMillis())}_${obj.optString("title").hashCode()}"
        }
        val title = obj.optString("title").ifBlank { return null }
        val message = obj.optString("message").ifBlank { title }
        val type = NotificationEventType.fromKey(obj.optString("type"))
        val defaultTarget = when (type) {
            NotificationEventType.ATTENDANCE_SUBMITTED -> config.attendanceUrl
            NotificationEventType.TASK_ASSIGNED, NotificationEventType.TASK_COMPLETED -> config.tasksUrl
            NotificationEventType.GENERAL -> config.baseUrl
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
            targetRole = obj.optString("target_role", "all").lowercase(),
            targetUserId = obj.optString("target_user_id"),
            targetUrl = targetUrl,
            timestampMillis = millis,
            isRead = false
        )
    }

    private fun matchesRoleAndUser(notif: StoreNotification, config: PortalConfig): Boolean {
        val roleMatch = notif.targetRole.isBlank() ||
            notif.targetRole == "all" ||
            notif.targetRole.equals(config.userRole.key, ignoreCase = true)
        if (!roleMatch) return false

        return notif.targetUserId.isBlank() ||
            config.userId.isBlank() ||
            notif.targetUserId.equals(config.userId, ignoreCase = true)
    }
}
