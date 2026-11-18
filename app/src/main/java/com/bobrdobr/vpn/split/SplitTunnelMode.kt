package com.bobrdobr.vpn.split

/**
 * Split Tunneling operation mode.
 */
enum class SplitTunnelMode(val title: String, val subtitle: String) {
    /**
     * Whitelist: Only selected applications will route through the BobrDobr VPN dam tunnel.
     * Maps to [android.net.VpnService.Builder.addAllowedApplication].
     */
    WHITELIST("Только выбранные", "Через VPN идут только отмеченные приложения"),

    /**
     * Blacklist (Bypass): All applications route through VPN EXCEPT selected applications.
     * Maps to [android.net.VpnService.Builder.addDisallowedApplication].
     */
    BLACKLIST("Все кроме выбранных", "Отмеченные приложения идут напрямую в интернет")
}
