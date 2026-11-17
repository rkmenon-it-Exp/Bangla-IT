package com.example.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.model.NotificationEventType
import com.example.model.StoreNotification
import java.util.Locale

object NotificationHelper {

    const val CHANNEL_ATTENDANCE = "channel_admin_attendance"
    const val CHANNEL_TASKS = "channel_staff_tasks"
    const val CHANNEL_UPDATES = "channel_task_updates"
    const val CHANNEL_GPS_TRACKING = "channel_gps_tracking"

    const val EXTRA_TARGET_URL = "extra_target_url"
    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    const val EXTRA_EVENT_TYPE = "extra_event_type"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val attendanceChannel = NotificationChannel(
            CHANNEL_ATTENDANCE,
            "Admin Attendance Alerts (অ্যাটেন্ডেন্স)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications sent to Admin when staff log in and submit attendance."
            enableVibration(true)
        }

        val taskChannel = NotificationChannel(
            CHANNEL_TASKS,
            "Staff Task Alerts (নতুন টাস্ক)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications sent to staff when a new task is assigned."
            enableVibration(true)
        }

        val updateChannel = NotificationChannel(
            CHANNEL_UPDATES,
            "Task Completion & Refresh (কাজ সম্পন্ন)",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Updates when tasks are completed and pages auto-refresh."
        }

        val gpsChannel = NotificationChannel(
            CHANNEL_GPS_TRACKING,
            "Live GPS Tracker (জিপিএস ট্র্যাকিং)",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ongoing indicator while Bangla Store continuously syncs staff GPS location."
            setShowBadge(false)
        }

        manager.createNotificationChannels(
            listOf(attendanceChannel, taskChannel, updateChannel, gpsChannel)
        )
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun buildLocationTrackingNotification(
        context: Context,
        latitude: Double? = null,
        longitude: Double? = null
    ): Notification {
        ensureChannels(context)
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            9001,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (latitude != null && longitude != null) {
            String.format(
                Locale.US,
                "লাইভ GPS ট্র্যাকিং সক্রিয় (%.5f, %.5f)",
                latitude,
                longitude
            )
        } else {
            "বাংলা স্টোর ব্যাকগ্রাউন্ড GPS লোকেশন ট্র্যাকিং চালু আছে"
        }

        return NotificationCompat.Builder(context, CHANNEL_GPS_TRACKING)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setColor(0xFF0D6E5E.toInt())
            .setContentTitle("বাংলা স্টোর GPS ট্র্যাকার সক্রিয়")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    fun showNotification(context: Context, notification: StoreNotification) {
        ensureChannels(context)
        if (!hasNotificationPermission(context)) return

        val channelId = when (notification.type) {
            NotificationEventType.ATTENDANCE_SUBMITTED -> CHANNEL_ATTENDANCE
            NotificationEventType.TASK_ASSIGNED -> CHANNEL_TASKS
            NotificationEventType.TASK_COMPLETED, NotificationEventType.GENERAL -> CHANNEL_UPDATES
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TARGET_URL, notification.targetUrl)
            putExtra(EXTRA_NOTIFICATION_ID, notification.id)
            putExtra(EXTRA_EVENT_TYPE, notification.type.key)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notification.id.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setColor(0xFF0D6E5E.toInt())
            .setContentTitle(notification.title)
            .setContentText(notification.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(
                if (notification.type == NotificationEventType.TASK_COMPLETED) {
                    NotificationCompat.PRIORITY_DEFAULT
                } else {
                    NotificationCompat.PRIORITY_HIGH
                }
            )

        runCatching {
            NotificationManagerCompat.from(context).notify(
                notification.id.hashCode(),
                builder.build()
            )
        }
    }
}
