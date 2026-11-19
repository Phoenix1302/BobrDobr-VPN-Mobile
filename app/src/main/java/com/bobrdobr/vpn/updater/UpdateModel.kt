package com.bobrdobr.vpn.updater

import android.net.Uri

/**
 * Release metadata obtained from GitHub Releases.
 */
data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val changelog: String,
    val htmlUrl: String,
    val apkDownloadUrl: String,
    val apkSize: Long,
    val isPrerelease: Boolean = false
)

/**
 * Lifecycle states of the application update engine.
 */
sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data class UpdateAvailable(val release: ReleaseInfo) : UpdateState()
    data object UpToDate : UpdateState()
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : UpdateState()
    data class ReadyToInstall(val apkUri: Uri) : UpdateState()
    data class Error(val message: String) : UpdateState()
}
