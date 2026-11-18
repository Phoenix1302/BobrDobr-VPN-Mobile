package org.bobrdobr.vpn.core.engine

import android.util.Log
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
            Log.i(TAG, "Successfully loaded libvpn_core_jni.so")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libvpn_core_jni.so: ${e.message}")
            isLibraryLoaded = false
        }
    }

    /**
     * Interface for receiving status transitions and errors from native engines.
     */
    fun interface StatusListener {
        fun onStatusChanged(statusCode: Int, message: String)
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
     */
    @JvmStatic
    external fun nativeStartTunnel(
        tunFd: Int,
        configJson: String,
        protector: SocketProtector,
        statusListener: StatusListener?
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
