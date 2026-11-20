package org.bobrdobr.vpn.core.engine

import org.bobrdobr.vpn.core.model.TunnelStats
import org.bobrdobr.vpn.core.service.SocketProtector

/**
 * Unified interface for pluggable native VPN engines (Rust AmneziaWG vs Go OpenConnect).
 */
interface IVpnEngine {
    /**
     * Starts the native protocol tunnel on the provided TUN descriptor (or via dynamic 2-phase config).
     *
     * @param tunFd File descriptor detached from Android ParcelFileDescriptor, or -1 for dynamic creation.
     * @param configJson Protocol configuration string (keys, obfuscation headers, endpoints).
     * @param socketProtector Callback to protect transport sockets from routing loops.
     * @param tunConfigCallback Optional dynamic TUN creation callback (used by Go OpenConnect).
     * @return Result.success if initiated properly, Result.failure on error.
     */
    fun start(
        tunFd: Int,
        configJson: String,
        socketProtector: SocketProtector,
        tunConfigCallback: JniBridge.TunConfigCallback? = null
    ): Result<Unit>

    /**
     * Gracefully stops the native tunnel, releasing sockets, ending threads, and sending disconnect notices.
     */
    fun stop(): Result<Unit>

    /**
     * Updates the physical network handle without tearing down the TUN descriptor.
     *
     * @param netId Android Network.getNetworkHandle() value or 0 for system default.
     */
    fun updateNetwork(netId: Long): Result<Unit>

    /**
     * Queries current instantaneous traffic metrics and throughput from the native engine.
     */
    fun getStats(): TunnelStats
}
