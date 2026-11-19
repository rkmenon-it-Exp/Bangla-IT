package com.example.sync

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.work.WorkManager
import com.example.data.StorePreferencesRepository
import com.example.location.LocationPermissionHelper
import com.example.location.LocationSyncWorker
import com.example.notifications.NotificationHelper
import com.example.webview.OfflinePageCache
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Detects remote device disconnect/revoke commands returned by the Admin Panel and performs
 * a complete local reset (DataStore wipe, WebView cookies/storage/cache clear, background worker
 * cancellation, and foreground app reboot trigger).
 */
object DeviceDisconnectManager {

    private val resetMutex = Mutex()
    private val _disconnectEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val disconnectEvents: SharedFlow<String> = _disconnectEvents.asSharedFlow()

    private val DISCONNECT_STATUS_VALUES = setOf(
        "disconnected",
        "revoked",
        "unlinked",
        "removed",
        "deleted",
        "reset",
        "wipe",
        "force_reset",
        "disconnect",
        "unlink",
        "device_disconnected",
        "device_revoked",
        "device_unlinked",
        "device_not_found",
        "unauthorized_device",
        "blocked"
    )

    fun isRemoteDisconnectSignal(httpCode: Int, rootObj: JSONObject?): Boolean {
        if (httpCode == 410) return true

        if (rootObj != null) {
            val candidates = buildList {
                add(rootObj)
                rootObj.optJSONObject("app_config")?.let(::add)
                rootObj.optJSONObject("config")?.let(::add)
                rootObj.optJSONObject("device")?.let(::add)
                rootObj.optJSONObject("data")?.let(::add)
            }
            for (obj in candidates) {
                if (hasDisconnectFlag(obj)) return true
            }
        }

        if (httpCode == 401 || httpCode == 403) {
            val rawText = rootObj?.toString().orEmpty().lowercase()
            if (rawText.contains("disconnect") ||
                rawText.contains("revoke") ||
                rawText.contains("unlink") ||
                rawText.contains("reset_app") ||
                rawText.contains("device_not_found") ||
                rawText.contains("wipe")
            ) {
                return true
            }
        }
        return false
    }

    private fun hasDisconnectFlag(obj: JSONObject): Boolean {
        val trueKeys = listOf(
            "disconnected",
            "is_disconnected",
            "device_disconnected",
            "revoked",
            "is_revoked",
            "device_revoked",
            "unlinked",
            "device_unlinked",
            "force_reset",
            "reset_app",
            "wipe_data",
            "clear_data"
        )
        for (key in trueKeys) {
            if (obj.has(key) && obj.optBoolean(key, false)) {
                return true
            }
        }

        val falseKeys = listOf(
            "device_connected",
            "is_bound",
            "device_bound",
            "device_active",
            "device_authorized"
        )
        for (key in falseKeys) {
            if (obj.has(key) && !obj.optBoolean(key, true)) {
                return true
            }
        }

        val stringKeys = listOf(
            "device_status",
            "connection_status",
            "status",
            "action",
            "command",
            "error_code",
            "code"
        )
        for (key in stringKeys) {
            val value = obj.optString(key, "").trim().lowercase()
            if (value in DISCONNECT_STATUS_VALUES) {
                return true
            }
        }
        return false
    }

    suspend fun executeFullResetAndWipe(
        context: Context,
        repository: StorePreferencesRepository,
        reasonMessage: String = "অ্যাডমিন প্যানেল থেকে এই ফোনটি ডিসকানেক্ট করা হয়েছে — অ্যাপ রিসেট করা হয়েছে।",
        showNotification: Boolean = true,
        emitRebootEvent: Boolean = true
    ) {
        resetMutex.withLock {
            val appCtx = context.applicationContext
            // 1. Stop foreground GPS service & cancel all scheduled background WorkManager jobs
            LocationPermissionHelper.stopTrackingService(appCtx)
            NotificationSyncWorker.schedulePeriodicSync(appCtx, enabled = false)
            LocationSyncWorker.schedulePeriodicLocationSync(appCtx, enabled = false)
            runCatching { WorkManager.getInstance(appCtx).cancelAllWork() }

            // 2. Wipe WebView cookies, WebStorage (localStorage/IndexedDB), offline HTML cache & app cacheDir
            runCatching {
                val cm = CookieManager.getInstance()
                cm.removeAllCookies(null)
                cm.flush()
            }
            runCatching {
                WebStorage.getInstance().deleteAllData()
            }
            runCatching {
                OfflinePageCache(appCtx).clearAll()
            }
            runCatching {
                appCtx.cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
            }

            // 3. Wipe ALL persisted DataStore keys so the app starts completely fresh
            repository.clearAllDataAndReset()

            // 4. Notify user (helpful if triggered from background worker) & emit reboot event for foreground UI
            if (showNotification) {
                NotificationHelper.showRemoteDisconnectNotification(appCtx, reasonMessage)
            }
            if (emitRebootEvent) {
                _disconnectEvents.tryEmit(reasonMessage)
            }
        }
    }
}
