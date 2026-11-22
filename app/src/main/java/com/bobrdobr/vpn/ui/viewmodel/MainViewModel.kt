package com.bobrdobr.vpn.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobrdobr.vpn.repository.ServerRepository
import com.bobrdobr.vpn.routing.DnsLeakProtector
import com.bobrdobr.vpn.split.SplitTunnelManager
import com.bobrdobr.vpn.subscription.SubscriptionManager
import com.bobrdobr.vpn.subscription.SubscriptionStatus
import com.bobrdobr.vpn.subscription.canConnect
import com.bobrdobr.vpn.ui.model.ServerProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.bobrdobr.vpn.core.model.TunnelStats
import org.bobrdobr.vpn.core.model.VpnProtocol
import org.bobrdobr.vpn.core.model.VpnState
import org.bobrdobr.vpn.core.service.VpnTunnelService

enum class NavigationTab(val title: String) {
    DASHBOARD("Главная"),
    SERVERS("Серверы"),
    SUBSCRIPTION("Подписка"),
    SETTINGS("Настройки"),
    SPLIT_TUNNEL("Раздельное туннелирование"),
    DIAGNOSTICS("Отладка и диагностика")
}

class MainViewModel : ViewModel() {

    companion object {
        const val PREFS_NAME = "bobrdobr_vpn_prefs"
        const val KEY_BYPASS_ENABLED = "key_bypass_enabled"
        const val KEY_BYPASS_SUBNETS = "key_bypass_subnets"
        const val KEY_SELECTED_SERVER_ID = "key_selected_server_id"
        const val DEFAULT_BYPASS_SUBNETS = ""

        val EMPTY_SERVER = ServerProfile(
            id = "none",
            name = "Шлюз не выбран",
            location = "Не настроено",
            countryCode = "XX",
            flagEmoji = "⚡",
            protocol = VpnProtocol.AMNEZIA_WG,
            serverAddress = "",
            serverPort = 0,
            pingMs = 0,
            loadPercent = 0
        )
    }

    // Tunnel and connection state directly from vpn-core
    val vpnState: StateFlow<VpnState> = VpnTunnelService.vpnState
    val tunnelStats: StateFlow<TunnelStats> = VpnTunnelService.tunnelStats

    // Current Navigation Tab
    private val _currentTab = MutableStateFlow(NavigationTab.DASHBOARD)
    val currentTab: StateFlow<NavigationTab> = _currentTab.asStateFlow()

    // Available Servers and Selected Server
    private val _servers = MutableStateFlow<List<ServerProfile>>(emptyList())
    val servers: StateFlow<List<ServerProfile>> = _servers.asStateFlow()

    private val _selectedServer = MutableStateFlow<ServerProfile>(EMPTY_SERVER)
    val selectedServer: StateFlow<ServerProfile> = _selectedServer.asStateFlow()

    // Settings state
    private val _killSwitchEnabled = MutableStateFlow(true)
    val killSwitchEnabled: StateFlow<Boolean> = _killSwitchEnabled.asStateFlow()

    private val _autoConnectEnabled = MutableStateFlow(false)
    val autoConnectEnabled: StateFlow<Boolean> = _autoConnectEnabled.asStateFlow()

    private var prefs: SharedPreferences? = null

    private val _bypassEnabled = MutableStateFlow(false)
    val bypassEnabled: StateFlow<Boolean> = _bypassEnabled.asStateFlow()
    val bypassLocalEnabled: StateFlow<Boolean> get() = bypassEnabled

    private val _bypassSubnets = MutableStateFlow(DEFAULT_BYPASS_SUBNETS)
    val bypassSubnets: StateFlow<String> = _bypassSubnets.asStateFlow()

    private val _selectedDns = MutableStateFlow("DNS из конфигурации туннеля (по умолчанию)")
    val selectedDns: StateFlow<String> = _selectedDns.asStateFlow()

    // Subscription status
    private val _isSubscribing = MutableStateFlow(false)
    val isSubscribing: StateFlow<Boolean> = _isSubscribing.asStateFlow()

    private val _subscriptionMessage = MutableStateFlow("")
    val subscriptionMessage: StateFlow<String> = _subscriptionMessage.asStateFlow()

    private val _subscriptionBlockedDialogState = MutableStateFlow<SubscriptionStatus?>(null)
    val subscriptionBlockedDialogState: StateFlow<SubscriptionStatus?> = _subscriptionBlockedDialogState.asStateFlow()

    fun dismissSubscriptionBlockedDialog() {
        _subscriptionBlockedDialogState.value = null
    }

    private var isRepoInitialized = false

    fun initRepository(context: Context) {
        if (!isRepoInitialized) {
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            _bypassEnabled.value = p.getBoolean(KEY_BYPASS_ENABLED, false)
            _bypassSubnets.value = p.getString(KEY_BYPASS_SUBNETS, DEFAULT_BYPASS_SUBNETS) ?: DEFAULT_BYPASS_SUBNETS
        }
        if (isRepoInitialized) return
        isRepoInitialized = true
        val repo = ServerRepository.getInstance(context)
        viewModelScope.launch {
            repo.allServers.collect { list ->
                _servers.value = list
                if (list.isNotEmpty()) {
                    val savedServerId = prefs?.getString(KEY_SELECTED_SERVER_ID, null)
                    val currentId = _selectedServer.value.id

                    val targetServer = if (currentId != "none" && list.any { it.id == currentId }) {
                        list.first { it.id == currentId }
                    } else if (!savedServerId.isNullOrBlank() && list.any { it.id == savedServerId }) {
                        list.first { it.id == savedServerId }
                    } else {
                        list.first()
                    }
                    _selectedServer.value = targetServer
                } else {
                    _selectedServer.value = EMPTY_SERVER
                }
            }
        }
    }

    fun setNavigationTab(tab: NavigationTab) {
        _currentTab.value = tab
    }

    fun isTunnelActive(): Boolean {
        return when (vpnState.value) {
            is VpnState.Connected, is VpnState.Connecting, is VpnState.Reconnecting -> true
            else -> false
        }
    }

    fun selectServer(server: ServerProfile, context: Context? = null) {
        val prevServer = _selectedServer.value
        _selectedServer.value = server
        prefs?.edit()?.putString(KEY_SELECTED_SERVER_ID, server.id)?.apply()
        if (context != null && prevServer.id != server.id && isTunnelActive()) {
            org.bobrdobr.vpn.core.logger.DebugLogger.i("MainViewModel", "Active tunnel detected while switching to '${server.name}'. Reconnecting...")
            connectDirectly(context)
        }
    }

    fun connectToServer(context: Context, server: ServerProfile, launchVpnPrepare: ((Intent) -> Unit)? = null) {
        _selectedServer.value = server
        prefs?.edit()?.putString(KEY_SELECTED_SERVER_ID, server.id)?.apply()
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null && launchVpnPrepare != null) {
            launchVpnPrepare(prepareIntent)
        } else {
            connectDirectly(context)
        }
    }

    fun restartTunnelIfActive(context: Context) {
        if (isTunnelActive() && _selectedServer.value != EMPTY_SERVER && _selectedServer.value.serverAddress.isNotBlank()) {
            org.bobrdobr.vpn.core.logger.DebugLogger.i("MainViewModel", "Tunnel restart requested with updated routing parameters...")
            connectDirectly(context)
        }
    }

    fun addCustomServer(context: Context, server: ServerProfile) {
        val repo = ServerRepository.getInstance(context)
        repo.addCustomServer(server)
        _selectedServer.value = server
        prefs?.edit()?.putString(KEY_SELECTED_SERVER_ID, server.id)?.apply()
    }

    fun removeServer(context: Context, id: String) {
        val repo = ServerRepository.getInstance(context)
        repo.removeServer(id)
        if (_selectedServer.value.id == id) {
            prefs?.edit()?.remove(KEY_SELECTED_SERVER_ID)?.apply()
        }
    }

    fun clearSubscription(context: Context) {
        val subManager = SubscriptionManager.getInstance(context)
        subManager.clearSubscription()
        _subscriptionMessage.value = "Подписка отключена"
    }

    fun toggleKillSwitch(enabled: Boolean) {
        _killSwitchEnabled.value = enabled
    }

    fun toggleAutoConnect(enabled: Boolean) {
        _autoConnectEnabled.value = enabled
    }

    fun toggleBypass(enabled: Boolean, context: Context? = null) {
        _bypassEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_BYPASS_ENABLED, enabled)?.apply()
        context?.let { restartTunnelIfActive(it) }
    }

    fun toggleBypassLocal(enabled: Boolean, context: Context? = null) {
        toggleBypass(enabled, context)
    }

    fun setBypassSubnets(subnets: String, context: Context? = null) {
        _bypassSubnets.value = subnets
        prefs?.edit()?.putString(KEY_BYPASS_SUBNETS, subnets)?.apply()
        context?.let { restartTunnelIfActive(it) }
    }

    fun setDns(dns: String) {
        _selectedDns.value = dns
    }

    /**
     * Connect or disconnect VPN, requesting Android VpnService permissions if needed.
     */
    fun toggleVpn(context: Context, launchVpnPrepare: (Intent) -> Unit) {
        when (vpnState.value) {
            is VpnState.Connected, is VpnState.Connecting, is VpnState.Reconnecting -> {
                VpnTunnelService.stop(context)
            }
            is VpnState.Disconnected, is VpnState.Error -> {
                if (_selectedServer.value == EMPTY_SERVER || _selectedServer.value.serverAddress.isBlank()) {
                    Toast.makeText(context, "Сначала добавьте шлюз или активируйте подписку", Toast.LENGTH_SHORT).show()
                    return
                }

                // Check subscription status if selected server is from subscription
                if (_selectedServer.value.isFromSubscription) {
                    val subManager = SubscriptionManager.getInstance(context)
                    val currentStatus = subManager.subscriptionInfo.value.currentStatus
                    if (!currentStatus.canConnect) {
                        _subscriptionBlockedDialogState.value = currentStatus
                        return
                    }
                }

                val prepareIntent = VpnService.prepare(context)
                if (prepareIntent != null) {
                    launchVpnPrepare(prepareIntent)
                } else {
                    connectDirectly(context)
                }
            }
            is VpnState.Disconnecting -> {}
        }
    }

    /**
     * Start VPN service directly once permissions are granted.
     * Enforces DNS servers from config (for server-side domain routing) with fallback.
     */
    fun connectDirectly(context: Context) {
        if (_selectedServer.value == EMPTY_SERVER || _selectedServer.value.serverAddress.isBlank()) {
            Toast.makeText(context, "Выберите шлюз или подключите подписку", Toast.LENGTH_SHORT).show()
            return
        }

        // Check subscription status if selected server is from subscription
        if (_selectedServer.value.isFromSubscription) {
            val subManager = SubscriptionManager.getInstance(context)
            val currentStatus = subManager.subscriptionInfo.value.currentStatus
            if (!currentStatus.canConnect) {
                _subscriptionBlockedDialogState.value = currentStatus
                return
            }
        }

        val config = com.bobrdobr.vpn.routing.TunnelConfigAssembler.assemble(
            context = context,
            profile = _selectedServer.value,
            bypassEnabled = _bypassEnabled.value,
            bypassSubnetsRaw = _bypassSubnets.value,
            fallbackDnsSelection = _selectedDns.value
        )

        VpnTunnelService.start(context, config)
    }

    /**
     * Synchronizes subscription with remote server.
     */
    fun refreshSubscription(context: Context, customUrl: String? = null) {
        viewModelScope.launch {
            _isSubscribing.value = true
            _subscriptionMessage.value = "Обновление конфигураций..."

            val subManager = SubscriptionManager.getInstance(context)
            val targetUrl = customUrl ?: subManager.getActiveSubscriptionUrl()

            if (!targetUrl.isNullOrBlank()) {
                val result = subManager.fetchSubscription(targetUrl)
                if (result.isSuccess) {
                    val info = result.getOrNull()!!
                    _subscriptionMessage.value = "Шлюзы обновлены! Активно шлюзов: ${info.profiles.size}"
                } else {
                    _subscriptionMessage.value = "Ошибка: ${result.exceptionOrNull()?.message ?: "Сбой соединения"}"
                }
            } else {
                _subscriptionMessage.value = "Укажите ссылку на подписку"
            }
            _isSubscribing.value = false
        }
    }
}
