package com.example.webview

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class CachedPageSnapshot(
    val url: String,
    val title: String,
    val html: String,
    val savedAtMillis: Long
)

class OfflinePageCache(context: Context) {

    private val cacheDir = File(context.cacheDir, "offline_pages").apply {
        if (!exists()) mkdirs()
    }

    fun saveSnapshot(url: String, title: String, rawHtml: String) {
        if (url.isBlank() || rawHtml.isBlank() || url.startsWith("data:", ignoreCase = true)) {
            return
        }
        val cleanHtml = sanitizeForOfflineRender(rawHtml)
        if (cleanHtml.length < 40) return

        runCatching {
            val file = fileForUrl(url)
            val json = JSONObject().apply {
                put("url", url)
                put("title", title.ifBlank { url })
                put("html", cleanHtml)
                put("saved_at", System.currentTimeMillis())
            }
            file.writeText(json.toString(), Charsets.UTF_8)
            pruneOldFiles()
        }
    }

    fun loadSnapshot(url: String): CachedPageSnapshot? {
        if (url.isBlank()) return null
        val exact = readSnapshotFile(fileForUrl(url))
        if (exact != null) return exact

        // Fallback to normalized URL without trailing slash
        val normalized = url.trimEnd('/')
        val normalizedSnap = readSnapshotFile(fileForUrl(normalized))
        if (normalizedSnap != null) return normalizedSnap

        return null
    }

    fun listCachedPages(): List<CachedPageSnapshot> {
        return runCatching {
            cacheDir.listFiles()
                ?.mapNotNull { readSnapshotFile(it) }
                ?.sortedByDescending { it.savedAtMillis }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun readSnapshotFile(file: File): CachedPageSnapshot? {
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            CachedPageSnapshot(
                url = json.optString("url"),
                title = json.optString("title"),
                html = json.optString("html"),
                savedAtMillis = json.optLong("saved_at", 0L)
            )
        }.getOrNull()
    }

    private fun sanitizeForOfflineRender(html: String): String {
        val withoutScripts = SCRIPT_TAG_REGEX.replace(html, "")
        val offlineNoticeBanner = """
            <div style="background:#0D6E5E;color:#FFFFFF;padding:10px 14px;font-family:sans-serif;font-size:13px;border-bottom:2px solid #D97706;">
              <strong>অফলাইন ক্যাশ মোড (Offline Cached View)</strong> — ইন্টারনেট সংযোগ না থাকায় সর্বশেষ সংরক্ষিত তথ্য দেখানো হচ্ছে।
            </div>
        """.trimIndent()
        val match = BODY_OPEN_TAG_REGEX.find(withoutScripts)
        return if (match != null) {
            withoutScripts.replaceRange(match.range, "${match.value}$offlineNoticeBanner")
        } else {
            "$offlineNoticeBanner$withoutScripts"
        }
    }

    private fun pruneOldFiles() {
        val files = cacheDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        if (files.size > MAX_CACHED_PAGES) {
            files.drop(MAX_CACHED_PAGES).forEach { it.delete() }
        }
    }

    private fun fileForUrl(url: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$digest.json")
    }

    companion object {
        private const val MAX_CACHED_PAGES = 15
        private val SCRIPT_TAG_REGEX = Regex(
            "<script\\b[^<]*(?:(?!</script>)<[^<]*)*</script>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val BODY_OPEN_TAG_REGEX = Regex(
            "<body[^>]*>",
            setOf(RegexOption.IGNORE_CASE)
        )
    }
}
