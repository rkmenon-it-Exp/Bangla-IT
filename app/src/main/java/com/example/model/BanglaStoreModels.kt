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

data class StoreNotification(
    val id: String,
    val type: NotificationEventType,
    val title: String,
    val message: String,
    val actorName: String = "",
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
    val syncStatus: String = "GPS অপেক্ষমাণ (Waiting)"
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
    val userRole: UserRole = UserRole.ADMIN,
    val userId: String = "",
    val userName: String = "",
    val isLoggedIn: Boolean = false,
    val autoRefreshOnTaskComplete: Boolean = true,
    val backgroundSyncEnabled: Boolean = true,
    val gpsTrackingEnabled: Boolean = true
)

enum class AppTab(
    val route: String,
    val labelBn: String,
    val labelEn: String
) {
    PORTAL("portal", "পোর্টাল", "Portal"),
    ATTENDANCE("attendance", "অ্যাটেন্ডেন্স", "Attendance"),
    TASKS("tasks", "টাস্ক", "Tasks"),
    ALERTS("alerts", "অ্যালার্ট", "Alerts")
}

enum class AlertFilter(val labelBn: String) {
    ALL("সবগুলো (All)"),
    ATTENDANCE("অ্যাটেন্ডেন্স"),
    TASKS("টাস্ক ও আপডেট")
}

const val DEFAULT_BASE_URL = "https://banglait.net/shop"
const val DEFAULT_ATTENDANCE_URL = "https://banglait.net/shop/dashboard.php?staff_view=attendance"
const val DEFAULT_TASKS_URL = "https://banglait.net/shop/tasks.php"
const val DEFAULT_NOTIFICATION_API_URL = "https://banglait.net/shop/api/notifications.php"
const val DEFAULT_LOCATION_API_URL = "https://banglait.net/shop/api/location.php"
