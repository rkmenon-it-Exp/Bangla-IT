package com.example.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.StorePreferencesRepository
import com.example.location.LocationPermissionHelper
import com.example.location.LocationSyncWorker
import com.example.model.AlertFilter
import com.example.model.AppTab
import com.example.model.LocationSnapshot
import com.example.model.NotificationEventType
import com.example.model.PortalConfig
import com.example.model.StoreNotification
import com.example.notifications.NotificationHelper
import com.example.sync.NotificationPoller
import com.example.sync.NotificationSyncWorker
import com.example.webview.BanglaStoreBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface WebViewCommand {
    data class LoadUrl(val url: String) : WebViewCommand
    data object Reload : WebViewCommand
    data object GoBack : WebViewCommand
}

data class BanglaStoreUiState(
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

    private val _selectedTab = MutableStateFlow(AppTab.PORTAL)
    private var lastWebTab: AppTab = AppTab.PORTAL
    private val _alertFilter = MutableStateFlow(AlertFilter.ALL)
    private val _pageProgress = MutableStateFlow(100)
    private val _currentUrl = MutableStateFlow("")
    private val _canGoBack = MutableStateFlow(false)
    private val _webError = MutableStateFlow<String?>(null)
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
    @Volatile
    var isWebInnerScrolled: Boolean = false
        private set

    private val _webViewCommands = MutableSharedFlow<WebViewCommand>(extraBufferCapacity = 16)
    val webViewCommands: SharedFlow<WebViewCommand> = _webViewCommands.asSharedFlow()

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

    val uiState: StateFlow<BanglaStoreUiState> = combine(
        _selectedTab,
        repository.configFlow,
        repository.notificationsFlow,
        _alertFilter,
        repository.syncStatusFlow,
        repository.locationFlow,
        _pageProgress,
        _currentUrl,
        _canGoBack,
        _webError,
        _isSyncing,
        _hasNotificationPermission,
        _hasForegroundLocation,
        _hasBackgroundLocation,
        _showBackgroundLocationDialog
    ) { values ->
        val tab = values[0] as AppTab
        val config = values[1] as PortalConfig
        @Suppress("UNCHECKED_CAST")
        val allNotifications = values[2] as List<StoreNotification>
        val filter = values[3] as AlertFilter
        @Suppress("UNCHECKED_CAST")
        val syncPair = values[4] as Pair<Long, String>
        val location = values[5] as LocationSnapshot
        val progress = values[6] as Int
        val currentUrl = values[7] as String
        val canGoBack = values[8] as Boolean
        val webError = values[9] as String?
        val isSyncing = values[10] as Boolean
        val hasNotifPerm = values[11] as Boolean
        val hasFgLoc = values[12] as Boolean
        val hasBgLoc = values[13] as Boolean
        val showBgDialog = values[14] as Boolean

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
                repository.markLoggedInUserId(userId)
            }
        },
        onLogoutDetectedCallback = {
            viewModelScope.launch {
                repository.clearLoginSession()
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
        }
        startForegroundSyncLoop()
    }

    private fun startForegroundSyncLoop() {
        viewModelScope.launch {
            while (isActive) {
                val config = repository.getConfigSnapshot()
                if (config.backgroundSyncEnabled) {
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
        viewModelScope.launch {
            repository.clearLoginSession()
        }
    }

    fun selectTab(tab: AppTab) {
        _selectedTab.value = tab
        val config = uiState.value.config
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

    fun reloadCurrentWebPage() {
        _webError.value = null
        _webViewCommands.tryEmit(WebViewCommand.Reload)
    }

    fun navigateWebBack() {
        _webViewCommands.tryEmit(WebViewCommand.GoBack)
    }

    fun updateWebProgress(progress: Int) {
        _pageProgress.value = progress
    }

    fun updateWebNavigationState(url: String?, canGoBack: Boolean) {
        if (!url.isNullOrBlank()) {
            _currentUrl.value = url
        }
        _canGoBack.value = canGoBack
    }

    fun setWebError(error: String?) {
        _webError.value = error
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
            val ctx = getApplication<Application>()
            if (uiState.value.config.gpsTrackingEnabled &&
                LocationPermissionHelper.hasForegroundLocationPermission(ctx)
            ) {
                LocationPermissionHelper.startTrackingService(ctx)
            }
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
}
