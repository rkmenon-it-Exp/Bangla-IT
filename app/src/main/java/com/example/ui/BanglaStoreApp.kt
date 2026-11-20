package com.example.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PersonPinCircle
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.GpsOff
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PersonPinCircle
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.example.R
import com.example.location.LocationPermissionHelper
import com.example.model.DEFAULT_GENERIC_PORTAL_URL
import com.example.model.GpsCollectionMode
import com.example.model.RemoteBottomTab
import com.example.ui.screens.AlertsInboxScreen
import com.example.ui.screens.PortalWebViewScreen
import com.example.ui.screens.QrCodeScannerDialog
import com.example.ui.screens.SettingsBottomSheet
import com.example.ui.screens.ShopBindingBottomSheet
import com.example.ui.screens.UnboundWelcomeScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BanglaStoreApp(
    viewModel: BanglaStoreViewModel,
    onRebootApp: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val shopBindingState by viewModel.shopBindingState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var askedInitialPermissions by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showShopBindingSheet by remember { mutableStateOf(false) }
    var showQrScannerDialog by remember { mutableStateOf(false) }

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

    // Only request GPS/Notification permissions once a store is actually bound
    LaunchedEffect(uiState.isConfigLoaded, uiState.config.isCustomStoreBound) {
        if (uiState.isConfigLoaded && uiState.config.isCustomStoreBound && !askedInitialPermissions) {
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

    LaunchedEffect(viewModel.remoteDisconnectEvents) {
        viewModel.remoteDisconnectEvents.collect { reason ->
            showSettingsSheet = false
            showShopBindingSheet = false
            showQrScannerDialog = false
            runCatching {
                android.widget.Toast.makeText(
                    context,
                    reason,
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            delay(450)
            onRebootApp()
        }
    }

    // Wait for persisted DataStore config before mounting the WebView so a bound store
    // never loads the global portal URL on frame 1.
    if (!uiState.isConfigLoaded) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = modifier.fillMaxSize()
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                CircularProgressIndicator()
            }
        }
        return
    }

    val isStoreBound = uiState.config.isCustomStoreBound
    val showAlertsOverlay = isStoreBound && uiState.isAlertsOverlayVisible
    val activeWorkspace = uiState.activeWorkspaceTab
    val primaryStoreTab = uiState.primaryStoreTab
    val isPrimaryWorkspaceActive = !showAlertsOverlay && (!isStoreBound || activeWorkspace.isPrimaryStore)
    val activeSecondaryState = if (isStoreBound && !activeWorkspace.isPrimaryStore) {
        uiState.secondaryWebStates[activeWorkspace.id]
    } else {
        null
    }

    val activeCanGoBack = when {
        showAlertsOverlay -> false
        !isStoreBound || activeWorkspace.isPrimaryStore -> uiState.canGoBack
        else -> activeSecondaryState?.canGoBack ?: false
    }

    val gpsRequiredByPanel = isStoreBound &&
        uiState.config.remoteControl.gpsEnabled &&
        uiState.config.remoteControl.gpsMode != GpsCollectionMode.OFF
    val isGpsBlocked = gpsRequiredByPanel && (
        !uiState.hasForegroundLocationPermission ||
            !uiState.isDeviceGpsEnabled ||
            !uiState.config.gpsTrackingEnabled
        )

    LaunchedEffect(isGpsBlocked) {
        if (isGpsBlocked) {
            showSettingsSheet = false
            showShopBindingSheet = false
            showQrScannerDialog = false
        }
    }

    BackHandler(enabled = isGpsBlocked) {
        // Block navigating away from mandatory GPS prompt until GPS is enabled
    }

    BackHandler(enabled = !isGpsBlocked && showAlertsOverlay) {
        viewModel.exitAlertsScreen()
    }

    BackHandler(enabled = !isGpsBlocked && isStoreBound && !showAlertsOverlay && !activeWorkspace.isPrimaryStore && !activeCanGoBack) {
        viewModel.selectPrimaryWorkspace()
    }

    val bottomNavTabs = remember(isStoreBound, uiState.visibleBottomTabs) {
        if (isStoreBound) uiState.visibleBottomTabs.take(5) else emptyList()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp && bottomNavTabs.isNotEmpty()

        if (!isStoreBound) {
            UnboundWelcomeScreen(
                onOpenUrlBinding = {
                    viewModel.clearShopBindingError()
                    showShopBindingSheet = true
                },
                onOpenQrScanner = {
                    viewModel.clearShopBindingError()
                    showQrScannerDialog = true
                },
                onOpenSettings = {
                    showSettingsSheet = true
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    expandedHeight = 72.dp,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    navigationIcon = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 6.dp, end = 2.dp)
                        ) {
                            if (activeCanGoBack) {
                                IconButton(
                                    onClick = { viewModel.navigateActiveWebBack() },
                                    modifier = Modifier.testTag("top_back_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                        contentDescription = stringResource(R.string.action_back)
                                    )
                                }
                            }
                            // Once bound to an Admin Panel, the store logo is strictly display-only:
                            // binding/unlinking is locked and can only be revoked from the Admin Panel.
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .testTag("top_store_logo_badge")
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                ) {
                                    if (isStoreBound && uiState.config.logoUrl.isNotBlank()) {
                                        SubcomposeAsyncImage(
                                            model = uiState.config.logoUrl,
                                            contentDescription = stringResource(R.string.cd_shop_binding_logo),
                                            contentScale = ContentScale.Crop,
                                            error = {
                                                Image(
                                                    painter = painterResource(id = R.drawable.ic_bangla_store_fg),
                                                    contentDescription = stringResource(R.string.cd_shop_binding_logo),
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Image(
                                            painter = painterResource(id = R.drawable.ic_bangla_store_fg),
                                            contentDescription = stringResource(R.string.cd_shop_binding_logo),
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp)
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isStoreBound) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.outlineVariant
                                            }
                                        )
                                )
                            }
                        }
                    },
                    title = {
                        if (!isStoreBound) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        viewModel.clearShopBindingError()
                                        showShopBindingSheet = true
                                    }
                                    .padding(vertical = 2.dp, horizontal = 4.dp)
                                    .testTag("top_unbound_connect_header")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.app_name),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Link,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Text(
                                                text = "কানেক্ট করুন",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = "লোগো ট্যাপ করুন বা সেটিংসে QR স্ক্যান করুন",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                                    modifier = Modifier.testTag("top_workspace_tabs")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .horizontalScroll(rememberScrollState())
                                            .padding(3.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        uiState.visibleTopTabs.forEach { topTab ->
                                            TopWorkspaceTabChip(
                                                label = topTab.label,
                                                icon = resolveTabIcon(
                                                    iconKey = topTab.icon,
                                                    selected = uiState.selectedWorkspaceId == topTab.id
                                                ),
                                                selected = uiState.selectedWorkspaceId == topTab.id,
                                                onClick = { viewModel.selectWorkspace(topTab) },
                                                modifier = Modifier.testTag("workspace_tab_${topTab.id}")
                                            )
                                        }
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val gpsActive = uiState.hasForegroundLocationPermission &&
                                        uiState.isDeviceGpsEnabled &&
                                        uiState.config.gpsTrackingEnabled &&
                                        uiState.config.remoteControl.gpsEnabled
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (gpsActive) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.tertiary
                                                }
                                            )
                                    )
                                    Text(
                                        text = buildString {
                                            if (!activeWorkspace.isPrimaryStore) {
                                                append(activeWorkspace.label)
                                            } else {
                                                append(uiState.config.userRole.shortLabel)
                                                if (uiState.config.userId.isNotBlank()) {
                                                    append(" (")
                                                    append(uiState.config.userId)
                                                    append(")")
                                                }
                                            }
                                            val panelBadge = uiState.config.remoteControl.panelName
                                                .ifBlank { uiState.config.storeCode.ifBlank { uiState.config.panelId } }
                                            if (panelBadge.isNotBlank()) {
                                                append(" • ")
                                                append(panelBadge)
                                            }
                                            append(" • ")
                                            val onlineLabel = if (uiState.networkTransportLabel.isNotBlank()) {
                                                uiState.networkTransportLabel
                                            } else {
                                                "Online"
                                            }
                                            append(
                                                when {
                                                    !uiState.isOnline -> "Offline"
                                                    gpsActive -> "$onlineLabel • GPS"
                                                    else -> onlineLabel
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
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.reloadActiveWorkspace() },
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
                if (!useRail && bottomNavTabs.isNotEmpty()) {
                    NavigationBar {
                        bottomNavTabs.forEach { tab ->
                            val selected = if (tab.type == "alerts") {
                                showAlertsOverlay
                            } else {
                                isPrimaryWorkspaceActive && uiState.selectedBottomTabId == tab.id
                            }
                            NavigationBarItem(
                                selected = selected,
                                onClick = { viewModel.selectBottomTab(tab) },
                                icon = {
                                    BottomTabIconWithBadge(
                                        tab = tab,
                                        selected = selected,
                                        unreadCount = uiState.unreadCount
                                    )
                                },
                                label = {
                                    Text(
                                        text = tab.label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                modifier = Modifier.testTag("nav_tab_${tab.id}")
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
                        bottomNavTabs.forEach { tab ->
                            val selected = if (tab.type == "alerts") {
                                showAlertsOverlay
                            } else {
                                isPrimaryWorkspaceActive && uiState.selectedBottomTabId == tab.id
                            }
                            NavigationRailItem(
                                selected = selected,
                                onClick = { viewModel.selectBottomTab(tab) },
                                icon = {
                                    BottomTabIconWithBadge(
                                        tab = tab,
                                        selected = selected,
                                        unreadCount = uiState.unreadCount
                                    )
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag("rail_tab_${tab.id}")
                            )
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    if (!isStoreBound) {
                        // Unbound clean launch: display default generic login portal only
                        key("unbound_generic_portal") {
                            PortalWebViewScreen(
                                initialUrl = DEFAULT_GENERIC_PORTAL_URL,
                                isVisible = true,
                                isOnline = uiState.isOnline,
                                pendingSyncCount = 0,
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
                                onSessionCookieCaptured = {},
                                onLogoutUrlVisited = {},
                                onSnapshotCaptured = viewModel::saveCachedPageSnapshot,
                                onOfflineCacheModeChanged = viewModel::setShowingOfflineCache,
                                onSelectCachedSnapshot = viewModel::openCachedSnapshot,
                                onShowCustomOfflineScreen = viewModel::showCustomOfflineScreen,
                                onOpenAlerts = {},
                                onManualRefresh = { viewModel.reloadCurrentWebPage() },
                                isInnerScrolledProvider = { viewModel.isWebInnerScrolled },
                                extraHeadersProvider = viewModel::buildWebViewHeaders,
                                boundStoreRootUrl = "",
                                boundLoginUrl = "",
                                useFullStoreHooks = false,
                                tagPrefix = "generic_portal",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zIndex(2f)
                            )
                        }
                    } else {
                        // Bound primary workspace WebView — locked strictly to the bound store's login/portal URL
                        key(primaryStoreTab.id, uiState.config.boundStoreUrl) {
                            val primaryVisible = !showAlertsOverlay &&
                                uiState.selectedWorkspaceId == primaryStoreTab.id
                            PortalWebViewScreen(
                                initialUrl = primaryStoreTab.url.ifBlank { uiState.config.baseUrl },
                                isVisible = primaryVisible,
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
                                onOpenAlerts = { viewModel.openAlertsTab() },
                                onManualRefresh = {
                                    viewModel.reloadCurrentWebPage()
                                    viewModel.syncNow()
                                },
                                isInnerScrolledProvider = { viewModel.isWebInnerScrolled },
                                extraHeadersProvider = viewModel::buildWebViewHeaders,
                                boundStoreRootUrl = uiState.config.boundStoreUrl,
                                boundLoginUrl = uiState.config.baseUrl,
                                useFullStoreHooks = true,
                                tagPrefix = primaryStoreTab.id,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zIndex(if (primaryVisible) 2f else 0f)
                            )
                        }

                        // Dynamic secondary top workspaces (panel-configured tabs), kept alive once opened
                        uiState.visibleTopTabs
                            .filter { !it.isPrimaryStore && it.id in uiState.openedWorkspaceIds }
                            .forEach { topTab ->
                                key(topTab.id, uiState.config.boundStoreUrl) {
                                    val tabVisible = !showAlertsOverlay && uiState.selectedWorkspaceId == topTab.id
                                    val secState = uiState.secondaryWebStates[topTab.id]
                                        ?: SecondaryWebState(workspaceId = topTab.id, currentUrl = topTab.url)

                                    PortalWebViewScreen(
                                        initialUrl = topTab.url,
                                        isVisible = tabVisible,
                                        isOnline = uiState.isOnline,
                                        pendingSyncCount = uiState.pendingSyncCount,
                                        pageProgress = secState.pageProgress,
                                        canGoBack = secState.canGoBack,
                                        webError = secState.webError,
                                        isShowingOfflineCache = secState.isShowingOfflineCache,
                                        cachedPages = uiState.cachedPages,
                                        offlinePageCache = viewModel.offlinePageCache,
                                        jsBridge = viewModel.getSecondaryBridge(topTab.id),
                                        commands = viewModel.getSecondaryCommands(topTab.id),
                                        onProgressChanged = { p -> viewModel.updateSecondaryProgress(topTab.id, p) },
                                        onNavigationChanged = { u, b ->
                                            viewModel.updateSecondaryNavigationState(topTab.id, u, b)
                                        },
                                        onErrorChanged = { err -> viewModel.setSecondaryWebError(topTab.id, err) },
                                        onSessionCookieCaptured = {},
                                        onLogoutUrlVisited = {},
                                        onSnapshotCaptured = viewModel::saveCachedPageSnapshot,
                                        onOfflineCacheModeChanged = { cache ->
                                            viewModel.setSecondaryShowingOfflineCache(topTab.id, cache)
                                        },
                                        onSelectCachedSnapshot = { snap ->
                                            viewModel.openSecondaryCachedSnapshot(topTab.id, snap)
                                        },
                                        onShowCustomOfflineScreen = {
                                            viewModel.showSecondaryCustomOfflineScreen(topTab.id)
                                        },
                                        onOpenAlerts = { viewModel.openAlertsTab() },
                                        onManualRefresh = {
                                            viewModel.reloadSecondaryWebPage(topTab.id, topTab.url)
                                        },
                                        isInnerScrolledProvider = { viewModel.isSecondaryInnerScrolled(topTab.id) },
                                        extraHeadersProvider = viewModel::buildWebViewHeaders,
                                        useFullStoreHooks = false,
                                        tagPrefix = topTab.id,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .zIndex(if (tabVisible) 2f else 0f)
                                    )
                                }
                            }
                    }

                    if (showAlertsOverlay) {
                        Surface(
                            color = MaterialTheme.colorScheme.background,
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(3f)
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
        } // end else (isStoreBound)

        if (isGpsBlocked) {
            MandatoryGpsBlockingOverlay(
                hasPermission = uiState.hasForegroundLocationPermission,
                isDeviceGpsOn = uiState.isDeviceGpsEnabled,
                isAppGpsToggleOn = uiState.config.gpsTrackingEnabled,
                onGrantPermission = {
                    foregroundLocationOnlyLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                },
                onOpenAppSettings = {
                    LocationPermissionHelper.openAppDetailsSettings(context)
                },
                onOpenLocationSettings = {
                    LocationPermissionHelper.openLocationSettings(context)
                },
                onEnableAppGpsToggle = {
                    viewModel.toggleGpsTracking(true)
                },
                onRecheck = {
                    viewModel.refreshDeviceAndPermissionState()
                }
            )
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
            onOpenQrScanner = {
                if (!isStoreBound) {
                    showSettingsSheet = false
                    viewModel.clearShopBindingError()
                    showQrScannerDialog = true
                }
            },
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

    if (showShopBindingSheet && !isStoreBound) {
        ShopBindingBottomSheet(
            config = uiState.config,
            bindingState = shopBindingState,
            onBindShopUrl = { url ->
                viewModel.bindShopUrl(url, onReadyToReboot = onRebootApp)
            },
            onOpenQrScanner = {
                showShopBindingSheet = false
                viewModel.clearShopBindingError()
                showQrScannerDialog = true
            },
            onClearError = viewModel::clearShopBindingError,
            onDismiss = { showShopBindingSheet = false }
        )
    }

    if (showQrScannerDialog && !isStoreBound) {
        QrCodeScannerDialog(
            bindingState = shopBindingState,
            onQrCodeScanned = { rawQr ->
                viewModel.bindShopFromQrPayload(rawQr, onReadyToReboot = onRebootApp)
            },
            onClearError = viewModel::clearShopBindingError,
            onDismiss = {
                showQrScannerDialog = false
                viewModel.clearShopBindingError()
            }
        )
    }
}

@Composable
private fun TopWorkspaceTabChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0f)
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier
            .heightIn(min = 38.dp)
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BottomTabIconWithBadge(
    tab: RemoteBottomTab,
    selected: Boolean,
    unreadCount: Int
) {
    val icon = resolveTabIcon(tab.icon.ifBlank { tab.id }, selected)

    if (tab.type == "alerts" && unreadCount > 0) {
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
            Icon(imageVector = icon, contentDescription = tab.label)
        }
    } else {
        Icon(imageVector = icon, contentDescription = tab.label)
    }
}

private fun resolveTabIcon(iconKey: String, selected: Boolean): ImageVector {
    return when (iconKey.trim().lowercase()) {
        "router", "isp" -> if (selected) Icons.Rounded.Router else Icons.Outlined.Router
        "attendance", "person_pin" ->
            if (selected) Icons.Rounded.PersonPinCircle else Icons.Outlined.PersonPinCircle
        "tasks", "task", "checklist" ->
            if (selected) Icons.Rounded.TaskAlt else Icons.Outlined.TaskAlt
        "alerts", "notifications", "notification" ->
            if (selected) Icons.Rounded.Notifications else Icons.Outlined.Notifications
        "dashboard", "admin" ->
            if (selected) Icons.Rounded.Dashboard else Icons.Outlined.Dashboard
        "billing", "receipt", "accounts", "invoice" ->
            if (selected) Icons.Rounded.ReceiptLong else Icons.Outlined.ReceiptLong
        "staff", "people", "group", "users" ->
            if (selected) Icons.Rounded.Group else Icons.Outlined.Group
        "web", "link", "globe" ->
            if (selected) Icons.Rounded.Language else Icons.Outlined.Language
        else -> if (selected) Icons.Rounded.Storefront else Icons.Outlined.Storefront
    }
}

@Composable
private fun MandatoryGpsBlockingOverlay(
    hasPermission: Boolean,
    isDeviceGpsOn: Boolean,
    isAppGpsToggleOn: Boolean,
    onGrantPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onEnableAppGpsToggle: () -> Unit,
    onRecheck: () -> Unit
) {
    val primaryReason = when {
        !hasPermission -> "GPS লোকেশন অনুমতি দেওয়া হয়নি"
        !isDeviceGpsOn -> "ফোনের GPS / Location বন্ধ আছে"
        else -> "অ্যাপের GPS ট্র্যাকিং সেটিংস বন্ধ আছে"
    }

    val primaryButtonLabel = when {
        !hasPermission -> "GPS অনুমতি দিন"
        !isDeviceGpsOn -> "ফোনের GPS অন করুন"
        else -> "অ্যাপ GPS চালু করুন"
    }

    val primaryAction = when {
        !hasPermission -> onGrantPermission
        !isDeviceGpsOn -> onOpenLocationSettings
        else -> onEnableAppGpsToggle
    }

    Surface(
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.98f),
        modifier = Modifier
            .fillMaxSize()
            .zIndex(10f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .testTag("mandatory_gps_blocking_overlay")
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            ElevatedCard(
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.widthIn(max = 460.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.errorContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GpsOff,
                            contentDescription = "GPS বন্ধ",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Text(
                        text = "GPS অন করা আবশ্যক",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = primaryReason,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "GPS অন করা ছাড়া এই সফটওয়্যার ব্যবহার করা যাবে না। একবার GPS অনুমতি দিলে অ্যাপ বন্ধ বা ইন্টারনেট না থাকলেও ব্যাকগ্রাউন্ডে লোকেশন সংরক্ষিত থাকবে এবং ১ ঘণ্টা বা প্যানেল রিকোয়েস্টে সার্ভারে পাঠানো হবে।",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            GpsCheckItem(
                                label = "লোকেশন অনুমতি (Permission)",
                                ok = hasPermission
                            )
                            GpsCheckItem(
                                label = "ফোনের GPS সুইচ (Location ON)",
                                ok = isDeviceGpsOn
                            )
                            GpsCheckItem(
                                label = "অ্যাপ GPS ট্র্যাকিং",
                                ok = isAppGpsToggleOn
                            )
                        }
                    }

                    Button(
                        onClick = primaryAction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 50.dp)
                            .testTag("mandatory_gps_action_button")
                    ) {
                        Text(primaryButtonLabel)
                    }

                    if (!hasPermission) {
                        OutlinedButton(
                            onClick = onOpenAppSettings,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("mandatory_gps_app_settings_button")
                        ) {
                            Text("অ্যাপ সেটিংস থেকে চালু করুন")
                        }
                    } else if (!isDeviceGpsOn) {
                        TextButton(
                            onClick = onRecheck,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("mandatory_gps_recheck_button")
                        ) {
                            Text("GPS অন করেছি — আবার চেক করুন")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GpsCheckItem(
    label: String,
    ok: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = if (ok) "$label — চালু আছে" else "$label — বন্ধ আছে",
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            fontWeight = if (ok) FontWeight.Normal else FontWeight.SemiBold
        )
    }
}
