package com.bobrdobr.vpn.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.bobrdobr.vpn.repository.ServerRepository
import com.bobrdobr.vpn.routing.TunnelConfigAssembler
import com.bobrdobr.vpn.subscription.SubscriptionManager
import com.bobrdobr.vpn.subscription.canConnect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.bobrdobr.vpn.core.service.VpnTunnelService

/**
 * BootReceiver: Automatically activates the VPN tunnel upon system startup
 * if the user has explicitly enabled Auto-Connect in Settings.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        const val PREFS_NAME = "bobrdobr_vpn_prefs"
        const val KEY_AUTO_CONNECT = "key_auto_connect_enabled"
        const val KEY_BYPASS_ENABLED = "key_bypass_enabled"
        const val KEY_BYPASS_SUBNETS = "key_bypass_subnets"
        const val KEY_SELECTED_DNS = "key_selected_dns"
        const val KEY_SELECTED_SERVER_ID = "key_selected_server_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        Log.i(TAG, "Received boot event: $action")
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isAutoConnectEnabled = prefs.getBoolean(KEY_AUTO_CONNECT, false)

        if (!isAutoConnectEnabled) {
            Log.d(TAG, "Auto-connect is disabled in settings. Skipping boot startup.")
            return
        }

        // Check if VPN permission has been granted
        if (VpnService.prepare(context) != null) {
            Log.w(TAG, "VpnService permission not granted or revoked. Cannot auto-connect on boot.")
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(15_000L) {
                    val subManager = SubscriptionManager.getInstance(context)
                    val repo = ServerRepository.getInstance(context)
                    val servers = repo.allServers.value

                    val savedServerId = prefs.getString(KEY_SELECTED_SERVER_ID, null)
                    val targetServer = if (!savedServerId.isNullOrBlank()) {
                        servers.firstOrNull { it.id == savedServerId } ?: servers.firstOrNull()
                    } else {
                        servers.firstOrNull()
                    }

                    if (targetServer == null) {
                        Log.w(TAG, "No server profile configured. Cannot auto-connect on boot.")
                        return@withTimeout
                    }

                    // If target server is from subscription, verify that subscription is active and allowed to connect
                    if (targetServer.isFromSubscription && !subManager.subscriptionInfo.value.currentStatus.canConnect) {
                        Log.w(TAG, "Subscription is expired or blocked. Auto-connect blocked.")
                        return@withTimeout
                    }

                    val bypassEnabled = prefs.getBoolean(KEY_BYPASS_ENABLED, false)
                    val bypassSubnets = prefs.getString(KEY_BYPASS_SUBNETS, "") ?: ""
                    val selectedDns = prefs.getString(KEY_SELECTED_DNS, "Cloudflare") ?: "Cloudflare"

                    val config = TunnelConfigAssembler.assemble(
                        context = context,
                        profile = targetServer,
                        bypassEnabled = bypassEnabled,
                        bypassSubnetsRaw = bypassSubnets,
                        fallbackDnsSelection = selectedDns
                    )

                    Log.i(TAG, "Auto-connecting to server '${targetServer.name}' on boot...")
                    VpnTunnelService.start(context, config)
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Auto-connect on boot timed out after 15 seconds")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to auto-connect on boot: ${t.message}", t)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
