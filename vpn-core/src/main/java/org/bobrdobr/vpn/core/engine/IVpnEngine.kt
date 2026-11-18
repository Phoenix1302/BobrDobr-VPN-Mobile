package org.bobrdobr.vpn.core.engine

import org.bobrdobr.vpn.core.model.TunnelStats
import org.bobrdobr.vpn.core.service.SocketProtector

/**
 * Unified interface for pluggable native VPN engines (Rust AmneziaWG vs Go OpenConnect).
 */
interface IVpnEngine {
    /**
     * Starts the native protocol tunnel on the provided TUN descriptor.
     *
     * @param tunFd File descriptor detached from Android ParcelFileDescriptor.
     * @param configJson Protocol configuration string (keys, obfuscation headers, endpoints).
     * @param socketProtector Callback to protect transport sockets from routing loops.
     * @return Result.success if initiated properly, Result.failure on error.
     */
    fun start(tunFd: Int, configJson: String, socketProtector: SocketProtector): Result<Unit>

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
