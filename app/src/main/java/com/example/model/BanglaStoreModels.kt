package com.example.model

enum class UserRole(
    val key: String,
    val labelBn: String,
    val shortLabel: String
) {
    ADMIN(
        key = "admin",
        labelBn = "অ্যাডমিন (Admin)",
        shortLabel = "Admin"
    ),
    STAFF(
        key = "staff",
        labelBn = "স্টাফ / কর্মী (Staff)",
        shortLabel = "Staff"
    );

    companion object {
        fun fromKey(raw: String?): UserRole {
            return when (raw?.trim()?.lowercase()) {
                "staff", "employee", "user", "worker" -> STAFF
                else -> ADMIN
            }
        }
    }
}

enum class NotificationEventType(
    val key: String,
    val labelBn: String,
    val labelEn: String
) {
    ATTENDANCE_SUBMITTED(
        key = "attendance_submitted",
        labelBn = "অ্যাটেন্ডেন্স",
        labelEn = "Attendance"
    ),
    TASK_ASSIGNED(
        key = "task_assigned",
        labelBn = "নতুন টাস্ক",
        labelEn = "Task Assigned"
    ),
    TASK_COMPLETED(
        key = "task_completed",
        labelBn = "কাজ সম্পন্ন",
        labelEn = "Task Completed"
    ),
    GPS_REQUEST(
        key = "gps_request",
        labelBn = "GPS অনুরোধ",
        labelEn = "GPS Request"
    ),
    GENERAL(
        key = "general",
        labelBn = "বিজ্ঞপ্তি",
        labelEn = "Notice"
    );

    companion object {
        fun fromKey(raw: String?): NotificationEventType {
            return entries.firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) } ?: GENERAL
        }
    }
}

enum class AlertDeliveryMode(val key: String) {
    POPUP_SOUND("popup_sound"),
    SILENT("silent"),
    INBOX_ONLY("inbox_only"),
    OFF("off");

    companion object {
        fun fromKey(raw: String?): AlertDeliveryMode {
            return entries.firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) } ?: POPUP_SOUND
        }
    }
}

enum class TaskRefreshMode(val key: String) {
    VERSION_AND_EVENTS("version_and_events"),
    EVENTS_ONLY("events_only"),
    OFF("off");

    companion object {
        fun fromKey(raw: String?): TaskRefreshMode {
            return entries.firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) }
                ?: VERSION_AND_EVENTS
        }
    }
}

enum class GpsCollectionMode(val key: String) {
    ON_DEMAND_OR_HOURLY("on_demand_or_hourly"),
    CONTINUOUS("continuous"),
    OFF("off");

    companion object {
        fun fromKey(raw: String?): GpsCollectionMode {
            return when (raw?.trim()?.lowercase()) {
                "continuous", "live", "realtime" -> CONTINUOUS
                "off", "disabled", "none" -> OFF
                else -> ON_DEMAND_OR_HOURLY
            }
        }
    }
}

enum class GpsUploadTrigger(val key: String) {
    PANEL_REQUEST("panel_request"),
    HOURLY_SCHEDULE("hourly_schedule"),
    CONTINUOUS("continuous")
}

data class RemoteTopTab(
    val id: String,
    val label: String,
    val url: String,
    val icon: String = "storefront",
    val roles: List<String> = listOf("all"),
    val enabled: Boolean = true,
    val isPrimaryStore: Boolean = false
)

data class RemoteBottomTab(
    val id: String,
    val label: String,
    val url: String,
    val icon: String = "storefront",
    val type: String = "web",
    val roles: List<String> = listOf("all"),
    val enabled: Boolean = true
)

data class RemoteControlSettings(
    val configVersion: String = "",
    val panelName: String = "",
    val topTabs: List<RemoteTopTab> = defaultTopTabs(),
    val bottomTabs: List<RemoteBottomTab> = defaultBottomTabs(),
    val notificationsEnabled: Boolean = true,
    val alertDeliveryMode: AlertDeliveryMode = AlertDeliveryMode.POPUP_SOUND,
    val syncIntervalSeconds: Int = 30,
    val backgroundSyncEnabled: Boolean = true,
    val allowedEventTypes: List<String> = emptyList(),
    val gpsEnabled: Boolean = true,
    val gpsMode: GpsCollectionMode = GpsCollectionMode.ON_DEMAND_OR_HOURLY,
    val gpsIntervalSeconds: Int = 3600,
    val gpsMinDistanceMeters: Float = 10f,
    val gpsBackgroundEnabled: Boolean = true,
    val taskAutoRefreshEnabled: Boolean = true,
    val taskRefreshMode: TaskRefreshMode = TaskRefreshMode.VERSION_AND_EVENTS,
    val refreshCooldownSeconds: Int = 20,
    val refreshOnlyOnTabs: List<String> = emptyList()
)

data class StoreNotification(
    val id: String,
    val type: NotificationEventType,
    val title: String,
    val message: String,
    val actorName: String = "",
    val panelId: String = "",
    val targetRole: String = "all",
    val targetUserId: String = "",
    val targetUrl: String = DEFAULT_BASE_URL,
    val timestampMillis: Long = System.currentTimeMillis(),
    val isRead: Boolean = false
)

data class LocationSnapshot(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracyMeters: Float = 0f,
    val timestampMillis: Long = 0L,
    val syncStatus: String = "প্যানেল রিকোয়েস্ট বা ১ ঘণ্টা অন্তর প্রস্তুত"
) {
    val hasFix: Boolean
        get() = timestampMillis > 0L && (latitude != 0.0 || longitude != 0.0)
}

data class PortalConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val attendanceUrl: String = DEFAULT_ATTENDANCE_URL,
    val tasksUrl: String = DEFAULT_TASKS_URL,
    val notificationApiUrl: String = DEFAULT_NOTIFICATION_API_URL,
    val locationApiUrl: String = DEFAULT_LOCATION_API_URL,
    val configApiUrl: String = DEFAULT_CONFIG_API_URL,
    val panelId: String = "",
    val userRole: UserRole = UserRole.ADMIN,
    val userId: String = "",
    val userName: String = "",
    val isLoggedIn: Boolean = false,
    val previousTab: AppTab = AppTab.PORTAL,
    val previousTabId: String = "portal",
    val previousUrl: String = "",
    val autoRefreshOnTaskComplete: Boolean = true,
    val backgroundSyncEnabled: Boolean = true,
    val gpsTrackingEnabled: Boolean = true,
    val remoteControl: RemoteControlSettings = RemoteControlSettings()
)

enum class AppTab(
    val route: String,
    val labelBn: String,
    val labelEn: String
) {
    PORTAL("portal", "পোর্টাল", "Portal"),
    ATTENDANCE("attendance", "অ্যাটেন্ডেন্স", "Attendance"),
    TASKS("tasks", "টাস্ক", "Tasks"),
    ALERTS("alerts", "অ্যালার্ট", "Alerts");

    val isWebTab: Boolean
        get() = this == PORTAL || this == ATTENDANCE || this == TASKS

    companion object {
        fun fromRoute(raw: String?): AppTab {
            return entries.firstOrNull {
                it.route.equals(raw?.trim(), ignoreCase = true) && it.isWebTab
            } ?: PORTAL
        }
    }
}

enum class AppWorkspace(
    val id: String,
    val labelBn: String,
    val labelEn: String
) {
    BANGLA_STORE("bangla_store", "বাংলা স্টোর", "Bangla Store"),
    ISP("isp", "ISP", "ISP")
}

enum class AlertFilter(val labelBn: String) {
    ALL("সবগুলো (All)"),
    ATTENDANCE("অ্যাটেন্ডেন্স"),
    TASKS("টাস্ক ও আপডেট")
}

fun defaultTopTabs(): List<RemoteTopTab> = listOf(
    RemoteTopTab(
        id = AppWorkspace.BANGLA_STORE.id,
        label = "বাংলা স্টোর",
        url = DEFAULT_BASE_URL,
        icon = "storefront",
        roles = listOf("all"),
        enabled = true,
        isPrimaryStore = true
    ),
    RemoteTopTab(
        id = AppWorkspace.ISP.id,
        label = "ISP",
        url = DEFAULT_ISP_URL,
        icon = "router",
        roles = listOf("all"),
        enabled = true,
        isPrimaryStore = false
    )
)

fun defaultBottomTabs(): List<RemoteBottomTab> = listOf(
    RemoteBottomTab(
        id = AppTab.PORTAL.route,
        label = "পোর্টাল",
        url = DEFAULT_BASE_URL,
        icon = "storefront",
        type = "web",
        roles = listOf("all"),
        enabled = true
    ),
    RemoteBottomTab(
        id = AppTab.ATTENDANCE.route,
        label = "অ্যাটেন্ডেন্স",
        url = DEFAULT_ATTENDANCE_URL,
        icon = "attendance",
        type = "web",
        roles = listOf("all"),
        enabled = true
    ),
    RemoteBottomTab(
        id = AppTab.TASKS.route,
        label = "টাস্ক",
        url = DEFAULT_TASKS_URL,
        icon = "tasks",
        type = "web",
        roles = listOf("all"),
        enabled = true
    ),
    RemoteBottomTab(
        id = AppTab.ALERTS.route,
        label = "অ্যালার্ট",
        url = "",
        icon = "alerts",
        type = "alerts",
        roles = listOf("all"),
        enabled = true
    )
)

const val DEFAULT_BASE_URL = "https://banglait.net/shop"
const val DEFAULT_ATTENDANCE_URL = "https://banglait.net/shop/dashboard.php?staff_view=attendance"
const val DEFAULT_TASKS_URL = "https://banglait.net/shop/tasks.php"
const val DEFAULT_ISP_URL = "https://admin.banglait.net/"
const val DEFAULT_NOTIFICATION_API_URL = "https://banglait.net/shop/api/notifications.php"
const val DEFAULT_LOCATION_API_URL = "https://banglait.net/shop/api/location.php"
const val DEFAULT_CONFIG_API_URL = "https://banglait.net/shop/api/app_config.php"
