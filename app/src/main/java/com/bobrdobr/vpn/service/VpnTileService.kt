package com.bobrdobr.vpn.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.bobrdobr.vpn.core.model.VpnState
import org.bobrdobr.vpn.core.service.VpnTunnelService
import com.bobrdobr.vpn.MainActivity
import com.bobrdobr.vpn.subscription.canConnect
import com.bobrdobr.vpn.ui.model.ServerProfile

/**
 * QuickSettings Tile allowing one-tap BobrDobr VPN toggle from the Android notification shade.
 */
@RequiresApi(Build.VERSION_CODES.N)
class VpnTileService : TileService() {

    private var serviceScope: CoroutineScope? = null
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        stateJob = serviceScope?.launch {
            VpnTunnelService.vpnState.collectLatest { state ->
                updateTileState(state)
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        stateJob?.cancel()
        serviceScope?.cancel()
        serviceScope = null
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return

        val currentState = VpnTunnelService.vpnState.value
        when (currentState) {
            is VpnState.Connected, is VpnState.Connecting, is VpnState.Reconnecting -> {
                // Turn off VPN
                VpnTunnelService.stop(this)
            }
            is VpnState.Disconnected, is VpnState.Error -> {
                // Check if VpnService permissions are already granted
                val prepareIntent = VpnService.prepare(this)
                if (prepareIntent != null) {
                    // Requires user prompt in Activity
                    val appIntent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val pendingIntent = android.app.PendingIntent.getActivity(
                            this,
                            0,
                            appIntent,
                            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        )
                        startActivityAndCollapse(pendingIntent)
                    } else {
                        @Suppress("DEPRECATION")
                        startActivityAndCollapse(appIntent)
                    }
                } else {
                    // Permission already granted -> connect to selected server or open app if none configured
                    val repo = com.bobrdobr.vpn.repository.ServerRepository.getInstance(this)
                    val prefs = getSharedPreferences("bobrdobr_vpn_prefs", Context.MODE_PRIVATE)
                    val servers = repo.allServers.value
                    val savedServerId = prefs.getString("key_selected_server_id", null)
                    val targetServer = if (!savedServerId.isNullOrBlank()) {
                        servers.firstOrNull { it.id == savedServerId } ?: servers.firstOrNull()
                    } else {
                        servers.firstOrNull()
                    }

                    val subManager = com.bobrdobr.vpn.subscription.SubscriptionManager.getInstance(this)
                    val canConnect = subManager.subscriptionInfo.value.currentStatus.canConnect
                    if (targetServer != null && (!targetServer.isFromSubscription || canConnect)) {
                        val bypassEnabled = prefs.getBoolean("key_bypass_enabled", false)
                        val bypassSubnets = prefs.getString("key_bypass_subnets", "") ?: ""
                        val selectedDns = prefs.getString("key_selected_dns", "Cloudflare") ?: "Cloudflare"
                        val killSwitchEnabled = prefs.getBoolean("key_kill_switch_enabled", false)

                        val config = com.bobrdobr.vpn.routing.TunnelConfigAssembler.assemble(
                            context = this,
                            profile = targetServer,
                            bypassEnabled = bypassEnabled,
                            bypassSubnetsRaw = bypassSubnets,
                            fallbackDnsSelection = selectedDns,
                            killSwitchEnabled = killSwitchEnabled
                        )
                        VpnTunnelService.start(this, config)
                    } else {
                        val appIntent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            val pendingIntent = android.app.PendingIntent.getActivity(
                                this,
                                0,
                                appIntent,
                                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            )
                            startActivityAndCollapse(pendingIntent)
                        } else {
                            @Suppress("DEPRECATION")
                            startActivityAndCollapse(appIntent)
                        }
                    }
                }
            }
            is VpnState.Disconnecting -> {
                // In teardown
            }
        }
    }

    private fun updateTileState(state: VpnState) {
        val tile = qsTile ?: return

        when (state) {
            is VpnState.Connected -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "БобрДобр: Защищен"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = state.protocol.displayName
                }
            }
            is VpnState.Connecting, is VpnState.Reconnecting -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = "БобрДобр: Связь..."
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Возведение плотины"
                }
            }
            is VpnState.Disconnecting -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = "БобрДобр: Открытие..."
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Завершение сессии"
                }
            }
            is VpnState.Error -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "БобрДобр: Сбой"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Прорыв плотины"
                }
            }
            VpnState.Disconnected -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "БобрДобр: Отключен"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Шлюзы открыты"
                }
            }
        }

        tile.updateTile()
    }
}
