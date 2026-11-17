package com.example.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.StorePreferencesRepository
import com.example.location.LocationPermissionHelper
import com.example.location.LocationSyncWorker
import com.example.location.LocationUploader
import com.example.model.AlertFilter
import com.example.model.AppTab
import com.example.model.AppWorkspace
import com.example.model.DEFAULT_ISP_URL
import com.example.model.LocationSnapshot
import com.example.model.NotificationEventType
import com.example.model.PortalConfig
import com.example.model.StoreNotification
import com.example.notifications.NotificationHelper
import com.example.sync.ImmediateSyncWorker
import com.example.sync.NetworkMonitor
import com.example.sync.NotificationPoller
import com.example.sync.NotificationSyncWorker
import com.example.webview.BanglaStoreBridge
import com.example.webview.CachedPageSnapshot
import com.example.webview.OfflinePageCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface WebViewCommand {
    data class LoadUrl(val url: String) : WebViewCommand
    data class LoadHtmlSnapshot(val baseUrl: String, val html: String) : WebViewCommand
    data object Reload : WebViewCommand
    data object GoBack : WebViewCommand
}

data class IspWebState(
    val hasOpened: Boolean = false,
    val pageProgress: Int = 100,
    val currentUrl: String = DEFAULT_ISP_URL,
    val canGoBack: Boolean = false,
    val webError: String? = null,
    val isShowingOfflineCache: Boolean = false
)

data class BanglaStoreUiState(
    val selectedWorkspace: AppWorkspace = AppWorkspace.BANGLA_STORE,
    val selectedTab: AppTab = AppTab.PORTAL,
    val config: PortalConfig = PortalConfig(),
    val notifications: List<StoreNotification> = emptyList(),
    val filteredNotifications: List<StoreNotification> = emptyList(),
    val alertFilter: AlertFilter = AlertFilter.ALL,
    val unreadCount: Int = 0,
    val pageProgress: Int = 100,
    val currentUrl: String = "",
    val canGoBack: Boolean = false,
    val webError: String? = null,
    val ispState: IspWebState = IspWebState(),
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
    val showBackgroundLocationDialog: Boolean = false
)

class BanglaStoreViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = StorePreferencesRepository(application)
    private val poller = NotificationPoller(application, repository)
    private val locationUploader = LocationUploader(application, repository)
    private val networkMonitor = NetworkMonitor(application)
    val offlinePageCache = OfflinePageCache(application)

    private val _selectedWorkspace = MutableStateFlow(AppWorkspace.BANGLA_STORE)
    private val _selectedTab = MutableStateFlow(AppTab.PORTAL)
    private var lastWebTab: AppTab = AppTab.PORTAL
    private val _alertFilter = MutableStateFlow(AlertFilter.ALL)
    private val _pageProgress = MutableStateFlow(100)
    private val _currentUrl = MutableStateFlow("")
    private val _canGoBack = MutableStateFlow(false)
    private val _webError = MutableStateFlow<String?>(null)
    private val _isShowingOfflineCache = MutableStateFlow(false)
    private val _ispState = MutableStateFlow(IspWebState())
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
    private val _showBackgroundLocationDialog = MutableStateFlow(false)
    private var promptedBackgroundThisSession = false
    private var wasOnline = networkMonitor.isOnline.value
    private var sawLoginPage = false
    private var pendingPostLoginRedirect = false

    @Volatile
    var isWebInnerScrolled: Boolean = false
        private set

    @Volatile
    var isIspInnerScrolled: Boolean = false
        private set

    private val _webViewCommands = MutableSharedFlow<WebViewCommand>(extraBufferCapacity = 16)
    val webViewCommands: SharedFlow<WebViewCommand> = _webViewCommands.asSharedFlow()

    private val _ispWebViewCommands = MutableSharedFlow<WebViewCommand>(extraBufferCapacity = 16)
    val ispWebViewCommands: SharedFlow<WebViewCommand> = _ispWebViewCommands.asSharedFlow()

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

    val uiState: StateFlow<BanglaStoreUiState> = combine(
        _selectedWorkspace,
        _selectedTab,
        repository.configFlow,
        repository.notificationsFlow,
        _alertFilter,
        repository.syncStatusFlow,
        repository.locationFlow,
        repository.pendingSyncCountFlow,
        networkMonitor.isOnline,
        _pageProgress,
        _currentUrl,
        _canGoBack,
        _webError,
        _ispState,
        _isShowingOfflineCache,
        _cachedPages,
        _isSyncing,
        _hasNotificationPermission,
        _hasForegroundLocation,
        _hasBackgroundLocation,
        _showBackgroundLocationDialog
    ) { values ->
        val workspace = values[0] as AppWorkspace
        val tab = values[1] as AppTab
        val config = values[2] as PortalConfig
        @Suppress("UNCHECKED_CAST")
        val allNotifications = values[3] as List<StoreNotification>
        val filter = values[4] as AlertFilter
        @Suppress("UNCHECKED_CAST")
        val syncPair = values[5] as Pair<Long, String>
        val location = values[6] as LocationSnapshot
        val pendingCount = values[7] as Int
        val isOnline = values[8] as Boolean
        val progress = values[9] as Int
        val currentUrl = values[10] as String
        val canGoBack = values[11] as Boolean
        val webError = values[12] as String?
        val ispState = values[13] as IspWebState
        val showingOfflineCache = values[14] as Boolean
        @Suppress("UNCHECKED_CAST")
        val cachedList = values[15] as List<CachedPageSnapshot>
        val isSyncing = values[16] as Boolean
        val hasNotifPerm = values[17] as Boolean
        val hasFgLoc = values[18] as Boolean
        val hasBgLoc = values[19] as Boolean
        val showBgDialog = values[20] as Boolean

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

        BanglaStoreUiState(
            selectedWorkspace = workspace,
            selectedTab = tab,
            config = config,
            notifications = allNotifications,
            filteredNotifications = filtered,
            alertFilter = filter,
            unreadCount = allNotifications.count { !it.isRead },
            pageProgress = progress,
            currentUrl = currentUrl.ifBlank { config.baseUrl },
            canGoBack = canGoBack,
            webError = webError,
            ispState = ispState,
            isOnline = isOnline,
            pendingSyncCount = pendingCount,
            isShowingOfflineCache = showingOfflineCache,
            cachedPages = cachedList,
            isSyncing = isSyncing,
            lastSyncTimeMillis = syncPair.first,
            lastSyncStatus = syncPair.second,
            locationSnapshot = location,
            hasNotificationPermission = hasNotifPerm,
            hasForegroundLocationPermission = hasFgLoc,
            hasBackgroundLocationPermission = hasBgLoc,
            showBackgroundLocationDialog = showBgDialog
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = BanglaStoreUiState()
    )

    val jsBridge: BanglaStoreBridge = BanglaStoreBridge(
        onSessionDetectedCallback = { role, userId, userName ->
            viewModelScope.launch {
                repository.updateSessionFromWeb(role, userId, userName)
            }
        },
        onLoginSubmittedCallback = { userId ->
            viewModelScope.launch {
                pendingPostLoginRedirect = true
                if (userId.isNotBlank()) {
                    repository.markLoggedInUserId(userId)
                }
            }
        },
        onLogoutDetectedCallback = {
            viewModelScope.launch {
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
                val resolvedTargetUrl = rawNotif.targetUrl.ifBlank {
                    when (rawNotif.type) {
                        NotificationEventType.ATTENDANCE_SUBMITTED -> config.attendanceUrl
                        NotificationEventType.TASK_ASSIGNED,
                        NotificationEventType.TASK_COMPLETED -> config.tasksUrl
                        NotificationEventType.GENERAL -> config.baseUrl
                    }
                }
                val finalNotif = rawNotif.copy(targetUrl = resolvedTargetUrl)
                val isNew = repository.recordIncomingNotification(finalNotif, deduplicate = true)
                if (isNew && shouldShowForCurrentRole(finalNotif, config)) {
                    NotificationHelper.showNotification(getApplication(), finalNotif)
                    _snackbarMessages.tryEmit(finalNotif.title)
                }
                if (shouldAutoRefresh && config.autoRefreshOnTaskComplete) {
                    _webViewCommands.tryEmit(WebViewCommand.Reload)
                    _snackbarMessages.tryEmit("টাস্ক আপডেট হয়েছে — পেজ অটো-রিফ্রেশ করা হচ্ছে")
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
        onInnerScrollChangedCallback = { scrolled ->
            isWebInnerScrolled = scrolled
        },
        locationSnapshotProvider = { uiState.value.locationSnapshot }
    )

    val ispJsBridge: BanglaStoreBridge = BanglaStoreBridge(
        onSessionDetectedCallback = { _, _, _ -> },
        onLoginSubmittedCallback = { _ -> },
        onLogoutDetectedCallback = {},
        onPageReadyCallback = { _, _ -> },
        onIncomingEventCallback = { _, _ -> },
        onRefreshRequestedCallback = { targetUrl ->
            viewModelScope.launch {
                if (targetUrl.isNullOrBlank()) {
                    _ispWebViewCommands.tryEmit(WebViewCommand.Reload)
                } else {
                    _ispWebViewCommands.tryEmit(WebViewCommand.LoadUrl(targetUrl))
                }
            }
        },
        onInnerScrollChangedCallback = { scrolled ->
            isIspInnerScrolled = scrolled
        },
        locationSnapshotProvider = { uiState.value.locationSnapshot }
    )

    private suspend fun handleWebPageReady(url: String, hasLoginForm: Boolean) {
        if (_isShowingOfflineCache.value ||
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

            val config = repository.getConfigSnapshot()
            val targetTab = if (config.previousTab.isWebTab) config.previousTab else AppTab.PORTAL
            val tabDefaultUrl = defaultUrlForTab(targetTab, config)
            val savedPrevUrl = config.previousUrl.takeIf {
                it.startsWith("https://", ignoreCase = true) &&
                    !isLoginUrl(it) &&
                    !isLogoutUrl(it)
            }
            val targetUrl = savedPrevUrl ?: tabDefaultUrl

            val isPortalHomeAlreadyOpen = targetTab == AppTab.PORTAL &&
                urlsEffectivelyEqual(targetUrl, config.baseUrl) &&
                url.startsWith(config.baseUrl, ignoreCase = true)

            if (!isPortalHomeAlreadyOpen &&
                (targetTab != _selectedTab.value || !urlsEffectivelyEqual(url, targetUrl))
            ) {
                _selectedTab.value = targetTab
                lastWebTab = targetTab
                _webError.value = null
                _isShowingOfflineCache.value = false
                _webViewCommands.tryEmit(WebViewCommand.LoadUrl(targetUrl))
                _snackbarMessages.tryEmit("লগইন সম্পন্ন — পূর্ববর্তী ট্যাবে (${targetTab.labelBn}) যাওয়া হচ্ছে")
                return
            }
        }

        // Record current authenticated web tab & URL as the previous tab for future logins
        val activeWebTab = if (_selectedTab.value.isWebTab) _selectedTab.value else lastWebTab
        repository.savePreviousWebLocation(activeWebTab, url)
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

    init {
        NotificationHelper.ensureChannels(application)
        viewModelScope.launch {
            val config = repository.getConfigSnapshot()
            NotificationSyncWorker.schedulePeriodicSync(
                getApplication(),
                config.backgroundSyncEnabled
            )
            if (config.gpsTrackingEnabled &&
                LocationPermissionHelper.hasForegroundLocationPermission(getApplication())
            ) {
                LocationPermissionHelper.startTrackingService(getApplication())
                LocationSyncWorker.schedulePeriodicLocationSync(getApplication(), true)
            }
            // Arm reconnect worker if there are already queued items or currently offline
            if (!networkMonitor.isOnline.value || repository.getPendingLocations().isNotEmpty()) {
                ImmediateSyncWorker.scheduleOnReconnect(getApplication())
            }
            delay(3000)
            com.example.sync.AppUpdateChecker.checkForUpdate(getApplication(), triggerNotification = true)
        }
        observeNetworkRecovery()
        startForegroundSyncLoop()
    }

    private fun observeNetworkRecovery() {
        viewModelScope.launch {
            networkMonitor.isOnline
                .collect { online ->
                    if (!online) {
                        wasOnline = false
                        ImmediateSyncWorker.scheduleOnReconnect(getApplication())
                    } else if (!wasOnline) {
                        wasOnline = true
                        val flushed = locationUploader.flushPendingLocations()
                        val hadOfflineUi = _webError.value != null || _isShowingOfflineCache.value
                        val hadIspOfflineUi = _ispState.value.webError != null ||
                            _ispState.value.isShowingOfflineCache
                        _webError.value = null
                        _isShowingOfflineCache.value = false
                        _ispState.update { it.copy(webError = null, isShowingOfflineCache = false) }
                        if (hadOfflineUi) {
                            _webViewCommands.tryEmit(WebViewCommand.Reload)
                        }
                        if (hadIspOfflineUi && _ispState.value.hasOpened) {
                            _ispWebViewCommands.tryEmit(WebViewCommand.Reload)
                        }
                        runPollCycle(showSnackbarOnError = false)
                        _snackbarMessages.tryEmit(
                            if (flushed > 0) {
                                "অনলাইন: $flushed টি সংরক্ষিত ডেটা সিঙ্ক হয়েছে"
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
                if (config.backgroundSyncEnabled && networkMonitor.isOnline.value) {
                    locationUploader.flushPendingLocations()
                    runPollCycle(showSnackbarOnError = false)
                }
                delay(30_000L)
            }
        }
    }

    fun onPermissionsUpdated(allowBackgroundPrompt: Boolean = true) {
        val ctx = getApplication<Application>()
        val notifGranted = NotificationHelper.hasNotificationPermission(ctx)
        val fgLocGranted = LocationPermissionHelper.hasForegroundLocationPermission(ctx)
        val bgLocGranted = LocationPermissionHelper.hasBackgroundLocationPermission(ctx)

        _hasNotificationPermission.value = notifGranted
        _hasForegroundLocation.value = fgLocGranted
        _hasBackgroundLocation.value = bgLocGranted

        viewModelScope.launch {
            val config = repository.getConfigSnapshot()
            if (fgLocGranted && config.gpsTrackingEnabled) {
                LocationPermissionHelper.startTrackingService(ctx)
                LocationSyncWorker.schedulePeriodicLocationSync(ctx, true)
                if (allowBackgroundPrompt &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    !bgLocGranted &&
                    !promptedBackgroundThisSession
                ) {
                    promptedBackgroundThisSession = true
                    _showBackgroundLocationDialog.value = true
                }
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

    fun selectWorkspace(workspace: AppWorkspace) {
        _selectedWorkspace.value = workspace
        if (workspace == AppWorkspace.ISP) {
            _ispState.update { it.copy(hasOpened = true) }
        }
        if (_selectedTab.value == AppTab.ALERTS) {
            _selectedTab.value = lastWebTab
        }
    }

    fun exitAlertsScreen() {
        _selectedTab.value = lastWebTab
    }

    fun selectTab(tab: AppTab) {
        _selectedTab.value = tab
        if (tab.isWebTab) {
            _selectedWorkspace.value = AppWorkspace.BANGLA_STORE
        }
        val config = uiState.value.config
        if (tab.isWebTab && lastWebTab != tab) {
            viewModelScope.launch {
                repository.savePreviousWebLocation(tab, defaultUrlForTab(tab, config))
            }
        }
        when (tab) {
            AppTab.PORTAL -> {
                if (lastWebTab != AppTab.PORTAL || _currentUrl.value.isBlank()) {
                    _webViewCommands.tryEmit(WebViewCommand.LoadUrl(config.baseUrl))
                }
                lastWebTab = AppTab.PORTAL
            }
            AppTab.ATTENDANCE -> {
                if (lastWebTab != AppTab.ATTENDANCE ||
                    !_currentUrl.value.contains("staff_view=attendance", ignoreCase = true)
                ) {
                    _webViewCommands.tryEmit(WebViewCommand.LoadUrl(config.attendanceUrl))
                }
                lastWebTab = AppTab.ATTENDANCE
            }
            AppTab.TASKS -> {
                if (lastWebTab != AppTab.TASKS ||
                    !_currentUrl.value.contains("tasks.php", ignoreCase = true)
                ) {
                    _webViewCommands.tryEmit(WebViewCommand.LoadUrl(config.tasksUrl))
                }
                lastWebTab = AppTab.TASKS
            }
            AppTab.ALERTS -> Unit
        }
    }

    fun setAlertFilter(filter: AlertFilter) {
        _alertFilter.value = filter
    }

    fun reloadActiveWorkspace() {
        if (_selectedWorkspace.value == AppWorkspace.ISP) {
            reloadIspWebPage()
        } else {
            reloadCurrentWebPage()
            syncNow()
        }
    }

    fun navigateActiveWebBack() {
        if (_selectedWorkspace.value == AppWorkspace.ISP) {
            _ispWebViewCommands.tryEmit(WebViewCommand.GoBack)
        } else {
            navigateWebBack()
        }
    }

    fun reloadCurrentWebPage() {
        networkMonitor.refreshNow()
        _webError.value = null
        _isShowingOfflineCache.value = false
        val target = _currentUrl.value.ifBlank { uiState.value.config.baseUrl }
        if (target.startsWith("http", ignoreCase = true)) {
            _webViewCommands.tryEmit(WebViewCommand.LoadUrl(target))
        } else {
            _webViewCommands.tryEmit(WebViewCommand.Reload)
        }
    }

    fun reloadIspWebPage() {
        networkMonitor.refreshNow()
        _ispState.update { it.copy(webError = null, isShowingOfflineCache = false) }
        val target = _ispState.value.currentUrl.ifBlank { DEFAULT_ISP_URL }
        if (target.startsWith("http", ignoreCase = true)) {
            _ispWebViewCommands.tryEmit(WebViewCommand.LoadUrl(target))
        } else {
            _ispWebViewCommands.tryEmit(WebViewCommand.Reload)
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

    fun updateIspProgress(progress: Int) {
        _ispState.update { it.copy(pageProgress = progress) }
    }

    fun updateIspNavigationState(url: String?, canGoBack: Boolean) {
        _ispState.update { state ->
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

    fun setIspWebError(error: String?) {
        _ispState.update { it.copy(webError = error) }
    }

    fun setIspShowingOfflineCache(showing: Boolean) {
        _ispState.update { it.copy(isShowingOfflineCache = showing) }
    }

    fun openIspCachedSnapshot(snapshot: CachedPageSnapshot) {
        _ispState.update { it.copy(webError = null, isShowingOfflineCache = true) }
        _ispWebViewCommands.tryEmit(WebViewCommand.LoadHtmlSnapshot(snapshot.url, snapshot.html))
    }

    fun showIspCustomOfflineScreen() {
        _ispState.update {
            it.copy(
                isShowingOfflineCache = false,
                webError = "ইন্টারনেট সংযোগ বিচ্ছিন্ন রয়েছে (Offline)"
            )
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
            NotificationSyncWorker.schedulePeriodicSync(getApplication(), enabled)
            _snackbarMessages.tryEmit(
                if (enabled) "ব্যাকগ্রাউন্ড নোটিফিকেশন সিঙ্ক চালু হয়েছে" else "ব্যাকগ্রাউন্ড সিঙ্ক বন্ধ করা হয়েছে"
            )
        }
    }

    fun toggleGpsTracking(enabled: Boolean) {
        viewModelScope.launch {
            val current = repository.getConfigSnapshot()
            repository.saveConfig(current.copy(gpsTrackingEnabled = enabled))
            val ctx = getApplication<Application>()
            if (enabled) {
                if (LocationPermissionHelper.hasForegroundLocationPermission(ctx)) {
                    LocationPermissionHelper.startTrackingService(ctx)
                    LocationSyncWorker.schedulePeriodicLocationSync(ctx, true)
                    _snackbarMessages.tryEmit("সর্বক্ষণিক GPS ট্র্যাকিং চালু হয়েছে")
                } else {
                    _snackbarMessages.tryEmit("অনুগ্রহ করে প্রথমে GPS লোকেশন অনুমতি দিন")
                }
            } else {
                LocationPermissionHelper.stopTrackingService(ctx)
                LocationSyncWorker.schedulePeriodicLocationSync(ctx, false)
                _snackbarMessages.tryEmit("GPS ট্র্যাকিং সাময়িকভাবে বন্ধ করা হয়েছে")
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            val online = networkMonitor.refreshNow()
            val ctx = getApplication<Application>()
            if (!online) {
                ImmediateSyncWorker.scheduleOnReconnect(ctx)
                _snackbarMessages.tryEmit("অফলাইন আছেন — অনলাইনে আসলেই স্বয়ংক্রিয়ভাবে সব সিঙ্ক হবে")
                return@launch
            }
            if (uiState.value.config.gpsTrackingEnabled &&
                LocationPermissionHelper.hasForegroundLocationPermission(ctx)
            ) {
                LocationPermissionHelper.startTrackingService(ctx)
            }
            locationUploader.flushPendingLocations()
            runPollCycle(showSnackbarOnError = true)
        }
    }

    private suspend fun runPollCycle(showSnackbarOnError: Boolean) {
        if (_isSyncing.value) return
        _isSyncing.value = true
        val result = poller.pollOnce(showSystemNotifications = true)
        _isSyncing.value = false

        if (result.shouldAutoRefreshTasks) {
            _webViewCommands.tryEmit(WebViewCommand.Reload)
            _snackbarMessages.tryEmit("টাস্ক আপডেট পাওয়া গেছে — অটো-রিফ্রেশ হয়েছে")
        } else if (result.newNotificationsCount > 0) {
            _snackbarMessages.tryEmit("${result.newNotificationsCount} টি নতুন নোটিফিকেশন এসেছে")
        } else if (showSnackbarOnError) {
            _snackbarMessages.tryEmit(result.statusMessage)
        }
    }

    fun openNotification(notification: StoreNotification) {
        viewModelScope.launch {
            repository.markRead(notification.id)
            val target = notification.targetUrl.ifBlank { uiState.value.config.baseUrl }
            _selectedWorkspace.value = AppWorkspace.BANGLA_STORE
            _selectedTab.value = when (notification.type) {
                NotificationEventType.ATTENDANCE_SUBMITTED -> {
                    lastWebTab = AppTab.ATTENDANCE
                    AppTab.ATTENDANCE
                }
                NotificationEventType.TASK_ASSIGNED,
                NotificationEventType.TASK_COMPLETED -> {
                    lastWebTab = AppTab.TASKS
                    AppTab.TASKS
                }
                NotificationEventType.GENERAL -> {
                    lastWebTab = AppTab.PORTAL
                    AppTab.PORTAL
                }
            }
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
            _selectedWorkspace.value = AppWorkspace.BANGLA_STORE
            _selectedTab.value = when (type) {
                NotificationEventType.ATTENDANCE_SUBMITTED -> {
                    lastWebTab = AppTab.ATTENDANCE
                    AppTab.ATTENDANCE
                }
                NotificationEventType.TASK_ASSIGNED,
                NotificationEventType.TASK_COMPLETED -> {
                    lastWebTab = AppTab.TASKS
                    AppTab.TASKS
                }
                NotificationEventType.GENERAL -> {
                    lastWebTab = AppTab.PORTAL
                    AppTab.PORTAL
                }
            }
            _webViewCommands.tryEmit(WebViewCommand.LoadUrl(targetUrl))
        }
    }

    private fun shouldShowForCurrentRole(
        notif: StoreNotification,
        config: PortalConfig
    ): Boolean {
        val roleMatches = notif.targetRole == "all" ||
            notif.targetRole.equals(config.userRole.key, ignoreCase = true)
        if (!roleMatches) return false
        return notif.targetUserId.isBlank() ||
            config.userId.isBlank() ||
            notif.targetUserId.equals(config.userId, ignoreCase = true)
    }

    override fun onCleared() {
        networkMonitor.unregister()
        super.onCleared()
    }
}
