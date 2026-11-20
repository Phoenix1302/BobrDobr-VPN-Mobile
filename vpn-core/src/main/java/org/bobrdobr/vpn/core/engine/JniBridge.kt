package org.bobrdobr.vpn.core.engine

import android.util.Log
import org.bobrdobr.vpn.core.logger.DebugLogger
import org.bobrdobr.vpn.core.model.TunnelStats
import org.bobrdobr.vpn.core.service.SocketProtector

/**
 * Direct JNI bridge to the C-ABI engine API (`libvpn_core_jni.so`).
 */
object JniBridge {
    private const val TAG = "BobrDobr_JniBridge"

    const val STATUS_DISCONNECTED = 0
    const val STATUS_CONNECTING = 1
    const val STATUS_CONNECTED = 2
    const val STATUS_DISCONNECTING = 3
    const val STATUS_RECONNECTING = 4
    const val STATUS_ERROR = 5

    const val ENGINE_SUCCESS = 0
    const val ENGINE_ERR_GENERIC = -1
    const val ENGINE_ERR_INVALID_ARG = -2
    const val ENGINE_ERR_PROTECT_FAILED = -3
    const val ENGINE_ERR_SOCKET_INIT = -4
    const val ENGINE_ERR_HANDSHAKE = -5
    const val ENGINE_ERR_INVALID_CONFIG = -6
    const val ENGINE_ERR_ALREADY_RUNNING = -7
    const val ENGINE_ERR_NOT_RUNNING = -8

    var isLibraryLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("vpn_core_jni")
            isLibraryLoaded = true
            DebugLogger.i(TAG, "Successfully loaded libvpn_core_jni.so")
        } catch (e: UnsatisfiedLinkError) {
            DebugLogger.e(TAG, "Failed to load libvpn_core_jni.so: ${e.message}", e)
            isLibraryLoaded = false
        }
    }

    fun statusToString(code: Int): String = when (code) {
        STATUS_DISCONNECTED -> "DISCONNECTED (0)"
        STATUS_CONNECTING -> "CONNECTING (1)"
        STATUS_CONNECTED -> "CONNECTED (2)"
        STATUS_DISCONNECTING -> "DISCONNECTING (3)"
        STATUS_RECONNECTING -> "RECONNECTING (4)"
        STATUS_ERROR -> "ERROR (5)"
        else -> "UNKNOWN ($code)"
    }

    fun errorCodeToString(code: Int): String = when (code) {
        ENGINE_SUCCESS -> "SUCCESS (0)"
        ENGINE_ERR_GENERIC -> "GENERIC_ERROR (-1)"
        ENGINE_ERR_INVALID_ARG -> "INVALID_ARGUMENT (-2)"
        ENGINE_ERR_PROTECT_FAILED -> "PROTECT_SOCKET_FAILED (-3)"
        ENGINE_ERR_SOCKET_INIT -> "SOCKET_INIT_FAILED (-4)"
        ENGINE_ERR_HANDSHAKE -> "HANDSHAKE_FAILED (-5)"
        ENGINE_ERR_INVALID_CONFIG -> "INVALID_CONFIG (-6)"
        ENGINE_ERR_ALREADY_RUNNING -> "ALREADY_RUNNING (-7)"
        ENGINE_ERR_NOT_RUNNING -> "NOT_RUNNING (-8)"
        else -> "UNKNOWN_ERROR ($code)"
    }

    /**
     * Interface for receiving status transitions and errors from native engines.
     */
    fun interface StatusListener {
        fun onStatusChanged(statusCode: Int, message: String)
    }

    /**
     * Interface for dynamic 2-phase TUN creation callback (e.g. OpenConnect CSTP negotiated config).
     */
    fun interface TunConfigCallback {
        /**
         * Invoked by native engine with negotiated parameters JSON (IP, netmask, DNS, MTU, routes).
         * @return Captured integer TUN file descriptor, or -1 on error.
         */
        fun onTunConfigReady(configJson: String): Int
    }

    /**
     * Dynamically opens and binds symbols from the specified native library.
     *
     * @param libName Name of the library, e.g. "libengine_rust.so" or "libengine_go.so".
     */
    @JvmStatic
    external fun nativeInitEngine(libName: String): Int

    /**
     * Starts the native protocol tunnel.
     *
     * @param tunFd File descriptor detached from Android ParcelFileDescriptor, or -1 for 2-phase negotiation.
     * @param configJson Protocol configuration string in JSON format.
     * @param protector Socket protection callback to prevent routing loops.
     * @param statusListener Status reporting callback.
     * @param tunConfigCallback Dynamic 2-phase TUN creation callback (optional).
     */
    @JvmStatic
    external fun nativeStartTunnel(
        tunFd: Int,
        configJson: String,
        protector: SocketProtector,
        statusListener: StatusListener?,
        tunConfigCallback: TunConfigCallback? = null
    ): Int

    /**
     * Stops the active native tunnel.
     */
    @JvmStatic
    external fun nativeStopTunnel(): Int

    /**
     * Updates underlying network handle for zero-drop roaming.
     */
    @JvmStatic
    external fun nativeUpdateNetwork(netId: Long): Int

    /**
     * Queries current instantaneous traffic metrics from native engine.
     */
    @JvmStatic
    external fun nativeGetTunnelStats(): TunnelStats?
}
