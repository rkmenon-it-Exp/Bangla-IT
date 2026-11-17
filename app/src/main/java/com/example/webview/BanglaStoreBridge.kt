package com.example.webview

import android.webkit.JavascriptInterface
import com.example.model.LocationSnapshot
import com.example.model.NotificationEventType
import com.example.model.StoreNotification
import com.example.model.UserRole
import org.json.JSONObject

class BanglaStoreBridge(
    private val onSessionDetectedCallback: (UserRole?, String?, String?) -> Unit,
    private val onLoginSubmittedCallback: (String) -> Unit,
    private val onLogoutDetectedCallback: () -> Unit,
    private val onIncomingEventCallback: (StoreNotification, Boolean) -> Unit,
    private val onRefreshRequestedCallback: (String?) -> Unit,
    private val onInnerScrollChangedCallback: (Boolean) -> Unit,
    private val locationSnapshotProvider: () -> LocationSnapshot
) {

    @JavascriptInterface
    fun onSessionDetected(role: String?, userId: String?, userName: String?) {
        val parsedRole = if (role.isNullOrBlank()) null else UserRole.fromKey(role)
        onSessionDetectedCallback(parsedRole, userId, userName)
    }

    @JavascriptInterface
    fun onLoginSubmitted(userId: String?) {
        if (!userId.isNullOrBlank()) {
            onLoginSubmittedCallback(userId.trim())
        }
    }

    @JavascriptInterface
    fun onLogoutDetected() {
        onLogoutDetectedCallback()
    }

    @JavascriptInterface
    fun setInnerScrollActive(active: Boolean) {
        onInnerScrollChangedCallback(active)
    }

    @JavascriptInterface
    fun getCurrentLocationJson(): String {
        val snap = locationSnapshotProvider()
        return JSONObject().apply {
            put("has_fix", snap.hasFix)
            put("latitude", snap.latitude)
            put("longitude", snap.longitude)
            put("accuracy", snap.accuracyMeters.toDouble())
            put("timestamp", snap.timestampMillis)
            put("status", snap.syncStatus)
        }.toString()
    }

    @JavascriptInterface
    fun onAttendanceSubmitted(
        staffName: String?,
        timeText: String?,
        targetUrl: String?
    ) {
        val actor = staffName?.ifBlank { "একজন কর্মী" } ?: "একজন কর্মী"
        val timePart = if (!timeText.isNullOrBlank()) " ($timeText)" else ""
        val notification = StoreNotification(
            id = "att_${System.currentTimeMillis()}_${actor.hashCode()}",
            type = NotificationEventType.ATTENDANCE_SUBMITTED,
            title = "নতুন অ্যাটেন্ডেন্স জমা হয়েছে",
            message = "$actor সফলভাবে অ্যাটেন্ডেন্স জমা দিয়েছেন$timePart।",
            actorName = actor,
            targetRole = UserRole.ADMIN.key,
            targetUserId = "",
            targetUrl = targetUrl?.ifBlank { "" }.orEmpty(),
            timestampMillis = System.currentTimeMillis(),
            isRead = false
        )
        onIncomingEventCallback(notification, false)
    }

    @JavascriptInterface
    fun onTaskAssigned(
        assigneeUserId: String?,
        taskTitle: String?,
        assignedBy: String?,
        targetUrl: String?
    ) {
        val titleText = taskTitle?.ifBlank { "নতুন কাজ" } ?: "নতুন কাজ"
        val byText = if (!assignedBy.isNullOrBlank()) " — $assignedBy" else ""
        val notification = StoreNotification(
            id = "task_${System.currentTimeMillis()}_${titleText.hashCode()}",
            type = NotificationEventType.TASK_ASSIGNED,
            title = "আপনার জন্য নতুন টাস্ক যোগ হয়েছে",
            message = "$titleText$byText",
            actorName = assignedBy.orEmpty(),
            targetRole = UserRole.STAFF.key,
            targetUserId = assigneeUserId.orEmpty(),
            targetUrl = targetUrl?.ifBlank { "" }.orEmpty(),
            timestampMillis = System.currentTimeMillis(),
            isRead = false
        )
        onIncomingEventCallback(notification, true)
    }

    @JavascriptInterface
    fun onTaskCompleted(
        taskId: String?,
        taskTitle: String?,
        completedBy: String?
    ) {
        val actor = completedBy?.ifBlank { "কর্মী" } ?: "কর্মী"
        val titleText = taskTitle?.ifBlank { "একটি টাস্ক" } ?: "একটি টাস্ক"
        val notification = StoreNotification(
            id = "done_${taskId.orEmpty()}_${System.currentTimeMillis()}",
            type = NotificationEventType.TASK_COMPLETED,
            title = "টাস্ক কমপ্লিট হয়েছে",
            message = "$actor \"$titleText\" সম্পন্ন করেছেন। পেজ অটোমেটিক রিফ্রেশ হচ্ছে।",
            actorName = actor,
            targetRole = "all",
            targetUserId = "",
            targetUrl = "",
            timestampMillis = System.currentTimeMillis(),
            isRead = false
        )
        onIncomingEventCallback(notification, true)
    }

    @JavascriptInterface
    fun requestRefresh(targetUrl: String?) {
        onRefreshRequestedCallback(targetUrl)
    }

    companion object {
        const val BRIDGE_NAME = "BanglaStoreBridge"

        val AUTO_HOOK_JS = """
            (function() {
                if (window.__banglaStoreHooked) return;
                window.__banglaStoreHooked = true;
                try {
                    var roleMeta = document.querySelector('meta[name="banglastore-role"]');
                    var userMeta = document.querySelector('meta[name="banglastore-user-id"]');
                    var nameMeta = document.querySelector('meta[name="banglastore-user-name"]');
                    if (roleMeta || userMeta || nameMeta) {
                        window.BanglaStoreBridge.onSessionDetected(
                            roleMeta ? roleMeta.content : '',
                            userMeta ? userMeta.content : '',
                            nameMeta ? nameMeta.content : ''
                        );
                    }

                    // Detect whether touch starts inside an already-scrolled inner container
                    document.addEventListener('touchstart', function(ev) {
                        try {
                            var scrolled = (window.scrollY || document.documentElement.scrollTop || 0) > 0;
                            var el = ev.target;
                            while (el && el !== document && !scrolled) {
                                if (el.scrollTop && el.scrollTop > 0) {
                                    scrolled = true;
                                }
                                el = el.parentElement;
                            }
                            window.BanglaStoreBridge.setInnerScrollActive(scrolled);
                        } catch (e) {}
                    }, { passive: true });

                    // Capture staff user_id when submitting a login form (contains password input)
                    document.addEventListener('submit', function(ev) {
                        try {
                            var form = ev.target;
                            if (!form || !form.querySelector('input[type="password"]')) return;
                            var idInput = form.querySelector(
                                'input[name*="user"], input[name*="id"], input[name*="email"], input[name*="phone"], input[type="email"], input[type="text"]'
                            );
                            if (idInput && idInput.value) {
                                window.BanglaStoreBridge.onLoginSubmitted(idInput.value);
                            }
                        } catch (e) {}
                    }, true);

                    // Detect explicit user-initiated logout link/button clicks
                    document.addEventListener('click', function(ev) {
                        try {
                            var target = ev.target && ev.target.closest ? ev.target.closest('a, button') : null;
                            if (!target) return;
                            var href = (target.getAttribute('href') || '').toLowerCase();
                            var text = (target.innerText || '').toLowerCase();
                            if (href.indexOf('logout') !== -1 || text.indexOf('logout') !== -1 || text.indexOf('log out') !== -1) {
                                window.BanglaStoreBridge.onLogoutDetected();
                            }
                        } catch (e) {}
                    }, true);

                    window.addEventListener('message', function(e) {
                        var d = e.data;
                        if (!d || d.source !== 'bangla_store') return;
                        var p = d.payload || {};
                        if (d.event === 'attendance_submitted') {
                            window.BanglaStoreBridge.onAttendanceSubmitted(p.staffName || '', p.timeText || '', p.targetUrl || '');
                        } else if (d.event === 'task_assigned') {
                            window.BanglaStoreBridge.onTaskAssigned(p.assigneeUserId || '', p.taskTitle || '', p.assignedBy || '', p.targetUrl || '');
                        } else if (d.event === 'task_completed') {
                            window.BanglaStoreBridge.onTaskCompleted(p.taskId || '', p.taskTitle || '', p.completedBy || '');
                        } else if (d.event === 'refresh') {
                            window.BanglaStoreBridge.requestRefresh(p.targetUrl || '');
                        }
                    });
                } catch (err) {}
            })();
        """.trimIndent()
    }
}
