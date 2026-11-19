package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AssignmentInd
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PersonPinCircle
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.AlertFilter
import com.example.model.NotificationEventType
import com.example.model.StoreNotification
import com.example.model.UserRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AlertsInboxScreen(
    notifications: List<StoreNotification>,
    activeFilter: AlertFilter,
    currentRole: UserRole,
    unreadCount: Int,
    onFilterChanged: (AlertFilter) -> Unit,
    onNotificationClick: (StoreNotification) -> Unit,
    onMarkAllRead: () -> Unit,
    onClearAll: () -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "নোটিফিকেশন ইনবক্স (Alerts)",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "সক্রিয় মোড: ${currentRole.labelBn} • অপঠিত: $unreadCount টি",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row {
                        if (unreadCount > 0) {
                            TextButton(
                                onClick = onMarkAllRead,
                                modifier = Modifier.testTag("mark_all_read_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DoneAll,
                                    contentDescription = "Mark all read",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("সব পঠিত")
                            }
                        }
                        if (notifications.isNotEmpty()) {
                            IconButton(
                                onClick = onClearAll,
                                modifier = Modifier.testTag("clear_alerts_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteSweep,
                                    contentDescription = "Clear notifications"
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AlertFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = activeFilter == filter,
                        onClick = { onFilterChanged(filter) },
                        label = { Text(filter.labelBn) },
                        modifier = Modifier.testTag("alert_filter_${filter.name.lowercase()}")
                    )
                }
            }

            if (notifications.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.img_store_banner),
                                contentDescription = "Empty notifications banner",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .clip(RoundedCornerShape(16.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Text(
                                text = "এখনো কোনো নোটিফিকেশন আসেনি",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "কর্মীরা অ্যাটেন্ডেন্স দিলে অ্যাডমিনের পেজে এবং নতুন টাস্ক যোগ করলে স্টাফের পেজে এখানে নোটিফিকেশন জমা হবে।",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = onSyncNow,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("empty_alerts_sync_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Sync,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("এখনই সিঙ্ক চেক করুন (Sync Now)")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(notifications, key = { it.id }) { item ->
                        NotificationCard(
                            notification = item,
                            onClick = { onNotificationClick(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(
    notification: StoreNotification,
    onClick: () -> Unit
) {
    val icon = when (notification.type) {
        NotificationEventType.ATTENDANCE_SUBMITTED -> Icons.Rounded.PersonPinCircle
        NotificationEventType.TASK_ASSIGNED -> Icons.Rounded.AssignmentInd
        NotificationEventType.TASK_UPDATED -> Icons.Rounded.Sync
        NotificationEventType.TASK_COMPLETED -> Icons.Rounded.CheckCircle
        NotificationEventType.GPS_REQUEST, NotificationEventType.GENERAL -> Icons.Rounded.NotificationsActive
    }
    val badgeContainerColor = when (notification.type) {
        NotificationEventType.ATTENDANCE_SUBMITTED -> MaterialTheme.colorScheme.primaryContainer
        NotificationEventType.TASK_ASSIGNED -> MaterialTheme.colorScheme.tertiaryContainer
        NotificationEventType.TASK_UPDATED -> MaterialTheme.colorScheme.secondaryContainer
        NotificationEventType.TASK_COMPLETED -> MaterialTheme.colorScheme.secondaryContainer
        NotificationEventType.GPS_REQUEST, NotificationEventType.GENERAL -> MaterialTheme.colorScheme.surfaceVariant
    }
    val badgeContentColor = when (notification.type) {
        NotificationEventType.ATTENDANCE_SUBMITTED -> MaterialTheme.colorScheme.onPrimaryContainer
        NotificationEventType.TASK_ASSIGNED -> MaterialTheme.colorScheme.onTertiaryContainer
        NotificationEventType.TASK_UPDATED -> MaterialTheme.colorScheme.onSecondaryContainer
        NotificationEventType.TASK_COMPLETED -> MaterialTheme.colorScheme.onSecondaryContainer
        NotificationEventType.GPS_REQUEST, NotificationEventType.GENERAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("notification_card_${notification.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (notification.isRead) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (notification.isRead) 1.dp else 3.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(badgeContainerColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = notification.type.labelEn,
                    tint = badgeContentColor
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = badgeContainerColor,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = notification.type.labelBn,
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeContentColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!notification.isRead) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.tertiary)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = formatTimestamp(notification.timestampMillis),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = notification.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (notification.isRead) FontWeight.Medium else FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = notification.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "পোর্টালে ওপেন করুন",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(millis: Long): String {
    return runCatching {
        SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(millis))
    }.getOrDefault("")
}
