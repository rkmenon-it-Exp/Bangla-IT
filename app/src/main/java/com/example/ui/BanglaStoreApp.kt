package com.example.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PersonPinCircle
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PersonPinCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.location.LocationPermissionHelper
import com.example.model.AppTab
import com.example.ui.screens.AlertsInboxScreen
import com.example.ui.screens.PortalWebViewScreen
import com.example.ui.screens.SettingsBottomSheet
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BanglaStoreApp(
    viewModel: BanglaStoreViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var askedInitialPermissions by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    val initialPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        viewModel.onPermissionsUpdated(allowBackgroundPrompt = true)
        val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        coroutineScope.launch {
            snackbarHostState.showSnackbar(
                if (locationGranted) {
                    "GPS লোকেশন ট্র্যাকিং চালু হয়েছে"
                } else {
                    "GPS লোকেশন অনুমতি পাওয়া যায়নি — সেটিংস থেকে চালু করুন"
                }
            )
        }
    }

    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.dismissBackgroundLocationDialog()
        viewModel.onPermissionsUpdated(allowBackgroundPrompt = false)
        coroutineScope.launch {
            snackbarHostState.showSnackbar(
                if (granted) {
                    "ব্যাকগ্রাউন্ড GPS ('সব সময়') চালু হয়েছে"
                } else {
                    "ব্যাকগ্রাউন্ড GPS অনুমতি দেওয়া হয়নি"
                }
            )
        }
    }

    val notificationOnlyLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.onPermissionsUpdated(allowBackgroundPrompt = false)
    }

    val foregroundLocationOnlyLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.onPermissionsUpdated(allowBackgroundPrompt = true)
    }

    LaunchedEffect(Unit) {
        if (!askedInitialPermissions) {
            askedInitialPermissions = true
            val permsToRequest = buildList {
                if (!uiState.hasForegroundLocationPermission) {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !uiState.hasNotificationPermission
                ) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (permsToRequest.isNotEmpty()) {
                initialPermissionsLauncher.launch(permsToRequest.toTypedArray())
            } else {
                viewModel.onPermissionsUpdated(allowBackgroundPrompt = true)
            }
        }
    }

    LaunchedEffect(viewModel.snackbarMessages) {
        viewModel.snackbarMessages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val isWebTab = uiState.selectedTab == AppTab.PORTAL ||
        uiState.selectedTab == AppTab.ATTENDANCE ||
        uiState.selectedTab == AppTab.TASKS

    BackHandler(enabled = !isWebTab) {
        viewModel.selectTab(AppTab.PORTAL)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    navigationIcon = {
                        if (isWebTab && uiState.canGoBack) {
                            IconButton(
                                onClick = { viewModel.navigateWebBack() },
                                modifier = Modifier.testTag("top_back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = stringResource(R.string.action_back)
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .padding(start = 12.dp, end = 4.dp)
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_bangla_store_fg),
                                    contentDescription = stringResource(R.string.cd_app_logo),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (uiState.hasForegroundLocationPermission && uiState.config.gpsTrackingEnabled) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.tertiary
                                            }
                                        )
                                )
                                Text(
                                    text = buildString {
                                        append(uiState.config.userRole.shortLabel)
                                        if (uiState.config.userId.isNotBlank()) {
                                            append(" (")
                                            append(uiState.config.userId)
                                            append(")")
                                        }
                                        append(" • ")
                                        append(
                                            when {
                                                !uiState.isOnline -> "Offline"
                                                uiState.hasForegroundLocationPermission && uiState.config.gpsTrackingEnabled -> "Online • GPS"
                                                else -> "Online"
                                            }
                                        )
                                        if (uiState.pendingSyncCount > 0) {
                                            append(" (${uiState.pendingSyncCount} queued)")
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.selectTab(AppTab.ALERTS) },
                            modifier = Modifier.testTag("top_alerts_button")
                        ) {
                            BadgedBox(
                                badge = {
                                    if (uiState.unreadCount > 0) {
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.tertiary,
                                            contentColor = MaterialTheme.colorScheme.onTertiary
                                        ) {
                                            Text(
                                                text = if (uiState.unreadCount > 99) "99+" else "${uiState.unreadCount}"
                                            )
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Notifications,
                                    contentDescription = stringResource(R.string.cd_notifications)
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                viewModel.reloadCurrentWebPage()
                                viewModel.syncNow()
                            },
                            modifier = Modifier.testTag("top_refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.cd_refresh_page)
                            )
                        }
                        IconButton(
                            onClick = { showSettingsSheet = true },
                            modifier = Modifier.testTag("top_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = stringResource(R.string.cd_settings)
                            )
                        }
                    }
                )
            },
            bottomBar = {
                if (!useRail) {
                    NavigationBar {
                        AppTab.entries.forEach { tab ->
                            val selected = uiState.selectedTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { viewModel.selectTab(tab) },
                                icon = {
                                    TabIconWithBadge(
                                        tab = tab,
                                        selected = selected,
                                        unreadCount = uiState.unreadCount
                                    )
                                },
                                label = {
                                    Text(
                                        text = tab.labelBn,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                modifier = Modifier.testTag("nav_tab_${tab.route}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(modifier = Modifier.fillMaxHeight()) {
                        Spacer(modifier = Modifier.size(8.dp))
                        AppTab.entries.forEach { tab ->
                            val selected = uiState.selectedTab == tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { viewModel.selectTab(tab) },
                                icon = {
                                    TabIconWithBadge(
                                        tab = tab,
                                        selected = selected,
                                        unreadCount = uiState.unreadCount
                                    )
                                },
                                label = { Text(tab.labelBn) },
                                modifier = Modifier.testTag("rail_tab_${tab.route}")
                            )
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    PortalWebViewScreen(
                        initialUrl = uiState.config.baseUrl,
                        isVisible = isWebTab,
                        isOnline = uiState.isOnline,
                        pendingSyncCount = uiState.pendingSyncCount,
                        pageProgress = uiState.pageProgress,
                        canGoBack = uiState.canGoBack,
                        webError = uiState.webError,
                        isShowingOfflineCache = uiState.isShowingOfflineCache,
                        cachedPages = uiState.cachedPages,
                        offlinePageCache = viewModel.offlinePageCache,
                        jsBridge = viewModel.jsBridge,
                        commands = viewModel.webViewCommands,
                        onProgressChanged = viewModel::updateWebProgress,
                        onNavigationChanged = viewModel::updateWebNavigationState,
                        onErrorChanged = viewModel::setWebError,
                        onSessionCookieCaptured = viewModel::onSessionCookieCaptured,
                        onLogoutUrlVisited = viewModel::onLogoutUrlVisited,
                        onSnapshotCaptured = viewModel::saveCachedPageSnapshot,
                        onOfflineCacheModeChanged = viewModel::setShowingOfflineCache,
                        onSelectCachedSnapshot = viewModel::openCachedSnapshot,
                        onShowCustomOfflineScreen = viewModel::showCustomOfflineScreen,
                        onOpenAlerts = { viewModel.selectTab(AppTab.ALERTS) },
                        onManualRefresh = {
                            viewModel.reloadCurrentWebPage()
                            viewModel.syncNow()
                        },
                        isInnerScrolledProvider = { viewModel.isWebInnerScrolled },
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(if (isWebTab) 1f else 0f)
                    )

                    if (uiState.selectedTab == AppTab.ALERTS) {
                        Surface(
                            color = MaterialTheme.colorScheme.background,
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(2f)
                        ) {
                            AlertsInboxScreen(
                                notifications = uiState.filteredNotifications,
                                activeFilter = uiState.alertFilter,
                                currentRole = uiState.config.userRole,
                                unreadCount = uiState.unreadCount,
                                onFilterChanged = viewModel::setAlertFilter,
                                onNotificationClick = viewModel::openNotification,
                                onMarkAllRead = viewModel::markAllNotificationsRead,
                                onClearAll = viewModel::clearAllNotifications,
                                onSyncNow = viewModel::syncNow
                            )
                        }
                    }
                }
            }
        }
    }

    if (uiState.showBackgroundLocationDialog && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val optionLabel = remember(context) {
            LocationPermissionHelper.getBackgroundPermissionOptionLabel(context)
        }
        AlertDialog(
            onDismissRequest = { viewModel.dismissBackgroundLocationDialog() },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.GpsFixed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text("ব্যাকগ্রাউন্ড GPS লোকেশন অনুমতি")
            },
            text = {
                Text(
                    "অ্যাপ বন্ধ থাকলেও স্টাফের লাইভ GPS লোকেশন মূল সফটওয়্যারে পাঠাতে সেটিংস থেকে \"$optionLabel\" নির্বাচন করুন।"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        backgroundLocationLauncher.launch(
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION
                        )
                    },
                    modifier = Modifier.testTag("confirm_background_gps_button")
                ) {
                    Text("অনুমতি দিন")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissBackgroundLocationDialog() },
                    modifier = Modifier.testTag("dismiss_background_gps_button")
                ) {
                    Text("এখন নয়")
                }
            }
        )
    }

    if (showSettingsSheet) {
        SettingsBottomSheet(
            config = uiState.config,
            locationSnapshot = uiState.locationSnapshot,
            hasNotificationPermission = uiState.hasNotificationPermission,
            hasForegroundLocationPermission = uiState.hasForegroundLocationPermission,
            hasBackgroundLocationPermission = uiState.hasBackgroundLocationPermission,
            isSyncing = uiState.isSyncing,
            lastSyncTimeMillis = uiState.lastSyncTimeMillis,
            lastSyncStatus = uiState.lastSyncStatus,
            onRequestNotificationPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationOnlyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onRequestForegroundLocation = {
                foregroundLocationOnlyLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            },
            onRequestBackgroundLocation = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    showSettingsSheet = false
                    viewModel.promptBackgroundLocationNow()
                }
            },
            onToggleAutoRefresh = viewModel::toggleAutoRefresh,
            onToggleBackgroundSync = viewModel::toggleBackgroundSync,
            onToggleGpsTracking = viewModel::toggleGpsTracking,
            onSyncNow = viewModel::syncNow,
            onDismiss = { showSettingsSheet = false }
        )
    }
}

@Composable
private fun TabIconWithBadge(
    tab: AppTab,
    selected: Boolean,
    unreadCount: Int
) {
    val icon: ImageVector = when (tab) {
        AppTab.PORTAL -> if (selected) Icons.Rounded.Storefront else Icons.Outlined.Storefront
        AppTab.ATTENDANCE -> if (selected) Icons.Rounded.PersonPinCircle else Icons.Outlined.PersonPinCircle
        AppTab.TASKS -> if (selected) Icons.Rounded.TaskAlt else Icons.Outlined.TaskAlt
        AppTab.ALERTS -> if (selected) Icons.Rounded.Notifications else Icons.Outlined.Notifications
    }

    if (tab == AppTab.ALERTS && unreadCount > 0) {
        BadgedBox(
            badge = {
                Badge(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary
                ) {
                    Text(if (unreadCount > 99) "99+" else "$unreadCount")
                }
            }
        ) {
            Icon(imageVector = icon, contentDescription = tab.labelEn)
        }
    } else {
        Icon(imageVector = icon, contentDescription = tab.labelEn)
    }
}
