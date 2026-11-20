package com.bobrdobr.vpn.routing

import android.content.Context
import com.bobrdobr.vpn.split.SplitTunnelManager
import com.bobrdobr.vpn.ui.model.ServerProfile
import org.bobrdobr.vpn.core.logger.DebugLogger
import org.bobrdobr.vpn.core.model.CidrRoute
import org.bobrdobr.vpn.core.model.TunnelConfig
import org.bobrdobr.vpn.core.model.VpnProtocol
import org.bobrdobr.vpn.core.routing.CidrUtils

/**
 * Unified assembler for [TunnelConfig].
 *
 * Implements strict rules:
 * 1. Strict DNS: Sole primary source is .conf AWG / dns from subscription profile.
 *    Fallback DNS presets (Cloudflare, AdGuard, Google, etc.) are used ONLY if
 *    the server profile declares NO DNS at all.
 * 2. Host-routes (/32 or /128) are explicitly added for all active DNS servers.
 * 3. Separate Bypass Subnets:
 *    - If bypass is enabled, custom bypass subnets are parsed.
 *    - For WireGuard/AmneziaWG: bypassSubnets are subtracted from AllowedIPs (base routes)
 *      while strictly preserving the tunnel subnet and DNS server routes.
 *    - For OpenConnect: bypassSubnets are passed in [TunnelConfig.bypassSubnets] so that
 *      dynamic 2-phase CSTP negotiation can subtract them from server-pushed SplitInclude routes.
 * 4. Per-app split tunneling (allowed / disallowed packages) is applied via [SplitTunnelManager].
 */
object TunnelConfigAssembler {

    private const val TAG = "TunnelConfigAssembler"

    fun assemble(
        context: Context,
        profile: ServerProfile,
        bypassEnabled: Boolean = false,
        bypassSubnetsRaw: String = "",
        fallbackDnsSelection: String = "Cloudflare"
    ): TunnelConfig {
        var baseConfig = profile.toTunnelConfig()

        // 1. Per-App Split Tunneling (Whitelist / Blacklist)
        try {
            val splitManager = SplitTunnelManager.getInstance(context)
            baseConfig = splitManager.applyToConfig(baseConfig)
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Error applying per-app split tunneling: ${e.message}")
        }

        // 2. Strict DNS: Primary is profile.dnsServers; fallback presets only if empty
        val activeDns: List<String> = if (profile.dnsServers.isNotEmpty()) {
            DebugLogger.i(TAG, "Using primary server-side DNS from profile: ${profile.dnsServers}")
            profile.dnsServers
        } else {
            val fallback = when {
                fallbackDnsSelection.contains("AdGuard", ignoreCase = true) -> listOf("94.140.14.14", "94.140.15.15")
                fallbackDnsSelection.contains("Google", ignoreCase = true) -> listOf("8.8.8.8", "8.8.4.4")
                fallbackDnsSelection.contains("Quad9", ignoreCase = true) -> listOf("9.9.9.9", "149.112.112.112")
                fallbackDnsSelection.contains("Cloudflare", ignoreCase = true) -> listOf("1.1.1.1", "1.0.0.1")
                else -> listOf("1.1.1.1", "1.0.0.1")
            }
            DebugLogger.i(TAG, "No DNS declared in profile, applying fallback preset ($fallbackDnsSelection): $fallback")
            fallback
        }

        // 3. Parse Bypass Subnets
        val bypassSubnetsList = mutableListOf<CidrRoute>()
        if (bypassEnabled && bypassSubnetsRaw.isNotBlank()) {
            val tokens = bypassSubnetsRaw.split(',', ';', '\n', ' ')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            for (token in tokens) {
                val block = CidrUtils.CidrBlock.parse(token)
                if (block != null) {
                    bypassSubnetsList.add(block.toCidrRoute())
                }
            }
        }

        // 4. Calculate Routes
        val computedRoutes = if (baseConfig.protocol == VpnProtocol.AMNEZIA_WG || baseConfig.protocol == VpnProtocol.WIREGUARD) {
            val baseRoutes = if (baseConfig.routes.isNotEmpty()) baseConfig.routes else listOf(CidrRoute.DEFAULT_IPV4)
            val filteredRoutes = if (bypassEnabled && bypassSubnetsList.isNotEmpty()) {
                CidrUtils.subtractRouteLists(baseRoutes, bypassSubnetsList).toMutableList()
            } else {
                baseRoutes.toMutableList()
            }

            // Always preserve explicit /32 host routes for active DNS servers
            for (dns in activeDns) {
                if (dns.contains(".")) {
                    filteredRoutes.add(CidrRoute(dns, 32))
                } else if (dns.contains(":")) {
                    filteredRoutes.add(CidrRoute(dns, 128))
                }
            }

            // Preserve client virtual IPv4 host route
            if (baseConfig.ipv4Address.isNotBlank()) {
                filteredRoutes.add(CidrRoute(baseConfig.ipv4Address, 32))
            }

            filteredRoutes.distinct()
        } else {
            // For OpenConnect, default base route is 0.0.0.0/0 minus bypassSubnets,
            // which will also be dynamically adjusted in onTunConfigReady if split_include is pushed
            val baseRoutes = listOf(CidrRoute.DEFAULT_IPV4)
            val filteredRoutes = if (bypassEnabled && bypassSubnetsList.isNotEmpty()) {
                CidrUtils.subtractRouteLists(baseRoutes, bypassSubnetsList).toMutableList()
            } else {
                baseRoutes.toMutableList()
            }
            for (dns in activeDns) {
                if (dns.contains(".")) {
                    filteredRoutes.add(CidrRoute(dns, 32))
                } else if (dns.contains(":")) {
                    filteredRoutes.add(CidrRoute(dns, 128))
                }
            }
            filteredRoutes.distinct()
        }

        return baseConfig.copy(
            dnsServers = activeDns,
            routes = computedRoutes,
            bypassSubnets = bypassSubnetsList
        )
    }
}
