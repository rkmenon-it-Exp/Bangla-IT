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
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.model.AlertDeliveryMode
import com.example.model.NotificationEventType
import com.example.model.StoreNotification
import java.util.Locale

object NotificationHelper {

    const val CHANNEL_ATTENDANCE = "channel_admin_attendance"
    const val CHANNEL_TASKS = "channel_staff_tasks"
    const val CHANNEL_UPDATES = "channel_task_updates"
    const val CHANNEL_SILENT_UPDATES = "channel_silent_updates"
    const val CHANNEL_GPS_TRACKING = "channel_gps_tracking"
    const val CHANNEL_APP_UPDATES = "channel_app_updates"

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

        val silentChannel = NotificationChannel(
            CHANNEL_SILENT_UPDATES,
            "Silent Updates (নীরব বিজ্ঞপ্তি)",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Silent panel notifications without sound or pop-up."
        }

        val appUpdateChannel = NotificationChannel(
            CHANNEL_APP_UPDATES,
            "App Software Updates (অ্যাপ আপডেট)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications when a new software version or APK is published on GitHub."
            enableVibration(true)
        }

        manager.createNotificationChannels(
            listOf(attendanceChannel, taskChannel, updateChannel, silentChannel, gpsChannel, appUpdateChannel)
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

    fun buildLocationDisabledWarningNotification(context: Context): Notification {
        ensureChannels(context)
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            9002,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setColor(0xFFC62828.toInt())
            .setContentTitle("⚠️ GPS বন্ধ আছে — অন করুন")
            .setContentText("GPS অন করা ছাড়া বাংলা স্টোর ব্যবহার করা যাবে না। এখনই লোকেশন অন করুন।")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("আপনার ফোনের GPS বন্ধ আছে। GPS অন করা ছাড়া বাংলা স্টোর সফটওয়্যার ব্যবহার করা যাবে না। অনুগ্রহ করে এখনই GPS অন করুন।")
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()
    }

    fun showNotification(
        context: Context,
        notification: StoreNotification,
        deliveryMode: AlertDeliveryMode = AlertDeliveryMode.POPUP_SOUND
    ) {
        if (deliveryMode == AlertDeliveryMode.OFF || deliveryMode == AlertDeliveryMode.INBOX_ONLY) {
            return
        }
        ensureChannels(context)
        if (!hasNotificationPermission(context)) return

        val channelId = if (deliveryMode == AlertDeliveryMode.SILENT) {
            CHANNEL_SILENT_UPDATES
        } else {
            when (notification.type) {
                NotificationEventType.ATTENDANCE_SUBMITTED -> CHANNEL_ATTENDANCE
                NotificationEventType.TASK_ASSIGNED -> CHANNEL_TASKS
                NotificationEventType.TASK_COMPLETED,
                NotificationEventType.GPS_REQUEST,
                NotificationEventType.GENERAL -> CHANNEL_UPDATES
            }
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
            .setSilent(deliveryMode == AlertDeliveryMode.SILENT)
            .setPriority(
                when {
                    deliveryMode == AlertDeliveryMode.SILENT -> NotificationCompat.PRIORITY_LOW
                    notification.type == NotificationEventType.TASK_COMPLETED -> NotificationCompat.PRIORITY_DEFAULT
                    else -> NotificationCompat.PRIORITY_HIGH
                }
            )

        runCatching {
            NotificationManagerCompat.from(context).notify(
                notification.id.hashCode(),
                builder.build()
            )
        }
    }

    fun showAppUpdateNotification(
        context: Context,
        newVersion: String,
        downloadUrl: String,
        releaseNotes: String = ""
    ) {
        ensureChannels(context)
        if (!hasNotificationPermission(context)) return

        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            9999,
            browserIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val noteText = if (releaseNotes.isNotBlank()) {
            "নতুন সংস্করণ $newVersion পাওয়া গেছে:\n$releaseNotes\n\nডাউনলোড করতে ট্যাপ করুন।"
        } else {
            "নতুন সংস্করণ $newVersion পাওয়া গেছে। নতুন ফিচার ও বাগ ফিক্স পেতে এখনই আপডেট করুন।"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_APP_UPDATES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setColor(0xFF0D6E5E.toInt())
            .setContentTitle("বাংলা স্টোর নতুন আপডেট ($newVersion)")
            .setContentText("নতুন আপডেট ইনস্টল করতে এখানে ট্যাপ করুন।")
            .setStyle(NotificationCompat.BigTextStyle().bigText(noteText))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        runCatching {
            NotificationManagerCompat.from(context).notify(9999, builder.build())
        }
    }
}
