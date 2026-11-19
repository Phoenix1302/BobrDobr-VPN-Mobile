package com.bobrdobr.vpn.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * AppUpdateManager: Central coordinator for checking, streaming, and triggering APK updates.
 */
class AppUpdateManager private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "AppUpdateManager"
        private const val PREFS_NAME = "bobrdobr_update_prefs"
        private const val KEY_INCLUDE_PRERELEASES = "key_include_prereleases"

        @Volatile
        private var instance: AppUpdateManager? = null

        fun getInstance(context: Context): AppUpdateManager {
            return instance ?: synchronized(this) {
                instance ?: AppUpdateManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun detectDefaultIncludePrereleases(version: String): Boolean {
        val v = version.lowercase()
        return v.contains("pre") || v.contains("beta") || v.contains("alpha") || v.contains("rc") || v.contains("-")
    }

    private fun getCurrentVersion(): String {
        return try {
            val pInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            pInfo.versionName ?: "0.8.2-prerelease"
        } catch (_: Exception) {
            "0.8.2-prerelease"
        }
    }

    private val _includePrereleases = MutableStateFlow(
        if (prefs.contains(KEY_INCLUDE_PRERELEASES)) {
            prefs.getBoolean(KEY_INCLUDE_PRERELEASES, false)
        } else {
            detectDefaultIncludePrereleases(getCurrentVersion())
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

    /**
     * Checks for new versions on GitHub Releases.
     */
    fun check(isManual: Boolean = false) {
        val currentVersion = getCurrentVersion()

        _updateState.value = UpdateState.Checking

        managerScope.launch {
            Log.d(TAG, "Checking for updates (currentVersion=$currentVersion, isManual=$isManual, includePrereleases=${_includePrereleases.value})...")
            val result = UpdateChecker.checkForUpdate(
                currentVersionName = currentVersion,
                includePrereleases = _includePrereleases.value
            )

            result.fold(
                onSuccess = { release ->
                    if (release != null) {
                        Log.i(TAG, "Update found: ${release.versionName} (prerelease=${release.isPrerelease})")
                        _updateState.value = UpdateState.UpdateAvailable(release)
                    } else {
                        Log.d(TAG, "App is up to date")
                        _updateState.value = UpdateState.UpToDate
                    }
                },
                onFailure = { err ->
                    Log.w(TAG, "Failed update check: ${err.message}")
                    _updateState.value = UpdateState.Error(err.message ?: "Не удалось проверить обновления")
                }
            )
        }
    }

    /**
     * Downloads APK from GitHub Releases with byte-level progress reporting and launches the system installer.
     */
    fun downloadAndInstall(context: Context, release: ReleaseInfo) {
        managerScope.launch {
            try {
                val updatesDir = context.cacheDir.resolve("updates")
                if (!updatesDir.exists()) {
                    updatesDir.mkdirs()
                }

                val apkFile = updatesDir.resolve("BobrDobr_v${release.versionName}.apk")
                if (apkFile.exists()) {
                    apkFile.delete()
                }

                _updateState.value = UpdateState.Downloading(
                    progress = 0f,
                    downloadedBytes = 0L,
                    totalBytes = release.apkSize
                )

                downloadApkFile(release.apkDownloadUrl, apkFile, release.apkSize)

                Log.i(TAG, "APK download finished: ${apkFile.absolutePath} (${apkFile.length()} bytes)")

                val apkUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )

                _updateState.value = UpdateState.ReadyToInstall(apkUri)

                withContext(Dispatchers.Main) {
                    launchPackageInstaller(context, apkUri)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading or installing update: ${e.message}", e)
                _updateState.value = UpdateState.Error(e.message ?: "Ошибка загрузки обновления")
            }
        }
    }

    /**
     * Resets state back to Idle.
     */
    fun resetState() {
        _updateState.value = UpdateState.Idle
    }

    private suspend fun downloadApkFile(
        initialUrl: String,
        targetFile: File,
        expectedSize: Long
    ) = withContext(Dispatchers.IO) {
        var currentUrl = initialUrl
        var redirects = 0
        var connection: HttpURLConnection? = null

        // Follow HTTP redirects (GitHub releases assets redirect to AWS S3)
        while (redirects < 5) {
            val urlObj = URL(currentUrl)
            connection = (urlObj.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 30000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "BobrDobr-VPN-Mobile")
                setRequestProperty("Accept", "application/octet-stream, */*")
            }

            val status = connection.responseCode
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                if (!location.isNullOrEmpty()) {
                    currentUrl = location
                    connection.disconnect()
                    redirects++
                    continue
                }
            }
            break
        }

        val conn = connection ?: throw IllegalStateException("Failed to establish download connection")
        try {
            val responseCode = conn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Download failed with HTTP $responseCode")
            }

            val totalBytes = conn.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            var downloaded = 0L
            var lastReportedTime = 0L

            conn.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(16384)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead

                        val now = System.currentTimeMillis()
                        // Throttle state updates to at most once every 100ms
                        if (now - lastReportedTime > 100 || downloaded == totalBytes) {
                            lastReportedTime = now
                            val progress = if (totalBytes > 0) {
                                (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            _updateState.value = UpdateState.Downloading(
                                progress = progress,
                                downloadedBytes = downloaded,
                                totalBytes = totalBytes
                            )
                        }
                    }
                    output.flush()
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun launchPackageInstaller(context: Context, apkUri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
