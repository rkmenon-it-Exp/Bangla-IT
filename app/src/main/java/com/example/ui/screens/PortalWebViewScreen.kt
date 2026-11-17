package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.R
import com.example.location.LocationPermissionHelper
import com.example.ui.WebViewCommand
import com.example.webview.BanglaStoreBridge
import com.example.webview.CachedPageSnapshot
import com.example.webview.OfflinePageCache
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONTokener

@OptIn(ExperimentalLayoutApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PortalWebViewScreen(
    initialUrl: String,
    isVisible: Boolean,
    isOnline: Boolean,
    pendingSyncCount: Int,
    pageProgress: Int,
    canGoBack: Boolean,
    webError: String?,
    isShowingOfflineCache: Boolean,
    cachedPages: List<CachedPageSnapshot>,
    offlinePageCache: OfflinePageCache,
    jsBridge: BanglaStoreBridge,
    commands: SharedFlow<WebViewCommand>,
    onProgressChanged: (Int) -> Unit,
    onNavigationChanged: (String?, Boolean) -> Unit,
    onErrorChanged: (String?) -> Unit,
    onSessionCookieCaptured: (String?) -> Unit,
    onLogoutUrlVisited: () -> Unit,
    onSnapshotCaptured: (String, String, String) -> Unit,
    onOfflineCacheModeChanged: (Boolean) -> Unit,
    onSelectCachedSnapshot: (CachedPageSnapshot) -> Unit,
    onShowCustomOfflineScreen: () -> Unit,
    onOpenAlerts: () -> Unit,
    onManualRefresh: () -> Unit,
    isInnerScrolledProvider: () -> Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var lastFailedFallbackUrl by remember { mutableStateOf<String?>(null) }

    val currentIsOnline by rememberUpdatedState(isOnline)
    val currentIsShowingOfflineCache by rememberUpdatedState(isShowingOfflineCache)
    val currentOnManualRefresh by rememberUpdatedState(onManualRefresh)
    val currentOnErrorChanged by rememberUpdatedState(onErrorChanged)
    val currentIsInnerScrolled by rememberUpdatedState(isInnerScrolledProvider)
    val currentOnSnapshotCaptured by rememberUpdatedState(onSnapshotCaptured)
    val currentOnOfflineCacheModeChanged by rememberUpdatedState(onOfflineCacheModeChanged)

    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        pendingFileCallback?.onReceiveValue(uris.toTypedArray())
        pendingFileCallback = null
    }

    val swipeRefreshLayout = remember(context) {
        DeliberateSwipeRefreshLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setColorSchemeColors(0xFF0D6E5E.toInt(), 0xFFB45309.toInt())
        }
    }

    val webView = remember(context) {
        WebView(context).apply {
            val webViewInstance = this
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webViewInstance, true)
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = false
                displayZoomControls = false
                allowFileAccess = false
                allowContentAccess = true
                setGeolocationEnabled(true)
                cacheMode = if (currentIsOnline) {
                    WebSettings.LOAD_DEFAULT
                } else {
                    WebSettings.LOAD_CACHE_ELSE_NETWORK
                }
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            addJavascriptInterface(jsBridge, BanglaStoreBridge.BRIDGE_NAME)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    onProgressChanged(newProgress)
                }

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?
                ) {
                    val granted = LocationPermissionHelper.hasForegroundLocationPermission(context)
                    callback?.invoke(origin, granted, false)
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    pendingFileCallback?.onReceiveValue(null)
                    pendingFileCallback = filePathCallback
                    val mimeTypes = fileChooserParams?.acceptTypes
                        ?.filter { it.isNotBlank() }
                        ?.toTypedArray()
                        ?.takeIf { it.isNotEmpty() }
                        ?: arrayOf("*/*")
                    fileChooserLauncher.launch(mimeTypes)
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    currentOnErrorChanged(null)
                    onProgressChanged(5)
                    onNavigationChanged(url, view?.canGoBack() ?: false)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    onProgressChanged(100)
                    onNavigationChanged(url, view?.canGoBack() ?: false)
                    swipeRefreshLayout.isRefreshing = false
                    CookieManager.getInstance().flush()
                    val cookie = url?.let { CookieManager.getInstance().getCookie(it) }
                    onSessionCookieCaptured(cookie)
                    if (!url.isNullOrBlank() && url.contains("logout", ignoreCase = true)) {
                        onLogoutUrlVisited()
                    }
                    if (!currentIsShowingOfflineCache) {
                        view?.evaluateJavascript(BanglaStoreBridge.AUTO_HOOK_JS, null)
                    }

                    // Save basic HTML snapshot while online so essential store/task info remains viewable offline
                    if (currentIsOnline &&
                        !currentIsShowingOfflineCache &&
                        !url.isNullOrBlank() &&
                        url.startsWith("https://", ignoreCase = true)
                    ) {
                        val pageTitle = view?.title.orEmpty()
                        view?.evaluateJavascript(
                            "(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })();"
                        ) { rawResult ->
                            val decoded = runCatching {
                                JSONTokener(rawResult).nextValue() as? String
                            }.getOrNull()
                            if (!decoded.isNullOrBlank()) {
                                currentOnSnapshotCaptured(url, pageTitle, decoded)
                            }
                        }
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase().orEmpty()
                    if (scheme == "http" || scheme == "https") {
                        return false
                    }
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                    return true
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        swipeRefreshLayout.isRefreshing = false
                        onProgressChanged(100)
                        val failingUrl = request.url?.toString().orEmpty()

                        if (!currentIsOnline &&
                            failingUrl.isNotBlank() &&
                            lastFailedFallbackUrl != failingUrl
                        ) {
                            val cached = offlinePageCache.loadSnapshot(failingUrl)
                            if (cached != null) {
                                lastFailedFallbackUrl = failingUrl
                                currentOnOfflineCacheModeChanged(true)
                                currentOnErrorChanged(null)
                                view?.loadDataWithBaseURL(
                                    cached.url,
                                    cached.html,
                                    "text/html",
                                    "UTF-8",
                                    cached.url
                                )
                                return
                            }
                        }

                        currentOnOfflineCacheModeChanged(false)
                        val desc = error?.description?.toString()
                            ?: "ওয়েবসাইটের সাথে সংযোগ করা যাচ্ছে না"
                        currentOnErrorChanged(desc)
                    }
                }
            }
            loadUrl(initialUrl)
        }
    }

    LaunchedEffect(isOnline) {
        webView.settings.cacheMode = if (isOnline) {
            WebSettings.LOAD_DEFAULT
        } else {
            WebSettings.LOAD_CACHE_ELSE_NETWORK
        }
        if (isOnline) {
            lastFailedFallbackUrl = null
        }
    }

    LaunchedEffect(swipeRefreshLayout, webView) {
        if (webView.parent == null) {
            swipeRefreshLayout.addView(
                webView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            webView.scrollY > 0 || webView.canScrollVertically(-1) || currentIsInnerScrolled()
        }
        swipeRefreshLayout.setOnRefreshListener {
            if (swipeRefreshLayout.wasLongDeliberatePull()) {
                currentOnErrorChanged(null)
                currentOnManualRefresh()
            } else {
                swipeRefreshLayout.isRefreshing = false
            }
        }
    }

    LaunchedEffect(commands) {
        commands.collect { command ->
            when (command) {
                is WebViewCommand.LoadUrl -> {
                    currentOnErrorChanged(null)
                    currentOnOfflineCacheModeChanged(false)
                    lastFailedFallbackUrl = null
                    webView.settings.cacheMode = if (currentIsOnline) {
                        WebSettings.LOAD_DEFAULT
                    } else {
                        WebSettings.LOAD_CACHE_ELSE_NETWORK
                    }
                    webView.loadUrl(command.url)
                }
                is WebViewCommand.LoadHtmlSnapshot -> {
                    currentOnErrorChanged(null)
                    currentOnOfflineCacheModeChanged(true)
                    webView.loadDataWithBaseURL(
                        command.baseUrl,
                        command.html,
                        "text/html",
                        "UTF-8",
                        command.baseUrl
                    )
                }
                WebViewCommand.Reload -> {
                    currentOnErrorChanged(null)
                    currentOnOfflineCacheModeChanged(false)
                    lastFailedFallbackUrl = null
                    swipeRefreshLayout.isRefreshing = true
                    webView.settings.cacheMode = if (currentIsOnline) {
                        WebSettings.LOAD_DEFAULT
                    } else {
                        WebSettings.LOAD_CACHE_ELSE_NETWORK
                    }
                    webView.reload()
                }
                WebViewCommand.GoBack -> {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            pendingFileCallback?.onReceiveValue(null)
            swipeRefreshLayout.removeAllViews()
            webView.stopLoading()
            webView.destroy()
        }
    }

    BackHandler(enabled = isVisible && canGoBack) {
        if (webView.canGoBack()) {
            webView.goBack()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(visible = !isOnline || isShowingOfflineCache) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    tonalElevation = 2.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("offline_cached_banner")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudOff,
                                contentDescription = "Offline status",
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = buildString {
                                    append(
                                        if (isShowingOfflineCache) {
                                            "অফলাইন ক্যাশ কনটেন্ট"
                                        } else {
                                            "অফলাইন মোড"
                                        }
                                    )
                                    if (pendingSyncCount > 0) {
                                        append(" • $pendingSyncCount টি সিঙ্ক অপেক্ষমাণ")
                                    }
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isShowingOfflineCache) {
                            TextButton(
                                onClick = onShowCustomOfflineScreen,
                                modifier = Modifier.testTag("view_offline_screen_button")
                            ) {
                                Text("অফলাইন স্ক্রিন")
                            }
                        } else {
                            TextButton(
                                onClick = onManualRefresh,
                                modifier = Modifier.testTag("banner_retry_button")
                            ) {
                                Text("রিট্রাই")
                            }
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { swipeRefreshLayout },
                    update = { layout ->
                        if (pageProgress >= 100 && layout.isRefreshing) {
                            layout.isRefreshing = false
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("portal_webview_swipe_refresh")
                )

                androidx.compose.animation.AnimatedVisibility(
                    visible = pageProgress in 1..99 && webError == null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    LinearProgressIndicator(
                        progress = { pageProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.tertiary,
                        trackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = pageProgress in 1..99 && webError == null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        tonalElevation = 6.dp,
                        shadowElevation = 6.dp,
                        modifier = Modifier.testTag("webview_loading_indicator")
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(40.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.primaryContainer,
                                strokeWidth = 4.dp
                            )
                            Text(
                                text = "লোড হচ্ছে ($pageProgress%)",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                if (webError != null) {
                    CustomOfflineScreen(
                        isOnline = isOnline,
                        pendingSyncCount = pendingSyncCount,
                        errorDetail = webError,
                        cachedPages = cachedPages,
                        onRetry = {
                            currentOnErrorChanged(null)
                            onManualRefresh()
                        },
                        onSelectCachedPage = onSelectCachedSnapshot,
                        onOpenAlerts = onOpenAlerts
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomOfflineScreen(
    isOnline: Boolean,
    pendingSyncCount: Int,
    errorDetail: String,
    cachedPages: List<CachedPageSnapshot>,
    onRetry: () -> Unit,
    onSelectCachedPage: (CachedPageSnapshot) -> Unit,
    onOpenAlerts: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxSize()
            .testTag("custom_offline_screen")
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_store_banner),
                        contentDescription = "Bangla Store offline illustration",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(145.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Icon(
                        imageVector = Icons.Rounded.WifiOff,
                        contentDescription = "Offline indicator",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(34.dp)
                    )
                    Text(
                        text = if (isOnline) "পোর্টাল লোড করা যায়নি" else "আপনি অফলাইনে আছেন (Offline)",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = if (isOnline) {
                            "সার্ভারের সাথে সংযোগ স্থাপন করা যায়নি ($errorDetail)।"
                        } else {
                            "ইন্টারনেট সংযোগ নেই। সংযোগ ফিরে আসা মাত্রই অ্যাপ বন্ধ থাকলেও আপনার সব লোকেশন ও তথ্য স্বয়ংক্রিয়ভাবে সিঙ্ক হয়ে যাবে।"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    if (pendingSyncCount > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Sync,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "$pendingSyncCount টি GPS ডেটা অফলাইনে জমা আছে — অনলাইনে গেলেই সাথে সাথে পাঠানো হবে",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }

                    if (cachedPages.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "সংরক্ষিত ক্যাশ পেজ (Cached Offline Content):",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                cachedPages.take(4).forEach { cached ->
                                    val label = when {
                                        cached.url.contains("attendance", ignoreCase = true) -> "অ্যাটেন্ডেন্স (ক্যাশ)"
                                        cached.url.contains("tasks.php", ignoreCase = true) -> "টাস্ক (ক্যাশ)"
                                        else -> cached.title.take(24).ifBlank { "হোম পোর্টাল (ক্যাশ)" }
                                    }
                                    AssistChip(
                                        onClick = { onSelectCachedPage(cached) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Rounded.History,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        label = { Text(label) }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = onRetry,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("webview_retry_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("পুনরায় চেষ্টা করুন (Retry)")
                    }
                    OutlinedButton(
                        onClick = onOpenAlerts,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("offline_open_alerts_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Notifications,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("অফলাইন অ্যালার্ট ইনবক্স দেখুন")
                    }
                }
            }
        }
    }
}

private class DeliberateSwipeRefreshLayout(context: Context) : SwipeRefreshLayout(context) {
    private val density = context.resources.displayMetrics.density
    private val interceptSlopPx = 44f * density
    private val minInterceptHoldMs = 160L
    private val minRefreshHoldMs = 550L

    private var downX = 0f
    private var downY = 0f
    private var downTimeMs = 0L
    private var lastTriggerPullDurationMs = 0L

    init {
        setDistanceToTriggerSync((200f * density).toInt())
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downTimeMs = SystemClock.uptimeMillis()
                return super.onInterceptTouchEvent(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - downY
                val dx = kotlin.math.abs(ev.x - downX)
                val elapsed = SystemClock.uptimeMillis() - downTimeMs
                if (dy < interceptSlopPx || dy <= dx * 1.2f || elapsed < minInterceptHoldMs) {
                    return false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastTriggerPullDurationMs = SystemClock.uptimeMillis() - downTimeMs
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            lastTriggerPullDurationMs = SystemClock.uptimeMillis() - downTimeMs
        }
        return super.onTouchEvent(ev)
    }

    fun wasLongDeliberatePull(): Boolean {
        val currentElapsed = SystemClock.uptimeMillis() - downTimeMs
        return maxOf(lastTriggerPullDurationMs, currentElapsed) >= minRefreshHoldMs
    }
}
