package com.bobrdobr.vpn.routing

import org.bobrdobr.vpn.core.model.CidrRoute
import java.net.Inet4Address
import java.net.InetAddress

/**
 * High-performance mathematical IPv4 CIDR arithmetic & subtraction utility.
 *
 * Implements recursive binary tree decomposition ([SubtractCIDRs]) ported from the
 * BobrDobr VPN core architecture to enable local RFC1918 bypass without tearing down the TUN fd.
 */
object CidrUtils {

    /**
     * Standard RFC1918 private subnets and link-local ranges.
     */
    val RFC1918_EXCLUDES: List<CidrRoute> = listOf(
        CidrRoute("10.0.0.0", 8),       // Class A Private
        CidrRoute("172.16.0.0", 12),    // Class B Private
        CidrRoute("192.168.0.0", 16),   // Class C Private
        CidrRoute("169.254.0.0", 16)    // Link-Local (APIPA)
    )

    data class CidrBlock(
        val ip: Long,     // Unsigned 32-bit representation (stored in Long)
        val prefix: Int   // Prefix length: 0..32
    ) {
        val mask: Long = if (prefix == 0) 0L else ((-1L shl (32 - prefix)) and 0xFFFFFFFFL)
        val networkIp: Long = ip and mask

        fun contains(otherIp: Long): Boolean {
            return (otherIp and mask) == networkIp
        }

        fun overlaps(other: CidrBlock): Boolean {
            return if (prefix <= other.prefix) {
                this.contains(other.networkIp)
            } else {
                other.contains(this.networkIp)
            }
        }

        fun toCidrRoute(): CidrRoute {
            val a = (networkIp ushr 24) and 0xFFL
            val b = (networkIp ushr 16) and 0xFFL
            val c = (networkIp ushr 8) and 0xFFL
            val d = networkIp and 0xFFL
            return CidrRoute("$a.$b.$c.$d", prefix)
        }

        companion object {
            fun parse(cidrStr: String): CidrBlock? {
                val parts = cidrStr.trim().split("/")
                val ipStr = parts[0]
                val prefix = if (parts.size > 1) parts[1].toIntOrNull() ?: 32 else 32
                if (prefix !in 0..32) return null

                val ipLong = parseIpv4ToLong(ipStr) ?: return null
                return CidrBlock(ipLong, prefix)
            }

            fun fromRoute(route: CidrRoute): CidrBlock? {
                val ipLong = parseIpv4ToLong(route.address) ?: return null
                if (route.prefix !in 0..32) return null
                return CidrBlock(ipLong, route.prefix)
            }
        }
    }

    fun parseIpv4ToLong(ipStr: String): Long? {
        return try {
            val addr = InetAddress.getByName(ipStr)
            if (addr !is Inet4Address) return null
            val bytes = addr.address
            var result = 0L
            for (b in bytes) {
                result = (result shl 8) or (b.toLong() and 0xFFL)
            }
            result and 0xFFFFFFFFL
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Recursively subtracts [excludeCIDRs] from [baseCIDR] and returns the minimal set
     * of non-overlapping CIDR blocks covering baseCIDR \ excludes.
     */
    fun subtractCIDRs(baseCIDR: CidrBlock, excludeCIDRs: List<CidrBlock>): List<CidrBlock> {
        val normBase = CidrBlock(baseCIDR.networkIp, baseCIDR.prefix)
        if (excludeCIDRs.isEmpty()) {
            return listOf(normBase)
        }

        // Filter excludes to only those overlapping normBase
        val filteredExcludes = excludeCIDRs
            .map { CidrBlock(it.networkIp, it.prefix) }
            .filter { normBase.overlaps(it) }

        if (filteredExcludes.isEmpty()) {
            return listOf(normBase)
        }

        // If any exclude completely covers normBase, normBase is entirely eliminated
        for (exc in filteredExcludes) {
            if (exc.prefix <= normBase.prefix) {
                return emptyList()
            }
        }

        if (normBase.prefix >= 32) {
            return emptyList()
        }

        // Split normBase into two equal halves (child prefix = prefix + 1)
        val childPrefix = normBase.prefix + 1
        val bitShift = 31 - normBase.prefix

        val leftIp = normBase.networkIp
        val leftNet = CidrBlock(leftIp, childPrefix)

        val rightIp = normBase.networkIp or (1L shl bitShift)
        val rightNet = CidrBlock(rightIp, childPrefix)

        // Partition excludes into left and right subnets
        val leftExcludes = mutableListOf<CidrBlock>()
        val rightExcludes = mutableListOf<CidrBlock>()

        for (exc in filteredExcludes) {
            if ((exc.networkIp and (1L shl bitShift)) == 0L) {
                leftExcludes.add(exc)
            } else {
                rightExcludes.add(exc)
            }
        }

        val result = mutableListOf<CidrBlock>()
        if (leftExcludes.isEmpty()) {
            result.add(leftNet)
        } else {
            result.addAll(subtractCIDRs(leftNet, leftExcludes))
        }

        if (rightExcludes.isEmpty()) {
            result.add(rightNet)
        } else {
            result.addAll(subtractCIDRs(rightNet, rightExcludes))
        }

        return result
    }

    /**
     * Subtracts a list of [excludeRoutes] from a list of [baseRoutes].
     */
    fun subtractRouteLists(
        baseRoutes: List<CidrRoute>,
        excludeRoutes: List<CidrRoute>
    ): List<CidrRoute> {
        val baseBlocks = baseRoutes.mapNotNull { CidrBlock.fromRoute(it) }
        val excludeBlocks = excludeRoutes.mapNotNull { CidrBlock.fromRoute(it) }

        val result = mutableListOf<CidrRoute>()
        for (base in baseBlocks) {
            val remaining = subtractCIDRs(base, excludeBlocks)
            for (rem in remaining) {
                result.add(rem.toCidrRoute())
            }
        }
        return result
    }

    /**
     * Generates a routing table for the VPN tunnel with optional local network (RFC1918) bypass.
     * When [bypassLocal] is true, RFC1918 subnets are excluded from the default 0.0.0.0/0 route.
     */
    fun buildTunnelRoutes(
        bypassLocal: Boolean,
        customExcludes: List<String> = emptyList()
    ): List<CidrRoute> {
        if (!bypassLocal && customExcludes.isEmpty()) {
            return listOf(CidrRoute.DEFAULT_IPV4)
        }

        val allExcludes = mutableListOf<CidrRoute>()
        if (bypassLocal) {
            allExcludes.addAll(RFC1918_EXCLUDES)
        }

        for (custom in customExcludes) {
            CidrBlock.parse(custom)?.let { allExcludes.add(it.toCidrRoute()) }
        }

        return subtractRouteLists(listOf(CidrRoute.DEFAULT_IPV4), allExcludes)
    }
}
