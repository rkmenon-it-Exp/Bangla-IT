package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.GpsOff
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.AlertDeliveryMode
import com.example.model.GpsCollectionMode
import com.example.model.LocationSnapshot
import com.example.model.PortalConfig
import com.example.model.TaskRefreshMode
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
    onOpenQrScanner: () -> Unit,
    onUnlinkStore: () -> Unit,
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
    val remote = config.remoteControl
    var showUnlinkConfirmDialog by remember { mutableStateOf(false) }

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
                text = "অ্যাডমিন ও অ্যাপ সেটিংস",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Admin Store Binding via QR Code Scanner
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sheet_qr_binding_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.QrCodeScanner,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (config.isCustomStoreBound) {
                                    "শপ পরিবর্তন (QR কোড বাইন্ডিং)"
                                } else {
                                    "শপ কানেক্ট করুন (QR কোড স্ক্যান)"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "অ্যাডমিন প্যানেলের QR কোড স্ক্যান করে সরাসরি শপের সাথে অ্যাপ বাইন্ড ও রিবুট করুন।",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Button(
                        onClick = onOpenQrScanner,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("sheet_scan_qr_bind_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("কিউআর কোড স্ক্যান করুন (Scan QR Code)")
                    }
                }
            }

            if (!config.isCustomStoreBound) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sheet_unbound_notice_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Storefront,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "কোনো শপ যুক্ত নেই (Not Bound)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "শপ কানেক্ট করার পর ওই শপের নোটিফিকেশন, ব্যাকগ্রাউন্ড GPS এবং প্যানেল সিঙ্ক সেটিংস এখানে স্বয়ংক্রিয়ভাবে চালু হবে।",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.size(8.dp))
            } else {
                // Active Panel Isolation & Remote Control Summary Card
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                    ),
                    modifier = Modifier.testTag("sheet_remote_panel_card")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AdminPanelSettings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = buildString {
                                    append("সক্রিয় শপ: ")
                                    append(config.effectiveStoreDisplayName)
                                    val code = config.storeCode.ifBlank { config.panelId }
                                    if (code.isNotBlank()) {
                                        append(" ($code)")
                                    }
                                },
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                        Text(
                            text = config.boundStoreUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        val alertModeBn = when (remote.alertDeliveryMode) {
                            AlertDeliveryMode.POPUP_SOUND -> "সাউন্ড + পপ-আপ"
                            AlertDeliveryMode.SILENT -> "নীরব পুশ (Silent)"
                            AlertDeliveryMode.INBOX_ONLY -> "শুধু ইনবক্স"
                            AlertDeliveryMode.OFF -> "বন্ধ"
                        }
                        val refreshModeBn = when (remote.taskRefreshMode) {
                            TaskRefreshMode.VERSION_AND_EVENTS -> "ভার্সন + ইভেন্ট (${remote.refreshCooldownSeconds}s কুলডাউন)"
                            TaskRefreshMode.EVENTS_ONLY -> "শুধু ইভেন্ট (${remote.refreshCooldownSeconds}s কুলডাউন)"
                            TaskRefreshMode.OFF -> "অটো-রিফ্রেশ বন্ধ"
                        }
                        val gpsLabelBn = when {
                            !remote.gpsEnabled || remote.gpsMode == GpsCollectionMode.OFF -> "প্যানেল থেকে বন্ধ"
                            remote.gpsMode == GpsCollectionMode.CONTINUOUS ->
                                "লাইভ (${remote.gpsIntervalSeconds}s / ${remote.gpsMinDistanceMeters.toInt()}m)"
                            else -> "প্যানেল রিকোয়েস্ট বা ১ ঘণ্টা অন্তর"
                        }
                        Text(
                            text = "অ্যালার্ট: ${if (remote.notificationsEnabled) alertModeBn else "বন্ধ"} • সিঙ্ক: প্রতি ${remote.syncIntervalSeconds}s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "GPS: $gpsLabelBn • টাস্ক: $refreshModeBn",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

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
                        checked = config.gpsTrackingEnabled && remote.gpsEnabled,
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
                        checked = config.autoRefreshOnTaskComplete && remote.taskAutoRefreshEnabled,
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
                        checked = config.backgroundSyncEnabled && remote.backgroundSyncEnabled,
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
                        .heightIn(min = 48.dp)
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

                OutlinedButton(
                    onClick = { showUnlinkConfirmDialog = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(bottom = 12.dp)
                        .testTag("sheet_unlink_store_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LinkOff,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("শপ আনলিংক ও রিবুট করুন (Unlink Store)")
                }
            }
        }
    }

    if (showUnlinkConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showUnlinkConfirmDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.LinkOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("শপ আনলিংক করবেন?") },
            text = {
                Text(
                    "\"${config.effectiveStoreDisplayName}\" থেকে আনলিংক করলে বর্তমান সেশন, ব্যাকগ্রাউন্ড GPS ও শপের সব কনফিগ মুছে গিয়ে অ্যাপটি রিবুট হবে।"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnlinkConfirmDialog = false
                        onUnlinkStore()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("sheet_confirm_unlink_button")
                ) {
                    Text("আনলিংক ও রিবুট করুন")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnlinkConfirmDialog = false }) {
                    Text("বাতিল")
                }
            }
        )
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
