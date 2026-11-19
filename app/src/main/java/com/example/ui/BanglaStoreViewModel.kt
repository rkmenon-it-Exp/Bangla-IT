package com.example.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.StorePreferencesRepository
import com.example.location.LocationPermissionHelper
import com.example.location.LocationSyncWorker
import com.example.location.LocationUploader
import com.example.location.OnDemandGpsCapturer
import com.example.model.AlertDeliveryMode
import com.example.model.AlertFilter
import com.example.model.AppTab
import com.example.model.AppWorkspace
import com.example.model.DEFAULT_GENERIC_PORTAL_URL
import com.example.model.GpsCollectionMode
import com.example.model.GpsUploadTrigger
import com.example.model.LocationSnapshot
import com.example.model.NotificationEventType
import com.example.model.PortalConfig
import com.example.model.RemoteBottomTab
import com.example.model.RemoteTopTab
import com.example.model.ShopBindingUiState
import com.example.model.StoreNotification
import com.example.model.TaskRefreshMode
import com.example.model.defaultBottomTabs
import com.example.model.defaultTopTabs
import com.example.notifications.NotificationHelper
import com.example.sync.AppUpdateChecker
import com.example.sync.DeviceTelemetryHelper
import com.example.sync.ImmediateSyncWorker
import com.example.sync.NetworkMonitor
import com.example.sync.NotificationPoller
import com.example.sync.NotificationSyncWorker
import com.example.sync.RemoteConfigSyncer
import com.example.sync.ShopHandshakeManager
import com.example.sync.ShopHandshakeResult
import com.example.webview.BanglaStoreBridge
import com.example.webview.CachedPageSnapshot
import com.example.webview.OfflinePageCache
import org.json.JSONObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

sealed interface WebViewCommand {
    data class LoadUrl(val url: String) : WebViewCommand
    data class LoadHtmlSnapshot(val baseUrl: String, val html: String) : WebViewCommand
    data object Reload : WebViewCommand
    data object GoBack : WebViewCommand
}

data class SecondaryWebState(
    val workspaceId: String,
    val pageProgress: Int = 100,
    val currentUrl: String = "",
    val canGoBack: Boolean = false,
    val webError: String? = null,
    val isShowingOfflineCache: Boolean = false
)

data class BanglaStoreUiState(
    val isConfigLoaded: Boolean = false,
    val selectedWorkspaceId: String = AppWorkspace.BANGLA_STORE.id,
    val openedWorkspaceIds: Set<String> = setOf(AppWorkspace.BANGLA_STORE.id),
    val visibleTopTabs: List<RemoteTopTab> = emptyList(),
    val visibleBottomTabs: List<RemoteBottomTab> = emptyList(),
    val selectedBottomTabId: String = AppTab.PORTAL.route,
    val config: PortalConfig = PortalConfig(),
    val notifications: List<StoreNotification> = emptyList(),
    val filteredNotifications: List<StoreNotification> = emptyList(),
    val alertFilter: AlertFilter = AlertFilter.ALL,
    val unreadCount: Int = 0,
    val pageProgress: Int = 100,
    val currentUrl: String = "",
    val canGoBack: Boolean = false,
    val webError: String? = null,
    val secondaryWebStates: Map<String, SecondaryWebState> = emptyMap(),
    val isOnline: Boolean = true,
    val pendingSyncCount: Int = 0,
    val isShowingOfflineCache: Boolean = false,
    val cachedPages: List<CachedPageSnapshot> = emptyList(),
    val isSyncing: Boolean = false,
    val lastSyncTimeMillis: Long = 0L,
    val lastSyncStatus: String = "সিঙ্ক প্রস্তুত (Ready)",
    val locationSnapshot: LocationSnapshot = LocationSnapshot(),
    val hasNotificationPermission: Boolean = true,
    val hasForegroundLocationPermission: Boolean = false,
    val hasBackgroundLocationPermission: Boolean = false,
    val isDeviceGpsEnabled: Boolean = true,
    val showBackgroundLocationDialog: Boolean = false
) {
    val activeWorkspaceTab: RemoteTopTab
        get() = visibleTopTabs.firstOrNull { it.id == selectedWorkspaceId }
            ?: visibleTopTabs.firstOrNull()
            ?: defaultTopTabs(
                primaryUrl = config.baseUrl,
                primaryLabel = config.effectiveStoreDisplayName,
                includeDefaultIsp = false
            ).first()

    val primaryStoreTab: RemoteTopTab
        get() = visibleTopTabs.firstOrNull { it.isPrimaryStore }
            ?: visibleTopTabs.firstOrNull()
            ?: defaultTopTabs(
                primaryUrl = config.baseUrl,
                primaryLabel = config.effectiveStoreDisplayName,
                includeDefaultIsp = false
            ).first()

    val isAlertsOverlayVisible: Boolean
        get() = config.isCustomStoreBound && (
            visibleBottomTabs.firstOrNull { it.id == selectedBottomTabId }?.type == "alerts" ||
                selectedBottomTabId == AppTab.ALERTS.route
            )
}

class BanglaStoreViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = StorePreferencesRepository(application)
    private val poller = NotificationPoller(application, repository)
    private val remoteConfigSyncer = RemoteConfigSyncer(application, repository)
    private val shopHandshakeManager = ShopHandshakeManager(application, repository)
    private val locationUploader = LocationUploader(application, repository)
    private val gpsCapturer = OnDemandGpsCapturer(application, repository, locationUploader)
    private val networkMonitor = NetworkMonitor(application)
    val offlinePageCache = OfflinePageCache(application)

    @Volatile
    private var cachedUniqueDeviceId: String = ""

    private val _shopBindingState = MutableStateFlow(ShopBindingUiState())
    val shopBindingState: StateFlow<ShopBindingUiState> = _shopBindingState.asStateFlow()

    private val _selectedWorkspaceId = MutableStateFlow(AppWorkspace.BANGLA_STORE.id)
    private val _openedWorkspaceIds = MutableStateFlow(setOf(AppWorkspace.BANGLA_STORE.id))
    private val _selectedBottomTabId = MutableStateFlow(AppTab.PORTAL.route)
    private var lastWebBottomTabId: String = AppTab.PORTAL.route
    private val _alertFilter = MutableStateFlow(AlertFilter.ALL)
    private val _pageProgress = MutableStateFlow(100)
    private val _currentUrl = MutableStateFlow("")
    private val _canGoBack = MutableStateFlow(false)
    private val _webError = MutableStateFlow<String?>(null)
    private val _isShowingOfflineCache = MutableStateFlow(false)
    private val _secondaryWebStates = MutableStateFlow<Map<String, SecondaryWebState>>(emptyMap())
    private val _cachedPages = MutableStateFlow(offlinePageCache.listCachedPages())
    private val _isSyncing = MutableStateFlow(false)
    private val _hasNotificationPermission = MutableStateFlow(
        NotificationHelper.hasNotificationPermission(application)
    )
    private val _hasForegroundLocation = MutableStateFlow(
        LocationPermissionHelper.hasForegroundLocationPermission(application)
    )
    private val _hasBackgroundLocation = MutableStateFlow(
        LocationPermissionHelper.hasBackgroundLocationPermission(application)
    )
    private val _isDeviceGpsEnabled = MutableStateFlow(
        LocationPermissionHelper.isDeviceLocationEnabled(application)
    )
    private val _showBackgroundLocationDialog = MutableStateFlow(false)
    private var promptedBackgroundThisSession = false
    private var wasOnline = networkMonitor.isOnline.value
    private var sawLoginPage = false
    private var pendingPostLoginRedirect = false
    private var lastAutoRefreshMillis = 0L

    @Volatile
    var isWebInnerScrolled: Boolean = false
        private set

    private val secondaryInnerScrolledMap = ConcurrentHashMap<String, Boolean>()
    private val secondaryCommandsMap = ConcurrentHashMap<String, MutableSharedFlow<WebViewCommand>>()
    private val secondaryBridgesMap = ConcurrentHashMap<String, BanglaStoreBridge>()

    private val _webViewCommands = MutableSharedFlow<WebViewCommand>(extraBufferCapacity = 16)
    val webViewCommands: SharedFlow<WebViewCommand> = _webViewCommands.asSharedFlow()

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

    private data class WebAggregate(
        val selectedWorkspaceId: String,
        val openedWorkspaceIds: Set<String>,
        val selectedBottomTabId: String,
        val pageProgress: Int,
        val currentUrl: String,
        val canGoBack: Boolean,
        val webError: String?,
        val isShowingOfflineCache: Boolean,
        val secondaryStates: Map<String, SecondaryWebState>,
        val cachedPages: List<CachedPageSnapshot>
    )

    private val webAggregateFlow = combine(
        _selectedWorkspaceId,
        _openedWorkspaceIds,
        _selectedBottomTabId,
        _pageProgress,
        _currentUrl,
        _canGoBack,
        _webError,
        _isShowingOfflineCache,
        _secondaryWebStates,
        _cachedPages
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        WebAggregate(
            selectedWorkspaceId = values[0] as String,
            openedWorkspaceIds = values[1] as Set<String>,
            selectedBottomTabId = values[2] as String,
            pageProgress = values[3] as Int,
            currentUrl = values[4] as String,
            canGoBack = values[5] as Boolean,
            webError = values[6] as String?,
            isShowingOfflineCache = values[7] as Boolean,
            secondaryStates = values[8] as Map<String, SecondaryWebState>,
            cachedPages = values[9] as List<CachedPageSnapshot>
        )
    }

    val uiState: StateFlow<BanglaStoreUiState> = combine(
        webAggregateFlow,
        repository.configFlow,
        repository.notificationsFlow,
        _alertFilter,
        repository.syncStatusFlow,
        repository.locationFlow,
        repository.pendingSyncCountFlow,
        networkMonitor.isOnline,
        _isSyncing,
        _hasNotificationPermission,
        _hasForegroundLocation,
        _hasBackgroundLocation,
        _isDeviceGpsEnabled,
        _showBackgroundLocationDialog
    ) { values ->
        val webAgg = values[0] as WebAggregate
        val config = values[1] as PortalConfig
        @Suppress("UNCHECKED_CAST")
        val allNotifications = values[2] as List<StoreNotification>
        val filter = values[3] as AlertFilter
        @Suppress("UNCHECKED_CAST")
        val syncPair = values[4] as Pair<Long, String>
        val location = values[5] as LocationSnapshot
        val pendingCount = values[6] as Int
        val isOnline = values[7] as Boolean
        val isSyncing = values[8] as Boolean
        val hasNotifPerm = values[9] as Boolean
        val hasFgLoc = values[10] as Boolean
        val hasBgLoc = values[11] as Boolean
        val isDeviceGps = values[12] as Boolean
        val showBgDialog = values[13] as Boolean

        val isBound = config.isCustomStoreBound
        val roleKey = config.userRole.key.lowercase()

        val visibleTop = if (!isBound) {
            emptyList()
        } else {
            config.remoteControl.topTabs
                .filter { tab ->
                    tab.enabled && (tab.roles.isEmpty() || "all" in tab.roles || roleKey in tab.roles)
                }
                .ifEmpty {
                    defaultTopTabs(
                        primaryUrl = config.baseUrl,
                        primaryLabel = config.effectiveStoreDisplayName,
                        includeDefaultIsp = false
                    )
                }
        }

        val visibleBottom = if (!isBound) {
            emptyList()
        } else {
            config.remoteControl.bottomTabs
                .filter { tab ->
                    tab.enabled && (tab.roles.isEmpty() || "all" in tab.roles || roleKey in tab.roles)
                }
                .ifEmpty {
                    defaultBottomTabs(
                        baseUrl = config.baseUrl,
                        attendanceUrl = config.attendanceUrl,
                        tasksUrl = config.tasksUrl
                    )
                }
        }

        val primaryId = (visibleTop.firstOrNull { it.isPrimaryStore } ?: visibleTop.firstOrNull())?.id
            ?: AppWorkspace.BANGLA_STORE.id
        val effectiveWorkspaceId = if (visibleTop.any { it.id == webAgg.selectedWorkspaceId }) {
            webAgg.selectedWorkspaceId
        } else {
            primaryId
        }
        val effectiveOpened = webAgg.openedWorkspaceIds + primaryId

        val effectiveBottomTabId = if (visibleBottom.any { it.id == webAgg.selectedBottomTabId }) {
            webAgg.selectedBottomTabId
        } else {
            visibleBottom.firstOrNull()?.id ?: AppTab.PORTAL.route
        }

        val filtered = when (filter) {
            AlertFilter.ALL -> allNotifications
            AlertFilter.ATTENDANCE -> allNotifications.filter {
                it.type == NotificationEventType.ATTENDANCE_SUBMITTED
            }
            AlertFilter.TASKS -> allNotifications.filter {
                it.type == NotificationEventType.TASK_ASSIGNED ||
                    it.type == NotificationEventType.TASK_COMPLETED
            }
        }

        val fallbackUrl = if (isBound) config.baseUrl else DEFAULT_GENERIC_PORTAL_URL

        BanglaStoreUiState(
            isConfigLoaded = true,
            selectedWorkspaceId = effectiveWorkspaceId,
            openedWorkspaceIds = effectiveOpened,
            visibleTopTabs = visibleTop,
            visibleBottomTabs = visibleBottom,
            selectedBottomTabId = effectiveBottomTabId,
            config = config,
            notifications = allNotifications,
            filteredNotifications = filtered,
            alertFilter = filter,
            unreadCount = allNotifications.count { !it.isRead },
            pageProgress = webAgg.pageProgress,
            currentUrl = webAgg.currentUrl.ifBlank { fallbackUrl },
            canGoBack = webAgg.canGoBack,
            webError = webAgg.webError,
            secondaryWebStates = webAgg.secondaryStates,
            isOnline = isOnline,
            pendingSyncCount = pendingCount,
            isShowingOfflineCache = webAgg.isShowingOfflineCache,
            cachedPages = webAgg.cachedPages,
            isSyncing = isSyncing,
            lastSyncTimeMillis = syncPair.first,
            lastSyncStatus = syncPair.second,
            locationSnapshot = location,
            hasNotificationPermission = hasNotifPerm,
            hasForegroundLocationPermission = hasFgLoc,
            hasBackgroundLocationPermission = hasBgLoc,
            isDeviceGpsEnabled = isDeviceGps,
            showBackgroundLocationDialog = showBgDialog
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = BanglaStoreUiState(isConfigLoaded = false)
    )

    val jsBridge: BanglaStoreBridge = BanglaStoreBridge(
        onSessionDetectedCallback = { role, userId, userName, panelId ->
            viewModelScope.launch {
                val prev = repository.getConfigSnapshot()
                if (!prev.isCustomStoreBound) return@launch
                repository.updateSessionFromWeb(role, userId, userName, panelId)
                val updated = repository.getConfigSnapshot()
                if (updated.panelId != prev.panelId ||
                    updated.userId != prev.userId ||
                    updated.userRole != prev.userRole
                ) {
                    remoteConfigSyncer.syncConfig()
                }
            }
        },
        onLoginSubmittedCallback = { userId ->
            viewModelScope.launch {
                val cfg = repository.getConfigSnapshot()
                if (!cfg.isCustomStoreBound) return@launch
                pendingPostLoginRedirect = true
                if (userId.isNotBlank()) {
                    repository.markLoggedInUserId(userId)
                }
            }
        },
        onLogoutDetectedCallback = {
            viewModelScope.launch {
                val cfg = repository.getConfigSnapshot()
                if (!cfg.isCustomStoreBound) return@launch
                sawLoginPage = true
                pendingPostLoginRedirect = false
                repository.clearLoginSession()
            }
        },
        onPageReadyCallback = { url, hasLoginForm ->
            viewModelScope.launch {
                handleWebPageReady(url, hasLoginForm)
            }
        },
        onIncomingEventCallback = { rawNotif, shouldAutoRefresh ->
            viewModelScope.launch {
                val config = repository.getConfigSnapshot()
                if (!config.isCustomStoreBound) return@launch
                val remoteControl = config.remoteControl
                if (remoteControl.allowedEventTypes.isNotEmpty() &&
                    rawNotif.type.key.lowercase() !in remoteControl.allowedEventTypes
                ) {
                    return@launch
                }

                val resolvedTargetUrl = rawNotif.targetUrl.ifBlank {
                    when (rawNotif.type) {
                        NotificationEventType.ATTENDANCE_SUBMITTED -> config.attendanceUrl
                        NotificationEventType.TASK_ASSIGNED,
                        NotificationEventType.TASK_COMPLETED -> config.tasksUrl
                        NotificationEventType.GPS_REQUEST,
                        NotificationEventType.GENERAL -> config.baseUrl
                    }
                }
                val finalNotif = rawNotif.copy(
                    panelId = rawNotif.panelId.ifBlank { config.panelId },
                    targetUrl = resolvedTargetUrl
                )
                val isNew = repository.recordIncomingNotification(finalNotif, deduplicate = true)
                val canDeliver = remoteControl.notificationsEnabled &&
                    remoteControl.alertDeliveryMode != AlertDeliveryMode.OFF
                if (isNew && canDeliver && shouldShowForCurrentRole(finalNotif, config)) {
                    NotificationHelper.showNotification(
                        getApplication(),
                        finalNotif,
                        remoteControl.alertDeliveryMode
                    )
                    _snackbarMessages.tryEmit(finalNotif.title)
                }
                if (shouldAutoRefresh) {
                    tryAutoRefreshTasks(
                        config = config,
                        message = "টাস্ক আপডেট হয়েছে — পেজ অটো-রিফ্রেশ করা হচ্ছে"
                    )
                }
            }
        },
        onRefreshRequestedCallback = { targetUrl ->
            viewModelScope.launch {
                if (targetUrl.isNullOrBlank()) {
                    _webViewCommands.tryEmit(WebViewCommand.Reload)
                } else {
                    _webViewCommands.tryEmit(WebViewCommand.LoadUrl(targetUrl))
                }
            }
        },
        onGpsRequestedCallback = { requestId ->
            viewModelScope.launch {
                val config = repository.getConfigSnapshot()
                if (!config.isCustomStoreBound) return@launch
                val snap = gpsCapturer.captureAndUploadIfAllowed(
                    trigger = GpsUploadTrigger.PANEL_REQUEST,
                    requestId = requestId,
                    isBackground = false
                )
                if (snap != null) {
                    _snackbarMessages.tryEmit("প্যানেল রিকোয়েস্ট অনুযায়ী GPS পাঠানো হয়েছে")
                }
            }
        },
        onInnerScrollChangedCallback = { scrolled ->
            isWebInnerScrolled = scrolled
        },
        locationSnapshotProvider = { uiState.value.locationSnapshot },
        deviceInfoProvider = { buildDeviceInfoJson() },
        deviceIdProvider = { currentDeviceId() },
        deviceModelProvider = { DeviceTelemetryHelper.getDeviceModel() },
        batteryLevelProvider = { DeviceTelemetryHelper.getBatteryLevel(getApplication()) }
    )

    private fun currentDeviceId(): String {
        val ctx = getApplication<Application>()
        return cachedUniqueDeviceId.ifBlank { DeviceTelemetryHelper.getDeviceId(ctx) }
    }

    fun buildWebViewHeaders(): Map<String, String> {
        return DeviceTelemetryHelper.buildRequestHeaders(getApplication(), currentDeviceId())
    }

    private fun buildDeviceInfoJson(): String {
        val ctx = getApplication<Application>()
        val cfg = uiState.value.config
        val deviceId = currentDeviceId()
        return JSONObject().apply {
            put("device_id", deviceId)
            put("unique_device_id", deviceId)
            put("device_model", DeviceTelemetryHelper.getDeviceModel())
            put("os_version", DeviceTelemetryHelper.getOsVersion())
            put("app_version", DeviceTelemetryHelper.getAppVersion())
            put("battery_level", DeviceTelemetryHelper.getBatteryLevel(ctx))
            put("store_code", cfg.storeCode)
            put("panel_id", cfg.panelId)
            put("is_bound", cfg.isCustomStoreBound)
        }.toString()
    }

    fun getSecondaryCommands(workspaceId: String): SharedFlow<WebViewCommand> {
        return getOrCreateSecondaryCommandFlow(workspaceId).asSharedFlow()
    }

    private fun getOrCreateSecondaryCommandFlow(workspaceId: String): MutableSharedFlow<WebViewCommand> {
        return secondaryCommandsMap.getOrPut(workspaceId) {
            MutableSharedFlow(extraBufferCapacity = 16)
        }
    }

    fun getSecondaryBridge(workspaceId: String): BanglaStoreBridge {
        return secondaryBridgesMap.getOrPut(workspaceId) {
            BanglaStoreBridge(
                onSessionDetectedCallback = { _, _, _, _ -> },
                onLoginSubmittedCallback = { _ -> },
                onLogoutDetectedCallback = {},
                onPageReadyCallback = { _, _ -> },
                onIncomingEventCallback = { _, _ -> },
                onRefreshRequestedCallback = { targetUrl ->
                    viewModelScope.launch {
                        val flow = getOrCreateSecondaryCommandFlow(workspaceId)
                        if (targetUrl.isNullOrBlank()) {
                            flow.tryEmit(WebViewCommand.Reload)
                        } else {
                            flow.tryEmit(WebViewCommand.LoadUrl(targetUrl))
                        }
                    }
                },
                onInnerScrollChangedCallback = { scrolled ->
                    secondaryInnerScrolledMap[workspaceId] = scrolled
                },
                locationSnapshotProvider = { uiState.value.locationSnapshot },
                deviceInfoProvider = { buildDeviceInfoJson() },
                deviceIdProvider = { currentDeviceId() },
                deviceModelProvider = { DeviceTelemetryHelper.getDeviceModel() },
                batteryLevelProvider = { DeviceTelemetryHelper.getBatteryLevel(getApplication()) }
            )
        }
    }

    fun isSecondaryInnerScrolled(workspaceId: String): Boolean {
        return secondaryInnerScrolledMap[workspaceId] ?: false
    }

    private suspend fun handleWebPageReady(url: String, hasLoginForm: Boolean) {
        val initialCfg = repository.getConfigSnapshot()
        if (!initialCfg.isCustomStoreBound ||
            _isShowingOfflineCache.value ||
            url.isBlank() ||
            url.startsWith("data:", ignoreCase = true) ||
            !url.startsWith("http", ignoreCase = true)
        ) {
            return
        }

        val isLogout = isLogoutUrl(url)
        val isLogin = !isLogout && (hasLoginForm || isLoginUrl(url))

        if (isLogout || isLogin) {
            sawLoginPage = true
            return
        }

        // Authenticated non-login page loaded: check if we just completed a login flow
        if (pendingPostLoginRedirect || sawLoginPage) {
            pendingPostLoginRedirect = false
            sawLoginPage = false

            remoteConfigSyncer.syncConfig()

            val config = repository.getConfigSnapshot()
            val visibleBottom = uiState.value.visibleBottomTabs
            val targetBottomId = config.previousTabId.takeIf { id ->
                visibleBottom.any { it.id == id && it.type == "web" }
            } ?: if (config.previousTab.isWebTab) config.previousTab.route else AppTab.PORTAL.route

            val matchingBottomTab = visibleBottom.firstOrNull { it.id == targetBottomId }
            val legacyTab = AppTab.fromRoute(targetBottomId)
            val tabDefaultUrl = matchingBottomTab?.url?.ifBlank { null } ?: defaultUrlForTab(legacyTab, config)
            val savedPrevUrl = config.previousUrl.takeIf {
                it.startsWith("https://", ignoreCase = true) &&
                    !isLoginUrl(it) &&
                    !isLogoutUrl(it) &&
                    !RemoteConfigSyncer.isGlobalPortalUrlForBoundStore(it, config.boundStoreUrl)
            }
            val targetUrl = savedPrevUrl ?: tabDefaultUrl

            val isPortalHomeAlreadyOpen = targetBottomId == AppTab.PORTAL.route &&
                urlsEffectivelyEqual(targetUrl, config.baseUrl) &&
                url.startsWith(config.boundStoreUrl, ignoreCase = true)

            if (!isPortalHomeAlreadyOpen &&
                (targetBottomId != _selectedBottomTabId.value || !urlsEffectivelyEqual(url, targetUrl))
            ) {
                _selectedWorkspaceId.value = uiState.value.primaryStoreTab.id
                _selectedBottomTabId.value = targetBottomId
                lastWebBottomTabId = targetBottomId
                _webError.value = null
                _isShowingOfflineCache.value = false
                _webViewCommands.tryEmit(WebViewCommand.LoadUrl(targetUrl))
                val tabLabel = matchingBottomTab?.label ?: legacyTab.labelBn
                _snackbarMessages.tryEmit("লগইন সম্পন্ন — পূর্ববর্তী ট্যাবে ($tabLabel) যাওয়া হচ্ছে")
                return
            }
        }

        // Record current authenticated web tab & URL as the previous tab for future logins
        if (!RemoteConfigSyncer.isGlobalPortalUrlForBoundStore(url, initialCfg.boundStoreUrl)) {
            val activeId = if (!uiState.value.isAlertsOverlayVisible) {
                _selectedBottomTabId.value
            } else {
                lastWebBottomTabId
            }
            repository.savePreviousTabLocation(activeId, AppTab.fromRoute(activeId), url)
        }
    }

    private fun defaultUrlForTab(tab: AppTab, config: PortalConfig): String {
        return when (tab) {
            AppTab.ATTENDANCE -> config.attendanceUrl
            AppTab.TASKS -> config.tasksUrl
            AppTab.PORTAL, AppTab.ALERTS -> config.baseUrl
        }
    }

    private fun isLoginUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("login") ||
            lower.contains("signin") ||
            lower.contains("sign-in") ||
            lower.contains("auth.php")
    }

    private fun isLogoutUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("logout") || lower.contains("signout")
    }

    private fun urlsEffectivelyEqual(a: String, b: String): Boolean {
        return a.trim().trimEnd('/').equals(b.trim().trimEnd('/'), ignoreCase = true)
    }

    private val providerChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshDeviceAndPermissionState()
        }
    }

    init {
        NotificationHelper.ensureChannels(application)
        runCatching {
            application.registerReceiver(
                providerChangedReceiver,
                IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
            )
        }
        viewModelScope.launch {
            cachedUniqueDeviceId = repository.getOrCreateUniqueDeviceId()
            val initialConfig = repository.getConfigSnapshot()
            if (!initialConfig.isCustomStoreBound) {
                // Clean unbound launch: stop all background services/workers and do not contact any store
                LocationPermissionHelper.stopTrackingService(getApplication())
                NotificationSyncWorker.schedulePeriodicSync(getApplication(), enabled = false)
                LocationSyncWorker.schedulePeriodicLocationSync(getApplication(), enabled = false)
                return@launch
            }

            if (LocationPermissionHelper.hasForegroundLocationPermission(application)) {
                repository.ensureAndGetGpsScheduleAnchorTime()
            }
            remoteConfigSyncer.syncConfig()
            val config = repository.getConfigSnapshot()
            NotificationSyncWorker.schedulePeriodicSync(
                getApplication(),
                config.isCustomStoreBound && config.backgroundSyncEnabled && config.remoteControl.backgroundSyncEnabled
            )
            syncGpsWorkersAndService(config)
            // Arm reconnect worker if there are already queued items or currently offline
            if (!networkMonitor.isOnline.value || repository.getPendingLocations().isNotEmpty()) {
                ImmediateSyncWorker.scheduleOnReconnect(getApplication())
            }
            delay(3000)
            AppUpdateChecker.checkForUpdate(getApplication(), triggerNotification = true)
        }
        observeNetworkRecovery()
        startForegroundSyncLoop()
    }

    private fun syncGpsWorkersAndService(config: PortalConfig) {
        val ctx = getApplication<Application>()
        val fgGranted = LocationPermissionHelper.hasForegroundLocationPermission(ctx)
        val gpsAllowed = config.isCustomStoreBound &&
            config.gpsTrackingEnabled &&
            config.remoteControl.gpsEnabled &&
            config.remoteControl.gpsMode != GpsCollectionMode.OFF &&
            fgGranted

        if (!gpsAllowed) {
            LocationPermissionHelper.stopTrackingService(ctx)
            LocationSyncWorker.schedulePeriodicLocationSync(ctx, enabled = false)
            return
        }

        viewModelScope.launch {
            repository.ensureAndGetGpsScheduleAnchorTime()
        }

        // Keep background collector alive once permission is granted so offline GPS data continues
        // collecting even when app is closed, and sends immediately on reconnect once >= 1h elapses.
        if (config.remoteControl.gpsBackgroundEnabled || config.remoteControl.gpsMode == GpsCollectionMode.CONTINUOUS) {
            LocationPermissionHelper.startTrackingService(ctx)
        } else {
            LocationPermissionHelper.stopTrackingService(ctx)
        }

        val intervalHours = (config.remoteControl.gpsIntervalSeconds.coerceAtLeast(3600) / 3600L)
            .coerceAtLeast(1L)
        LocationSyncWorker.schedulePeriodicLocationSync(
            context = ctx,
            enabled = config.remoteControl.gpsBackgroundEnabled,
            intervalHours = intervalHours
        )
    }

    private fun observeNetworkRecovery() {
        viewModelScope.launch {
            networkMonitor.isOnline
                .collect { online ->
                    val currentCfg = repository.getConfigSnapshot()
                    if (!online) {
                        wasOnline = false
                        if (currentCfg.isCustomStoreBound) {
                            ImmediateSyncWorker.scheduleOnReconnect(getApplication())
                        }
                    } else if (!wasOnline) {
                        wasOnline = true
                        val hadOfflineUi = _webError.value != null || _isShowingOfflineCache.value
                        _webError.value = null
                        _isShowingOfflineCache.value = false
                        if (hadOfflineUi) {
                            _webViewCommands.tryEmit(WebViewCommand.Reload)
                        }
                        _secondaryWebStates.value.forEach { (wsId, state) ->
                            if (state.webError != null || state.isShowingOfflineCache) {
                                updateSecondaryState(wsId) {
                                    it.copy(webError = null, isShowingOfflineCache = false)
                                }
                                getOrCreateSecondaryCommandFlow(wsId).tryEmit(WebViewCommand.Reload)
                            }
                        }

                        if (!currentCfg.isCustomStoreBound) {
                            _snackbarMessages.tryEmit("ইন্টারনেট সংযোগ ফিরে এসেছে")
                            return@collect
                        }

                        remoteConfigSyncer.syncConfig()
                        val latestCfg = repository.getConfigSnapshot()
                        val intervalMs = latestCfg.remoteControl.gpsIntervalSeconds.coerceAtLeast(3600) * 1000L
                        val refTime = repository.getGpsReferenceUploadOrAnchorTime()
                        val elapsed = if (refTime > 0L) System.currentTimeMillis() - refTime else 0L
                        val hourlyDue = refTime > 0L && elapsed >= (intervalMs - 90_000L)

                        var flushed = locationUploader.flushPendingLocations(forceAll = hourlyDue)
                        if (hourlyDue && flushed == 0) {
                            val sent = gpsCapturer.captureAndUploadIfAllowed(
                                trigger = GpsUploadTrigger.HOURLY_SCHEDULE,
                                isBackground = false
                            )
                            if (sent != null) flushed = 1
                        }

                        runPollCycle(showSnackbarOnError = false)
                        _snackbarMessages.tryEmit(
                            if (flushed > 0) {
                                "অনলাইন: $flushed টি সংরক্ষিত GPS/ডেটা সিঙ্ক হয়েছে"
                            } else {
                                "ইন্টারনেট সংযোগ ফিরে এসেছে — অটো-সিঙ্ক সম্পন্ন"
                            }
                        )
                    }
                }
        }
    }

    private fun startForegroundSyncLoop() {
        viewModelScope.launch {
            while (isActive) {
                val config = repository.getConfigSnapshot()
                val effectiveSync = config.isCustomStoreBound &&
                    config.backgroundSyncEnabled &&
                    config.remoteControl.backgroundSyncEnabled
                if (effectiveSync && networkMonitor.isOnline.value) {
                    remoteConfigSyncer.syncConfig()
                    val latestCfg = repository.getConfigSnapshot()
                    val intervalMs = latestCfg.remoteControl.gpsIntervalSeconds.coerceAtLeast(3600) * 1000L
                    val refTime = repository.getGpsReferenceUploadOrAnchorTime()
                    val hourlyDue = refTime > 0L && (System.currentTimeMillis() - refTime) >= (intervalMs - 90_000L)

                    val flushed = locationUploader.flushPendingLocations(forceAll = hourlyDue)
                    runPollCycle(showSnackbarOnError = false)
                    if (hourlyDue && flushed == 0) {
                        gpsCapturer.captureAndUploadIfAllowed(
                            trigger = GpsUploadTrigger.HOURLY_SCHEDULE,
                            isBackground = false
                        )
                    }
                }
                val intervalMs = config.remoteControl.syncIntervalSeconds.coerceIn(10, 3600) * 1000L
                delay(intervalMs)
            }
        }
    }

    fun refreshDeviceAndPermissionState() {
        val ctx = getApplication<Application>()
        _isDeviceGpsEnabled.value = LocationPermissionHelper.isDeviceLocationEnabled(ctx)
        onPermissionsUpdated(allowBackgroundPrompt = true)
    }

    fun onPermissionsUpdated(allowBackgroundPrompt: Boolean = true) {
        val ctx = getApplication<Application>()
        val notifGranted = NotificationHelper.hasNotificationPermission(ctx)
        val fgLocGranted = LocationPermissionHelper.hasForegroundLocationPermission(ctx)
        val bgLocGranted = LocationPermissionHelper.hasBackgroundLocationPermission(ctx)
        val gpsHardwareOn = LocationPermissionHelper.isDeviceLocationEnabled(ctx)

        _hasNotificationPermission.value = notifGranted
        _hasForegroundLocation.value = fgLocGranted
        _hasBackgroundLocation.value = bgLocGranted
        _isDeviceGpsEnabled.value = gpsHardwareOn

        viewModelScope.launch {
            val config = repository.getConfigSnapshot()
            if (!config.isCustomStoreBound) {
                syncGpsWorkersAndService(config)
                return@launch
            }
            if (fgLocGranted) {
                repository.ensureAndGetGpsScheduleAnchorTime()
            }
            syncGpsWorkersAndService(config)
            if (fgLocGranted &&
                config.gpsTrackingEnabled &&
                config.remoteControl.gpsEnabled &&
                allowBackgroundPrompt &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                !bgLocGranted &&
                !promptedBackgroundThisSession
            ) {
                promptedBackgroundThisSession = true
                _showBackgroundLocationDialog.value = true
            }
        }
    }

    fun promptBackgroundLocationNow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            _showBackgroundLocationDialog.value = true
        }
    }

    fun dismissBackgroundLocationDialog() {
        _showBackgroundLocationDialog.value = false
    }

    fun onSessionCookieCaptured(cookie: String?) {
        viewModelScope.launch {
            repository.saveSessionCookie(cookie)
        }
    }

    fun onLogoutUrlVisited() {
        sawLoginPage = true
        pendingPostLoginRedirect = false
        viewModelScope.launch {
            repository.clearLoginSession()
        }
    }

    fun saveCachedPageSnapshot(url: String, title: String, html: String) {
        viewModelScope.launch {
            offlinePageCache.saveSnapshot(url, title, html)
            _cachedPages.value = offlinePageCache.listCachedPages()
        }
    }

    fun setShowingOfflineCache(showing: Boolean) {
        _isShowingOfflineCache.value = showing
    }

    fun openCachedSnapshot(snapshot: CachedPageSnapshot) {
        _webError.value = null
        _isShowingOfflineCache.value = true
        _webViewCommands.tryEmit(WebViewCommand.LoadHtmlSnapshot(snapshot.url, snapshot.html))
    }

    fun showCustomOfflineScreen() {
        _isShowingOfflineCache.value = false
        _webError.value = "ইন্টারনেট সংযোগ বিচ্ছিন্ন রয়েছে (Offline)"
    }

    fun selectWorkspace(workspace: RemoteTopTab) {
        _selectedWorkspaceId.value = workspace.id
        _openedWorkspaceIds.update { it + workspace.id }
        if (!workspace.isPrimaryStore && !_secondaryWebStates.value.containsKey(workspace.id)) {
            _secondaryWebStates.update { map ->
                map + (workspace.id to SecondaryWebState(workspaceId = workspace.id, currentUrl = workspace.url))
            }
        }
        if (uiState.value.isAlertsOverlayVisible) {
            _selectedBottomTabId.value = lastWebBottomTabId
        }
    }

    fun selectPrimaryWorkspace() {
        selectWorkspace(uiState.value.primaryStoreTab)
    }

    fun exitAlertsScreen() {
        _selectedBottomTabId.value = lastWebBottomTabId
    }

    fun openAlertsTab() {
        val alertsTab = uiState.value.visibleBottomTabs.firstOrNull { it.type == "alerts" }
        if (alertsTab != null) {
            selectBottomTab(alertsTab)
        } else {
            _selectedBottomTabId.value = AppTab.ALERTS.route
        }
    }

    fun selectBottomTab(tab: RemoteBottomTab) {
        _selectedBottomTabId.value = tab.id
        if (tab.type == "alerts") {
            return
        }

        val primaryTab = uiState.value.primaryStoreTab
        _selectedWorkspaceId.value = primaryTab.id
        _openedWorkspaceIds.update { it + primaryTab.id }

        val targetUrl = tab.url.ifBlank { defaultUrlForTab(AppTab.fromRoute(tab.id), uiState.value.config) }
        val tabChanged = lastWebBottomTabId != tab.id
        if (tabChanged) {
            viewModelScope.launch {
                repository.savePreviousTabLocation(tab.id, AppTab.fromRoute(tab.id), targetUrl)
            }
        }

        if (tabChanged || _currentUrl.value.isBlank() || !urlsEffectivelyEqual(_currentUrl.value, targetUrl)) {
            _webViewCommands.tryEmit(WebViewCommand.LoadUrl(targetUrl))
        }
        lastWebBottomTabId = tab.id
    }

    fun setAlertFilter(filter: AlertFilter) {
        _alertFilter.value = filter
    }

    fun reloadActiveWorkspace() {
        if (!uiState.value.config.isCustomStoreBound) {
            reloadCurrentWebPage()
            return
        }
        val active = uiState.value.activeWorkspaceTab
        if (active.isPrimaryStore) {
            reloadCurrentWebPage()
            syncNow()
        } else {
            reloadSecondaryWebPage(active.id, active.url)
        }
    }

    fun navigateActiveWebBack() {
        if (!uiState.value.config.isCustomStoreBound) {
            navigateWebBack()
            return
        }
        val active = uiState.value.activeWorkspaceTab
        if (active.isPrimaryStore) {
            navigateWebBack()
        } else {
            getOrCreateSecondaryCommandFlow(active.id).tryEmit(WebViewCommand.GoBack)
        }
    }

    fun reloadCurrentWebPage() {
        networkMonitor.refreshNow()
        _webError.value = null
        _isShowingOfflineCache.value = false
        val fallback = if (uiState.value.config.isCustomStoreBound) {
            uiState.value.config.baseUrl
        } else {
            DEFAULT_GENERIC_PORTAL_URL
        }
        val target = _currentUrl.value.ifBlank { fallback }
        if (target.startsWith("http", ignoreCase = true)) {
            _webViewCommands.tryEmit(WebViewCommand.LoadUrl(target))
        } else {
            _webViewCommands.tryEmit(WebViewCommand.Reload)
        }
    }

    fun reloadSecondaryWebPage(workspaceId: String, fallbackUrl: String) {
        networkMonitor.refreshNow()
        updateSecondaryState(workspaceId) { it.copy(webError = null, isShowingOfflineCache = false) }
        val target = _secondaryWebStates.value[workspaceId]?.currentUrl.orEmpty().ifBlank { fallbackUrl }
        val cmdFlow = getOrCreateSecondaryCommandFlow(workspaceId)
        if (target.startsWith("http", ignoreCase = true)) {
            cmdFlow.tryEmit(WebViewCommand.LoadUrl(target))
        } else {
            cmdFlow.tryEmit(WebViewCommand.Reload)
        }
    }

    fun navigateWebBack() {
        _webViewCommands.tryEmit(WebViewCommand.GoBack)
    }

    fun updateWebProgress(progress: Int) {
        _pageProgress.value = progress
    }

    fun updateWebNavigationState(url: String?, canGoBack: Boolean) {
        if (!url.isNullOrBlank() && !url.startsWith("data:", ignoreCase = true)) {
            _currentUrl.value = url
        }
        _canGoBack.value = canGoBack
    }

    fun setWebError(error: String?) {
        _webError.value = error
    }

    fun updateSecondaryProgress(workspaceId: String, progress: Int) {
        updateSecondaryState(workspaceId) { it.copy(pageProgress = progress) }
    }

    fun updateSecondaryNavigationState(workspaceId: String, url: String?, canGoBack: Boolean) {
        updateSecondaryState(workspaceId) { state ->
            state.copy(
                currentUrl = if (!url.isNullOrBlank() && !url.startsWith("data:", ignoreCase = true)) {
                    url
                } else {
                    state.currentUrl
                },
                canGoBack = canGoBack
            )
        }
    }

    fun setSecondaryWebError(workspaceId: String, error: String?) {
        updateSecondaryState(workspaceId) { it.copy(webError = error) }
    }

    fun setSecondaryShowingOfflineCache(workspaceId: String, showing: Boolean) {
        updateSecondaryState(workspaceId) { it.copy(isShowingOfflineCache = showing) }
    }

    fun openSecondaryCachedSnapshot(workspaceId: String, snapshot: CachedPageSnapshot) {
        updateSecondaryState(workspaceId) { it.copy(webError = null, isShowingOfflineCache = true) }
        getOrCreateSecondaryCommandFlow(workspaceId)
            .tryEmit(WebViewCommand.LoadHtmlSnapshot(snapshot.url, snapshot.html))
    }

    fun showSecondaryCustomOfflineScreen(workspaceId: String) {
        updateSecondaryState(workspaceId) {
            it.copy(
                isShowingOfflineCache = false,
                webError = "ইন্টারনেট সংযোগ বিচ্ছিন্ন রয়েছে (Offline)"
            )
        }
    }

    private fun updateSecondaryState(
        workspaceId: String,
        transform: (SecondaryWebState) -> SecondaryWebState
    ) {
        _secondaryWebStates.update { currentMap ->
            val prev = currentMap[workspaceId] ?: SecondaryWebState(workspaceId = workspaceId)
            currentMap + (workspaceId to transform(prev))
        }
    }

    fun toggleAutoRefresh(enabled: Boolean) {
        viewModelScope.launch {
            val current = repository.getConfigSnapshot()
            repository.saveConfig(current.copy(autoRefreshOnTaskComplete = enabled))
            _snackbarMessages.tryEmit(
                if (enabled) "অটো-রিফ্রেশ চালু হয়েছে" else "অটো-রিফ্রেশ বন্ধ করা হয়েছে"
            )
        }
    }

    fun toggleBackgroundSync(enabled: Boolean) {
        viewModelScope.launch {
            val current = repository.getConfigSnapshot()
            repository.saveConfig(current.copy(backgroundSyncEnabled = enabled))
            NotificationSyncWorker.schedulePeriodicSync(
                getApplication(),
                enabled && current.remoteControl.backgroundSyncEnabled
            )
            _snackbarMessages.tryEmit(
                if (enabled) "ব্যাকগ্রাউন্ড নোটিফিকেশন সিঙ্ক চালু হয়েছে" else "ব্যাকগ্রাউন্ড সিঙ্ক বন্ধ করা হয়েছে"
            )
        }
    }

    fun toggleGpsTracking(enabled: Boolean) {
        viewModelScope.launch {
            val updated = repository.getConfigSnapshot().copy(gpsTrackingEnabled = enabled)
            repository.saveConfig(updated)
            val ctx = getApplication<Application>()
            syncGpsWorkersAndService(updated)
            if (enabled) {
                if (!updated.remoteControl.gpsEnabled) {
                    _snackbarMessages.tryEmit("প্যানেল থেকে GPS ট্র্যাকিং বন্ধ রাখা হয়েছে")
                } else if (LocationPermissionHelper.hasForegroundLocationPermission(ctx)) {
                    _snackbarMessages.tryEmit("ব্যাকগ্রাউন্ড GPS চালু — অফলাইনেও সংগ্রহ হবে এবং প্যানেল রিকোয়েস্ট বা ১ ঘণ্টা পর পর পাঠাবে")
                } else {
                    _snackbarMessages.tryEmit("অনুগ্রহ করে প্রথমে GPS লোকেশন অনুমতি দিন")
                }
            } else {
                _snackbarMessages.tryEmit("GPS ট্র্যাকিং বন্ধ করা হয়েছে — ব্যবহার করতে GPS চালু রাখুন")
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            val currentCfg = repository.getConfigSnapshot()
            if (!currentCfg.isCustomStoreBound) {
                _snackbarMessages.tryEmit("প্রথমে একটি শপ কানেক্ট (বাইন্ড) করুন")
                return@launch
            }
            val online = networkMonitor.refreshNow()
            val ctx = getApplication<Application>()
            if (!online) {
                ImmediateSyncWorker.scheduleOnReconnect(ctx)
                _snackbarMessages.tryEmit("অফলাইন আছেন — অনলাইনে আসলেই স্বয়ংক্রিয়ভাবে সব সিঙ্ক হবে")
                return@launch
            }
            val cfgResult = remoteConfigSyncer.syncConfig()
            val latest = repository.getConfigSnapshot()
            syncGpsWorkersAndService(latest)
            locationUploader.flushPendingLocations()
            runPollCycle(
                showSnackbarOnError = true,
                configUpdated = cfgResult.updated || cfgResult.gpsRequestFulfilled
            )
        }
    }

    private suspend fun runPollCycle(
        showSnackbarOnError: Boolean,
        configUpdated: Boolean = false
    ) {
        if (_isSyncing.value) return
        _isSyncing.value = true
        val result = poller.pollOnce(showSystemNotifications = true)
        _isSyncing.value = false

        val config = repository.getConfigSnapshot()
        if (result.shouldAutoRefreshTasks) {
            tryAutoRefreshTasks(
                config = config,
                message = "টাস্ক আপডেট পাওয়া গেছে — অটো-রিফ্রেশ হয়েছে"
            )
        } else if (result.newNotificationsCount > 0) {
            _snackbarMessages.tryEmit("${result.newNotificationsCount} টি নতুন নোটিফিকেশন এসেছে")
        } else if (configUpdated) {
            _snackbarMessages.tryEmit("প্যানেল থেকে নতুন অ্যাপ কনফিগ সিঙ্ক হয়েছে")
        } else if (showSnackbarOnError) {
            _snackbarMessages.tryEmit(result.statusMessage)
        }
    }

    private fun tryAutoRefreshTasks(config: PortalConfig, message: String) {
        val rc = config.remoteControl
        if (!config.autoRefreshOnTaskComplete ||
            !rc.taskAutoRefreshEnabled ||
            rc.taskRefreshMode == TaskRefreshMode.OFF
        ) {
            return
        }

        val stateSnapshot = uiState.value
        val activeWorkspaceIsPrimary = stateSnapshot.activeWorkspaceTab.isPrimaryStore
        if (rc.refreshOnlyOnTabs.isNotEmpty()) {
            val activeTab = stateSnapshot.selectedBottomTabId.lowercase()
            if (!activeWorkspaceIsPrimary || activeTab !in rc.refreshOnlyOnTabs) {
                return
            }
        }

        val now = System.currentTimeMillis()
        val cooldownMs = rc.refreshCooldownSeconds.coerceIn(5, 3600) * 1000L
        if (now - lastAutoRefreshMillis < cooldownMs) {
            return
        }

        lastAutoRefreshMillis = now
        _webViewCommands.tryEmit(WebViewCommand.Reload)
        _snackbarMessages.tryEmit(message)
    }

    fun openNotification(notification: StoreNotification) {
        viewModelScope.launch {
            repository.markRead(notification.id)
            val target = notification.targetUrl.ifBlank { uiState.value.config.baseUrl }
            val primary = uiState.value.primaryStoreTab
            _selectedWorkspaceId.value = primary.id
            val nextTabId = when (notification.type) {
                NotificationEventType.ATTENDANCE_SUBMITTED -> AppTab.ATTENDANCE.route
                NotificationEventType.TASK_ASSIGNED,
                NotificationEventType.TASK_COMPLETED -> AppTab.TASKS.route
                NotificationEventType.GPS_REQUEST,
                NotificationEventType.GENERAL -> AppTab.PORTAL.route
            }
            val visibleIds = uiState.value.visibleBottomTabs
            val resolvedId = visibleIds.firstOrNull { it.id == nextTabId }?.id
                ?: visibleIds.firstOrNull { it.type == "web" }?.id
                ?: AppTab.PORTAL.route
            _selectedBottomTabId.value = resolvedId
            lastWebBottomTabId = resolvedId
            _webError.value = null
            _isShowingOfflineCache.value = false
            _webViewCommands.tryEmit(WebViewCommand.LoadUrl(target))
        }
    }

    fun markAllNotificationsRead() {
        viewModelScope.launch {
            repository.markAllRead()
            _snackbarMessages.tryEmit("সব নোটিফিকেশন পঠিত হিসেবে চিহ্নিত হয়েছে")
        }
    }

    fun clearAllNotifications() {
        viewModelScope.launch {
            repository.clearNotifications()
            _snackbarMessages.tryEmit("নোটিফিকেশন তালিকা পরিষ্কার করা হয়েছে")
        }
    }

    fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return
        val targetUrl = intent.getStringExtra(NotificationHelper.EXTRA_TARGET_URL)
        val notifId = intent.getStringExtra(NotificationHelper.EXTRA_NOTIFICATION_ID)
        val eventType = intent.getStringExtra(NotificationHelper.EXTRA_EVENT_TYPE)

        if (!notifId.isNullOrBlank()) {
            viewModelScope.launch {
                repository.markRead(notifId)
            }
        }
        if (!targetUrl.isNullOrBlank()) {
            val type = NotificationEventType.fromKey(eventType)
            val primary = uiState.value.primaryStoreTab
            _selectedWorkspaceId.value = primary.id
            val desiredId = when (type) {
                NotificationEventType.ATTENDANCE_SUBMITTED -> AppTab.ATTENDANCE.route
                NotificationEventType.TASK_ASSIGNED,
                NotificationEventType.TASK_COMPLETED -> AppTab.TASKS.route
                NotificationEventType.GPS_REQUEST,
                NotificationEventType.GENERAL -> AppTab.PORTAL.route
            }
            val visible = uiState.value.visibleBottomTabs
            val resolvedId = visible.firstOrNull { it.id == desiredId }?.id
                ?: visible.firstOrNull { it.type == "web" }?.id
                ?: AppTab.PORTAL.route
            _selectedBottomTabId.value = resolvedId
            lastWebBottomTabId = resolvedId
            _webViewCommands.tryEmit(WebViewCommand.LoadUrl(targetUrl))
        }
    }

    private fun shouldShowForCurrentRole(
        notif: StoreNotification,
        config: PortalConfig
    ): Boolean {
        if (config.panelId.isNotBlank() &&
            notif.panelId.isNotBlank() &&
            !notif.panelId.equals(config.panelId, ignoreCase = true)
        ) {
            return false
        }
        val roleMatches = notif.targetRole == "all" ||
            notif.targetRole.equals(config.userRole.key, ignoreCase = true)
        if (!roleMatches) return false
        return notif.targetUserId.isBlank() ||
            config.userId.isBlank() ||
            notif.targetUserId.equals(config.userId, ignoreCase = true)
    }

    fun clearShopBindingError() {
        _shopBindingState.update { it.copy(errorMessage = null, statusMessage = null) }
    }

    fun bindShopFromQrPayload(rawQr: String, onReadyToReboot: () -> Unit) {
        if (_shopBindingState.value.isLoading) return
        val extractedUrl = ShopHandshakeManager.extractStoreUrlFromQr(rawQr)
        if (extractedUrl.isNullOrBlank()) {
            _shopBindingState.value = ShopBindingUiState(
                isLoading = false,
                errorMessage = "এই QR কোডে সঠিক শপের URL পাওয়া যায়নি। অ্যাডমিন প্যানেলের স্টোর QR কোড স্ক্যান করুন।"
            )
            return
        }
        bindShopUrl(extractedUrl, onReadyToReboot)
    }

    fun bindShopUrl(rawUrl: String, onReadyToReboot: () -> Unit) {
        if (_shopBindingState.value.isLoading) return
        viewModelScope.launch {
            _shopBindingState.value = ShopBindingUiState(
                isLoading = true,
                errorMessage = null,
                statusMessage = "শপের সাথে সংযোগ ও ডেটা সিঙ্ক করা হচ্ছে..."
            )
            when (val result = shopHandshakeManager.performHandshake(rawUrl)) {
                is ShopHandshakeResult.Failure -> {
                    _shopBindingState.value = ShopBindingUiState(
                        isLoading = false,
                        errorMessage = result.errorMessageBn
                    )
                }
                is ShopHandshakeResult.Success -> {
                    _shopBindingState.value = ShopBindingUiState(
                        isLoading = true,
                        statusMessage = "বাইন্ডিং সংরক্ষণ ও অ্যাপ রিবুট করা হচ্ছে..."
                    )
                    // Wipe old tenant's WebView cookies & cached offline pages before saving new store
                    runCatching {
                        val cm = CookieManager.getInstance()
                        cm.removeAllCookies(null)
                        cm.flush()
                    }
                    offlinePageCache.clearAll()
                    _cachedPages.value = emptyList()

                    repository.applyShopBindingAndResetSession(
                        boundStoreUrl = result.boundStoreUrl,
                        storeCode = result.storeCode,
                        storeName = result.storeName,
                        appTitle = result.appTitle,
                        logoUrl = result.logoUrl,
                        themeColorHex = result.themeColorHex,
                        panelId = result.panelId,
                        baseUrl = result.baseUrl,
                        attendanceUrl = result.attendanceUrl,
                        tasksUrl = result.tasksUrl,
                        notificationApiUrl = result.notificationApiUrl,
                        locationApiUrl = result.locationApiUrl,
                        syncGpsApiUrl = result.syncGpsApiUrl,
                        configApiUrl = result.configApiUrl,
                        remoteControl = result.remoteControl
                    )
                    delay(200)
                    _shopBindingState.value = ShopBindingUiState(isLoading = false)
                    onReadyToReboot()
                }
            }
        }
    }

    fun resetShopBinding(onReadyToReboot: () -> Unit) {
        if (_shopBindingState.value.isLoading) return
        viewModelScope.launch {
            _shopBindingState.value = ShopBindingUiState(
                isLoading = true,
                statusMessage = "শপ আনলিংক ও অ্যাপ রিবুট হচ্ছে..."
            )
            val ctx = getApplication<Application>()
            LocationPermissionHelper.stopTrackingService(ctx)
            NotificationSyncWorker.schedulePeriodicSync(ctx, enabled = false)
            LocationSyncWorker.schedulePeriodicLocationSync(ctx, enabled = false)
            runCatching {
                val cm = CookieManager.getInstance()
                cm.removeAllCookies(null)
                cm.flush()
            }
            offlinePageCache.clearAll()
            _cachedPages.value = emptyList()
            repository.resetToDefaultStore()
            delay(200)
            _shopBindingState.value = ShopBindingUiState(isLoading = false)
            onReadyToReboot()
        }
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(providerChangedReceiver) }
        networkMonitor.unregister()
        super.onCleared()
    }
}
