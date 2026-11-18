package com.bobrdobr.vpn.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class GitHubRelease(
    val tagName: String,
    val version: String,
    val title: String,
    val changelog: String,
    val htmlUrl: String,
    val downloadUrl: String,
    val apkSize: Long
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
        const val CURRENT_VERSION_NAME = "1.0.0"
        const val CURRENT_VERSION_CODE = 100
        const val GITHUB_REPO_URL = "https://github.com/Phoenix1302/BobrDobr-VPN-Mobile"
        const val AUTHOR_PROFILE_URL = "https://github.com/Phoenix1302"
        private const val LATEST_RELEASE_API =
            "https://api.github.com/repos/Phoenix1302/BobrDobr-VPN-Mobile/releases/latest"

        @Volatile
        private var instance: AppUpdateManager? = null

        fun getInstance(context: Context): AppUpdateManager {
            return instance ?: synchronized(this) {
                instance ?: AppUpdateManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

    fun check(isManual: Boolean = false) {
        if (_updateState.value is UpdateState.Checking || _updateState.value is UpdateState.Downloading) {
            return
        }

        _updateState.value = UpdateState.Checking

        scope.launch {
            try {
                val release = withContext(Dispatchers.IO) {
                    fetchLatestRelease()
                }

                if (release != null && isNewerVersion(release.version, CURRENT_VERSION_NAME)) {
                    _updateState.value = UpdateState.UpdateAvailable(release)
                    if (isManual) {
                        _manualCheckEvent.tryEmit(ManualCheckResult.Available(release))
                    }
                } else {
                    _updateState.value = UpdateState.UpToDate
                    if (isManual) {
                        _manualCheckEvent.tryEmit(ManualCheckResult.UpToDate)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: ${e.message}")
                val errorMsg = e.message ?: "Сбой проверки обновлений"
                _updateState.value = UpdateState.Error(errorMsg)
                if (isManual) {
                    _manualCheckEvent.tryEmit(ManualCheckResult.Failed(errorMsg))
                }
            }
        }
    }

    fun dismissUpdate() {
        if (_updateState.value is UpdateState.UpdateAvailable ||
            _updateState.value is UpdateState.UpToDate ||
            _updateState.value is UpdateState.Error
        ) {
            _updateState.value = UpdateState.Idle
        }
    }

    fun downloadAndInstall(release: GitHubRelease) {
        if (_updateState.value is UpdateState.Downloading) return

        scope.launch {
            try {
                _updateState.value = UpdateState.Downloading(
                    release = release,
                    progressPercent = 0,
                    downloadedBytes = 0,
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
        val url = URL(LATEST_RELEASE_API)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "BobrDobr-VPN-Android/$CURRENT_VERSION_NAME")
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")

        val code = conn.responseCode
        if (code != 200) {
            Log.w(TAG, "GitHub API returned HTTP $code")
            return null
        }

        val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
        val root = JSONObject(jsonStr)

        val tagName = root.optString("tag_name", "")
        val title = root.optString("name", tagName)
        val body = root.optString("body", "")
        val htmlUrl = root.optString("html_url", GITHUB_REPO_URL)
        val version = tagName.trimStart('v', 'V')

        var downloadUrl = ""
        var apkSize = 0L

        val assets = root.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val assetName = asset.optString("name", "")
                if (assetName.endsWith(".apk", ignoreCase = true)) {
                    downloadUrl = asset.optString("browser_download_url", "")
                    apkSize = asset.optLong("size", 0L)
                    break
                }
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
            apkSize = apkSize
        )
    }

    private fun downloadApk(release: GitHubRelease): File {
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

    private fun isNewerVersion(remote: String, current: String): Boolean {
        try {
            val remoteParts = remote.split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
            val currentParts = current.split(".").map { it.filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }

            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        } catch (_: Exception) {
            return false
        }
    }
}
