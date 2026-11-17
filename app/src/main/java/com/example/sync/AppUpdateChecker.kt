package com.example.sync

import android.content.Context
import com.example.notifications.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val hasUpdate: Boolean,
    val latestVersion: String = "",
    val downloadUrl: String = "",
    val releaseNotes: String = ""
)

object AppUpdateChecker {
    private const val GITHUB_REPO_OWNER = "rkmenon-it-Exp"
    private const val GITHUB_REPO_NAME = "Bangla-IT"
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/latest"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdate(context: Context, triggerNotification: Boolean = true): AppUpdateInfo = withContext(Dispatchers.IO) {
        try {
            val currentVersion = runCatching {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                pInfo.versionName ?: "1.0"
            }.getOrDefault("1.0")

            val request = Request.Builder()
                .url(LATEST_RELEASE_API)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "BanglaStore-AndroidApp")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext AppUpdateInfo(hasUpdate = false)
                }

                val bodyString = response.body?.string().orEmpty()
                if (bodyString.isBlank()) return@withContext AppUpdateInfo(hasUpdate = false)

                val json = JSONObject(bodyString)
                val tagName = json.optString("tag_name", "").trim().trimStart('v', 'V')
                val releaseBody = json.optString("body", "")

                var downloadUrl = json.optString("html_url", "")
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = asset.optString("browser_download_url", downloadUrl)
                            break
                        }
                    }
                }

                val isNewer = isVersionNewer(currentVersion, tagName)
                if (isNewer && triggerNotification && downloadUrl.isNotBlank()) {
                    NotificationHelper.showAppUpdateNotification(
                        context = context,
                        newVersion = tagName,
                        downloadUrl = downloadUrl,
                        releaseNotes = releaseBody
                    )
                }

                return@withContext AppUpdateInfo(
                    hasUpdate = isNewer,
                    latestVersion = tagName,
                    downloadUrl = downloadUrl,
                    releaseNotes = releaseBody
                )
            }
        } catch (e: Exception) {
            AppUpdateInfo(hasUpdate = false)
        }
    }

    private fun isVersionNewer(current: String, latest: String): Boolean {
        if (latest.isBlank()) return false
        val currParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }

        val length = maxOf(currParts.size, latestParts.size)
        for (i in 0 until length) {
            val c = currParts.getOrElse(i) { 0 }
            val l = latestParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}
