package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.GpsOff
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.LocationSnapshot
import com.example.model.PortalConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    config: PortalConfig,
    locationSnapshot: LocationSnapshot,
    hasNotificationPermission: Boolean,
    hasForegroundLocationPermission: Boolean,
    hasBackgroundLocationPermission: Boolean,
    isSyncing: Boolean,
    lastSyncTimeMillis: Long,
    lastSyncStatus: String,
    onRequestNotificationPermission: () -> Unit,
    onRequestForegroundLocation: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    onToggleAutoRefresh: (Boolean) -> Unit,
    onToggleBackgroundSync: (Boolean) -> Unit,
    onToggleGpsTracking: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "নোটিফিকেশন ও GPS সেটিংস",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            PermissionStatusCard(
                title = if (hasNotificationPermission) {
                    "নোটিফিকেশন অনুমতি চালু আছে"
                } else {
                    "নোটিফিকেশন অনুমতি প্রয়োজন"
                },
                subtitle = if (hasNotificationPermission) {
                    "অ্যাটেন্ডেন্স ও টাস্ক অ্যালার্ট সক্রিয়"
                } else {
                    "ফোনে অ্যালার্ট পেতে অনুমতি দিন"
                },
                isGranted = hasNotificationPermission,
                icon = if (hasNotificationPermission) {
                    Icons.Rounded.NotificationsActive
                } else {
                    Icons.Rounded.NotificationsOff
                },
                actionLabel = "অনুমতি দিন",
                buttonTestTag = "sheet_grant_permission_button",
                onActionClick = onRequestNotificationPermission
            )

            PermissionStatusCard(
                title = when {
                    hasBackgroundLocationPermission -> "সব সময় GPS ট্র্যাকিং সক্রিয় (Background)"
                    hasForegroundLocationPermission -> "GPS চালু — ব্যাকগ্রাউন্ড অনুমতি বাকি"
                    else -> "GPS লোকেশন অনুমতি প্রয়োজন"
                },
                subtitle = when {
                    locationSnapshot.hasFix -> String.format(
                        Locale.US,
                        "%.5f, %.5f • %s",
                        locationSnapshot.latitude,
                        locationSnapshot.longitude,
                        locationSnapshot.syncStatus
                    )
                    hasForegroundLocationPermission -> locationSnapshot.syncStatus
                    else -> "স্টাফ লোকেশন পাঠাতে GPS অনুমতি দিন"
                },
                isGranted = hasBackgroundLocationPermission,
                icon = if (hasForegroundLocationPermission) {
                    Icons.Rounded.GpsFixed
                } else {
                    Icons.Rounded.GpsOff
                },
                actionLabel = if (!hasForegroundLocationPermission) {
                    "GPS চালু করুন"
                } else {
                    "সব সময় অনুমতি"
                },
                buttonTestTag = "sheet_grant_gps_button",
                onActionClick = {
                    if (!hasForegroundLocationPermission) {
                        onRequestForegroundLocation()
                    } else {
                        onRequestBackgroundLocation()
                    }
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "অলওয়েজ স্টাফ GPS ট্র্যাকিং",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "সফটওয়্যার বন্ধ থাকলেও ব্যাকগ্রাউন্ডে লোকেশন পাঠাবে (${config.userId.ifBlank { config.userRole.shortLabel }})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.gpsTrackingEnabled,
                    onCheckedChange = onToggleGpsTracking,
                    modifier = Modifier.testTag("sheet_gps_tracking_switch")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "টাস্ক কমপ্লিট অটো-রিফ্রেশ",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "কাজ শেষ হলে টাস্ক পেজ নিজে থেকেই রিফ্রেশ হবে",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.autoRefreshOnTaskComplete,
                    onCheckedChange = onToggleAutoRefresh,
                    modifier = Modifier.testTag("sheet_auto_refresh_switch")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "অটোমেটিক পুশ ও ব্যাকগ্রাউন্ড সিঙ্ক",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "অ্যাপ বন্ধ থাকলেও স্বয়ংক্রিয়ভাবে নোটিফিকেশন চেক করবে",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.backgroundSyncEnabled,
                    onCheckedChange = onToggleBackgroundSync,
                    modifier = Modifier.testTag("sheet_background_sync_switch")
                )
            }

            HorizontalDivider()

            val timeText = if (lastSyncTimeMillis > 0L) {
                SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
                    .format(Date(lastSyncTimeMillis))
            } else {
                "—"
            }
            Text(
                text = "সিঙ্ক স্ট্যাটাস ($timeText): $lastSyncStatus",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(
                onClick = onSyncNow,
                enabled = !isSyncing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .testTag("sheet_sync_now_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isSyncing) "সিঙ্ক হচ্ছে..." else "এখনই সিঙ্ক চেক করুন")
            }
        }
    }
}

@Composable
private fun PermissionStatusCard(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    actionLabel: String,
    buttonTestTag: String,
    onActionClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isGranted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!isGranted) {
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onActionClick,
                    modifier = Modifier.testTag(buttonTestTag)
                ) {
                    Text(actionLabel)
                }
            } else {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Granted",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
