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
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bobrdobr.vpn.core.engine.JniBridge
import org.bobrdobr.vpn.core.engine.NativeEngineController
import org.bobrdobr.vpn.core.logger.DebugLogger
import org.bobrdobr.vpn.core.model.CidrRoute
import org.bobrdobr.vpn.core.model.TunnelConfig
import org.bobrdobr.vpn.core.model.TunnelStats
import org.bobrdobr.vpn.core.model.VpnProtocol
import org.bobrdobr.vpn.core.model.VpnState
import org.bobrdobr.vpn.core.routing.CidrUtils
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * BobrDobr VPN Core Tunnel Service.
 *
 * Implements Android [VpnService] and [SocketProtector].
 * Manages foreground service lifecycle, notification updates, TUN descriptor capture via
 * ParcelFileDescriptor.detachFd(), network-aware transport reconnection,
 * 2-phase dynamic TUN initialization for CSTP, and routing loop prevention via VpnService.protect(fd).
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

        private var _currentConfig: TunnelConfig? = null
        val currentConfig: TunnelConfig? get() = _currentConfig

        // Thread-safe in-memory cache for resolved server hostnames -> IPv4
        private val resolvedIpCache = ConcurrentHashMap<String, String>()

        /**
         * Control whether UI is visible to optimize battery consumption by pausing ping monitoring.
         * Updates in-memory StateFlow without redundant service starts.
         */
        fun setUiVisibility(context: Context, isVisible: Boolean) {
            _isUiVisible.value = isVisible
            DebugLogger.d(TAG, "setUiVisibility updated to $isVisible")
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
         * Helper method to stop the VPN service safely.
         */
        fun stop(context: Context) {
            if (_vpnState.value is VpnState.Disconnected) {
                DebugLogger.d(TAG, "stop() called but service is already Disconnected, ignoring")
                return
            }
            try {
                val intent = Intent(context, VpnTunnelService::class.java).apply {
                    action = ACTION_DISCONNECT
                }
                context.startService(intent)
            } catch (e: Exception) {
                DebugLogger.w(TAG, "Failed to send ACTION_DISCONNECT to service: ${e.message}")
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tunnelMutex = Mutex()
    private val sessionCounter = AtomicLong(0)

    private lateinit var notificationManager: NotificationManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var engineController: NativeEngineController

    private var activeConfig: TunnelConfig? = null
    private var currentPhysicalNetwork: Network? = null
    private val physicalNetworks = mutableMapOf<Network, NetworkCapabilities>()
    private var networkChangeJob: Job? = null
    private var connectionNetwork: Network? = null
    private var requestedConfig: TunnelConfig? = null

    private var tunPfd: ParcelFileDescriptor? = null
    private val activeTunRawFd = AtomicInteger(-1)

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var pingMonitorJob: Job? = null
    private var watchdogJob: Job? = null
    private var connectJob: Job? = null
    private var lastMeasuredPingMs: Long = 0L
    private var lastNotifiedState: VpnState? = null
    private var activeAssignedIpv4: String? = null
    private var isUserInitiatedStop = false
    private var activeAssignedPrefix: Int = 24

    override fun onCreate() {
        super.onCreate()
        DebugLogger.i(TAG, "VpnTunnelService onCreate() - initializing notification and native controllers")

        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        createNotificationChannel()

        engineController = NativeEngineController(
            scope = serviceScope,
            onStateChanged = { sessionId, state ->
                serviceScope.launch {
                    if (sessionId != sessionCounter.get()) return@launch
                    DebugLogger.i(TAG, "NativeEngineController onStateChanged: $state")
                    _vpnState.value = state
                    updateNotification(state)

                    if (state is VpnState.Connected) {
                        DebugLogger.i(TAG, "Native handshake confirmed. Starting ping monitor.")
                        setUnderlyingNetworks(connectionNetwork?.let { arrayOf(it) })
                        activeConfig?.let { startPingMonitor(it) }
                    } else if (state is VpnState.Disconnected || state is VpnState.Error) {
                        stopPingMonitor()
                        watchdogJob?.cancel()
                        watchdogJob = null
                        if (_vpnState.value !is VpnState.Connecting) {
                            closeTunResources()
                        }
                    }
                }
            },
            onStatsUpdated = { stats ->
                val effectivePing = if (stats.pingMs > 0) stats.pingMs else lastMeasuredPingMs
                _tunnelStats.value = stats.copy(pingMs = effectivePing)
            }
        )

        registerNetworkMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            DebugLogger.w(TAG, "onStartCommand intent is null (process restart by OS)")
            val cfg = requestedConfig
            if (!isUserInitiatedStop && cfg != null) {
                DebugLogger.i(TAG, "Restoring tunnel after process restart for ${cfg.serverName}")
                start(this, cfg)
                return START_STICKY
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val action = intent.action ?: return START_NOT_STICKY
        DebugLogger.i(TAG, "onStartCommand action: $action, flags=$flags, startId=$startId")

        when (action) {
            ACTION_CONNECT -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG)
                if (configJson.isNullOrEmpty()) {
                    DebugLogger.e(TAG, "Connect intent missing EXTRA_CONFIG payload. Stopping service.")
                    stopSelf()
                    return START_NOT_STICKY
                }

                val config = try {
                    TunnelConfig.fromJson(configJson)
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "Failed to parse TunnelConfig from JSON: ${e.message}", e)
                    stopSelf()
                    return START_NOT_STICKY
                }

                isUserInitiatedStop = false
                requestedConfig = config
                _currentConfig = config
                val network = currentPhysicalNetwork ?: connectivityManager.activeNetwork?.takeIf {
                    connectivityManager.getNetworkCapabilities(it)?.let(::isUsablePhysicalNetwork) == true
                }
                val state = VpnState.Connecting(config.protocol, config.serverName)
                _vpnState.value = state
                startForegroundNotification(state)
                launchConnection(config, network, reconnecting = false)
            }

            ACTION_DISCONNECT -> {
                DebugLogger.i(TAG, "Received ACTION_DISCONNECT. Commencing graceful tunnel shutdown.")
                isUserInitiatedStop = true
                connectJob?.cancel()
                connectJob = null
                stopTunnelGracefully()
            }

            ACTION_SET_UI_VISIBILITY -> {
                val visible = intent.getBooleanExtra(EXTRA_UI_VISIBLE, true)
                _isUiVisible.value = visible
                DebugLogger.d(TAG, "Received ACTION_SET_UI_VISIBILITY: isUiVisible=$visible")
            }
        }

        return if (_vpnState.value is VpnState.Connected || _vpnState.value is VpnState.Connecting ||
            _vpnState.value is VpnState.Reconnecting) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    /**
     * Pre-resolves server domain name into an IPv4 address using the active physical network,
     * bypassing the TUN interface to prevent DNS resolution deadlocks.
     */
    private suspend fun resolvePhysicalServerIp(host: String, activeNet: Network?): String? {
        if (host.isBlank()) return null
        if (host.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
            return host
        }

        DebugLogger.i(TAG, "Pre-resolving serverAddress '$host' via physical DNS...")

        for (attempt in 1..3) {
            // 1. Try querying DNS explicitly on the underlying physical network
            try {
                if (activeNet != null) {
                    val addrs = activeNet.getAllByName(host)
                    val ipv4 = addrs.firstOrNull { it is Inet4Address }?.hostAddress
                    if (!ipv4.isNullOrBlank()) {
                        DebugLogger.i(TAG, "Pre-resolved '$host' via active physical network -> $ipv4")
                        resolvedIpCache[host] = ipv4
                        return ipv4
                    }
                }
            } catch (e: Exception) {
                DebugLogger.d(TAG, "Pre-resolve attempt $attempt via physical network failed: ${e.message}")
            }

            // 2. Fallback to standard system DNS resolution
            try {
                val addrs = InetAddress.getAllByName(host)
                val ipv4 = addrs.firstOrNull { it is Inet4Address }?.hostAddress
                if (!ipv4.isNullOrBlank()) {
                    DebugLogger.i(TAG, "Pre-resolved '$host' via InetAddress -> $ipv4")
                    resolvedIpCache[host] = ipv4
                    return ipv4
                }
            } catch (e: Exception) {
                DebugLogger.d(TAG, "Pre-resolve attempt $attempt via InetAddress failed: ${e.message}")
            }

            if (attempt < 3) {
                delay(200L)
            }
        }

        // 3. Fallback to cache if network was transitioning
        val cached = resolvedIpCache[host]
        if (!cached.isNullOrBlank()) {
            DebugLogger.w(TAG, "Physical DNS lookup failed for '$host', using cached fallback IP: $cached")
            return cached
        }

        DebugLogger.e(TAG, "Failed to resolve IP for host '$host' and no cached IP available")
        return null
    }

    /**
     * Dynamic two-phase TUN initialization callback for CSTP (OpenConnect / AnyConnect).
     * Invoked from native engine thread when server delivers dynamic parameters.
     */
    private fun onTunConfigReady(jsonStr: String): Int {
        DebugLogger.i(TAG, "onTunConfigReady: received dynamic CSTP tunnel config: $jsonStr")
        val config = activeConfig ?: return -1
        return try {
            val json = org.json.JSONObject(jsonStr)
            val ipv4 = json.optString("ipv4", config.ipv4Address)
            val ipv4Prefix = json.optInt("ipv4_prefix", config.ipv4Prefix)
            val mtu = json.optInt("mtu", config.mtu).coerceIn(1280, 1500)

            activeAssignedIpv4 = ipv4
            activeAssignedPrefix = ipv4Prefix

            val dynamicDns = mutableListOf<String>()
            val dnsArray = json.optJSONArray("dns")
            if (dnsArray != null) {
                for (i in 0 until dnsArray.length()) {
                    val d = dnsArray.getString(i).trim()
                    if (d.isNotEmpty()) dynamicDns.add(d)
                }
            }
            val effectiveDns = if (dynamicDns.isNotEmpty()) dynamicDns else config.dnsServers.ifEmpty { listOf("1.1.1.1") }

            // Update activeConfig with dynamically negotiated parameters from CSTP
            val updatedConfig = config.copy(
                ipv4Address = ipv4,
                ipv4Prefix = ipv4Prefix,
                dnsServers = effectiveDns,
                mtu = mtu
            )
            activeConfig = updatedConfig

            val splitInclude = mutableListOf<CidrRoute>()
            val incArray = json.optJSONArray("split_include")
            if (incArray != null) {
                for (i in 0 until incArray.length()) {
                    val s = incArray.getString(i).trim()
                    val parts = s.split("/")
                    if (parts.size == 2) {
                        val prefix = parts[1].toIntOrNull() ?: 32
                        splitInclude.add(CidrRoute(parts[0], prefix))
                    }
                }
            }

            val splitExclude = mutableListOf<CidrRoute>()
            val excArray = json.optJSONArray("split_exclude")
            if (excArray != null) {
                for (i in 0 until excArray.length()) {
                    val s = excArray.getString(i).trim()
                    val parts = s.split("/")
                    if (parts.size == 2) {
                        val prefix = parts[1].toIntOrNull() ?: 32
                        splitExclude.add(CidrRoute(parts[0], prefix))
                    }
                }
            }

            val baseRoutes = if (splitInclude.isNotEmpty()) {
                splitInclude
            } else if (config.routes.isNotEmpty()) {
                config.routes
            } else {
                listOf(CidrRoute.DEFAULT_IPV4)
            }

            val combinedExcludes = splitExclude + config.bypassSubnets
            val finalRoutes = CidrUtils.subtractRouteLists(baseRoutes, combinedExcludes)

            val builder = Builder().apply {
                setSession("БобрДобр VPN: ${config.serverName}")
                setMtu(mtu)
                addAddress(ipv4, ipv4Prefix)

                for (dns in effectiveDns) {
                    try {
                        addDnsServer(dns)
                        DebugLogger.d(TAG, "Added dynamic DNS server: $dns")
                    } catch (e: Exception) {
                        DebugLogger.w(TAG, "Failed to add dynamic DNS server $dns: ${e.message}")
                    }
                }

                for (route in finalRoutes) {
                    try {
                        addRoute(route.address, route.prefix)
                    } catch (e: Exception) {
                        DebugLogger.w(TAG, "Failed to add dynamic route $route: ${e.message}")
                    }
                }

                // Explicit /32 host routes for active DNS
                for (dns in effectiveDns) {
                    try {
                        if (dns.contains(".")) {
                            addRoute(dns, 32)
                        } else if (dns.contains(":")) {
                            addRoute(dns, 128)
                        }
                    } catch (_: Exception) {}
                }

                // Explicit /32 host route for calculated in-tunnel gateway
                try {
                    val gw = calculateInTunnelGatewayIp(ipv4, ipv4Prefix)
                    addRoute(gw, 32)
                    DebugLogger.d(TAG, "Added explicit /32 route for in-tunnel gateway: $gw")
                } catch (e: Exception) {
                    DebugLogger.w(TAG, "Failed to add explicit route for gateway: ${e.message}")
                }

                for (pkg in config.allowedApplications) {
                    try { addAllowedApplication(pkg) } catch (_: Exception) {}
                }
                for (pkg in config.disallowedApplications) {
                    try { addDisallowedApplication(pkg) } catch (_: Exception) {}
                }
                // Blocking mode preserved
            }

            closeTunResources()
            val pfd = builder.establish() ?: throw IllegalStateException("Builder.establish() returned null in onTunConfigReady")
            tunPfd = pfd
            val rawFd = pfd.detachFd()
            activeTunRawFd.set(rawFd)
            DebugLogger.i(TAG, "Dynamic TUN established successfully. Detached fd=$rawFd")
            if (_vpnState.value is VpnState.Connected) {
                startPingMonitor(updatedConfig)
            }
            rawFd
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to establish dynamic TUN: ${e.message}", e)
            -1
        }
    }

    /**
     * Creates and configures the Android TUN interface,
     * or prepares 2-phase dynamic initialization callback for OpenConnect,
     * and delegates control to [NativeEngineController].
     */
    private suspend fun establishTunnel(
        config: TunnelConfig,
        sessionId: Long,
        network: Network?,
        reuseTun: Boolean
    ) {
        if (sessionId != sessionCounter.get()) {
            DebugLogger.w(TAG, "Session ID mismatch ($sessionId vs ${sessionCounter.get()}), aborting establishTunnel")
            return
        }

        stopPingMonitor()
        try {
            engineController.stopTunnel()
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Error stopping previous engine in establishTunnel: ${e.message}")
        }
        if (!reuseTun) closeTunResources()

        DebugLogger.i(TAG, "Establishing TUN interface [session=$sessionId]: server='${config.serverName}' (${config.protocol.displayName}), MTU=${config.mtu}...")

        // Protocol validation
        if (config.protocol == VpnProtocol.AMNEZIA_WG || config.protocol == VpnProtocol.WIREGUARD) {
            if (config.rawConfigJson.isBlank()) {
                val err = "CRITICAL: AmneziaWG configuration text is EMPTY! Engine cannot start."
                DebugLogger.e(TAG, err)
                throw IllegalArgumentException(err)
            }
            if (!config.rawConfigJson.contains("PrivateKey", ignoreCase = true)) {
                DebugLogger.w(TAG, "WARNING: AmneziaWG configuration text does not declare 'PrivateKey'! Handshake will fail.")
            }
            if (!config.rawConfigJson.contains("[Interface]", ignoreCase = true)) {
                DebugLogger.w(TAG, "WARNING: AmneziaWG configuration text does not contain '[Interface]' section header!")
            }
        } else if (config.protocol == VpnProtocol.OPENCONNECT) {
            if (config.serverAddress.isBlank()) {
                val err = "CRITICAL: OpenConnect serverAddress is blank! Cannot connect."
                DebugLogger.e(TAG, err)
                throw IllegalArgumentException(err)
            }
        }

        var effectiveConfig = config
        var effectiveRawJson = config.rawConfigJson

        // Pre-resolve server hostname to IPv4 address BEFORE establish()
        val resolvedServerIp = resolvePhysicalServerIp(config.serverAddress, network)
        if (resolvedServerIp == null && config.serverAddress.isNotBlank() && !config.serverAddress.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
            val errMsg = "Не удалось определить IP-адрес сервера '${config.serverAddress}'. Проверьте интернет-соединение."
            DebugLogger.e(TAG, errMsg)
            val errState = VpnState.Error(errMsg, config.protocol, -6, IllegalStateException(errMsg))
            _vpnState.value = errState
            updateNotification(errState)
            throw IllegalStateException(errMsg)
        }

        // Rewrite server endpoint domain to resolved physical IP to avoid DNS deadlock after TUN establish
        if (!resolvedServerIp.isNullOrBlank()) {
            try {
                if (effectiveRawJson.trim().startsWith("{")) {
                    val json = org.json.JSONObject(effectiveRawJson)
                    val port = config.serverPort.takeIf { it > 0 } ?: if (config.protocol == VpnProtocol.AMNEZIA_WG) 51820 else 443

                    if (config.protocol == VpnProtocol.OPENCONNECT) {
                        val origHost = json.optString("host", config.serverAddress)
                        val isOrigDomain = origHost.isNotBlank() && !origHost.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))
                        if (isOrigDomain) {
                            if (!json.has("tls_sni") || json.optString("tls_sni").isBlank()) {
                                json.put("tls_sni", origHost)
                            }
                            if (!json.has("original_host") || json.optString("original_host").isBlank()) {
                                json.put("original_host", origHost)
                            }
                        }
                    }

                    if (json.has("server")) json.put("server", "$resolvedServerIp:$port")
                    if (json.has("serverAddress")) json.put("serverAddress", resolvedServerIp)
                    if (json.has("host")) json.put("host", resolvedServerIp)
                    if (json.has("endpoint")) {
                        val epStr = json.optString("endpoint", "")
                        val epPort = if (epStr.contains(":")) epStr.substringAfter(":") else port.toString()
                        json.put("endpoint", "$resolvedServerIp:$epPort")
                    }

                    for (key in listOf("config_text", "config_content", "config", "configText", "raw_config")) {
                        if (json.has(key)) {
                            val embedded = json.getString(key)
                            val endpointRegex = Regex("(?im)^(\\s*Endpoint\\s*=\\s*)([^:\\s#;]+)(?::(\\d+))?")
                            if (endpointRegex.containsMatchIn(embedded)) {
                                val rewritten = endpointRegex.replace(embedded) { mr ->
                                    val host = mr.groupValues[2].trim()
                                    val epPort = mr.groupValues[3].ifEmpty { port.toString() }
                                    if (!host.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                                        "${mr.groupValues[1]}$resolvedServerIp:$epPort"
                                    } else {
                                        mr.value
                                    }
                                }
                                json.put(key, rewritten)
                            }
                        }
                    }
                    effectiveRawJson = json.toString()
                    DebugLogger.i(TAG, "Replaced server domain with resolved IP $resolvedServerIp in config JSON")
                } else {
                    val port = config.serverPort.takeIf { it > 0 } ?: 51820
                    val endpointRegex = Regex("(?im)^(\\s*Endpoint\\s*=\\s*)([^:\\s#;]+)(?::(\\d+))?")
                    if (endpointRegex.containsMatchIn(effectiveRawJson)) {
                        effectiveRawJson = endpointRegex.replace(effectiveRawJson) { mr ->
                            val host = mr.groupValues[2].trim()
                            val epPort = mr.groupValues[3].ifEmpty { port.toString() }
                            if (!host.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                                "${mr.groupValues[1]}$resolvedServerIp:$epPort"
                            } else {
                                mr.value
                            }
                        }
                        DebugLogger.i(TAG, "Replaced AmneziaWG Endpoint domain with resolved IP $resolvedServerIp in INI text")
                    }
                }
            } catch (e: Exception) {
                DebugLogger.w(TAG, "Error rewriting Endpoint with resolved IP: ${e.message}")
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

        activeConfig = effectiveConfig
        _currentConfig = effectiveConfig
        activeAssignedIpv4 = effectiveConfig.ipv4Address
        activeAssignedPrefix = effectiveConfig.ipv4Prefix

        val tunConfigCallback = if (effectiveConfig.protocol == VpnProtocol.OPENCONNECT) {
            JniBridge.TunConfigCallback { configJson ->
                if (sessionId == sessionCounter.get()) onTunConfigReady(configJson) else -1
            }
        } else {
            null
        }

        val rawFd: Int
        if (effectiveConfig.protocol == VpnProtocol.OPENCONNECT) {
            // OpenConnect handles dynamic 2-phase TUN creation via tunConfigCallback
            rawFd = -1
            DebugLogger.i(TAG, "OpenConnect starting with dynamic 2-phase TUN callback")
        } else if (reuseTun && activeTunRawFd.get() >= 0) {
            rawFd = activeTunRawFd.get()
        } else {
            // WireGuard / AmneziaWG: establish TUN immediately
            val builder = Builder().apply {
                setSession("БобрДобр VPN: ${effectiveConfig.serverName}")
                setMtu(effectiveConfig.mtu)

                try {
                    addAddress(effectiveConfig.ipv4Address, effectiveConfig.ipv4Prefix)
                    DebugLogger.d(TAG, "Added virtual IPv4: ${effectiveConfig.ipv4Address}/${effectiveConfig.ipv4Prefix}")
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "Invalid IPv4 address: ${effectiveConfig.ipv4Address}/${effectiveConfig.ipv4Prefix}", e)
                    throw e
                }

                if (!effectiveConfig.ipv6Address.isNullOrEmpty()) {
                    try {
                        addAddress(effectiveConfig.ipv6Address, effectiveConfig.ipv6Prefix)
                        DebugLogger.d(TAG, "Added virtual IPv6: ${effectiveConfig.ipv6Address}/${effectiveConfig.ipv6Prefix}")
                    } catch (e: Exception) {
                        DebugLogger.w(TAG, "Could not add IPv6 address ${effectiveConfig.ipv6Address}: ${e.message}")
                    }
                }

                val effectiveDns = if (effectiveConfig.dnsServers.isNotEmpty()) {
                    DebugLogger.i(TAG, "Strictly applying server-side DNS from TunnelConfig: ${effectiveConfig.dnsServers}")
                    effectiveConfig.dnsServers
                } else {
                    DebugLogger.w(TAG, "No DNS specified in config, falling back to 1.1.1.1")
                    listOf("1.1.1.1")
                }

                for (dns in effectiveDns) {
                    try {
                        addDnsServer(dns)
                        DebugLogger.d(TAG, "Added DNS server to VpnService.Builder: $dns")
                    } catch (e: Exception) {
                        DebugLogger.w(TAG, "Failed to add DNS server $dns: ${e.message}")
                    }
                }

                for (route in effectiveConfig.routes) {
                    try {
                        addRoute(route.address, route.prefix)
                    } catch (e: Exception) {
                        DebugLogger.w(TAG, "Failed to add route $route: ${e.message}")
                    }
                }
                DebugLogger.d(TAG, "Configured ${effectiveConfig.routes.size} tunnel routes")

                // Explicit /32 host routes for active DNS
                for (dns in effectiveDns) {
                    try {
                        if (dns.contains(".")) {
                            addRoute(dns, 32)
                        } else if (dns.contains(":")) {
                            addRoute(dns, 128)
                        }
                    } catch (_: Exception) {}
                }

                // Explicit /32 host route for calculated in-tunnel gateway
                try {
                    val gw = calculateInTunnelGatewayIp(effectiveConfig.ipv4Address, effectiveConfig.ipv4Prefix)
                    addRoute(gw, 32)
                    DebugLogger.d(TAG, "Added explicit /32 route for in-tunnel gateway: $gw")
                } catch (e: Exception) {
                    DebugLogger.w(TAG, "Failed to add explicit route for gateway: ${e.message}")
                }

                for (pkg in effectiveConfig.allowedApplications) {
                    try {
                        addAllowedApplication(pkg)
                    } catch (e: Exception) {
                        DebugLogger.w(TAG, "Failed to add allowed application $pkg: ${e.message}")
                    }
                }

                for (pkg in effectiveConfig.disallowedApplications) {
                    try {
                        addDisallowedApplication(pkg)
                    } catch (e: Exception) {
                        DebugLogger.w(TAG, "Failed to add disallowed application $pkg: ${e.message}")
                    }
                }

                // Blocking mode preserved (setBlocking(false) removed)
            }

            val pfd = builder.establish()
                ?: run {
                    val err = "VpnService.Builder.establish() returned null. Is VPN permission granted?"
                    DebugLogger.e(TAG, err)
                    throw IllegalStateException(err)
                }

            tunPfd = pfd
            rawFd = pfd.detachFd()
            activeTunRawFd.set(rawFd)
            DebugLogger.i(TAG, "Successfully captured TUN file descriptor: $rawFd")
        }

        if (sessionId != sessionCounter.get()) {
            DebugLogger.w(TAG, "Session ID changed during setup, aborting engine launch")
            closeTunResources()
            return
        }

        // Launch native engine
        val protector = SocketProtector { fd ->
            sessionId == sessionCounter.get() && prepareTransportSocket(fd, network)
        }
        val startResult = engineController.startTunnel(effectiveConfig, rawFd, protector, tunConfigCallback, sessionId)
        if (sessionId != sessionCounter.get()) {
            engineController.stopTunnel()
            closeTunResources()
            return
        }
        if (startResult.isFailure) {
            val err = startResult.exceptionOrNull()?.message ?: "Native engine failed to start"
            DebugLogger.e(TAG, "engineController.startTunnel failed: $err")
            closeTunResources()
            throw IllegalStateException(err)
        }

        DebugLogger.i(TAG, "Native engine spawned. Waiting for cryptographic handshake confirmation...")

        // Timeout watchdog: If native handshake does not succeed within 25 seconds, report error
        watchdogJob = serviceScope.launch {
            delay(25000L)
            if (sessionId == sessionCounter.get() &&
                (_vpnState.value is VpnState.Connecting || _vpnState.value is VpnState.Reconnecting)) {
                val errMsg = "Сервер не ответил на запрос подключения (таймаут рукопожатия). Проверьте настройки или выберите другой шлюз."
                DebugLogger.w(TAG, errMsg)
                val timeoutState = VpnState.Error(
                    message = errMsg,
                    protocol = effectiveConfig.protocol,
                    errorCode = -4,
                    canRetry = true
                )
                _vpnState.value = timeoutState
                updateNotification(timeoutState)
                engineController.stopTunnel()
                closeTunResources()
            }
        }
    }

    /**
     * Implements [SocketProtector.protectSocket].
     * Invokes [android.net.VpnService.protect] to ensure outer transport sockets bypass the VPN routing table.
     */
    override fun protectSocket(socketFd: Int): Boolean {
        if (socketFd < 0) return false
        val success = protect(socketFd)
        DebugLogger.d(TAG, "protectSocket(fd=$socketFd) -> $success")
        return success
    }

    private fun prepareTransportSocket(fd: Int, network: Network?): Boolean {
        if (fd < 0) return false
        if (network != null) {
            try {
                // The duplicate shares the socket binding. The engine still owns the original fd.
                ParcelFileDescriptor.fromFd(fd).use { network.bindSocket(it.fileDescriptor) }
            } catch (e: Exception) {
                DebugLogger.w(TAG, "Transport socket binding failed on network ${network.networkHandle}", e)
                return false
            }
        }
        return protectSocket(fd)
    }

    private fun registerNetworkMonitor() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (isUsablePhysicalNetwork(capabilities)) {
                    physicalNetworks[network] = capabilities
                } else {
                    physicalNetworks.remove(network)
                }
                schedulePhysicalNetworkSelection()
            }

            override fun onLost(network: Network) {
                physicalNetworks.remove(network)
                schedulePhysicalNetworkSelection()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, callback, Handler(Looper.getMainLooper()))
            networkCallback = callback
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Failed to monitor physical networks", e)
        }
    }

    private fun isUsablePhysicalNetwork(capabilities: NetworkCapabilities): Boolean =
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)

    private fun schedulePhysicalNetworkSelection() {
        networkChangeJob?.cancel()
        networkChangeJob = serviceScope.launch {
            delay(350L)
            val validated = physicalNetworks.filterValues {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            val defaultNetwork = connectivityManager.activeNetwork
            val selected = when {
                defaultNetwork != null && defaultNetwork in validated -> defaultNetwork
                else -> validated.entries.firstOrNull {
                    it.value.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                }?.key ?: validated.entries.firstOrNull {
                    it.value.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                }?.key ?: validated.keys.firstOrNull { it == currentPhysicalNetwork }
                    ?: validated.keys.firstOrNull()
                    ?: currentPhysicalNetwork?.takeIf { it in physicalNetworks }
                    ?: defaultNetwork?.takeIf { it in physicalNetworks }
                    ?: physicalNetworks.keys.firstOrNull()
            }
            currentPhysicalNetwork = selected
            val config = requestedConfig ?: return@launch
            if (selected == null || selected == connectionNetwork || isUserInitiatedStop) return@launch
            if (_vpnState.value is VpnState.Disconnecting) return@launch
            launchConnection(config, selected, reconnecting = true)
        }
    }

    private fun launchConnection(config: TunnelConfig, network: Network?, reconnecting: Boolean) {
        connectJob?.cancel()
        watchdogJob?.cancel()
        // Reuse only the TUN from the running Rust session for this very same profile.
        val reuseTun = reconnecting && _vpnState.value is VpnState.Connected &&
            config.protocol != VpnProtocol.OPENCONNECT
        val sessionId = sessionCounter.incrementAndGet()
        connectionNetwork = network
        if (reconnecting) {
            val state = VpnState.Reconnecting(config.protocol, reason = "Physical network changed")
            _vpnState.value = state
            updateNotification(state)
        }
        DebugLogger.i(TAG, "Starting VPN session $sessionId on network ${network?.networkHandle}")
        connectJob = serviceScope.launch(Dispatchers.IO) {
            tunnelMutex.withLock {
                if (!isActive || sessionId != sessionCounter.get()) return@withLock
                try {
                    establishTunnel(config, sessionId, network, reuseTun)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (sessionId == sessionCounter.get()) {
                        closeTunResources()
                        val error = VpnState.Error(
                            message = e.message ?: "Failed to establish VPN transport",
                            protocol = config.protocol,
                            throwable = e,
                            canRetry = true
                        )
                        _vpnState.value = error
                        updateNotification(error)
                    }
                }
            }
        }
    }

    /**
     * Closes the raw detached TUN file descriptor and releases ParcelFileDescriptor.
     * This signals the Linux kernel to immediately remove the virtual tunX network interface.
     */
    private fun closeTunResources() {
        val fd = activeTunRawFd.getAndSet(-1)
        if (fd >= 0) {
            try {
                // Adopting the detached fd and closing it triggers the POSIX close(fd) syscall on /dev/tun
                ParcelFileDescriptor.adoptFd(fd).close()
                DebugLogger.i(TAG, "Successfully adopted and closed raw TUN fd=$fd")
            } catch (e: Exception) {
                DebugLogger.w(TAG, "Failed to close raw TUN fd=$fd: ${e.message}")
            }
        }

        try {
            tunPfd?.close()
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Error closing ParcelFileDescriptor: ${e.message}")
        }
        tunPfd = null
    }

    private fun stopTunnelGracefully() {
        DebugLogger.i(TAG, "stopTunnelGracefully: tearing down session")
        connectJob?.cancel()
        connectJob = null
        networkChangeJob?.cancel()
        networkChangeJob = null
        stopPingMonitor()
        watchdogJob?.cancel()
        watchdogJob = null
        val stopSession = sessionCounter.incrementAndGet()
        requestedConfig = null
        connectionNetwork = null

        _currentConfig = null
        _vpnState.value = VpnState.Disconnecting(activeConfig?.protocol)
        updateNotification(_vpnState.value)

        serviceScope.launch(Dispatchers.IO) {
            tunnelMutex.withLock {
                if (stopSession != sessionCounter.get()) return@withLock
                try {
                    engineController.stopTunnel()
                } catch (e: Exception) {
                    DebugLogger.w(TAG, "Error stopping native engine: ${e.message}", e)
                }

                closeTunResources()
                activeConfig = null
                lastMeasuredPingMs = 0L

                if (stopSession != sessionCounter.get()) return@withLock
                _vpnState.value = VpnState.Disconnected
                _tunnelStats.value = TunnelStats.EMPTY
                updateNotification(VpnState.Disconnected)
                DebugLogger.i(TAG, "Tunnel teardown completed. State=DISCONNECTED")

                serviceScope.launch(Dispatchers.Main) {
                    if (stopSession != sessionCounter.get()) return@launch
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        DebugLogger.i(TAG, "VpnTunnelService onDestroy()")

        connectJob?.cancel()
        connectJob = null
        networkChangeJob?.cancel()
        networkChangeJob = null
        stopPingMonitor()
        watchdogJob?.cancel()
        watchdogJob = null
        sessionCounter.incrementAndGet()

        try {
            networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Error unregistering network callback: ${e.message}", e)
        }

        // Stop the native worker before closing the TUN descriptor.  Closing
        // the descriptor first races the Rust packet loop and can leave a
        // native thread polling a reused fd during service teardown.
        try {
            engineController.stopTunnel()
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Error stopping native engine in onDestroy: ${e.message}")
        }
        closeTunResources()

        _currentConfig = null
        _vpnState.value = VpnState.Disconnected
        _tunnelStats.value = TunnelStats.EMPTY
        physicalNetworks.clear()
        serviceScope.cancel()

        super.onDestroy()
    }

    /**
     * Dynamically calculates the in-tunnel gateway IP address from the assigned client IP
     * and subnet prefix, without any hardcoded addresses.
     *
     * In standard VPN topologies (/24 to /30 subnets), the server gateway is the first usable host (.1).
     * For /32 point-to-point client addresses (common in WireGuard client peer configurations),
     * it derives the .1 host of the parent /24 network.
     */
    private fun calculateInTunnelGatewayIp(clientIp: String, prefix: Int): String {
        try {
            val parts = clientIp.trim().split(".")
            if (parts.size == 4) {
                val octets = parts.map { it.toInt() }
                if (octets.all { it in 0..255 }) {
                    if (prefix in 1..30) {
                        val ipInt = (octets[0] shl 24) or (octets[1] shl 16) or (octets[2] shl 8) or octets[3]
                        val mask = (-1L shl (32 - prefix)).toInt()
                        val netInt = ipInt and mask
                        val gwInt = netInt or 1
                        val gwIp = "${(gwInt ushr 24) and 0xFF}.${(gwInt ushr 16) and 0xFF}.${(gwInt ushr 8) and 0xFF}.${gwInt and 0xFF}"
                        return gwIp
                    } else {
                        // Point-to-point /32 or fallback to /24 .1 host
                        return "${octets[0]}.${octets[1]}.${octets[2]}.1"
                    }
                }
            }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Error calculating in-tunnel gateway IP for $clientIp/$prefix: ${e.message}")
        }
        return "10.8.0.1"
    }

    private fun startPingMonitor(config: TunnelConfig) {
        stopPingMonitor()
        pingMonitorJob = serviceScope.launch(Dispatchers.IO) {
            val clientIp = activeAssignedIpv4 ?: config.ipv4Address
            val prefix = if (activeAssignedPrefix in 1..32) activeAssignedPrefix else config.ipv4Prefix
            val gatewayIp = config.dnsServers.firstOrNull { it.contains(".") }
                ?: calculateInTunnelGatewayIp(clientIp, prefix)

            DebugLogger.i(TAG, "Starting In-Tunnel gateway ping monitor to target $gatewayIp (client: $clientIp/$prefix, 3s interval)")

            while (isActive) {
                delay(3000L)
                if (_vpnState.value !is VpnState.Connected) break

                // Battery optimization: pause pinging when UI is in background
                if (!_isUiVisible.value) {
                    continue
                }

                val startTime = SystemClock.elapsedRealtime()
                var rtt = -1L

                // 1. Fast UDP DNS query to in-tunnel gateway (port 53)
                try {
                    java.net.DatagramSocket().use { ds ->
                        ds.soTimeout = 800
                        val dnsQuery = byteArrayOf(
                            0xAA.toByte(), 0xBB.toByte(), // Transaction ID
                            0x01, 0x00,                   // Standard query
                            0x00, 0x01,                   // 1 Question
                            0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                            0x00,                         // Root '.'
                            0x00, 0x01,                   // Type A
                            0x00, 0x01                    // Class IN
                        )
                        val sendPacket = java.net.DatagramPacket(dnsQuery, dnsQuery.size, InetAddress.getByName(gatewayIp), 53)
                        ds.send(sendPacket)
                        val buf = ByteArray(512)
                        val recvPacket = java.net.DatagramPacket(buf, buf.size)
                        ds.receive(recvPacket)
                        rtt = SystemClock.elapsedRealtime() - startTime
                    }
                } catch (_: Exception) {}

                // 2. Fallback to TCP SYN/RST probe on ports 53 and 443
                if (rtt <= 0) {
                    for (port in intArrayOf(53, 443)) {
                        try {
                            val tcpStart = SystemClock.elapsedRealtime()
                            Socket().use { s ->
                                s.soTimeout = 600
                                s.connect(InetSocketAddress(gatewayIp, port), 600)
                                rtt = SystemClock.elapsedRealtime() - tcpStart
                            }
                            if (rtt > 0) break
                        } catch (e: java.net.ConnectException) {
                            // RST packet received from gateway proves the packet reached the in-tunnel gateway!
                            rtt = SystemClock.elapsedRealtime() - startTime
                            break
                        } catch (_: Exception) {}
                    }
                }

                // 3. Fallback to ICMP isReachable
                if (rtt <= 0) {
                    try {
                        val icmpStart = SystemClock.elapsedRealtime()
                        if (InetAddress.getByName(gatewayIp).isReachable(800)) {
                            rtt = SystemClock.elapsedRealtime() - icmpStart
                        }
                    } catch (_: Exception) {}
                }

                if (rtt in 1..2500) {
                    lastMeasuredPingMs = rtt
                    _tunnelStats.value = _tunnelStats.value.copy(pingMs = rtt)
                    DebugLogger.v(TAG, "In-tunnel gateway ping to $gatewayIp: ${rtt}ms")
                }
            }
        }
    }

    private fun stopPingMonitor() {
        pingMonitorJob?.cancel()
        pingMonitorJob = null
    }

    override fun onRevoke() {
        DebugLogger.w(TAG, "VPN permission revoked by system or user")
        stopTunnelGracefully()
        super.onRevoke()
    }

    // --- Notification & Foreground Service Management ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "БобрДобр VPN - Статус плотины",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Постоянное уведомление о статусе защищенного соединения VPN"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification(state: VpnState) {
        lastNotifiedState = state
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
        if (lastNotifiedState == state) {
            return
        }
        lastNotifiedState = state
        val notification = buildNotification(state)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(state: VpnState): Notification {
        val serverName = activeConfig?.serverName ?: "Сервер"

        val (contentTitle, contentText) = when (state) {
            is VpnState.Connected -> Pair(
                "БобрДобр VPN: Плотина под защитой",
                "Плотина под надежной защитой! (${state.protocol.displayName} - $serverName)"
            )
            is VpnState.Connecting -> Pair(
                "БобрДобр VPN: Возведение плотины…",
                "Возведение плотины… (Подключение к $serverName)"
            )
            is VpnState.Disconnecting -> Pair(
                "БобрДобр VPN: Открытие шлюзов…",
                "Открытие шлюзов… (Отключение)"
            )
            is VpnState.Reconnecting -> Pair(
                "БобрДобр VPN: Укрепление плотины…",
                "Укрепление плотины… (Повторное подключение к $serverName)"
            )
            is VpnState.Error -> Pair(
                "БобрДобр VPN: Прорыв плотины!",
                "Прорыв плотины! Ошибка: ${state.message}"
            )
            is VpnState.Disconnected -> Pair(
                "БобрДобр VPN: Шлюзы открыты",
                "Шлюзы открыты (Отключено)"
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
                "ОТКЛЮЧИТЬ",
                disconnectPendingIntent
            )
        }

        return builder.build()
    }
}
