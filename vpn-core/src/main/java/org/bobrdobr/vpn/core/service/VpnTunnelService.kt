package org.bobrdobr.vpn.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.bobrdobr.vpn.core.R
import org.bobrdobr.vpn.core.engine.NativeEngineController
import org.bobrdobr.vpn.core.model.TunnelConfig
import org.bobrdobr.vpn.core.model.TunnelStats
import org.bobrdobr.vpn.core.model.VpnProtocol
import org.bobrdobr.vpn.core.model.VpnState
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * BobrDobr VPN Core Tunnel Service.
 *
 * Implements Android [VpnService] and [SocketProtector].
 * Manages foreground service lifecycle, notification updates, TUN descriptor capture via
 * ParcelFileDescriptor.detachFd(), seamless network roaming without TUN re-creation,
 * and routing loop prevention via VpnService.protect(fd).
 */
class VpnTunnelService : VpnService(), SocketProtector {

    companion object {
        private const val TAG = "BobrDobr_VpnService"

        const val ACTION_CONNECT = "org.bobrdobr.vpn.core.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "org.bobrdobr.vpn.core.ACTION_DISCONNECT"
        const val ACTION_SET_UI_VISIBILITY = "org.bobrdobr.vpn.core.ACTION_SET_UI_VISIBILITY"
        const val EXTRA_CONFIG = "org.bobrdobr.vpn.core.EXTRA_CONFIG"
        const val EXTRA_UI_VISIBLE = "org.bobrdobr.vpn.core.EXTRA_UI_VISIBLE"

        private const val NOTIFICATION_ID = 1337
        private const val CHANNEL_ID = "bobrdobr_vpn_dam_channel"

        private val _vpnState = MutableStateFlow<VpnState>(VpnState.Disconnected)
        val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()

        private val _tunnelStats = MutableStateFlow(TunnelStats.EMPTY)
        val tunnelStats: StateFlow<TunnelStats> = _tunnelStats.asStateFlow()

        private val _isUiVisible = MutableStateFlow(true)
        val isUiVisible: StateFlow<Boolean> = _isUiVisible.asStateFlow()

        /**
         * Control whether UI is visible to optimize battery consumption by pausing ping monitoring.
         */
        fun setUiVisibility(context: Context, isVisible: Boolean) {
            _isUiVisible.value = isVisible
            try {
                val intent = Intent(context, VpnTunnelService::class.java).apply {
                    action = ACTION_SET_UI_VISIBILITY
                    putExtra(EXTRA_UI_VISIBLE, isVisible)
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.d(TAG, "VpnTunnelService not running or cannot startService for UI visibility: ${e.message}")
            }
        }

        /**
         * Helper method to start the VPN service.
         */
        fun start(context: Context, config: TunnelConfig) {
            val intent = Intent(context, VpnTunnelService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_CONFIG, config.toJson())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Helper method to stop the VPN service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, VpnTunnelService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var notificationManager: NotificationManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var engineController: NativeEngineController

    private var activeConfig: TunnelConfig? = null
    private var tunPfd: ParcelFileDescriptor? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var pingMonitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VpnTunnelService onCreate()")

        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        createNotificationChannel()

        engineController = NativeEngineController(
            scope = serviceScope,
            onStateChanged = { state ->
                serviceScope.launch {
                    _vpnState.value = state
                    updateNotification(state)
                }
            },
            onStatsUpdated = { stats ->
                _tunnelStats.value = stats
            }
        )

        registerNetworkMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        Log.i(TAG, "onStartCommand action: $action")

        when (action) {
            ACTION_CONNECT -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG)
                if (configJson.isNullOrEmpty()) {
                    Log.e(TAG, "Connect intent missing EXTRA_CONFIG")
                    stopSelf()
                    return START_NOT_STICKY
                }

                val config = try {
                    TunnelConfig.fromJson(configJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse TunnelConfig", e)
                    stopSelf()
                    return START_NOT_STICKY
                }

                activeConfig = config
                startForegroundNotification(VpnState.Connecting(config.protocol, config.serverName))

                serviceScope.launch(Dispatchers.IO) {
                    try {
                        establishTunnel(config)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to establish tunnel", e)
                        _vpnState.value = VpnState.Error(
                            message = e.message ?: "Failed to establish TUN interface",
                            protocol = config.protocol,
                            throwable = e,
                            canRetry = true
                        )
                        updateNotification(_vpnState.value)
                    }
                }
            }

            ACTION_DISCONNECT -> {
                Log.i(TAG, "Received ACTION_DISCONNECT. Graceful shutdown requested.")
                stopTunnelGracefully()
            }

            ACTION_SET_UI_VISIBILITY -> {
                val visible = intent.getBooleanExtra(EXTRA_UI_VISIBLE, true)
                _isUiVisible.value = visible
                Log.d(TAG, "Received ACTION_SET_UI_VISIBILITY: isUiVisible=$visible")
            }
        }

        return START_NOT_STICKY
    }

    /**
     * Creates and configures the Android TUN interface using [VpnService.Builder],
     * detaches the integer file descriptor via [ParcelFileDescriptor.detachFd],
     * and delegates control to [NativeEngineController].
     */
    @Synchronized
    private fun establishTunnel(config: TunnelConfig) {
        Log.i(TAG, "Establishing TUN interface for server: ${config.serverName} (${config.protocol.displayName})...")

        var effectiveConfig = config
        var effectiveRawJson = config.rawConfigJson

        // Pre-resolve server hostname to IPv4 address BEFORE establish()
        // so native code does not fail when physical DNS queries are captured by TUN
        val resolvedServerIp = try {
            if (config.serverAddress.isNotBlank() && !config.serverAddress.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                val addrs = InetAddress.getAllByName(config.serverAddress)
                val ipv4 = addrs.firstOrNull { it is Inet4Address }?.hostAddress
                if (!ipv4.isNullOrBlank()) {
                    Log.i(TAG, "Pre-resolved serverAddress '${config.serverAddress}' -> $ipv4")
                    ipv4
                } else null
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Could not pre-resolve '${config.serverAddress}': ${e.message}")
            null
        }

        // For AmneziaWG: check Endpoint in config.rawConfigJson
        if (config.protocol == VpnProtocol.AMNEZIA_WG && !resolvedServerIp.isNullOrBlank()) {
            try {
                val endpointRegex = Regex("(?i)(Endpoint\\s*=\\s*)([^:\\s]+)(:\\d+)")
                if (endpointRegex.containsMatchIn(effectiveRawJson)) {
                    effectiveRawJson = endpointRegex.replace(effectiveRawJson) { mr ->
                        val host = mr.groupValues[2]
                        if (!host.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                            "${mr.groupValues[1]}$resolvedServerIp${mr.groupValues[3]}"
                        } else {
                            mr.value
                        }
                    }
                    Log.i(TAG, "Replaced AmneziaWG Endpoint domain with resolved IP $resolvedServerIp")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error rewriting AmneziaWG Endpoint: ${e.message}")
            }
        }

        if (resolvedServerIp != null) {
            effectiveConfig = effectiveConfig.copy(
                serverAddress = resolvedServerIp,
                rawConfigJson = effectiveRawJson
            )
        } else if (effectiveRawJson != config.rawConfigJson) {
            effectiveConfig = effectiveConfig.copy(rawConfigJson = effectiveRawJson)
        }

        val builder = Builder().apply {
            setSession("БобрДобр VPN: ${effectiveConfig.serverName}")
            setMtu(effectiveConfig.mtu)

            // IPv4 virtual address
            try {
                addAddress(effectiveConfig.ipv4Address, effectiveConfig.ipv4Prefix)
            } catch (e: Exception) {
                Log.e(TAG, "Invalid IPv4 address: ${effectiveConfig.ipv4Address}/${effectiveConfig.ipv4Prefix}", e)
                throw e
            }

            // Optional IPv6 virtual address
            if (!effectiveConfig.ipv6Address.isNullOrEmpty()) {
                try {
                    addAddress(effectiveConfig.ipv6Address, effectiveConfig.ipv6Prefix)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not add IPv6 address ${effectiveConfig.ipv6Address}: ${e.message}")
                }
            }

            // DNS Servers: Enforce DNS specified by configuration (critical for server-side domain routing)
            val effectiveDns = if (effectiveConfig.dnsServers.isNotEmpty()) {
                Log.i(TAG, "Strictly applying server-side DNS from TunnelConfig: ${effectiveConfig.dnsServers}")
                effectiveConfig.dnsServers
            } else {
                Log.w(TAG, "No DNS specified in config, falling back to 1.1.1.1")
                listOf("1.1.1.1")
            }

            for (dns in effectiveDns) {
                try {
                    addDnsServer(dns)
                    Log.d(TAG, "Successfully added DNS server: $dns")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to add DNS server $dns", e)
                }
            }

            // Routes (Default 0.0.0.0/0 and ::/0 for full tunnel)
            for (route in effectiveConfig.routes) {
                try {
                    addRoute(route.address, route.prefix)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to add route $route", e)
                }
            }

            // Per-App Split Tunneling: Whitelist
            for (pkg in effectiveConfig.allowedApplications) {
                try {
                    addAllowedApplication(pkg)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to add allowed application $pkg", e)
                }
            }

            // Per-App Split Tunneling: Blacklist (Bypass)
            for (pkg in effectiveConfig.disallowedApplications) {
                try {
                    addDisallowedApplication(pkg)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to add disallowed application $pkg", e)
                }
            }

            // Non-blocking mode for efficient async epoll / mio packet loop
            setBlocking(false)
        }

        val pfd = builder.establish()
            ?: throw IllegalStateException("VpnService.Builder.establish() returned null. Is VPN permission granted?")

        tunPfd = pfd

        // Detach integer fd for native engine ownership
        val rawFd = pfd.detachFd()
        Log.i(TAG, "Successfully captured TUN file descriptor: $rawFd")

        // Launch native engine
        val startResult = engineController.startTunnel(effectiveConfig, rawFd, this)
        if (startResult.isFailure) {
            val err = startResult.exceptionOrNull()?.message ?: "Native engine failed to start"
            throw IllegalStateException(err)
        }

        _vpnState.value = VpnState.Connected(effectiveConfig.protocol, effectiveConfig.serverName)
        updateNotification(_vpnState.value)

        startPingMonitor(effectiveConfig)
    }

    /**
     * Implements [SocketProtector.protectSocket].
     * Invokes [android.net.VpnService.protect] to ensure outer transport sockets bypass the VPN routing table.
     */
    override fun protectSocket(socketFd: Int): Boolean {
        if (socketFd < 0) return false
        val success = protect(socketFd)
        Log.d(TAG, "protectSocket(fd=$socketFd) -> $success")
        return success
    }

    /**
     * Monitors default physical networks for zero-interruption roaming (e.g. Wi-Fi <-> Cellular).
     */
    private fun registerNetworkMonitor() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Physical network available: $network (handle=${network.networkHandle})")
                handleNetworkHandoff(network)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                ) {
                    Log.d(TAG, "Physical network capabilities changed: $network")
                    handleNetworkHandoff(network)
                }
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Physical network lost: $network")
            }
        }

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
            Log.i(TAG, "Registered physical network monitor")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NetworkCallback", e)
        }
    }

    private fun handleNetworkHandoff(network: Network) {
        if (_vpnState.value is VpnState.Connected) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                setUnderlyingNetworks(arrayOf(network))
            }
            engineController.updateNetwork(network.networkHandle)
        }
    }

    private fun stopTunnelGracefully() {
        stopPingMonitor()
        _vpnState.value = VpnState.Disconnecting(activeConfig?.protocol)
        updateNotification(_vpnState.value)

        serviceScope.launch(Dispatchers.IO) {
            try {
                engineController.stopTunnel()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping native engine", e)
            }

            try {
                tunPfd?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing ParcelFileDescriptor", e)
            }
            tunPfd = null
            activeConfig = null

            _vpnState.value = VpnState.Disconnected
            _tunnelStats.value = TunnelStats.EMPTY

            serviceScope.launch(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "VpnTunnelService onDestroy()")

        stopPingMonitor()

        try {
            networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering network callback", e)
        }

        engineController.stopTunnel()

        try {
            tunPfd?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ParcelFileDescriptor in onDestroy", e)
        }

        _vpnState.value = VpnState.Disconnected
        _tunnelStats.value = TunnelStats.EMPTY
        serviceScope.cancel()

        super.onDestroy()
    }

    private fun startPingMonitor(config: TunnelConfig) {
        stopPingMonitor()
        pingMonitorJob = serviceScope.launch(Dispatchers.IO) {
            // Determine gateway address inside tunnel:
            // 1. First DNS server from config (e.g. 10.8.0.1)
            // 2. Or replace last octet of client IPv4 with .1 (e.g. 10.8.0.2 -> 10.8.0.1)
            val gatewayIp = config.dnsServers.firstOrNull() ?: run {
                val parts = config.ipv4Address.split(".")
                if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.1" else "10.8.0.1"
            }

            Log.i(TAG, "Starting In-Tunnel gateway ping monitor to $gatewayIp")

            while (isActive) {
                delay(3000L)
                if (_vpnState.value !is VpnState.Connected) break

                // Battery optimization: pause pinging when UI is in background
                if (!_isUiVisible.value) {
                    continue
                }

                val startTime = SystemClock.elapsedRealtime()
                var rtt = -1L
                try {
                    Socket().use { s ->
                        s.soTimeout = 1200
                        s.connect(InetSocketAddress(gatewayIp, 53), 1200)
                        rtt = SystemClock.elapsedRealtime() - startTime
                    }
                } catch (e: java.net.ConnectException) {
                    // RST packet from gateway means packet traveled through tunnel and returned - valid RTT
                    rtt = SystemClock.elapsedRealtime() - startTime
                } catch (_: Exception) {}

                if (rtt in 1..2000) {
                    _tunnelStats.value = _tunnelStats.value.copy(pingMs = rtt)
                }
            }
        }
    }

    private fun stopPingMonitor() {
        pingMonitorJob?.cancel()
        pingMonitorJob = null
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked by system or user")
        stopTunnelGracefully()
        super.onRevoke()
    }

    // --- Notification & Foreground Service Management ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.vpn_channel_desc)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification(state: VpnState) {
        val notification = buildNotification(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: VpnState) {
        val notification = buildNotification(state)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(state: VpnState): Notification {
        val serverName = activeConfig?.serverName ?: "Сервер"

        val (contentTitle, contentText) = when (state) {
            is VpnState.Connected -> Pair(
                "БобрДобр VPN: Плотина под защитой",
                getString(R.string.vpn_status_connected, "${state.protocol.displayName} - $serverName")
            )
            is VpnState.Connecting -> Pair(
                "БобрДобр VPN: Возведение плотины…",
                getString(R.string.vpn_status_connecting, serverName)
            )
            is VpnState.Disconnecting -> Pair(
                "БобрДобр VPN: Открытие шлюзов…",
                getString(R.string.vpn_status_disconnecting)
            )
            is VpnState.Reconnecting -> Pair(
                "БобрДобр VPN: Укрепление плотины…",
                getString(R.string.vpn_status_reconnecting, serverName)
            )
            is VpnState.Error -> Pair(
                "БобрДобр VPN: Прорыв плотины!",
                getString(R.string.vpn_status_error, state.message)
            )
            is VpnState.Disconnected -> Pair(
                "БобрДобр VPN: Шлюзы открыты",
                getString(R.string.vpn_status_disconnected)
            )
        }

        val disconnectIntent = Intent(this, VpnTunnelService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            0,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Launch app activity when clicking notification
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val iconRes = applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_lock_lock

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setContentIntent(contentPendingIntent)
            .setOngoing(state is VpnState.Connected || state is VpnState.Connecting)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (state is VpnState.Connected || state is VpnState.Connecting) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.vpn_action_disconnect),
                disconnectPendingIntent
            )
        }

        return builder.build()
    }
}
