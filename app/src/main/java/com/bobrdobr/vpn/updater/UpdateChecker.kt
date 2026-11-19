package com.bobrdobr.vpn.updater

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for BobrDobr VPN updates and parses release metadata.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"

    const val GITHUB_LATEST_RELEASE_URL =
        "https://api.github.com/repos/Phoenix1302/BobrDobr-VPN-Mobile/releases/latest"
    const val GITHUB_RELEASES_URL =
        "https://api.github.com/repos/Phoenix1302/BobrDobr-VPN-Mobile/releases"

    /**
     * Checks GitHub Releases for a newer version than [currentVersionName].
     * Returns Result.success(ReleaseInfo) if a newer release is found.
     * Returns Result.success(null) if up-to-date or no APK asset exists.
     * Returns Result.failure(Exception) if a network or parsing error occurs.
     */
    suspend fun checkForUpdate(
        currentVersionName: String,
        apiUrl: String = GITHUB_LATEST_RELEASE_URL
    ): Result<ReleaseInfo?> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val urlObj = URL(apiUrl)
            connection = (urlObj.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "BobrDobr-VPN-Mobile")
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                // /releases/latest returns 404 if only prereleases exist; fallback to full releases list
                if (apiUrl == GITHUB_LATEST_RELEASE_URL) {
                    Log.d(TAG, "Latest release returned 404, falling back to all releases list...")
                    connection.disconnect()
                    return@withContext checkForUpdate(currentVersionName, GITHUB_RELEASES_URL)
                }
                Log.d(TAG, "No releases published yet on repository (404)")
                return@withContext Result.success(null)
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                return@withContext Result.failure(
                    IllegalStateException("GitHub API error HTTP $responseCode: $errorBody")
                )
            }

            val jsonStr = connection.inputStream.bufferedReader().use { it.readText() }
            val releaseInfo = parseReleaseInfo(jsonStr) ?: return@withContext Result.success(null)

            if (isNewerVersion(releaseInfo.versionName, currentVersionName)) {
                Log.i(TAG, "Newer version available: ${releaseInfo.versionName} > $currentVersionName")
                Result.success(releaseInfo)
            } else {
                Log.d(TAG, "Application is up-to-date: current=$currentVersionName, latest=${releaseInfo.versionName}")
                Result.success(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates: ${e.message}", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Parses the GitHub Release JSON object or array into a [ReleaseInfo] instance.
     * Supports both single release objects and releases arrays (taking the first release).
     * Returns null if JSON is invalid or no .apk asset is present.
     */
    fun parseReleaseInfo(jsonStr: String): ReleaseInfo? {
        val trimmed = jsonStr.trim()
        val root = if (trimmed.startsWith("[")) {
            val arr = org.json.JSONArray(trimmed)
            if (arr.length() == 0) return null
            arr.optJSONObject(0) ?: return null
        } else {
            JSONObject(trimmed)
        }
        val tagName = root.optString("tag_name", "").trim()
        if (tagName.isEmpty()) return null

        val versionName = cleanVersion(tagName)
        val title = root.optString("name", tagName).ifEmpty { tagName }
        val changelog = root.optString("body", "")
        val htmlUrl = root.optString("html_url", "")

        val assets = root.optJSONArray("assets") ?: return null
        var apkUrl = ""
        var apkSize = 0L

        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            if (name.endsWith(".apk", ignoreCase = true)) {
                apkUrl = asset.optString("browser_download_url", "")
                apkSize = asset.optLong("size", 0L)
                break
            }
        }

        if (apkUrl.isEmpty()) {
            return null
        }

        return ReleaseInfo(
            tagName = tagName,
            versionName = versionName,
            title = title,
            changelog = changelog,
            htmlUrl = htmlUrl,
            apkDownloadUrl = apkUrl,
            apkSize = apkSize
        )
    }

    /**
     * Normalizes version string removing leading 'v' / 'V' and extra whitespace.
     */
    fun cleanVersion(version: String): String {
        return version.trim().removePrefix("v").removePrefix("V").trim()
    }

    /**
     * Splits version into numerical components.
     */
    private fun parseVersionComponents(version: String): List<Int> {
        val cleaned = cleanVersion(version)
        val mainPart = cleaned.split("-")[0] // Strip pre-release suffix e.g. -beta
        return mainPart.split(".").mapNotNull { it.toIntOrNull() }
    }

    /**
     * Compares two semantic version strings.
     * Returns > 0 if v1 > v2, 0 if v1 == v2, < 0 if v1 < v2.
     */
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

        // Numerical components are equal. Follow SemVer: non-prerelease > prerelease
        val clean1 = cleanVersion(v1)
        val clean2 = cleanVersion(v2)
        val hasPre1 = clean1.contains("-")
        val hasPre2 = clean2.contains("-")

        return when {
            !hasPre1 && hasPre2 -> 1
            hasPre1 && !hasPre2 -> -1
            else -> 0
        }
    }

    /**
     * Determines whether [remoteVersion] is strictly newer than [currentVersion].
     */
    fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        return compareVersions(remoteVersion, currentVersion) > 0
    }
}
