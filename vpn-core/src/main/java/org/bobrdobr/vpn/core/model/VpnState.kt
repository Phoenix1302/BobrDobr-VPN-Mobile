package org.bobrdobr.vpn.core.model

/**
 * Represents the state machine of the BobrDobr VPN Dam Protection Service.
 */
sealed class VpnState {
    /**
     * VPN is inactive; network floodgates are open (unprotected).
     */
    data object Disconnected : VpnState()

    /**
     * VPN is establishing tunnel, allocating TUN descriptor, and performing protocol handshakes.
     */
    data class Connecting(
        val protocol: VpnProtocol,
        val serverName: String? = null
    ) : VpnState()

    /**
     * VPN tunnel is active; Beaver Dam is securely protecting traffic.
     */
    data class Connected(
        val protocol: VpnProtocol,
        val serverName: String? = null,
        val connectedSince: Long = System.currentTimeMillis()
    ) : VpnState()

    /**
     * VPN is tearing down tunnel, sending CSTP BYE or releasing TUN descriptors.
     */
    data class Disconnecting(
        val protocol: VpnProtocol? = null
    ) : VpnState()

    /**
     * VPN is automatically reconnecting due to network switch (Wi-Fi <-> Cellular) or transient link drop.
     */
    data class Reconnecting(
        val protocol: VpnProtocol,
        val attempt: Int = 1,
        val reason: String? = null
    ) : VpnState()

    /**
     * VPN encountered an error.
     */
    data class Error(
        val message: String,
        val protocol: VpnProtocol? = null,
        val errorCode: Int = -1,
        val throwable: Throwable? = null,
        val canRetry: Boolean = true
    ) : VpnState()
}

/**
 * Supported VPN Protocol Engines in BobrDobr VPN.
 */
enum class VpnProtocol(val displayName: String, val nativeLibName: String) {
    /**
     * AmneziaWG (Obfuscated WireGuard with Jc/Jmin/Jmax/S1/S2/H1-H4 headers)
     * Powered by zero-allocation Rust engine.
     */
    AMNEZIA_WG("AmneziaWG", "engine_rust"),

    /**
     * Standard WireGuard protocol.
     * Powered by zero-allocation Rust engine.
     */
    WIREGUARD("WireGuard", "engine_rust"),

    /**
     * OpenConnect CSTP/DTLS with uTLS Chrome fingerprint spoofing.
     * Powered by Go engine.
     */
    OPENCONNECT("OpenConnect", "engine_go");

    companion object {
        fun fromString(value: String): VpnProtocol = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true) ||
            it.displayName.equals(value, ignoreCase = true) ||
            value.equals("AnyConnect", ignoreCase = true) ||
            value.equals("ocserv", ignoreCase = true)
        } ?: AMNEZIA_WG
    }
}
