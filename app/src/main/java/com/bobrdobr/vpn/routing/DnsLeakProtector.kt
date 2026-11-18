package com.bobrdobr.vpn.routing

import android.util.Log
import org.bobrdobr.vpn.core.model.CidrRoute
import org.bobrdobr.vpn.core.model.TunnelConfig

/**
 * DNS Leak Protection and safe DNS endpoint configuration for BobrDobr VPN.
 *
 * Enforces strict routing of all DNS traffic exclusively inside the TUN interface
 * by inserting explicit host (/32) routes and overriding unencrypted ISP resolvers.
 */
object DnsLeakProtector {

    private const val TAG = "DnsLeakProtector"

    data class DnsPreset(
        val id: String,
        val displayName: String,
        val servers: List<String>,
        val description: String
    )

    val PRESETS = listOf(
        DnsPreset(
            id = "cloudflare",
            displayName = "Cloudflare UltraFast (1.1.1.1)",
            servers = listOf("1.1.1.1", "1.0.0.1"),
            description = "Минимальная задержка и строгая политика no-logs"
        ),
        DnsPreset(
            id = "adguard",
            displayName = "AdGuard Safe DNS (94.140.14.14)",
            servers = listOf("94.140.14.14", "94.140.15.15"),
            description = "Блокировка рекламы, вредоносных сайтов и трекеров"
        ),
        DnsPreset(
            id = "google",
            displayName = "Google Public DNS (8.8.8.8)",
            servers = listOf("8.8.8.8", "8.8.4.4"),
            description = "Глобальная надежность и стабильность Anycast"
        ),
        DnsPreset(
            id = "quad9",
            displayName = "Quad9 Threat Block (9.9.9.9)",
            servers = listOf("9.9.9.9", "149.112.112.112"),
            description = "Швейцарский фонд безопасности и защита от фишинга"
        )
    )

    fun getDefaultPreset(): DnsPreset = PRESETS.first()

    fun findPresetById(id: String): DnsPreset {
        return PRESETS.firstOrNull { it.id == id } ?: getDefaultPreset()
    }

    /**
     * Hardens a [TunnelConfig] against DNS leaks:
     * 1. Applies designated trusted DNS resolvers.
     * 2. Inserts explicit host (/32) routes for each DNS IP to guarantee DNS queries cannot
     *    fall back to physical interface gateways even if default routes fail.
     */
    fun applyStrictDns(
        config: TunnelConfig,
        dnsServers: List<String>
    ): TunnelConfig {
        val servers = if (dnsServers.isNotEmpty()) dnsServers else getDefaultPreset().servers

        // Create explicit /32 host routes for each DNS IP
        val dnsHostRoutes = servers.mapNotNull { ip ->
            if (CidrUtils.parseIpv4ToLong(ip) != null) {
                CidrRoute(ip, 32)
            } else {
                null
            }
        }

        // Combine with existing routes avoiding duplicates
        val combinedRoutes = config.routes.toMutableList()
        for (dnsRoute in dnsHostRoutes) {
            if (!combinedRoutes.any { it.address == dnsRoute.address && it.prefix == dnsRoute.prefix }) {
                combinedRoutes.add(dnsRoute)
            }
        }

        Log.d(TAG, "Applied strict DNS protection with servers: $servers and ${dnsHostRoutes.size} host routes")

        return config.copy(
            dnsServers = servers,
            routes = combinedRoutes
        )
    }
}
