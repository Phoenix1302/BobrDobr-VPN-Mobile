package org.bobrdobr.vpn.core.service

/**
 * Socket protection callback interface.
 * Implemented by [VpnTunnelService] to invoke [android.net.VpnService.protect].
 *
 * Native protocol engines (Rust AmneziaWG and Go OpenConnect) invoke this callback
 * synchronously whenever a new transport socket (UDP or TCP) is allocated,
 * before calling connect() or sending traffic. This prevents routing loops
 * where tunnel packets would otherwise be routed back into the TUN interface.
 */
fun interface SocketProtector {
    /**
     * Protects the given socket file descriptor from being routed through the VPN tunnel.
     *
     * @param socketFd Raw OS file descriptor of the socket.
     * @return true if the socket was successfully marked and protected by Android netd, false otherwise.
     */
    fun protectSocket(socketFd: Int): Boolean
}
