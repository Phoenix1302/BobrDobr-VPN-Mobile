package com.bobrdobr.vpn.routing

import org.bobrdobr.vpn.core.model.CidrRoute
import org.bobrdobr.vpn.core.routing.CidrUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CidrUtilsTest {

    @Test
    fun testParseIpv4() {
        val ipLong = CidrUtils.parseIpv4ToLong("192.168.1.1")
        assertNotNull(ipLong)
        assertEquals(0xC0A80101L, ipLong)
    }

    @Test
    fun testCidrBlockContains() {
        val block = CidrUtils.CidrBlock.parse("192.168.0.0/16")
        assertNotNull(block)

        val insideIp = CidrUtils.parseIpv4ToLong("192.168.10.5")!!
        val outsideIp = CidrUtils.parseIpv4ToLong("192.169.1.1")!!

        assertTrue(block!!.contains(insideIp))
        assertFalse(block.contains(outsideIp))
    }

    @Test
    fun testSubtractSimpleSubnet() {
        // Base: 192.168.1.0/24
        // Exclude: 192.168.1.128/25
        // Remaining should be: 192.168.1.0/25
        val base = CidrUtils.CidrBlock.parse("192.168.1.0/24")!!
        val exc = CidrUtils.CidrBlock.parse("192.168.1.128/25")!!

        val remaining = CidrUtils.subtractCIDRs(base, listOf(exc))
        assertEquals(1, remaining.size)
        assertEquals("192.168.1.0", remaining[0].toCidrRoute().address)
        assertEquals(25, remaining[0].prefix)
    }

    @Test
    fun testSubtractExactMatch() {
        // Exclude matches base -> remaining is empty
        val base = CidrUtils.CidrBlock.parse("10.0.0.0/8")!!
        val exc = CidrUtils.CidrBlock.parse("10.0.0.0/8")!!

        val remaining = CidrUtils.subtractCIDRs(base, listOf(exc))
        assertTrue(remaining.isEmpty())
    }

    @Test
    fun testSubtractRFC1918FromDefaultRoute() {
        val routes = CidrUtils.buildTunnelRoutes(bypassLocal = true)
        assertTrue(routes.isNotEmpty())

        val blocks = routes.mapNotNull { CidrUtils.CidrBlock.fromRoute(it) }

        // Test private addresses are NOT covered
        val privateIps = listOf(
            "10.0.0.1",
            "10.255.255.254",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.1.1",
            "192.168.254.254",
            "169.254.1.1"
        )

        for (ipStr in privateIps) {
            val ip = CidrUtils.parseIpv4ToLong(ipStr)!!
            val match = blocks.any { it.contains(ip) }
            assertFalse("Private IP $ipStr must NOT be routed through VPN", match)
        }

        // Test public internet addresses ARE covered
        val publicIps = listOf(
            "1.1.1.1",
            "8.8.8.8",
            "140.82.121.4",
            "77.88.8.8",
            "185.196.220.14"
        )

        for (ipStr in publicIps) {
            val ip = CidrUtils.parseIpv4ToLong(ipStr)!!
            val matchingBlocks = blocks.filter { it.contains(ip) }
            assertEquals("Public IP $ipStr must be covered by exactly 1 route", 1, matchingBlocks.size)
        }
    }
}
