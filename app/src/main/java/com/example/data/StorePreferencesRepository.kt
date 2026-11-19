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
import com.example.model.AlertDeliveryMode
import com.example.model.AppTab
import com.example.model.DEFAULT_ATTENDANCE_URL
import com.example.model.DEFAULT_BASE_URL
import com.example.model.DEFAULT_CONFIG_API_URL
import com.example.model.DEFAULT_GENERIC_PORTAL_URL
import com.example.model.DEFAULT_LOCATION_API_URL
import com.example.model.DEFAULT_NOTIFICATION_API_URL
import com.example.model.DEFAULT_SYNC_GPS_API_URL
import com.example.model.DEFAULT_TASKS_URL
import com.example.model.GpsCollectionMode
import com.example.model.LocationSnapshot
import com.example.model.NotificationEventType
import com.example.model.PortalConfig
import com.example.model.RemoteBottomTab
import com.example.model.RemoteControlSettings
import com.example.model.RemoteTopTab
import com.example.model.StoreBrandingUpdate
import com.example.model.StoreNotification
import com.example.model.TaskRefreshMode
import com.example.model.UserRole
import com.example.model.defaultBottomTabs
import com.example.model.defaultTopTabs
import com.example.sync.DeviceTelemetryHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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
        val SYNC_GPS_API_URL = stringPreferencesKey("sync_gps_api_url")
        val CONFIG_API_URL = stringPreferencesKey("config_api_url")
        val IS_BOUND = booleanPreferencesKey("is_bound")
        val BOUND_STORE_URL = stringPreferencesKey("bound_store_url")
        val STORE_CODE = stringPreferencesKey("store_code")
        val STORE_NAME = stringPreferencesKey("store_name")
        val APP_TITLE = stringPreferencesKey("app_title")
        val LOGO_URL = stringPreferencesKey("logo_url")
        val THEME_COLOR_HEX = stringPreferencesKey("theme_color_hex")
        val DEVICE_UUID = stringPreferencesKey("device_uuid")
        val PANEL_ID = stringPreferencesKey("panel_id")
        val USER_ROLE = stringPreferencesKey("user_role")
        val USER_ID = stringPreferencesKey("user_id")
        val USER_NAME = stringPreferencesKey("user_name")
        val IS_LOGGED_IN = booleanPreferencesKey("is_logged_in")
        val SESSION_COOKIE = stringPreferencesKey("session_cookie")
        val AUTO_REFRESH_ON_COMPLETE = booleanPreferencesKey("auto_refresh_on_complete")
        val BACKGROUND_SYNC_ENABLED = booleanPreferencesKey("background_sync_enabled")
        val GPS_TRACKING_ENABLED = booleanPreferencesKey("gps_tracking_enabled")
        val REMOTE_CONTROL_JSON = stringPreferencesKey("remote_control_json")
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
        val LAST_GPS_UPLOAD_TIME = longPreferencesKey("last_gps_upload_time")
        val LAST_GPS_SERVER_UPLOAD_TIME = longPreferencesKey("last_gps_server_upload_time")
        val GPS_SCHEDULE_ANCHOR_TIME = longPreferencesKey("gps_schedule_anchor_time")
        val LAST_FULFILLED_GPS_REQUEST_ID = stringPreferencesKey("last_fulfilled_gps_request_id")
        val PENDING_LOCATIONS_JSON = stringPreferencesKey("pending_locations_json")
        val PREVIOUS_TAB = stringPreferencesKey("previous_tab")
        val PREVIOUS_TAB_ID = stringPreferencesKey("previous_tab_id")
        val PREVIOUS_URL = stringPreferencesKey("previous_url")
    }

    val configFlow: Flow<PortalConfig> = context.banglaStoreDataStore.data.map { prefs ->
        val boundStore = prefs[Keys.BOUND_STORE_URL].orEmpty().trim().trimEnd('/')
        val isBound = (prefs[Keys.IS_BOUND] ?: false) && boundStore.isNotBlank()
        val legacyTab = AppTab.fromRoute(prefs[Keys.PREVIOUS_TAB])
        val storedTabId = prefs[Keys.PREVIOUS_TAB_ID]?.takeIf { it.isNotBlank() } ?: legacyTab.route

        if (!isBound) {
            // Unbound startup: NEVER auto-connect to any store's API/GPS/notifications.
            // Only display the default generic portal login page.
            return@map PortalConfig(
                isBound = false,
                baseUrl = DEFAULT_GENERIC_PORTAL_URL,
                attendanceUrl = "",
                tasksUrl = "",
                notificationApiUrl = "",
                locationApiUrl = "",
                syncGpsApiUrl = "",
                configApiUrl = "",
                boundStoreUrl = "",
                storeCode = "",
                storeName = "",
                appTitle = "",
                logoUrl = "",
                themeColorHex = "",
                panelId = "",
                userRole = UserRole.fromKey(prefs[Keys.USER_ROLE]),
                userId = "",
                userName = "",
                isLoggedIn = false,
                previousTab = AppTab.PORTAL,
                previousTabId = AppTab.PORTAL.route,
                previousUrl = "",
                autoRefreshOnTaskComplete = prefs[Keys.AUTO_REFRESH_ON_COMPLETE] ?: true,
                backgroundSyncEnabled = prefs[Keys.BACKGROUND_SYNC_ENABLED] ?: true,
                gpsTrackingEnabled = prefs[Keys.GPS_TRACKING_ENABLED] ?: true,
                remoteControl = RemoteControlSettings(
                    topTabs = emptyList(),
                    bottomTabs = emptyList(),
                    notificationsEnabled = false,
                    backgroundSyncEnabled = false,
                    gpsEnabled = false,
                    gpsMode = GpsCollectionMode.OFF,
                    gpsBackgroundEnabled = false,
                    taskAutoRefreshEnabled = false
                )
            )
        }

        val fallbackLoginUrl = "$boundStore/login"
        val effectiveBase = prefs[Keys.BASE_URL]?.ifBlank { null } ?: fallbackLoginUrl
        val storedAtt = prefs[Keys.ATTENDANCE_URL]
        val storedTasks = prefs[Keys.TASKS_URL]

        val fallbackAtt = "$boundStore/dashboard.php?staff_view=attendance"
        val fallbackTasks = "$boundStore/tasks.php"
        val fallbackNotifApi = "$boundStore/api/notifications.php"
        val fallbackLocApi = "$boundStore/api/location.php"
        val fallbackSyncGpsApi = "$boundStore/api/sync_gps.php"
        val fallbackConfigApi = "$boundStore/api/app_config.php"

        val storeDisplayName = prefs[Keys.APP_TITLE].orEmpty().ifBlank {
            prefs[Keys.STORE_NAME].orEmpty().ifBlank { "বাংলা স্টোর" }
        }
        val parsedRemote = parseRemoteControlJson(
            raw = prefs[Keys.REMOTE_CONTROL_JSON],
            fallbackBaseUrl = effectiveBase,
            fallbackAttendanceUrl = fallbackAtt,
            fallbackTasksUrl = fallbackTasks,
            fallbackPrimaryLabel = storeDisplayName,
            includeDefaultIsp = false
        )

        PortalConfig(
            isBound = true,
            baseUrl = effectiveBase,
            attendanceUrl = storedAtt?.ifBlank { fallbackAtt } ?: fallbackAtt,
            tasksUrl = storedTasks?.ifBlank { fallbackTasks } ?: fallbackTasks,
            notificationApiUrl = prefs[Keys.NOTIFICATION_API_URL]?.ifBlank { fallbackNotifApi }
                ?: fallbackNotifApi,
            locationApiUrl = prefs[Keys.LOCATION_API_URL]?.ifBlank { fallbackLocApi }
                ?: fallbackLocApi,
            syncGpsApiUrl = prefs[Keys.SYNC_GPS_API_URL]?.ifBlank { fallbackSyncGpsApi }
                ?: fallbackSyncGpsApi,
            configApiUrl = prefs[Keys.CONFIG_API_URL]?.ifBlank { fallbackConfigApi }
                ?: fallbackConfigApi,
            boundStoreUrl = boundStore,
            storeCode = prefs[Keys.STORE_CODE].orEmpty(),
            storeName = prefs[Keys.STORE_NAME].orEmpty(),
            appTitle = prefs[Keys.APP_TITLE].orEmpty(),
            logoUrl = prefs[Keys.LOGO_URL].orEmpty(),
            themeColorHex = prefs[Keys.THEME_COLOR_HEX].orEmpty(),
            panelId = prefs[Keys.PANEL_ID].orEmpty(),
            userRole = UserRole.fromKey(prefs[Keys.USER_ROLE]),
            userId = prefs[Keys.USER_ID].orEmpty(),
            userName = prefs[Keys.USER_NAME].orEmpty(),
            isLoggedIn = prefs[Keys.IS_LOGGED_IN] ?: false,
            previousTab = legacyTab,
            previousTabId = storedTabId,
            previousUrl = prefs[Keys.PREVIOUS_URL].orEmpty(),
            autoRefreshOnTaskComplete = prefs[Keys.AUTO_REFRESH_ON_COMPLETE] ?: true,
            backgroundSyncEnabled = prefs[Keys.BACKGROUND_SYNC_ENABLED] ?: true,
            gpsTrackingEnabled = prefs[Keys.GPS_TRACKING_ENABLED] ?: true,
            remoteControl = parsedRemote
        )
    }

    val notificationsFlow: Flow<List<StoreNotification>> = context.banglaStoreDataStore.data.map { prefs ->
        val currentPanel = prefs[Keys.PANEL_ID].orEmpty().trim()
        parseNotificationsJson(prefs[Keys.NOTIFICATIONS_JSON])
            .filter { notif ->
                currentPanel.isBlank() ||
                    notif.panelId.isBlank() ||
                    notif.panelId.equals(currentPanel, ignoreCase = true)
            }
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
            syncStatus = prefs[Keys.LAST_LOCATION_STATUS] ?: "প্যানেল রিকোয়েস্ট বা ১ ঘণ্টা অন্তর প্রস্তুত"
        )
    }

    val pendingSyncCountFlow: Flow<Int> = context.banglaStoreDataStore.data.map { prefs ->
        val raw = prefs[Keys.PENDING_LOCATIONS_JSON]
        if (raw.isNullOrBlank()) 0 else runCatching { JSONArray(raw).length() }.getOrDefault(0)
    }

    suspend fun getConfigSnapshot(): PortalConfig = configFlow.first()

    suspend fun getLatestLocationSnapshot(): LocationSnapshot = locationFlow.first()

    suspend fun saveConfig(config: PortalConfig) {
        context.banglaStoreDataStore.edit { prefs ->
            val boundRoot = prefs[Keys.BOUND_STORE_URL].orEmpty().trim().trimEnd('/')
            if (boundRoot.isNotBlank()) {
                prefs[Keys.BASE_URL] = sanitizeHttpsUrl(config.baseUrl, "$boundRoot/login")
                prefs[Keys.ATTENDANCE_URL] = sanitizeHttpsUrl(
                    config.attendanceUrl,
                    "$boundRoot/dashboard.php?staff_view=attendance"
                )
                prefs[Keys.TASKS_URL] = sanitizeHttpsUrl(config.tasksUrl, "$boundRoot/tasks.php")
                prefs[Keys.NOTIFICATION_API_URL] = sanitizeHttpsUrl(
                    config.notificationApiUrl,
                    "$boundRoot/api/notifications.php"
                )
                prefs[Keys.LOCATION_API_URL] = sanitizeHttpsUrl(
                    config.locationApiUrl,
                    "$boundRoot/api/location.php"
                )
                prefs[Keys.SYNC_GPS_API_URL] = sanitizeHttpsUrl(
                    config.syncGpsApiUrl,
                    "$boundRoot/api/sync_gps.php"
                )
                prefs[Keys.CONFIG_API_URL] = sanitizeHttpsUrl(
                    config.configApiUrl,
                    "$boundRoot/api/app_config.php"
                )
                prefs[Keys.PANEL_ID] = config.panelId.trim()
                prefs[Keys.USER_ROLE] = config.userRole.key
                prefs[Keys.USER_ID] = config.userId.trim()
                prefs[Keys.USER_NAME] = config.userName.trim()
            }
            prefs[Keys.AUTO_REFRESH_ON_COMPLETE] = config.autoRefreshOnTaskComplete
            prefs[Keys.BACKGROUND_SYNC_ENABLED] = config.backgroundSyncEnabled
            prefs[Keys.GPS_TRACKING_ENABLED] = config.gpsTrackingEnabled
        }
    }

    suspend fun saveRemoteControl(
        settings: RemoteControlSettings,
        urlOverrides: Map<String, String> = emptyMap(),
        brandingUpdate: StoreBrandingUpdate? = null
    ) {
        context.banglaStoreDataStore.edit { prefs ->
            prefs[Keys.REMOTE_CONTROL_JSON] = serializeRemoteControlJson(settings)
            urlOverrides["base_url"]?.let { prefs[Keys.BASE_URL] = sanitizeHttpsUrl(it, DEFAULT_BASE_URL) }
            urlOverrides["attendance_url"]?.let {
                prefs[Keys.ATTENDANCE_URL] = sanitizeHttpsUrl(it, DEFAULT_ATTENDANCE_URL)
            }
            urlOverrides["tasks_url"]?.let { prefs[Keys.TASKS_URL] = sanitizeHttpsUrl(it, DEFAULT_TASKS_URL) }
            urlOverrides["notification_api_url"]?.let {
                prefs[Keys.NOTIFICATION_API_URL] = sanitizeHttpsUrl(it, DEFAULT_NOTIFICATION_API_URL)
            }
            urlOverrides["location_api_url"]?.let {
                prefs[Keys.LOCATION_API_URL] = sanitizeHttpsUrl(it, DEFAULT_LOCATION_API_URL)
            }
            urlOverrides["sync_gps_api_url"]?.let {
                prefs[Keys.SYNC_GPS_API_URL] = sanitizeHttpsUrl(it, DEFAULT_SYNC_GPS_API_URL)
            }
            urlOverrides["config_api_url"]?.let {
                prefs[Keys.CONFIG_API_URL] = sanitizeHttpsUrl(it, DEFAULT_CONFIG_API_URL)
            }
            if (brandingUpdate != null) {
                brandingUpdate.storeCode?.takeIf { it.isNotBlank() }?.let { prefs[Keys.STORE_CODE] = it.trim() }
                brandingUpdate.storeName?.takeIf { it.isNotBlank() }?.let { prefs[Keys.STORE_NAME] = it.trim() }
                brandingUpdate.appTitle?.takeIf { it.isNotBlank() }?.let { prefs[Keys.APP_TITLE] = it.trim() }
                brandingUpdate.logoUrl?.takeIf { it.isNotBlank() }?.let {
                    prefs[Keys.LOGO_URL] = sanitizeHttpsUrl(it, "")
                }
                brandingUpdate.themeColorHex?.takeIf { it.isNotBlank() }?.let {
                    prefs[Keys.THEME_COLOR_HEX] = it.trim()
                }
            }
        }
    }

    suspend fun applyShopBindingAndResetSession(
        boundStoreUrl: String,
        storeCode: String,
        storeName: String,
        appTitle: String,
        logoUrl: String,
        themeColorHex: String,
        panelId: String,
        baseUrl: String,
        attendanceUrl: String,
        tasksUrl: String,
        notificationApiUrl: String,
        locationApiUrl: String,
        syncGpsApiUrl: String,
        configApiUrl: String,
        remoteControl: RemoteControlSettings
    ) {
        val cleanBound = sanitizeHttpsUrl(boundStoreUrl, DEFAULT_BASE_URL).trimEnd('/')
        context.banglaStoreDataStore.edit { prefs ->
            // Complete tenant reset so previous shop's session/notifications/GPS queue never cross over
            prefs[Keys.IS_LOGGED_IN] = false
            prefs[Keys.USER_ID] = ""
            prefs[Keys.USER_NAME] = ""
            prefs[Keys.SESSION_COOKIE] = ""
            prefs[Keys.NOTIFICATIONS_JSON] = "[]"
            prefs[Keys.SEEN_EVENT_IDS] = emptySet()
            prefs[Keys.LAST_TASK_VERSION] = ""
            prefs[Keys.PENDING_LOCATIONS_JSON] = "[]"
            prefs[Keys.LAST_FULFILLED_GPS_REQUEST_ID] = ""
            prefs[Keys.LAST_GPS_UPLOAD_TIME] = 0L
            prefs[Keys.LAST_GPS_SERVER_UPLOAD_TIME] = 0L
            prefs[Keys.PREVIOUS_TAB] = AppTab.PORTAL.route
            prefs[Keys.PREVIOUS_TAB_ID] = AppTab.PORTAL.route
            prefs[Keys.PREVIOUS_URL] = ""

            // Persist new bound shop metadata & endpoints
            prefs[Keys.IS_BOUND] = true
            prefs[Keys.BOUND_STORE_URL] = cleanBound
            prefs[Keys.STORE_CODE] = storeCode.trim()
            prefs[Keys.STORE_NAME] = storeName.trim()
            prefs[Keys.APP_TITLE] = appTitle.trim()
            prefs[Keys.LOGO_URL] = if (logoUrl.isBlank()) "" else sanitizeHttpsUrl(logoUrl, "")
            prefs[Keys.THEME_COLOR_HEX] = themeColorHex.trim()
            prefs[Keys.PANEL_ID] = panelId.trim().ifBlank { storeCode.trim() }

            prefs[Keys.BASE_URL] = sanitizeHttpsUrl(baseUrl, "$cleanBound/login")
            prefs[Keys.ATTENDANCE_URL] = sanitizeHttpsUrl(attendanceUrl, "$cleanBound/dashboard.php?staff_view=attendance")
            prefs[Keys.TASKS_URL] = sanitizeHttpsUrl(tasksUrl, "$cleanBound/tasks.php")
            prefs[Keys.NOTIFICATION_API_URL] = sanitizeHttpsUrl(notificationApiUrl, "$cleanBound/api/notifications.php")
            prefs[Keys.LOCATION_API_URL] = sanitizeHttpsUrl(locationApiUrl, "$cleanBound/api/location.php")
            prefs[Keys.SYNC_GPS_API_URL] = sanitizeHttpsUrl(syncGpsApiUrl, "$cleanBound/api/sync_gps.php")
            prefs[Keys.CONFIG_API_URL] = sanitizeHttpsUrl(configApiUrl, "$cleanBound/api/app_config.php")
            prefs[Keys.REMOTE_CONTROL_JSON] = serializeRemoteControlJson(remoteControl)
            prefs[Keys.LAST_SYNC_TIME] = System.currentTimeMillis()
            prefs[Keys.LAST_SYNC_STATUS] = "নতুন শপ বাইন্ডিং সম্পন্ন (${storeName.ifBlank { storeCode.ifBlank { cleanBound } }})"
        }
    }

    suspend fun resetToDefaultStore() {
        context.banglaStoreDataStore.edit { prefs ->
            prefs[Keys.IS_BOUND] = false
            prefs[Keys.BOUND_STORE_URL] = ""
            prefs[Keys.STORE_CODE] = ""
            prefs[Keys.STORE_NAME] = ""
            prefs[Keys.APP_TITLE] = ""
            prefs[Keys.LOGO_URL] = ""
            prefs[Keys.THEME_COLOR_HEX] = ""
            prefs[Keys.PANEL_ID] = ""
            prefs[Keys.BASE_URL] = DEFAULT_GENERIC_PORTAL_URL
            prefs[Keys.ATTENDANCE_URL] = ""
            prefs[Keys.TASKS_URL] = ""
            prefs[Keys.NOTIFICATION_API_URL] = ""
            prefs[Keys.LOCATION_API_URL] = ""
            prefs[Keys.SYNC_GPS_API_URL] = ""
            prefs[Keys.CONFIG_API_URL] = ""
            prefs[Keys.REMOTE_CONTROL_JSON] = ""
            prefs[Keys.IS_LOGGED_IN] = false
            prefs[Keys.USER_ID] = ""
            prefs[Keys.USER_NAME] = ""
            prefs[Keys.SESSION_COOKIE] = ""
            prefs[Keys.NOTIFICATIONS_JSON] = "[]"
            prefs[Keys.SEEN_EVENT_IDS] = emptySet()
            prefs[Keys.LAST_TASK_VERSION] = ""
            prefs[Keys.PENDING_LOCATIONS_JSON] = "[]"
            prefs[Keys.LAST_FULFILLED_GPS_REQUEST_ID] = ""
            prefs[Keys.LAST_GPS_UPLOAD_TIME] = 0L
            prefs[Keys.LAST_GPS_SERVER_UPLOAD_TIME] = 0L
            prefs[Keys.PREVIOUS_TAB] = AppTab.PORTAL.route
            prefs[Keys.PREVIOUS_TAB_ID] = AppTab.PORTAL.route
            prefs[Keys.PREVIOUS_URL] = ""
            prefs[Keys.LAST_SYNC_TIME] = 0L
            prefs[Keys.LAST_SYNC_STATUS] = "কোনো শপ যুক্ত নেই (Unbound)"
        }
    }

    suspend fun getOrCreateUniqueDeviceId(): String {
        val hardwareId = DeviceTelemetryHelper.getDeviceId(context)
        if (hardwareId.isNotBlank()) return hardwareId

        var deviceId = ""
        context.banglaStoreDataStore.edit { prefs ->
            val existing = prefs[Keys.DEVICE_UUID].orEmpty().trim()
            if (existing.isNotBlank()) {
                deviceId = existing
            } else {
                deviceId = UUID.randomUUID().toString()
                prefs[Keys.DEVICE_UUID] = deviceId
            }
        }
        return deviceId
    }

    suspend fun getLastSyncTimeMillis(): Long {
        return context.banglaStoreDataStore.data.first()[Keys.LAST_SYNC_TIME] ?: 0L
    }

    suspend fun updateSessionFromWeb(
        role: UserRole?,
        userId: String?,
        userName: String?,
        panelId: String? = null
    ) {
        context.banglaStoreDataStore.edit { prefs ->
            val isBound = (prefs[Keys.IS_BOUND] ?: false) &&
                prefs[Keys.BOUND_STORE_URL].orEmpty().isNotBlank()
            if (!isBound) {
                return@edit
            }

            val cleanNewPanel = panelId?.trim().orEmpty()
            val oldPanel = prefs[Keys.PANEL_ID].orEmpty().trim()
            val boundStoreCode = prefs[Keys.STORE_CODE].orEmpty().trim()
            val cleanNewUser = userId?.trim().orEmpty()
            val oldUser = prefs[Keys.USER_ID].orEmpty().trim()

            // Strict tenant lock: when bound to a specific panel, ignore conflicting cross-panel meta tags
            val lockedPanel = oldPanel.ifBlank { boundStoreCode }
            if (lockedPanel.isNotBlank() &&
                cleanNewPanel.isNotBlank() &&
                !cleanNewPanel.equals(lockedPanel, ignoreCase = true)
            ) {
                return@edit
            }

            val userChanged = cleanNewUser.isNotBlank() &&
                oldUser.isNotBlank() &&
                !cleanNewUser.equals(oldUser, ignoreCase = true)

            if (userChanged) {
                prefs[Keys.NOTIFICATIONS_JSON] = "[]"
                prefs[Keys.SEEN_EVENT_IDS] = emptySet()
                prefs[Keys.LAST_TASK_VERSION] = ""
                prefs[Keys.PREVIOUS_URL] = ""
                prefs[Keys.LAST_FULFILLED_GPS_REQUEST_ID] = ""
                prefs[Keys.LAST_GPS_UPLOAD_TIME] = 0L
            }

            if (oldPanel.isBlank() && cleanNewPanel.isNotBlank()) {
                prefs[Keys.PANEL_ID] = cleanNewPanel
            }
            if (role != null) prefs[Keys.USER_ROLE] = role.key
            if (cleanNewUser.isNotBlank()) {
                prefs[Keys.USER_ID] = cleanNewUser
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
            val cleanUser = userId.trim()
            val oldUser = prefs[Keys.USER_ID].orEmpty().trim()
            if (oldUser.isNotBlank() && !cleanUser.equals(oldUser, ignoreCase = true)) {
                prefs[Keys.NOTIFICATIONS_JSON] = "[]"
                prefs[Keys.SEEN_EVENT_IDS] = emptySet()
                prefs[Keys.LAST_TASK_VERSION] = ""
                prefs[Keys.REMOTE_CONTROL_JSON] = ""
                prefs[Keys.LAST_FULFILLED_GPS_REQUEST_ID] = ""
                prefs[Keys.LAST_GPS_UPLOAD_TIME] = 0L
            }
            prefs[Keys.USER_ID] = cleanUser
            prefs[Keys.IS_LOGGED_IN] = true
        }
    }

    suspend fun clearLoginSession() {
        context.banglaStoreDataStore.edit { prefs ->
            val isBound = prefs[Keys.BOUND_STORE_URL].orEmpty().isNotBlank()
            prefs[Keys.IS_LOGGED_IN] = false
            prefs[Keys.USER_ID] = ""
            prefs[Keys.USER_NAME] = ""
            prefs[Keys.SESSION_COOKIE] = ""
            prefs[Keys.NOTIFICATIONS_JSON] = "[]"
            prefs[Keys.SEEN_EVENT_IDS] = emptySet()
            prefs[Keys.LAST_TASK_VERSION] = ""
            prefs[Keys.LAST_FULFILLED_GPS_REQUEST_ID] = ""
            prefs[Keys.LAST_GPS_UPLOAD_TIME] = 0L
            if (isBound) {
                prefs[Keys.PANEL_ID] = prefs[Keys.STORE_CODE].orEmpty()
            } else {
                prefs[Keys.PANEL_ID] = ""
                prefs[Keys.REMOTE_CONTROL_JSON] = ""
            }
        }
    }

    suspend fun savePreviousWebLocation(tab: AppTab, url: String) {
        savePreviousTabLocation(tabId = tab.route, legacyTab = tab, url = url)
    }

    suspend fun savePreviousTabLocation(tabId: String, legacyTab: AppTab, url: String) {
        if (tabId == AppTab.ALERTS.route) return
        val cleanUrl = url.trim()
        if (cleanUrl.isNotBlank() && !cleanUrl.startsWith("http", ignoreCase = true)) return
        context.banglaStoreDataStore.edit { prefs ->
            prefs[Keys.PREVIOUS_TAB] = legacyTab.route
            prefs[Keys.PREVIOUS_TAB_ID] = tabId.ifBlank { legacyTab.route }
            if (cleanUrl.isNotBlank()) {
                prefs[Keys.PREVIOUS_URL] = cleanUrl
            }
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

    suspend fun enqueuePendingLocation(payload: JSONObject) {
        context.banglaStoreDataStore.edit { prefs ->
            val raw = prefs[Keys.PENDING_LOCATIONS_JSON]
            val array = if (raw.isNullOrBlank()) {
                JSONArray()
            } else {
                runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
            }
            array.put(payload)
            // Cap queue at 200 most recent offline points
            val trimmed = if (array.length() > 200) {
                val next = JSONArray()
                for (i in (array.length() - 200) until array.length()) {
                    next.put(array.get(i))
                }
                next
            } else {
                array
            }
            prefs[Keys.PENDING_LOCATIONS_JSON] = trimmed.toString()
        }
    }

    suspend fun getPendingLocations(): List<JSONObject> {
        val raw = context.banglaStoreDataStore.data.first()[Keys.PENDING_LOCATIONS_JSON]
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun setPendingLocations(remaining: List<JSONObject>) {
        context.banglaStoreDataStore.edit { prefs ->
            val array = JSONArray()
            remaining.forEach { array.put(it) }
            prefs[Keys.PENDING_LOCATIONS_JSON] = array.toString()
        }
    }

    suspend fun recordIncomingNotification(
        notification: StoreNotification,
        deduplicate: Boolean = true
    ): Boolean {
        var isNew = false
        context.banglaStoreDataStore.edit { prefs ->
            val currentPanel = prefs[Keys.PANEL_ID].orEmpty().trim()
            if (currentPanel.isNotBlank() &&
                notification.panelId.isNotBlank() &&
                !notification.panelId.equals(currentPanel, ignoreCase = true)
            ) {
                isNew = false
                return@edit
            }

            val scopedNotification = if (notification.panelId.isBlank() && currentPanel.isNotBlank()) {
                notification.copy(panelId = currentPanel)
            } else {
                notification
            }

            val seenIds = prefs[Keys.SEEN_EVENT_IDS]?.toMutableSet() ?: mutableSetOf()
            if (deduplicate && seenIds.contains(scopedNotification.id)) {
                isNew = false
                return@edit
            }
            isNew = true
            seenIds.add(scopedNotification.id)
            val trimmedSeen = if (seenIds.size > 200) {
                seenIds.toList().takeLast(150).toSet()
            } else {
                seenIds
            }
            prefs[Keys.SEEN_EVENT_IDS] = trimmedSeen

            val current = parseNotificationsJson(prefs[Keys.NOTIFICATIONS_JSON]).toMutableList()
            current.removeAll { it.id == scopedNotification.id }
            current.add(0, scopedNotification)
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

    suspend fun getLastGpsUploadTime(): Long {
        return context.banglaStoreDataStore.data.first()[Keys.LAST_GPS_UPLOAD_TIME] ?: 0L
    }

    suspend fun markGpsUploadTime(timestampMillis: Long) {
        context.banglaStoreDataStore.edit { prefs ->
            prefs[Keys.LAST_GPS_UPLOAD_TIME] = timestampMillis
        }
    }

    suspend fun getLastGpsServerUploadTime(): Long {
        return context.banglaStoreDataStore.data.first()[Keys.LAST_GPS_SERVER_UPLOAD_TIME] ?: 0L
    }

    suspend fun markGpsServerUploadTime(timestampMillis: Long) {
        context.banglaStoreDataStore.edit { prefs ->
            prefs[Keys.LAST_GPS_SERVER_UPLOAD_TIME] = timestampMillis
            prefs[Keys.LAST_GPS_UPLOAD_TIME] = timestampMillis
        }
    }

    suspend fun ensureAndGetGpsScheduleAnchorTime(): Long {
        var result = 0L
        context.banglaStoreDataStore.edit { prefs ->
            val existing = prefs[Keys.GPS_SCHEDULE_ANCHOR_TIME] ?: 0L
            if (existing > 0L) {
                result = existing
            } else {
                val now = System.currentTimeMillis()
                prefs[Keys.GPS_SCHEDULE_ANCHOR_TIME] = now
                result = now
            }
        }
        return result
    }

    suspend fun getGpsReferenceUploadOrAnchorTime(): Long {
        val prefs = context.banglaStoreDataStore.data.first()
        val serverTime = prefs[Keys.LAST_GPS_SERVER_UPLOAD_TIME] ?: 0L
        if (serverTime > 0L) return serverTime
        return prefs[Keys.GPS_SCHEDULE_ANCHOR_TIME] ?: 0L
    }

    suspend fun isGpsRequestFulfilled(requestId: String): Boolean {
        val clean = requestId.trim()
        if (clean.isEmpty()) return false
        return context.banglaStoreDataStore.data.first()[Keys.LAST_FULFILLED_GPS_REQUEST_ID]
            .orEmpty() == clean
    }

    suspend fun markGpsRequestFulfilled(requestId: String) {
        val clean = requestId.trim()
        if (clean.isEmpty()) return
        context.banglaStoreDataStore.edit { prefs ->
            prefs[Keys.LAST_FULFILLED_GPS_REQUEST_ID] = clean
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
                            panelId = obj.optString("panel_id"),
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
                put("panel_id", item.panelId)
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

    private fun parseRemoteControlJson(
        raw: String?,
        fallbackBaseUrl: String = DEFAULT_BASE_URL,
        fallbackAttendanceUrl: String = DEFAULT_ATTENDANCE_URL,
        fallbackTasksUrl: String = DEFAULT_TASKS_URL,
        fallbackPrimaryLabel: String = "বাংলা স্টোর",
        includeDefaultIsp: Boolean = true
    ): RemoteControlSettings {
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
        if (raw.isNullOrBlank()) {
            return RemoteControlSettings(topTabs = defaultTop, bottomTabs = defaultBottom)
        }
        return runCatching {
            val obj = JSONObject(raw)
            val topArr = obj.optJSONArray("top_tabs")
            val topTabs = if (topArr != null && topArr.length() > 0) {
                buildList {
                    for (i in 0 until topArr.length()) {
                        val t = topArr.optJSONObject(i) ?: continue
                        val id = t.optString("id").trim()
                        val label = t.optString("label").trim()
                        val url = t.optString("url").trim()
                        if (id.isNotBlank() && label.isNotBlank() && url.isNotBlank()) {
                            add(
                                RemoteTopTab(
                                    id = id,
                                    label = label,
                                    url = url,
                                    icon = t.optString("icon", "storefront"),
                                    roles = jsonArrayToList(t.optJSONArray("roles")).ifEmpty { listOf("all") },
                                    enabled = t.optBoolean("enabled", true),
                                    isPrimaryStore = t.optBoolean("is_primary_store", false)
                                )
                            )
                        }
                    }
                }.ifEmpty { defaultTop }
            } else {
                defaultTop
            }

            val bottomArr = obj.optJSONArray("bottom_tabs") ?: obj.optJSONArray("bottom_nav")
            val bottomTabs = if (bottomArr != null && bottomArr.length() > 0) {
                buildList {
                    for (i in 0 until bottomArr.length()) {
                        val b = bottomArr.optJSONObject(i) ?: continue
                        val id = b.optString("id").trim()
                        val label = b.optString("label").trim()
                        if (id.isNotBlank() && label.isNotBlank()) {
                            add(
                                RemoteBottomTab(
                                    id = id,
                                    label = label,
                                    url = b.optString("url"),
                                    icon = b.optString("icon", id),
                                    type = b.optString("type", if (id == "alerts") "alerts" else "web"),
                                    roles = jsonArrayToList(b.optJSONArray("roles")).ifEmpty { listOf("all") },
                                    enabled = b.optBoolean("enabled", true)
                                )
                            )
                        }
                    }
                }.ifEmpty { defaultBottom }
            } else {
                defaultBottom
            }

            RemoteControlSettings(
                configVersion = obj.optString("config_version", ""),
                panelName = obj.optString("panel_name", ""),
                topTabs = topTabs,
                bottomTabs = bottomTabs,
                notificationsEnabled = obj.optBoolean("notifications_enabled", true),
                alertDeliveryMode = AlertDeliveryMode.fromKey(obj.optString("alert_delivery_mode", "popup_sound")),
                syncIntervalSeconds = obj.optInt("sync_interval_seconds", 30).coerceIn(10, 3600),
                backgroundSyncEnabled = obj.optBoolean("background_sync_enabled", true),
                allowedEventTypes = jsonArrayToList(obj.optJSONArray("allowed_event_types")),
                gpsEnabled = obj.optBoolean("gps_enabled", true),
                gpsMode = GpsCollectionMode.fromKey(obj.optString("gps_mode", "on_demand_or_hourly")),
                gpsIntervalSeconds = obj.optInt("gps_interval_seconds", 3600).coerceIn(10, 86400),
                gpsMinDistanceMeters = obj.optDouble("gps_min_distance_meters", 10.0)
                    .toFloat()
                    .coerceIn(0f, 5000f),
                gpsBackgroundEnabled = obj.optBoolean("gps_background_enabled", true),
                taskAutoRefreshEnabled = obj.optBoolean("task_auto_refresh_enabled", true),
                taskRefreshMode = TaskRefreshMode.fromKey(obj.optString("task_refresh_mode", "version_and_events")),
                refreshCooldownSeconds = obj.optInt("refresh_cooldown_seconds", 20).coerceIn(5, 3600),
                refreshOnlyOnTabs = jsonArrayToList(obj.optJSONArray("refresh_only_on_tabs"))
            )
        }.getOrDefault(RemoteControlSettings(topTabs = defaultTop, bottomTabs = defaultBottom))
    }

    private fun serializeRemoteControlJson(settings: RemoteControlSettings): String {
        val obj = JSONObject().apply {
            put("config_version", settings.configVersion)
            put("panel_name", settings.panelName)
            put("notifications_enabled", settings.notificationsEnabled)
            put("alert_delivery_mode", settings.alertDeliveryMode.key)
            put("sync_interval_seconds", settings.syncIntervalSeconds)
            put("background_sync_enabled", settings.backgroundSyncEnabled)
            put("allowed_event_types", JSONArray(settings.allowedEventTypes))
            put("gps_enabled", settings.gpsEnabled)
            put("gps_mode", settings.gpsMode.key)
            put("gps_interval_seconds", settings.gpsIntervalSeconds)
            put("gps_min_distance_meters", settings.gpsMinDistanceMeters.toDouble())
            put("gps_background_enabled", settings.gpsBackgroundEnabled)
            put("task_auto_refresh_enabled", settings.taskAutoRefreshEnabled)
            put("task_refresh_mode", settings.taskRefreshMode.key)
            put("refresh_cooldown_seconds", settings.refreshCooldownSeconds)
            put("refresh_only_on_tabs", JSONArray(settings.refreshOnlyOnTabs))

            val topArr = JSONArray()
            settings.topTabs.forEach { tab ->
                topArr.put(
                    JSONObject().apply {
                        put("id", tab.id)
                        put("label", tab.label)
                        put("url", tab.url)
                        put("icon", tab.icon)
                        put("roles", JSONArray(tab.roles))
                        put("enabled", tab.enabled)
                        put("is_primary_store", tab.isPrimaryStore)
                    }
                )
            }
            put("top_tabs", topArr)

            val bottomArr = JSONArray()
            settings.bottomTabs.forEach { tab ->
                bottomArr.put(
                    JSONObject().apply {
                        put("id", tab.id)
                        put("label", tab.label)
                        put("url", tab.url)
                        put("icon", tab.icon)
                        put("type", tab.type)
                        put("roles", JSONArray(tab.roles))
                        put("enabled", tab.enabled)
                    }
                )
            }
            put("bottom_tabs", bottomArr)
        }
        return obj.toString()
    }

    private fun jsonArrayToList(array: JSONArray?): List<String> {
        if (array == null || array.length() == 0) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val v = array.optString(i).trim().lowercase()
                if (v.isNotBlank()) add(v)
            }
        }
    }
}
