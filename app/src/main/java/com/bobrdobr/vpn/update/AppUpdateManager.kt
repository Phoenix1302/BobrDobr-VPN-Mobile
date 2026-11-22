package com.bobrdobr.vpn.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import com.bobrdobr.vpn.BuildConfig

data class GitHubRelease(
    val tagName: String,
    val version: String,
    val title: String,
    val changelog: String,
    val htmlUrl: String,
    val downloadUrl: String,
    val apkSize: Long,
    val isPrerelease: Boolean = false
)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpdateAvailable(val release: GitHubRelease) : UpdateState()
    object UpToDate : UpdateState()
    data class Downloading(
        val release: GitHubRelease,
        val progressPercent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : UpdateState()
    data class ReadyToInstall(val release: GitHubRelease, val apkFile: File) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

class AppUpdateManager private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "AppUpdateManager"
        const val CURRENT_VERSION_NAME = BuildConfig.VERSION_NAME
        const val CURRENT_VERSION_CODE = BuildConfig.VERSION_CODE
        const val GITHUB_REPO_URL = "https://github.com/Phoenix1302/BobrDobr-VPN-Mobile"
        const val AUTHOR_PROFILE_URL = "https://github.com/Phoenix1302"

        private const val PREFS_NAME = "bobrdobr_update_prefs"
        private const val KEY_INCLUDE_PRERELEASES = "key_include_prereleases"
        private const val GITHUB_RELEASES_API =
            "https://api.github.com/repos/Phoenix1302/BobrDobr-VPN-Mobile/releases"
        private const val LATEST_RELEASE_API =
            "https://api.github.com/repos/Phoenix1302/BobrDobr-VPN-Mobile/releases/latest"

        @Volatile
        private var instance: AppUpdateManager? = null

        fun getInstance(context: Context): AppUpdateManager {
            return instance ?: synchronized(this) {
                instance ?: AppUpdateManager(context.applicationContext).also { instance = it }
            }
        }

        fun cleanVersion(v: String): String = v.trim().removePrefix("v").removePrefix("V").trim()

        fun parseVersionComponents(version: String): List<Int> {
            val cleaned = cleanVersion(version)
            val mainPart = cleaned.split("-")[0]
            return mainPart.split(".").mapNotNull { it.toIntOrNull() }
        }

        fun compareVersions(v1: String, v2: String): Int {
            val parts1 = parseVersionComponents(v1)
            val parts2 = parseVersionComponents(v2)
            val maxLen = maxOf(parts1.size, parts2.size)

            for (i in 0 until maxLen) {
                val num1 = parts1.getOrElse(i) { 0 }
                val num2 = parts2.getOrElse(i) { 0 }
                if (num1 != num2) {
                    return num1.compareTo(num2)
                }
            }

            // SemVer 2.0.0: when base versions are equal, a normal release has higher
            // precedence than a pre-release version (e.g. 0.9.8 > 0.9.8-prerelease).
            val pre1 = cleanVersion(v1).substringAfter("-", "")
            val pre2 = cleanVersion(v2).substringAfter("-", "")

            return when {
                pre1.isEmpty() && pre2.isNotEmpty() -> 1   // Final release is newer than pre-release
                pre1.isNotEmpty() && pre2.isEmpty() -> -1  // Pre-release is older than final release
                pre1.isNotEmpty() && pre2.isNotEmpty() -> pre1.compareTo(pre2)
                else -> 0
            }
        }

        fun isNewerVersion(remote: String, current: String): Boolean {
            return compareVersions(remote, current) > 0
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun detectDefaultIncludePrereleases(): Boolean {
        val v = CURRENT_VERSION_NAME.lowercase()
        return v.contains("pre") || v.contains("beta") || v.contains("alpha") || v.contains("rc") || v.contains("-")
    }

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _includePrereleases = MutableStateFlow(
        if (prefs.contains(KEY_INCLUDE_PRERELEASES)) {
            prefs.getBoolean(KEY_INCLUDE_PRERELEASES, false)
        } else {
            detectDefaultIncludePrereleases()
        }
    )
    val includePrereleases: StateFlow<Boolean> = _includePrereleases.asStateFlow()

    fun setIncludePrereleases(enabled: Boolean) {
        _includePrereleases.value = enabled
        try {
            prefs.edit().putBoolean(KEY_INCLUDE_PRERELEASES, enabled).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist includePrereleases: ${e.message}")
        }
        check(isManual = false)
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    // Shared flow for manual check result toasts
    private val _manualCheckEvent = MutableSharedFlow<ManualCheckResult>(extraBufferCapacity = 1)
    val manualCheckEvent: SharedFlow<ManualCheckResult> = _manualCheckEvent.asSharedFlow()

    sealed class ManualCheckResult {
        object UpToDate : ManualCheckResult()
        data class Available(val release: GitHubRelease) : ManualCheckResult()
        data class Failed(val error: String) : ManualCheckResult()
    }

    init {
        cleanOldApks()
    }

    /**
     * Cleans up leftover update APKs from previous updates in internal cache and files dirs.
     * Optionally preserves [keepFile] (e.g. freshly downloaded release ready to install).
     */
    fun cleanOldApks(keepFile: File? = null) {
        val targetDirs = listOf(
            File(appContext.cacheDir, "updates"),
            File(appContext.filesDir, "updates")
        )
        for (dir in targetDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            try {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(".apk", ignoreCase = true)) {
                        if (keepFile == null || file.absolutePath != keepFile.absolutePath) {
                            val deleted = file.delete()
                            Log.i(TAG, "Cleaned up old update APK: ${file.name} (success=$deleted)")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning update directory '${dir.absolutePath}': ${e.message}")
            }
        }
    }

    fun check(isManual: Boolean = false) {
        if (_updateState.value is UpdateState.Checking || _updateState.value is UpdateState.Downloading) {
            return
        }

        cleanOldApks()
        _updateState.value = UpdateState.Checking

        scope.launch {
            try {
                val release = withContext(Dispatchers.IO) {
                    fetchLatestRelease()
                }

                if (release != null && isNewerVersion(release.version, CURRENT_VERSION_NAME)) {
                    Log.i(TAG, "Update available: ${release.tagName} (prerelease=${release.isPrerelease})")
                    _updateState.value = UpdateState.UpdateAvailable(release)
                    if (isManual) {
                        _manualCheckEvent.emit(ManualCheckResult.Available(release))
                    }
                } else {
                    Log.i(TAG, "App is up to date (current=$CURRENT_VERSION_NAME, channelPrereleases=${_includePrereleases.value})")
                    _updateState.value = UpdateState.UpToDate
                    if (isManual) {
                        _manualCheckEvent.emit(ManualCheckResult.UpToDate)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed: ${e.message}", e)
                val errMsg = e.message ?: "Не удалось проверить обновления"
                _updateState.value = UpdateState.Error(errMsg)
                if (isManual) {
                    _manualCheckEvent.emit(ManualCheckResult.Failed(errMsg))
                }
            }
        }
    }

    fun downloadAndInstall(release: GitHubRelease) {
        if (_updateState.value is UpdateState.Downloading) {
            return
        }

        scope.launch {
            try {
                _updateState.value = UpdateState.Downloading(
                    release = release,
                    progressPercent = 0,
                    downloadedBytes = 0L,
                    totalBytes = release.apkSize
                )

                val apkFile = withContext(Dispatchers.IO) {
                    downloadApk(release)
                }

                _updateState.value = UpdateState.ReadyToInstall(release, apkFile)
                installApk(appContext, apkFile)
            } catch (e: Exception) {
                Log.e(TAG, "Download/Install failed: ${e.message}", e)
                _updateState.value = UpdateState.Error("Ошибка загрузки обновления: ${e.message}")
            }
        }
    }

    fun installApk(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start installer intent: ${e.message}", e)
        }
    }

    private fun fetchLatestRelease(): GitHubRelease? {
        val url = URL(GITHUB_RELEASES_API)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "BobrDobr-VPN-Android/$CURRENT_VERSION_NAME")
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")

        val code = conn.responseCode
        if (code != 200) {
            Log.w(TAG, "GitHub API returned HTTP $code")
            return null
        }

        val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }.trim()
        return parseRelease(jsonStr, _includePrereleases.value)
    }

    fun parseRelease(jsonStr: String, allowPrereleases: Boolean): GitHubRelease? {
        val trimmed = jsonStr.trim()
        val targetObj: JSONObject? = if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            var candidate: JSONObject? = null
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optBoolean("draft", false)) continue
                val isPre = obj.optBoolean("prerelease", false)
                if (!allowPrereleases && isPre) continue

                // Check if has .apk asset
                val assets = obj.optJSONArray("assets") ?: continue
                var hasApk = false
                for (j in 0 until assets.length()) {
                    val asset = assets.optJSONObject(j) ?: continue
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        hasApk = true
                        break
                    }
                }
                if (hasApk) {
                    candidate = obj
                    break
                }
            }
            candidate
        } else {
            val obj = JSONObject(trimmed)
            if (obj.optBoolean("draft", false)) return null
            val isPre = obj.optBoolean("prerelease", false)
            if (!allowPrereleases && isPre) return null
            obj
        }

        val root = targetObj ?: return null

        val tagName = root.optString("tag_name", "")
        if (tagName.isBlank()) return null

        val isPre = root.optBoolean("prerelease", false)
        val title = root.optString("name", tagName)
        val body = root.optString("body", "")
        val htmlUrl = root.optString("html_url", GITHUB_REPO_URL)
        val version = tagName.trimStart('v', 'V')

        var downloadUrl = ""
        var apkSize = 0L

        val assets = root.optJSONArray("assets")
        if (assets != null) {
            var bestAsset: JSONObject? = null
            var bestScore = -1
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val assetName = asset.optString("name", "")
                if (!assetName.endsWith(".apk", ignoreCase = true)) continue

                val lower = assetName.lowercase()
                val score = when {
                    lower.contains("bobrdobr-mobile") && !lower.contains("debug") -> 4
                    lower.contains("release") && !lower.contains("debug") -> 3
                    !lower.contains("debug") -> 2
                    else -> 1
                }
                if (score > bestScore) {
                    bestScore = score
                    bestAsset = asset
                }
            }
            if (bestAsset != null) {
                downloadUrl = bestAsset.optString("browser_download_url", "")
                apkSize = bestAsset.optLong("size", 0L)
            }
        }

        if (downloadUrl.isEmpty()) {
            downloadUrl = htmlUrl
        }

        return GitHubRelease(
            tagName = tagName,
            version = version,
            title = if (title.isNotBlank()) title else "Обновление $tagName",
            changelog = body,
            htmlUrl = htmlUrl,
            downloadUrl = downloadUrl,
            apkSize = apkSize,
            isPrerelease = isPre
        )
    }

    private fun downloadApk(release: GitHubRelease): File {
        cleanOldApks()

        val updatesDir = File(appContext.cacheDir, "updates")
        if (!updatesDir.exists()) {
            updatesDir.mkdirs()
        }

        val destinationFile = File(updatesDir, "bobrdobr-${release.version}.apk")
        if (destinationFile.exists()) {
            destinationFile.delete()
        }

        val url = URL(release.downloadUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "BobrDobr-VPN-Android/$CURRENT_VERSION_NAME")
        conn.instanceFollowRedirects = true

        val totalLength = conn.contentLengthLong.let { if (it > 0) it else release.apkSize }

        conn.inputStream.use { input ->
            FileOutputStream(destinationFile).use { output ->
                val buffer = ByteArray(16 * 1024)
                var bytesRead: Int
                var totalBytesRead = 0L
                var lastPercent = 0

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    val percent = if (totalLength > 0) {
                        ((totalBytesRead * 100) / totalLength).toInt().coerceIn(0, 100)
                    } else 0

                    if (percent != lastPercent) {
                        lastPercent = percent
                        _updateState.value = UpdateState.Downloading(
                            release = release,
                            progressPercent = percent,
                            downloadedBytes = totalBytesRead,
                            totalBytes = totalLength
                        )
                    }
                }
                output.flush()
            }
        }

        return destinationFile
    }
}
