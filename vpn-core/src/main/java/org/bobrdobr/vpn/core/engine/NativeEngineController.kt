package org.bobrdobr.vpn.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import org.bobrdobr.vpn.core.logger.DebugLogger
import org.bobrdobr.vpn.core.model.TunnelConfig
import org.bobrdobr.vpn.core.model.TunnelStats
import org.bobrdobr.vpn.core.model.VpnProtocol
import org.bobrdobr.vpn.core.model.VpnState
import org.bobrdobr.vpn.core.service.SocketProtector

/**
 * Controller and orchestrator for pluggable native VPN engines.
 * Selects between [RustVpnEngine] (AmneziaWG/WireGuard) and [GoVpnEngine] (OpenConnect),
 * dispatches JNI operations, monitors telemetry, and translates status callbacks.
 */
class NativeEngineController(
    private val scope: CoroutineScope,
    private val onStateChanged: (VpnState) -> Unit,
    private val onStatsUpdated: (TunnelStats) -> Unit
) {
    companion object {
        private const val TAG = "BobrDobr_EngineCtrl"
        private const val STATS_INTERVAL_MS = 1000L
    }

    private var activeEngine: IVpnEngine? = null
    private var activeConfig: TunnelConfig? = null
    private var statsJob: Job? = null
    private val lock = Any()
    private val sessionGeneration = AtomicLong(0)

    private fun statusListenerFor(generation: Long) = JniBridge.StatusListener { statusCode, message ->
        val currentProtocol: VpnProtocol
        val currentServer: String?
        synchronized(lock) {
            if (generation != sessionGeneration.get() || activeEngine == null) {
                DebugLogger.d(TAG, "Ignoring callback from stale engine generation=$generation code=$statusCode")
                return@StatusListener
            }
            currentProtocol = activeConfig?.protocol ?: VpnProtocol.AMNEZIA_WG
            currentServer = activeConfig?.serverName
        }
        val statusName = JniBridge.statusToString(statusCode)
        DebugLogger.i(TAG, "Native status update callback: code=$statusCode ($statusName), message='$message'")

        val newState = when (statusCode) {
            JniBridge.STATUS_DISCONNECTED -> VpnState.Disconnected
            JniBridge.STATUS_CONNECTING -> VpnState.Connecting(currentProtocol, currentServer)
            JniBridge.STATUS_CONNECTED -> VpnState.Connected(currentProtocol, currentServer)
            JniBridge.STATUS_DISCONNECTING -> VpnState.Disconnecting(currentProtocol)
            JniBridge.STATUS_RECONNECTING -> VpnState.Reconnecting(currentProtocol, reason = message)
            JniBridge.STATUS_ERROR -> VpnState.Error(
                message = message,
                protocol = currentProtocol,
                errorCode = statusCode,
                canRetry = true
            )
            else -> VpnState.Disconnected
        }
        if (generation != sessionGeneration.get()) {
            DebugLogger.d(TAG, "Ignoring stale callback before dispatch: generation=$generation current=${sessionGeneration.get()}")
            return@StatusListener
        }
        onStateChanged(newState)
    }

    /**
     * Starts the appropriate native VPN engine for the given tunnel configuration.
     *
     * @param config Tunnel configuration.
     * @param tunFd File descriptor detached from ParcelFileDescriptor.
     * @param protector Outer socket protection callback.
     */
    fun startTunnel(
        config: TunnelConfig,
        tunFd: Int,
        protector: SocketProtector,
        tunConfigCallback: JniBridge.TunConfigCallback? = null
    ): Result<Unit> {
        DebugLogger.i(TAG, "startTunnel requested: protocol=${config.protocol.displayName}, server='${config.serverName}', tunFd=$tunFd")

        // Stop any currently running engine first WITHOUT holding lock to prevent deadlocks
        stopInternal()

        val generation = sessionGeneration.incrementAndGet()
        val engine: IVpnEngine

        synchronized(lock) {
            activeConfig = config
            engine = when (config.protocol) {
                VpnProtocol.AMNEZIA_WG, VpnProtocol.WIREGUARD -> {
                    DebugLogger.i(TAG, "Instantiating RustVpnEngine for ${config.protocol.displayName}")
                    RustVpnEngine(statusListenerFor(generation))
                }
                VpnProtocol.OPENCONNECT -> {
                    DebugLogger.i(TAG, "Instantiating GoVpnEngine for ${config.protocol.displayName}")
                    GoVpnEngine(statusListenerFor(generation))
                }
            }

            activeEngine = engine
        }

        // Native start may synchronously invoke a JNI callback. Do not hold lock
        // while entering native code, otherwise the callback can deadlock here.
        val result = engine.start(tunFd, config.rawConfigJson, protector, tunConfigCallback)
        var staleStart = false
        synchronized(lock) {
            if (generation != sessionGeneration.get() || activeEngine !== engine) {
                DebugLogger.w(TAG, "Discarding start result from stale engine generation=$generation")
                staleStart = true
            } else {
            if (result.isSuccess) {
                DebugLogger.i(TAG, "Native engine started successfully. Commencing stats polling.")
                startStatsPolling()
            } else {
                val err = result.exceptionOrNull()?.message ?: "Unknown engine start failure"
                DebugLogger.e(TAG, "Failed to start native engine: $err", result.exceptionOrNull())
                activeEngine = null
                activeConfig = null
            }
            }
        }
        if (staleStart && result.isSuccess) engine.stop()
        return result
    }

    /**
     * Gracefully stops the active native tunnel and ends telemetry collection.
     */
    fun stopTunnel(): Result<Unit> {
        return stopInternal()
    }

    private fun stopInternal(): Result<Unit> {
        stopStatsPolling()
        val engine: IVpnEngine?
        synchronized(lock) {
            sessionGeneration.incrementAndGet()
            engine = activeEngine
            activeEngine = null
            activeConfig = null
        }
        if (engine == null) return Result.success(Unit)
        DebugLogger.i(TAG, "Stopping active engine (${engine::class.java.simpleName})...")
        // Calling engine.stop() OUTSIDE synchronized(lock) avoids deadlocks with worker thread callbacks
        val res = engine.stop()
        return res
    }

    /**
     * Updates physical network handle (e.g. on Wi-Fi <-> 4G/5G handoff).
     */
    fun updateNetwork(netId: Long): Result<Unit> = synchronized(lock) {
        val engine = activeEngine ?: return Result.failure(IllegalStateException("No active engine"))
        DebugLogger.i(TAG, "Dispatching updateNetwork(netId=$netId) to ${engine::class.java.simpleName}")
        return engine.updateNetwork(netId)
    }

    /**
     * Retrieves instantaneous telemetry counters from the engine.
     */
    fun getStats(): TunnelStats = synchronized(lock) {
        return activeEngine?.getStats() ?: TunnelStats.EMPTY
    }

    private fun startStatsPolling() {
        stopStatsPolling()
        statsJob = scope.launch(Dispatchers.Default) {
            val initialEngine = synchronized(lock) { activeEngine }
            var prevStats = initialEngine?.getStats() ?: TunnelStats.EMPTY
            var prevTime = System.currentTimeMillis()
            var hasEmittedFirst = false

            while (isActive) {
                if (!hasEmittedFirst) {
                    onStatsUpdated(prevStats)
                    hasEmittedFirst = true
                }
                delay(STATS_INTERVAL_MS)
                val engine = synchronized(lock) { activeEngine } ?: break
                val rawStats = engine.getStats()
                val now = System.currentTimeMillis()
                val deltaSec = (now - prevTime) / 1000.0

                val calculatedDown = if (deltaSec > 0 && rawStats.rxBytes >= prevStats.rxBytes) {
                    ((rawStats.rxBytes - prevStats.rxBytes) / deltaSec).toLong()
                } else 0L

                val calculatedUp = if (deltaSec > 0 && rawStats.txBytes >= prevStats.txBytes) {
                    ((rawStats.txBytes - prevStats.txBytes) / deltaSec).toLong()
                } else 0L

                // If native engine provides instantaneous speed in bps (bits per sec), convert to bytes/sec
                val nativeDownBytes = if (rawStats.rxSpeedBytesPerSec > 0) rawStats.rxSpeedBytesPerSec / 8 else 0L
                val nativeUpBytes = if (rawStats.txSpeedBytesPerSec > 0) rawStats.txSpeedBytesPerSec / 8 else 0L

                val finalDown = if (calculatedDown > 0) calculatedDown else nativeDownBytes
                val finalUp = if (calculatedUp > 0) calculatedUp else nativeUpBytes

                val computedStats = rawStats.copy(
                    rxSpeedBytesPerSec = finalDown,
                    txSpeedBytesPerSec = finalUp
                )

                prevStats = rawStats
                prevTime = now

                onStatsUpdated(computedStats)
            }
        }
    }

    private fun stopStatsPolling() {
        statsJob?.cancel()
        statsJob = null
    }
}
