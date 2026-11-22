package com.bobrdobr.vpn.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun testVersionComparison() {
        // v1.0.1 > 1.0.0 -> true
        assertTrue(AppUpdateManager.isNewerVersion("v1.0.1", "1.0.0"))

        // v1.0.0 vs 1.0.0 -> false (same version)
        assertFalse(AppUpdateManager.isNewerVersion("v1.0.0", "1.0.0"))

        // 1.0.0 vs 1.0.0 -> false
        assertFalse(AppUpdateManager.isNewerVersion("1.0.0", "1.0.0"))

        // v1.1.0 > 1.0.0 -> true
        assertTrue(AppUpdateManager.isNewerVersion("v1.1.0", "1.0.0"))

        // 1.0.0 vs v1.0.1 -> false (remote is older)
        assertFalse(AppUpdateManager.isNewerVersion("1.0.0", "v1.0.1"))

        // v2.0.0 > 1.9.9 -> true
        assertTrue(AppUpdateManager.isNewerVersion("v2.0.0", "1.9.9"))

        // v1.0.10 > v1.0.9 -> true
        assertTrue(AppUpdateManager.isNewerVersion("v1.0.10", "v1.0.9"))
    }

    @Test
    fun testCleanVersion() {
        assertEquals("1.0.0", AppUpdateManager.cleanVersion("v1.0.0"))
        assertEquals("1.0.0", AppUpdateManager.cleanVersion("V1.0.0"))
        assertEquals("1.2.3", AppUpdateManager.cleanVersion("  v1.2.3  "))
        assertEquals("1.0.0", AppUpdateManager.cleanVersion("1.0.0"))
        assertEquals("0.9.8-prerelease", AppUpdateManager.cleanVersion("v0.9.8-prerelease"))
    }

    @Test
    fun testSemVerPreReleaseVsReleaseComparison() {
        // SemVer: A final release is ALWAYS newer than a pre-release with the same base version
        assertTrue("0.9.8 final must be newer than 0.9.8-prerelease",
            AppUpdateManager.isNewerVersion("0.9.8", "0.9.8-prerelease"))
        assertTrue("v0.9.8 final must be newer than 0.9.8-prerelease",
            AppUpdateManager.isNewerVersion("v0.9.8", "0.9.8-prerelease"))

        // Reverse: pre-release is NOT newer than final release
        assertFalse("0.9.8-prerelease is not newer than 0.9.8 final",
            AppUpdateManager.isNewerVersion("0.9.8-prerelease", "0.9.8"))

        // Identical pre-release versions are not newer
        assertFalse(AppUpdateManager.isNewerVersion("0.9.8-prerelease", "0.9.8-prerelease"))

        // Higher pre-release versions are newer
        assertTrue(AppUpdateManager.isNewerVersion("0.9.9-prerelease", "0.9.8-prerelease"))
        assertTrue(AppUpdateManager.isNewerVersion("v1.0.0", "0.9.8-prerelease"))

        // Older version is not newer
        assertFalse(AppUpdateManager.isNewerVersion("0.9.7", "0.9.8-prerelease"))
    }
}
