package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.DEFAULT_ATTENDANCE_URL
import com.example.model.DEFAULT_BASE_URL
import com.example.model.DEFAULT_LOCATION_API_URL
import com.example.model.DEFAULT_NOTIFICATION_API_URL
import com.example.model.DEFAULT_TASKS_URL
import com.example.model.LocationSnapshot
import com.example.model.NotificationEventType
import com.example.model.PortalConfig
import com.example.model.StoreNotification
import com.example.model.UserRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.banglaStoreDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "bangla_store_prefs"
)

class StorePreferencesRepository(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val ATTENDANCE_URL = stringPreferencesKey("attendance_url")
        val TASKS_URL = stringPreferencesKey("tasks_url")
        val NOTIFICATION_API_URL = stringPreferencesKey("notification_api_url")
        val LOCATION_API_URL = stringPreferencesKey("location_api_url")
        val USER_ROLE = stringPreferencesKey("user_role")
        val USER_ID = stringPreferencesKey("user_id")
        val USER_NAME = stringPreferencesKey("user_name")
        val IS_LOGGED_IN = booleanPreferencesKey("is_logged_in")
        val SESSION_COOKIE = stringPreferencesKey("session_cookie")
        val AUTO_REFRESH_ON_COMPLETE = booleanPreferencesKey("auto_refresh_on_complete")
        val BACKGROUND_SYNC_ENABLED = booleanPreferencesKey("background_sync_enabled")
        val GPS_TRACKING_ENABLED = booleanPreferencesKey("gps_tracking_enabled")
        val NOTIFICATIONS_JSON = stringPreferencesKey("notifications_json")
        val SEEN_EVENT_IDS = stringSetPreferencesKey("seen_event_ids")
        val LAST_TASK_VERSION = stringPreferencesKey("last_task_version")
        val LAST_SYNC_TIME = longPreferencesKey("last_sync_time")
        val LAST_SYNC_STATUS = stringPreferencesKey("last_sync_status")
        val LAST_LAT = doublePreferencesKey("last_lat")
        val LAST_LNG = doublePreferencesKey("last_lng")
        val LAST_ACCURACY = floatPreferencesKey("last_accuracy")
        val LAST_LOCATION_TIME = longPreferencesKey("last_location_time")
        val LAST_LOCATION_STATUS = stringPreferencesKey("last_location_status")
    }

    val configFlow: Flow<PortalConfig> = context.banglaStoreDataStore.data.map { prefs ->
        val storedAtt = prefs[Keys.ATTENDANCE_URL]
        val storedTasks = prefs[Keys.TASKS_URL]
        PortalConfig(
            baseUrl = prefs[Keys.BASE_URL]?.ifBlank { DEFAULT_BASE_URL } ?: DEFAULT_BASE_URL,
            attendanceUrl = if (storedAtt.isNullOrBlank() || storedAtt == DEFAULT_BASE_URL) {
                DEFAULT_ATTENDANCE_URL
            } else {
                storedAtt
            },
            tasksUrl = if (storedTasks.isNullOrBlank() || storedTasks == DEFAULT_BASE_URL) {
                DEFAULT_TASKS_URL
            } else {
                storedTasks
            },
            notificationApiUrl = prefs[Keys.NOTIFICATION_API_URL]?.ifBlank { DEFAULT_NOTIFICATION_API_URL }
                ?: DEFAULT_NOTIFICATION_API_URL,
            locationApiUrl = prefs[Keys.LOCATION_API_URL]?.ifBlank { DEFAULT_LOCATION_API_URL }
                ?: DEFAULT_LOCATION_API_URL,
            userRole = UserRole.fromKey(prefs[Keys.USER_ROLE]),
            userId = prefs[Keys.USER_ID].orEmpty(),
            userName = prefs[Keys.USER_NAME].orEmpty(),
            isLoggedIn = prefs[Keys.IS_LOGGED_IN] ?: false,
            autoRefreshOnTaskComplete = prefs[Keys.AUTO_REFRESH_ON_COMPLETE] ?: true,
            backgroundSyncEnabled = prefs[Keys.BACKGROUND_SYNC_ENABLED] ?: true,
            gpsTrackingEnabled = prefs[Keys.GPS_TRACKING_ENABLED] ?: true
        )
    }

    val notificationsFlow: Flow<List<StoreNotification>> = context.banglaStoreDataStore.data.map { prefs ->
        parseNotificationsJson(prefs[Keys.NOTIFICATIONS_JSON])
            .sortedByDescending { it.timestampMillis }
    }

    val syncStatusFlow: Flow<Pair<Long, String>> = context.banglaStoreDataStore.data.map { prefs ->
        val time = prefs[Keys.LAST_SYNC_TIME] ?: 0L
        val status = prefs[Keys.LAST_SYNC_STATUS] ?: "সিঙ্ক প্রস্তুত (Ready)"
        time to status
    }

    val locationFlow: Flow<LocationSnapshot> = context.banglaStoreDataStore.data.map { prefs ->
        LocationSnapshot(
            latitude = prefs[Keys.LAST_LAT] ?: 0.0,
            longitude = prefs[Keys.LAST_LNG] ?: 0.0,
            accuracyMeters = prefs[Keys.LAST_ACCURACY] ?: 0f,
            timestampMillis = prefs[Keys.LAST_LOCATION_TIME] ?: 0L,
            syncStatus = prefs[Keys.LAST_LOCATION_STATUS] ?: "GPS অপেক্ষমাণ (Waiting)"
        )
    }

    suspend fun getConfigSnapshot(): PortalConfig = configFlow.first()

    suspend fun getLatestLocationSnapshot(): LocationSnapshot = locationFlow.first()

    suspend fun saveConfig(config: PortalConfig) {
        context.banglaStoreDataStore.edit { prefs ->
            prefs[Keys.BASE_URL] = sanitizeHttpsUrl(config.baseUrl, DEFAULT_BASE_URL)
            prefs[Keys.ATTENDANCE_URL] = sanitizeHttpsUrl(config.attendanceUrl, DEFAULT_ATTENDANCE_URL)
            prefs[Keys.TASKS_URL] = sanitizeHttpsUrl(config.tasksUrl, DEFAULT_TASKS_URL)
            prefs[Keys.NOTIFICATION_API_URL] = sanitizeHttpsUrl(
                config.notificationApiUrl,
                DEFAULT_NOTIFICATION_API_URL
            )
            prefs[Keys.LOCATION_API_URL] = sanitizeHttpsUrl(
                config.locationApiUrl,
                DEFAULT_LOCATION_API_URL
            )
            prefs[Keys.USER_ROLE] = config.userRole.key
            prefs[Keys.USER_ID] = config.userId.trim()
            prefs[Keys.USER_NAME] = config.userName.trim()
            prefs[Keys.AUTO_REFRESH_ON_COMPLETE] = config.autoRefreshOnTaskComplete
            prefs[Keys.BACKGROUND_SYNC_ENABLED] = config.backgroundSyncEnabled
            prefs[Keys.GPS_TRACKING_ENABLED] = config.gpsTrackingEnabled
        }
    }

    suspend fun updateSessionFromWeb(role: UserRole?, userId: String?, userName: String?) {
        context.banglaStoreDataStore.edit { prefs ->
            if (role != null) prefs[Keys.USER_ROLE] = role.key
            if (!userId.isNullOrBlank()) {
                prefs[Keys.USER_ID] = userId.trim()
                prefs[Keys.IS_LOGGED_IN] = true
            }
            if (!userName.isNullOrBlank()) prefs[Keys.USER_NAME] = userName.trim()
        }
    }

    suspend fun saveSessionCookie(cookie: String?) {
        if (cookie.isNullOrBlank()) return
        context.banglaStoreDataStore.edit { prefs ->
            prefs[Keys.SESSION_COOKIE] = cookie
        }
    }

    suspend fun getSessionCookie(): String {
        return context.banglaStoreDataStore.data.first()[Keys.SESSION_COOKIE].orEmpty()
    }

    suspend fun markLoggedInUserId(userId: String) {
        if (userId.isBlank()) return
        context.banglaStoreDataStore.edit { prefs ->
            prefs[Keys.USER_ID] = userId.trim()
            prefs[Keys.IS_LOGGED_IN] = true
        }
    }

    suspend fun clearLoginSession() {
        context.banglaStoreDataStore.edit { prefs ->
            prefs[Keys.IS_LOGGED_IN] = false
            prefs[Keys.USER_ID] = ""
            prefs[Keys.USER_NAME] = ""
            prefs[Keys.SESSION_COOKIE] = ""
        }
    }

    suspend fun saveLocationSnapshot(snapshot: LocationSnapshot) {
        context.banglaStoreDataStore.edit { prefs ->
            prefs[Keys.LAST_LAT] = snapshot.latitude
            prefs[Keys.LAST_LNG] = snapshot.longitude
            prefs[Keys.LAST_ACCURACY] = snapshot.accuracyMeters
            prefs[Keys.LAST_LOCATION_TIME] = snapshot.timestampMillis
            prefs[Keys.LAST_LOCATION_STATUS] = snapshot.syncStatus
        }
    }

    suspend fun recordIncomingNotification(
        notification: StoreNotification,
        deduplicate: Boolean = true
    ): Boolean {
        var isNew = false
        context.banglaStoreDataStore.edit { prefs ->
            val seenIds = prefs[Keys.SEEN_EVENT_IDS]?.toMutableSet() ?: mutableSetOf()
            if (deduplicate && seenIds.contains(notification.id)) {
                isNew = false
                return@edit
            }
            isNew = true
            seenIds.add(notification.id)
            val trimmedSeen = if (seenIds.size > 200) {
                seenIds.toList().takeLast(150).toSet()
            } else {
                seenIds
            }
            prefs[Keys.SEEN_EVENT_IDS] = trimmedSeen

            val current = parseNotificationsJson(prefs[Keys.NOTIFICATIONS_JSON]).toMutableList()
            current.removeAll { it.id == notification.id }
            current.add(0, notification)
            val capped = current.take(60)
            prefs[Keys.NOTIFICATIONS_JSON] = serializeNotificationsJson(capped)
        }
        return isNew
    }

    suspend fun markRead(notificationId: String) {
        context.banglaStoreDataStore.edit { prefs ->
            val updated = parseNotificationsJson(prefs[Keys.NOTIFICATIONS_JSON]).map { item ->
                if (item.id == notificationId) item.copy(isRead = true) else item
            }
            prefs[Keys.NOTIFICATIONS_JSON] = serializeNotificationsJson(updated)
        }
    }

    suspend fun markAllRead() {
        context.banglaStoreDataStore.edit { prefs ->
            val updated = parseNotificationsJson(prefs[Keys.NOTIFICATIONS_JSON]).map { item ->
                item.copy(isRead = true)
            }
            prefs[Keys.NOTIFICATIONS_JSON] = serializeNotificationsJson(updated)
        }
    }

    suspend fun clearNotifications() {
        context.banglaStoreDataStore.edit { prefs ->
            prefs[Keys.NOTIFICATIONS_JSON] = "[]"
        }
    }

    suspend fun getLastTaskVersion(): String {
        return context.banglaStoreDataStore.data.first()[Keys.LAST_TASK_VERSION].orEmpty()
    }

    suspend fun setLastTaskVersion(version: String) {
        context.banglaStoreDataStore.edit { prefs ->
            prefs[Keys.LAST_TASK_VERSION] = version
        }
    }

    suspend fun updateLastSync(timestampMillis: Long, statusMessage: String) {
        context.banglaStoreDataStore.edit { prefs ->
            prefs[Keys.LAST_SYNC_TIME] = timestampMillis
            prefs[Keys.LAST_SYNC_STATUS] = statusMessage
        }
    }

    private fun sanitizeHttpsUrl(raw: String, fallback: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return fallback
        return when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) ->
                "https://" + trimmed.removePrefix("http://").removePrefix("HTTP://")
            else -> "https://$trimmed"
        }
    }

    private fun parseNotificationsJson(raw: String?): List<StoreNotification> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(
                        StoreNotification(
                            id = obj.optString("id"),
                            type = NotificationEventType.fromKey(obj.optString("type")),
                            title = obj.optString("title"),
                            message = obj.optString("message"),
                            actorName = obj.optString("actor_name"),
                            targetRole = obj.optString("target_role", "all"),
                            targetUserId = obj.optString("target_user_id"),
                            targetUrl = obj.optString("target_url", DEFAULT_BASE_URL),
                            timestampMillis = obj.optLong("timestamp", System.currentTimeMillis()),
                            isRead = obj.optBoolean("is_read", false)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun serializeNotificationsJson(items: List<StoreNotification>): String {
        val array = JSONArray()
        items.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("type", item.type.key)
                put("title", item.title)
                put("message", item.message)
                put("actor_name", item.actorName)
                put("target_role", item.targetRole)
                put("target_user_id", item.targetUserId)
                put("target_url", item.targetUrl)
                put("timestamp", item.timestampMillis)
                put("is_read", item.isRead)
            }
            array.put(obj)
        }
        return array.toString()
    }
}
