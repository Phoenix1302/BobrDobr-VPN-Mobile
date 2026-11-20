package org.bobrdobr.vpn.core.engine

import org.bobrdobr.vpn.core.logger.DebugLogger
import org.bobrdobr.vpn.core.model.TunnelStats
import org.bobrdobr.vpn.core.service.SocketProtector

/**
 * Implementation of [IVpnEngine] delegating to the high-performance,
 * zero-allocation Rust engine (`libengine_rust.so`) for AmneziaWG and WireGuard.
 */
class RustVpnEngine(
    private val statusListener: JniBridge.StatusListener? = null
) : IVpnEngine {

    companion object {
        private const val TAG = "BobrDobr_RustEngine"
        private const val LIB_NAME = "libengine_rust.so"
    }

    private var isRunning: Boolean = false

    override fun start(
        tunFd: Int,
        configJson: String,
        socketProtector: SocketProtector,
        tunConfigCallback: JniBridge.TunConfigCallback?
    ): Result<Unit> {
        DebugLogger.i(TAG, "Initializing Rust VPN Engine ($LIB_NAME)...")

        val initRes = JniBridge.nativeInitEngine(LIB_NAME)
        if (initRes != JniBridge.ENGINE_SUCCESS) {
            val err = "Failed to load/initialize $LIB_NAME: ${JniBridge.errorCodeToString(initRes)}"
            DebugLogger.e(TAG, err)
            return Result.failure(IllegalStateException(err))
        }

        DebugLogger.i(TAG, "Starting Rust tunnel: tunFd=$tunFd, configLength=${configJson.length} bytes...")
        val startRes = JniBridge.nativeStartTunnel(tunFd, configJson, socketProtector, statusListener, tunConfigCallback)
        if (startRes != JniBridge.ENGINE_SUCCESS) {
            val err = "Rust engine failed to start tunnel: ${JniBridge.errorCodeToString(startRes)}"
            DebugLogger.e(TAG, err)
            return Result.failure(IllegalStateException(err))
        }

        isRunning = true
        DebugLogger.i(TAG, "Rust VPN Engine started successfully. Tunnel is active.")
        return Result.success(Unit)
    }

    override fun stop(): Result<Unit> {
        DebugLogger.i(TAG, "Stopping Rust VPN Engine...")
        isRunning = false
        val res = JniBridge.nativeStopTunnel()
        return if (res == JniBridge.ENGINE_SUCCESS) {
            DebugLogger.i(TAG, "Rust VPN Engine stopped successfully")
            Result.success(Unit)
        } else {
            val err = "Rust engine stop reported error: ${JniBridge.errorCodeToString(res)}"
            DebugLogger.w(TAG, err)
            Result.failure(IllegalStateException(err))
        }
    }

    override fun updateNetwork(netId: Long): Result<Unit> {
        DebugLogger.d(TAG, "Updating network in Rust VPN Engine to netId=$netId")
        val res = JniBridge.nativeUpdateNetwork(netId)
        return if (res == JniBridge.ENGINE_SUCCESS) {
            Result.success(Unit)
        } else {
            val err = "Failed to update network in Rust engine: ${JniBridge.errorCodeToString(res)}"
            DebugLogger.e(TAG, err)
            Result.failure(IllegalStateException(err))
        }
    }

    override fun getStats(): TunnelStats {
        return JniBridge.nativeGetTunnelStats() ?: TunnelStats.EMPTY
    }
}
