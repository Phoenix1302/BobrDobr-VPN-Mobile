package com.bobrdobr.vpn.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.format.DateTimeFormatter

class SubscriptionStatusTest {

    @Test
    fun testActiveStatusWithLongExpiration() {
        val now = System.currentTimeMillis()
        val futureDate = Instant.ofEpochMilli(now + 24L * 24 * 3600 * 1000L).toString() // 24 days in future

        val json = """
            {
              "status": "active",
              "user": {
                "username": "Egor",
                "role": "user",
                "expires_at": "$futureDate"
              },
              "server": {
                "host": "vpn.example.com",
                "app_version": "1.3.2"
              },
              "connections": []
            }
        """.trimIndent()

        val info = SubscriptionParser.parse(json, "https://vpn.example.com/api/v1/sub/testtoken")
        val status = info.currentStatus
        assertTrue("Status must be Active", status is SubscriptionStatus.Active)
        val active = status as SubscriptionStatus.Active
        assertFalse("Must NOT be warning period for > 3 days", active.isWarningPeriod)
        assertEquals("Egor", active.username)
        assertTrue("Remaining days should be >= 23", active.remainingDays >= 23)
        assertTrue("Can connect must be true", status.canConnect)
        assertFalse("Account must not be blocked", info.isAccountBlocked)
        assertFalse("Subscription must not be expired", info.isSubscriptionExpired)
    }

    @Test
    fun testActiveStatusWithWarningPeriod() {
        val now = System.currentTimeMillis()
        val twoDaysFuture = Instant.ofEpochMilli(now + 2L * 24 * 3600 * 1000L + 5 * 3600 * 1000L).toString() // 2 days 5 hrs

        val json = """
            {
              "status": "active",
              "user": {
                "username": "Egor",
                "role": "user",
                "expires_at": "$twoDaysFuture"
              },
              "server": {
                "host": "vpn.example.com",
                "app_version": "1.3.2"
              },
              "connections": []
            }
        """.trimIndent()

        val info = SubscriptionParser.parse(json, "https://vpn.example.com/api/v1/sub/testtoken")
        val status = info.currentStatus
        assertTrue("Status must be Active", status is SubscriptionStatus.Active)
        val active = status as SubscriptionStatus.Active
        assertTrue("Must be in warning period when <= 3 days left", active.isWarningPeriod)
        assertEquals("Истекает скоро", status.displayName)
        assertTrue("Can connect is still allowed in warning period", status.canConnect)
        assertEquals(2L, active.remainingDays)
        assertTrue("Formatted remaining should contain days and hours", active.formattedRemaining.contains("дн."))
    }

    @Test
    fun testExpiredStatusFromPastDate() {
        val now = System.currentTimeMillis()
        val pastDate = Instant.ofEpochMilli(now - 3600 * 1000L).toString() // 1 hour ago

        val json = """
            {
              "status": "active",
              "user": {
                "username": "Egor",
                "role": "user",
                "expires_at": "$pastDate"
              },
              "server": {
                "host": "vpn.example.com",
                "app_version": "1.3.2"
              },
              "connections": []
            }
        """.trimIndent()

        val info = SubscriptionParser.parse(json, "https://vpn.example.com/api/v1/sub/testtoken")
        val status = info.currentStatus
        assertTrue("Status must be Expired for past date", status is SubscriptionStatus.Expired)
        assertFalse("Can connect must be FALSE when expired", status.canConnect)
        assertTrue("isSubscriptionExpired must be true", info.isSubscriptionExpired)
    }

    @Test
    fun testExpiredStatusFromIsFrozen1() {
        val json = """
            {
              "status": "active",
              "is_frozen": 1,
              "user": {
                "username": "Egor",
                "is_frozen": 1
              },
              "connections": []
            }
        """.trimIndent()

        val info = SubscriptionParser.parse(json)
        val status = info.currentStatus
        assertTrue("Status must be Expired when is_frozen == 1", status is SubscriptionStatus.Expired)
        assertFalse("Can connect must be FALSE when is_frozen == 1", status.canConnect)
        assertTrue("isSubscriptionExpired must be true", info.isSubscriptionExpired)
    }

    @Test
    fun testBlockedByAdminFromIsFrozen2() {
        val json = """
            {
              "status": "blocked",
              "is_frozen": 2,
              "user": {
                "username": "Egor",
                "is_frozen": 2
              },
              "connections": []
            }
        """.trimIndent()

        val info = SubscriptionParser.parse(json)
        val status = info.currentStatus
        assertTrue("Status must be BlockedByAdmin when is_frozen == 2", status is SubscriptionStatus.BlockedByAdmin)
        assertFalse("Can connect must be FALSE when blocked by admin", status.canConnect)
        assertTrue("isAccountBlocked must be true", info.isAccountBlocked)
        assertEquals("Заблокирован администратором", status.displayName)
    }

    @Test
    fun testSubscriptionUserinfoHeaderExpireFallback() {
        val now = System.currentTimeMillis()
        val expireEpochSeconds = (now + 5L * 24 * 3600 * 1000L + 3600_000L) / 1000L

        // JSON has no user.expires_at, but header supplies it
        val json = """
            {
              "status": "active",
              "user": {
                "username": "Egor",
                "role": "user"
              },
              "server": {
                "host": "vpn.example.com"
              },
              "connections": []
            }
        """.trimIndent()

        val info = SubscriptionParser.parse(json, "https://vpn.example.com/api/v1/sub/test", headerExpireSeconds = expireEpochSeconds)
        val status = info.currentStatus
        assertTrue("Status must be Active using header expire", status is SubscriptionStatus.Active)
        val active = status as SubscriptionStatus.Active
        assertEquals(5L, active.remainingDays)
        assertFalse("Not warning for 5 days", active.isWarningPeriod)
    }

    @Test
    fun testDeriveDashboardUrl() {
        val url1 = "https://vpn.example.com/api/v1/sub/token_xyz"
        assertEquals("https://vpn.example.com/dashboard", SubscriptionParser.deriveDashboardUrl(url1))

        val url2 = "https://panel.myvpn.net:8443/sub/my-token-123"
        assertEquals("https://panel.myvpn.net:8443/dashboard", SubscriptionParser.deriveDashboardUrl(url2))

        val info = SubscriptionInfo(
            hasSubscription = true,
            url = url1
        )
        assertEquals("https://vpn.example.com/dashboard", info.getRenewalUrl())
    }

    @Test
    fun testOfflineClockExpiryDetection() {
        val past = System.currentTimeMillis() - 10000L
        val cached = SubscriptionInfo(
            hasSubscription = true,
            url = "https://vpn.example.com/sub/test",
            username = "Egor",
            expiresAt = past,
            isFrozen = 0
        )

        // Without internet sync, computeStatus() detects expiry dynamically
        val status = cached.computeStatus()
        assertTrue("Must detect expired status offline via device clock", status is SubscriptionStatus.Expired)
        assertFalse("Cannot connect when expired", status.canConnect)
        assertTrue(cached.isSubscriptionExpired)
    }

    @Test
    fun testInvalidTokenAndRateLimitedStates() {
        val invalidToken = SubscriptionStatus.InvalidToken
        assertFalse("Invalid token must block connect", invalidToken.canConnect)
        assertEquals("Токен недействителен или отозван", invalidToken.displayName)

        val rateLimited = SubscriptionStatus.RateLimited
        assertTrue("Rate limited still allows connection using cached profiles", rateLimited.canConnect)
    }
}
