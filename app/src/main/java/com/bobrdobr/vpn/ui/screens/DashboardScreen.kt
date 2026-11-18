package com.bobrdobr.vpn.ui.screens

import android.app.Activity
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bobrdobr.vpn.R
import org.bobrdobr.vpn.core.model.VpnState
import com.bobrdobr.vpn.ui.components.BeaverConnectButton
import com.bobrdobr.vpn.ui.components.CyberServerCard
import com.bobrdobr.vpn.ui.components.DamStatusBar
import com.bobrdobr.vpn.ui.components.HydroTelemetryBar
import com.bobrdobr.vpn.ui.components.UpdateDialog
import com.bobrdobr.vpn.ui.theme.BackgroundCard
import com.bobrdobr.vpn.ui.theme.BackgroundMain
import com.bobrdobr.vpn.ui.theme.CardBorder
import com.bobrdobr.vpn.ui.theme.NeonAmber
import com.bobrdobr.vpn.ui.theme.NeonCyan
import com.bobrdobr.vpn.ui.theme.NeonEmerald
import com.bobrdobr.vpn.ui.theme.TextMuted
import com.bobrdobr.vpn.ui.theme.TextPrimary
import com.bobrdobr.vpn.ui.theme.TextSecondary
import com.bobrdobr.vpn.ui.theme.neonGlow
import com.bobrdobr.vpn.ui.viewmodel.MainViewModel
import com.bobrdobr.vpn.update.AppUpdateManager
import com.bobrdobr.vpn.update.UpdateState

/**
 * DashboardScreen: The primary screen of BobrDobr VPN.
 * Hosts DamStatusBar, BeaverConnectButton, CyberServerCard, and HydroTelemetryBar.
 */
@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onNavigateToServers: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val tunnelStats by viewModel.tunnelStats.collectAsStateWithLifecycle()
    val selectedServer by viewModel.selectedServer.collectAsStateWithLifecycle()

    val updateManager = remember { AppUpdateManager.getInstance(context) }
    val updateState by updateManager.updateState.collectAsStateWithLifecycle()
    var showUpdateDialog by remember { mutableStateOf(false) }

    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.connectDirectly(context)
        }
    }

    val isConnected = vpnState is VpnState.Connected

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundMain)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Top App Header: Centered Brand with Mascot without circle (matches Image 6)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_beaver),
                contentDescription = "Logo",
                modifier = Modifier.size(34.dp)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Text(
                text = "БобрДобр VPN",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Black,
                    fontSize = 22.sp,
                    color = Color(0xFFFFA000),
                    letterSpacing = 0.5.sp,
                    shadow = Shadow(
                        color = Color(0xFFB45309),
                        offset = Offset(0f, 3f),
                        blurRadius = 1.5f
                    )
                )
            )

            // Neon Update Badge if an update is available
            if (updateState is UpdateState.UpdateAvailable) {
                val release = (updateState as UpdateState.UpdateAvailable).release
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .neonGlow(color = NeonCyan, radius = 6.dp, alpha = 0.35f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(NeonCyan.copy(alpha = 0.18f))
                        .border(1.dp, NeonCyan, RoundedCornerShape(8.dp))
                        .clickable { showUpdateDialog = true }
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.RocketLaunch,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${release.tagName} Доступно",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = NeonCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Dam Status Bar
        DamStatusBar(vpnState = vpnState)

        Spacer(modifier = Modifier.height(28.dp))

        // Central Beaver Connection Button
        BeaverConnectButton(
            vpnState = vpnState,
            onToggle = {
                viewModel.toggleVpn(context) { intent ->
                    vpnPrepareLauncher.launch(intent)
                }
            }
        )

        Spacer(modifier = Modifier.height(30.dp))

        // Current Gateway Card
        CyberServerCard(
            server = selectedServer,
            isConnected = isConnected,
            onClick = onNavigateToServers
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Real-Time Hydro Telemetry (RX / TX / Ping)
        HydroTelemetryBar(
            stats = tunnelStats,
            isConnected = isConnected
        )

        Spacer(modifier = Modifier.height(24.dp))
    }

    val availableRelease = (updateState as? UpdateState.UpdateAvailable)?.release
        ?: (updateState as? UpdateState.Downloading)?.release
        ?: (updateState as? UpdateState.ReadyToInstall)?.release

    if (showUpdateDialog && availableRelease != null) {
        UpdateDialog(
            release = availableRelease,
            updateState = updateState,
            onDismiss = { showUpdateDialog = false },
            onDownloadAndInstall = { rel ->
                updateManager.downloadAndInstall(rel)
            }
        )
    }
}
