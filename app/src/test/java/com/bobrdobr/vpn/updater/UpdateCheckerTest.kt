package com.bobrdobr.vpn.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    private val sampleGitHubReleaseJson = """
        {
          "tag_name": "v1.0.1",
          "name": "Release 1.0.1 - Beaver Dam Security Update",
          "body": "Changelog:\n- Fixed split tunneling DNS leaks\n- Improved AmneziaWG packet throughput",
          "html_url": "https://github.com/Phoenix1302/BobrDobr-VPN-Mobile/releases/tag/v1.0.1",
          "draft": false,
          "prerelease": false,
          "assets": [
            {
              "name": "source-code.tar.gz",
              "browser_download_url": "https://github.com/Phoenix1302/BobrDobr-VPN-Mobile/releases/download/v1.0.1/source.tar.gz",
              "size": 102400
            },
            {
              "name": "BobrDobr-v1.0.1.apk",
              "browser_download_url": "https://github.com/Phoenix1302/BobrDobr-VPN-Mobile/releases/download/v1.0.1/BobrDobr-v1.0.1.apk",
              "size": 51216141
            }
          ]
        }
    """.trimIndent()

    private val releaseWithoutApkJson = """
        {
          "tag_name": "v1.0.2",
          "name": "Release 1.0.2",
          "body": "No binary assets yet",
          "html_url": "https://github.com/Phoenix1302/BobrDobr-VPN-Mobile/releases/tag/v1.0.2",
          "assets": [
            {
              "name": "checksums.txt",
              "browser_download_url": "https://github.com/Phoenix1302/BobrDobr-VPN-Mobile/releases/download/v1.0.2/checksums.txt",
              "size": 512
            }
          ]
        }
    """.trimIndent()

    @Test
    fun testVersionComparison() {
        // v1.0.1 > 1.0.0 -> true
        assertTrue(UpdateChecker.isNewerVersion("v1.0.1", "1.0.0"))

        // v1.0.0 vs 1.0.0 -> false (same version)
        assertFalse(UpdateChecker.isNewerVersion("v1.0.0", "1.0.0"))

        // 1.0.0 vs 1.0.0 -> false
        assertFalse(UpdateChecker.isNewerVersion("1.0.0", "1.0.0"))

        // v1.1.0 > 1.0.0 -> true
        assertTrue(UpdateChecker.isNewerVersion("v1.1.0", "1.0.0"))

        // 1.0.0 vs v1.0.1 -> false (remote is older)
        assertFalse(UpdateChecker.isNewerVersion("1.0.0", "v1.0.1"))

        // v2.0.0 > 1.9.9 -> true
        assertTrue(UpdateChecker.isNewerVersion("v2.0.0", "1.9.9"))

        // v1.0.10 > v1.0.9 -> true
        assertTrue(UpdateChecker.isNewerVersion("v1.0.10", "v1.0.9"))
    }

    @Test
    fun testParseGitHubReleaseJson() {
        val release = UpdateChecker.parseReleaseInfo(sampleGitHubReleaseJson)
        assertNotNull(release)
        assertEquals("v1.0.1", release!!.tagName)
        assertEquals("1.0.1", release.versionName)
        assertEquals("Release 1.0.1 - Beaver Dam Security Update", release.title)
        assertTrue(release.changelog.contains("Beaver Dam Security Update") || release.changelog.contains("split tunneling"))
        assertEquals("https://github.com/Phoenix1302/BobrDobr-VPN-Mobile/releases/tag/v1.0.1", release.htmlUrl)
        assertEquals("https://github.com/Phoenix1302/BobrDobr-VPN-Mobile/releases/download/v1.0.1/BobrDobr-v1.0.1.apk", release.apkDownloadUrl)
        assertEquals(51216141L, release.apkSize)
    }

    @Test
    fun testParseReleaseWithoutApkAssetReturnsNull() {
        val release = UpdateChecker.parseReleaseInfo(releaseWithoutApkJson)
        assertNull(release)
    }

    @Test
    fun testCleanVersion() {
        assertEquals("1.0.0", UpdateChecker.cleanVersion("v1.0.0"))
        assertEquals("1.0.0", UpdateChecker.cleanVersion("V1.0.0"))
        assertEquals("1.2.3", UpdateChecker.cleanVersion("  v1.2.3  "))
        assertEquals("1.0.0", UpdateChecker.cleanVersion("1.0.0"))
    }
}
