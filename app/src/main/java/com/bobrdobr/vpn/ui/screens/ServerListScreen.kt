package com.bobrdobr.vpn.ui.screens

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.painterResource
import com.bobrdobr.vpn.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bobrdobr.vpn.config.ConfigValidator
import com.bobrdobr.vpn.config.QrConfigDecoder
import com.bobrdobr.vpn.config.ValidationResult
import com.bobrdobr.vpn.ui.components.DesktopTag
import com.bobrdobr.vpn.ui.components.ProtocolBadge
import com.bobrdobr.vpn.ui.model.ServerProfile
import com.bobrdobr.vpn.ui.theme.BackgroundCard
import com.bobrdobr.vpn.ui.theme.BackgroundCardActive
import com.bobrdobr.vpn.ui.theme.BackgroundInput
import com.bobrdobr.vpn.ui.theme.BackgroundMain
import com.bobrdobr.vpn.ui.theme.CardBorder
import com.bobrdobr.vpn.ui.theme.NeonAmber
import com.bobrdobr.vpn.ui.theme.NeonCyan
import com.bobrdobr.vpn.ui.theme.NeonEmerald
import com.bobrdobr.vpn.ui.theme.NeonPurple
import com.bobrdobr.vpn.ui.theme.NeonRed
import com.bobrdobr.vpn.ui.theme.TextMuted
import com.bobrdobr.vpn.ui.theme.TextPrimary
import com.bobrdobr.vpn.ui.theme.TextSecondary
import com.bobrdobr.vpn.ui.theme.neonGlow
import com.bobrdobr.vpn.ui.viewmodel.MainViewModel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.bobrdobr.vpn.core.model.VpnProtocol
import org.bobrdobr.vpn.core.model.VpnState
import org.bobrdobr.vpn.core.service.VpnTunnelService

/**
 * ServerListScreen: Selection of high-speed BobrDobr Dam gateways styled after the desktop client (Photo 4).
 * Features protocol-coded cards, direct Connect/Disconnect controls, segmented pill switch in manual dialog,
 * and robust QR/file import for AmneziaWG.
 */
@Composable
fun ServerListScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val selectedServer by viewModel.selectedServer.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val isVpnConnected = vpnState is VpnState.Connected

    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.connectDirectly(context)
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilterIndex by remember { mutableIntStateOf(0) }
    val filterTabs = listOf("Все", "AmneziaWG", "OpenConnect")

    var showAddServerDialog by remember { mutableStateOf(false) }
    var inspectingServer by remember { mutableStateOf<ServerProfile?>(null) }

    val filteredServers = servers.filter { server ->
        val matchesQuery = server.name.contains(searchQuery, ignoreCase = true) ||
                server.location.contains(searchQuery, ignoreCase = true) ||
                server.serverAddress.contains(searchQuery, ignoreCase = true)
        val matchesProtocol = when (selectedFilterIndex) {
            1 -> server.protocol == VpnProtocol.AMNEZIA_WG
            2 -> server.protocol == VpnProtocol.OPENCONNECT
            else -> true
        }
        matchesQuery && matchesProtocol
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundMain)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Top Header with Add Server Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Назад",
                        tint = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Шлюзы плотины",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                    Text(
                        text = "Выберите или добавьте шлюз вручную",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextSecondary
                        )
                    )
                }
            }

            IconButton(
                onClick = { showAddServerDialog = true },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(NeonCyan.copy(alpha = 0.15f))
                    .border(1.dp, NeonCyan.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Добавить шлюз",
                    tint = NeonCyan
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Protocol Filter Tabs (Segmented bar with weighted widths to prevent text wrapping on any screen size)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(BackgroundCard)
                .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            filterTabs.forEachIndexed { index, title ->
                val isSelected = selectedFilterIndex == index
                val tabWeight = when (index) {
                    0 -> 0.75f  // "Все"
                    1 -> 1.15f  // "AmneziaWG"
                    else -> 1.25f // "OpenConnect"
                }
                Box(
                    modifier = Modifier
                        .weight(tabWeight)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected) NeonCyan.copy(alpha = 0.2f)
                            else Color.Transparent
                        )
                        .then(
                            if (isSelected) Modifier.border(1.dp, NeonCyan.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            else Modifier
                        )
                        .clickable { selectedFilterIndex = index }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        maxLines = 1,
                        softWrap = false,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) NeonCyan else TextSecondary,
                            fontSize = 12.sp
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Search Input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = {
                Text(
                    text = "Поиск по стране, городу или IP...",
                    style = MaterialTheme.typography.bodyMedium.copy(color = TextMuted)
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Поиск",
                    tint = TextSecondary
                )
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = BackgroundCard,
                unfocusedContainerColor = BackgroundCard,
                focusedBorderColor = NeonCyan,
                unfocusedBorderColor = CardBorder,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Server List or Empty State
        if (filteredServers.isEmpty()) {
            EmptyServersState(
                onAddServerClick = { showAddServerDialog = true }
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredServers, key = { it.id }) { server ->
                    val isSelected = server.id == selectedServer.id
                    val isCurrentServerActive = isSelected && isVpnConnected

                    DesktopServerCard(
                        server = server,
                        isSelected = isSelected,
                        isActiveConnected = isCurrentServerActive,
                        onConnectClick = {
                            viewModel.selectServer(server)
                            viewModel.toggleVpn(context) { intent ->
                                vpnPrepareLauncher.launch(intent)
                            }
                        },
                        onDisconnectClick = {
                            VpnTunnelService.stop(context)
                        },
                        onSelectClick = {
                            viewModel.selectServer(server)
                            onBack()
                        },
                        onInspectClick = {
                            inspectingServer = server
                        }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    if (showAddServerDialog) {
        AddServerDialog(
            onDismiss = { showAddServerDialog = false },
            onAddServer = { newServer ->
                viewModel.addCustomServer(context, newServer)
                showAddServerDialog = false
                Toast.makeText(context, "Шлюз «${newServer.name}» успешно добавлен!", Toast.LENGTH_SHORT).show()
            }
        )
    }

    inspectingServer?.let { server ->
        ServerDetailsDialog(
            server = server,
            onDismiss = { inspectingServer = null },
            onDeleteCustom = if (server.isCustom) {
                {
                    viewModel.removeServer(context, server.id)
                    inspectingServer = null
                    Toast.makeText(context, "Шлюз «${server.name}» удален", Toast.LENGTH_SHORT).show()
                }
            } else null
        )
    }
}

@Composable
private fun EmptyServersState(
    onAddServerClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 40.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(20.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(BackgroundCard)
                    .border(1.dp, CardBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Dns,
                    contentDescription = null,
                    tint = NeonCyan,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Нет добавленных шлюзов",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Подключите подписку или добавьте шлюз вручную (+)",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onAddServerClick,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCyan,
                    contentColor = BackgroundMain
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Добавить шлюз вручную",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * DesktopServerCard: Server gateway card styled after the mature desktop client (Photo 4).
 * - Thin border by protocol color (AmneziaWG -> Amber, OpenConnect -> Cyan).
 * - Rich neon-emerald highlight when active/selected.
 * - [ sub ] and [ split/all ] tags.
 * - Action button [ Подключить ] / [ Отключить ] directly in card.
 */
@Composable
private fun DesktopServerCard(
    server: ServerProfile,
    isSelected: Boolean,
    isActiveConnected: Boolean,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onSelectClick: () -> Unit,
    onInspectClick: () -> Unit
) {
    val protocolBaseColor = when (server.protocol) {
        VpnProtocol.AMNEZIA_WG -> NeonAmber
        VpnProtocol.OPENCONNECT -> NeonCyan
        VpnProtocol.WIREGUARD -> NeonCyan
    }

    // Card border strictly adheres to protocol identity
    val cardBorderColor = if (isSelected || isActiveConnected) {
        protocolBaseColor
    } else {
        protocolBaseColor.copy(alpha = 0.4f)
    }

    val pingColor = when {
        server.pingMs < 45 -> NeonEmerald
        server.pingMs < 100 -> NeonAmber
        else -> NeonRed
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isActiveConnected) Modifier.neonGlow(color = protocolBaseColor, radius = 8.dp, alpha = 0.25f)
                else if (isSelected) Modifier.neonGlow(color = protocolBaseColor, radius = 5.dp, alpha = 0.16f)
                else Modifier
            )
            .clip(RoundedCornerShape(14.dp))
            .background(if (isActiveConnected || isSelected) BackgroundCardActive else BackgroundCard)
            .border(
                width = if (isActiveConnected || isSelected) 2.dp else 1.dp,
                color = cardBorderColor,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable { onSelectClick() }
            .padding(14.dp)
    ) {
        Column {
            // Row 1: Header (Protocol Emblem replacing globe, Name, Ping)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    val (protocolIcon, protocolColor) = when (server.protocol) {
                        VpnProtocol.AMNEZIA_WG -> Pair(R.drawable.ic_amnezia, NeonAmber)
                        VpnProtocol.WIREGUARD -> Pair(R.drawable.ic_amnezia, NeonCyan)
                        VpnProtocol.OPENCONNECT -> Pair(R.drawable.ic_openconnect, NeonCyan)
                    }

                    // Protocol Emblem Circle (replaces globe/planet emblem)
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(protocolColor.copy(alpha = 0.12f))
                            .border(1.dp, protocolColor.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = protocolIcon),
                            contentDescription = server.protocol.displayName,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = server.name,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 15.sp
                            ),
                            maxLines = 1
                        )
                        Text(
                            text = "${server.serverAddress}:${server.serverPort}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        )
                    }
                }

                // Right: Ping (if > 0) + Sleek Settings Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (server.pingMs > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(pingColor.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(pingColor)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${server.pingMs} мс",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = pingColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(protocolBaseColor.copy(alpha = 0.12f))
                            .border(1.dp, protocolBaseColor.copy(alpha = 0.35f), CircleShape)
                            .clickable { onInspectClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Параметры подключения",
                            tint = protocolBaseColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Row 2: Badges (Protocol, [ sub ])
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ProtocolBadge(protocol = server.protocol)

                if (server.isFromSubscription) {
                    DesktopTag(label = "sub", color = NeonPurple)
                } else if (server.isCustom) {
                    DesktopTag(label = "custom", color = NeonAmber)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Row 3: Status Indicator + Direct Action Button [ Подключить ] / [ Отключить ]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: Connection status dot
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isActiveConnected -> NeonEmerald
                                    isSelected -> NeonCyan
                                    else -> TextMuted
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when {
                            isActiveConnected -> "Подключено"
                            isSelected -> "Выбран"
                            else -> "Отключено"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = when {
                                isActiveConnected -> NeonEmerald
                                isSelected -> NeonCyan
                                else -> TextMuted
                            },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp
                        )
                    )
                }

                // Right: Action button
                if (isActiveConnected) {
                    Button(
                        onClick = onDisconnectClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonRed.copy(alpha = 0.15f),
                            contentColor = NeonRed
                        ),
                        border = BorderStroke(1.dp, NeonRed),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(32.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = "Отключить",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Button(
                        onClick = onConnectClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonEmerald.copy(alpha = 0.15f),
                            contentColor = NeonEmerald
                        ),
                        border = BorderStroke(1.dp, NeonEmerald),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(32.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = "Подключить",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerDetailsDialog(
    server: ServerProfile,
    onDismiss: () -> Unit,
    onDeleteCustom: (() -> Unit)? = null
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val (protocolIcon, protocolColor) = when (server.protocol) {
        VpnProtocol.AMNEZIA_WG -> Pair(R.drawable.ic_amnezia, NeonAmber)
        VpnProtocol.WIREGUARD -> Pair(R.drawable.ic_amnezia, NeonCyan)
        VpnProtocol.OPENCONNECT -> Pair(R.drawable.ic_openconnect, NeonCyan)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(protocolColor.copy(alpha = 0.15f))
                            .border(1.dp, protocolColor.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = protocolIcon),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = server.name,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (server.isFromSubscription) {
                                DesktopTag(label = "подписка", color = NeonPurple)
                                DesktopTag(label = "только чтение", color = TextSecondary)
                            } else if (server.isCustom) {
                                DesktopTag(label = "пользовательский", color = NeonAmber)
                            }
                        }
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(BackgroundCardActive.copy(alpha = 0.6f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Закрыть",
                        tint = TextSecondary,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Structured parameters card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BackgroundInput)
                        .border(0.5.dp, CardBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    ServerInfoRow(label = "Протокол", value = server.protocol.displayName)
                    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(CardBorder.copy(alpha = 0.4f)))
                    ServerInfoRow(label = "Адрес шлюза", value = "${server.serverAddress}:${server.serverPort}")
                    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(CardBorder.copy(alpha = 0.4f)))

                    val dnsText = if (server.dnsServers.isNotEmpty()) {
                        server.dnsServers.joinToString(", ")
                    } else {
                        "1.1.1.1, 1.0.0.1"
                    }
                    ServerInfoRow(label = "DNS серверы", value = dnsText)

                    if (server.protocol == VpnProtocol.OPENCONNECT) {
                        if (!server.username.isNullOrBlank()) {
                            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(CardBorder.copy(alpha = 0.4f)))
                            ServerInfoRow(label = "Пользователь", value = server.username)
                        }
                        if (!server.group.isNullOrBlank()) {
                            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(CardBorder.copy(alpha = 0.4f)))
                            ServerInfoRow(label = "Группа", value = server.group)
                        }
                    }
                }

                val config = server.configText
                if (!config.isNullOrBlank()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Конфигурация",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = TextSecondary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                )
                            )
                            TextButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(config))
                                    Toast.makeText(context, "Конфигурация скопирована в буфер", Toast.LENGTH_SHORT).show()
                                },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    tint = NeonCyan,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Скопировать",
                                    color = NeonCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 170.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF070B0E))
                                .border(0.5.dp, CardBorder, RoundedCornerShape(10.dp))
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(10.dp)
                        ) {
                            Text(
                                text = config,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp,
                                color = TextPrimary,
                                lineHeight = 15.sp,
                                softWrap = false
                            )
                        }
                    }
                }

                if (server.isFromSubscription) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(NeonPurple.copy(alpha = 0.08f))
                            .border(0.5.dp, NeonPurple.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = NeonPurple,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Параметры этого подключения управляются активной подпиской и защищены от изменений.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = NeonPurple.copy(alpha = 0.9f),
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (server.isCustom && onDeleteCustom != null) {
                    OutlinedButton(
                        onClick = onDeleteCustom,
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = NeonRed
                        ),
                        border = BorderStroke(1.dp, NeonRed.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = NeonRed
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Удалить",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonRed
                        )
                    }
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .then(if (server.isCustom && onDeleteCustom != null) Modifier.weight(1f) else Modifier.fillMaxWidth())
                        .height(42.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan.copy(alpha = 0.15f),
                        contentColor = NeonCyan
                    ),
                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.8f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(text = "Закрыть", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        containerColor = BackgroundCard,
        tonalElevation = 6.dp,
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun ServerInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            ),
            modifier = Modifier.weight(0.38f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End
            ),
            modifier = Modifier.weight(0.62f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * AddServerDialog: Comprehensive dialog to manually import AmneziaWG .conf/QR or OpenConnect credentials.
 * Modernized with a sleek segmented pill switch, dark inputs (#090E11), and no emojis in buttons.
 */
@Composable
private fun AddServerDialog(
    onDismiss: () -> Unit,
    onAddServer: (ServerProfile) -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = AmneziaWG, 1 = OpenConnect

    // AmneziaWG State
    var awgName by remember { mutableStateOf("") }
    var awgConfigText by remember { mutableStateOf("") }
    val awgValidation = remember(awgConfigText) { ConfigValidator.validateAwg(awgConfigText) }

    // OpenConnect State
    var ocName by remember { mutableStateOf("") }
    var ocHost by remember { mutableStateOf("") }
    var ocPort by remember { mutableStateOf("") }
    var ocSecret by remember { mutableStateOf("") }
    var ocUsername by remember { mutableStateOf("") }
    var ocPassword by remember { mutableStateOf("") }
    val ocValidation = remember(ocHost, ocPort, ocUsername, ocPassword, ocSecret) {
        ConfigValidator.validateOcserv(
            host = ocHost,
            port = ocPort.toIntOrNull() ?: 443,
            username = ocUsername,
            password = ocPassword,
            group = ocSecret.ifBlank { "split" }
        )
    }

    var showQrSourceDialog by remember { mutableStateOf(false) }

    // Launcher for file picker (.conf)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                awgConfigText = content
                val fileName = uri.lastPathSegment?.split("/")?.lastOrNull()?.replace(".conf", "")
                if (!fileName.isNullOrBlank()) {
                    awgName = fileName
                }
                Toast.makeText(context, "Файл конфигурации успешно загружен", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Ошибка чтения файла: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Launcher for gallery image picker (QR code decoding via ZXing)
    val galleryQrPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val decoded = QrConfigDecoder.decodeFromUri(context, uri)
            if (decoded != null && decoded.contains("[Interface]", ignoreCase = true)) {
                awgConfigText = decoded
                Toast.makeText(context, "Конфигурация AmneziaWG успешно считана из QR-изображения!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Не удалось распознать QR-код или файл не содержит конфигурацию WireGuard", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun startCameraQrScan() {
        try {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
            val scanner = GmsBarcodeScanning.getClient(context, options)
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    val raw = barcode.rawValue ?: barcode.displayValue ?: ""
                    val decoded = QrConfigDecoder.decodeRawText(raw) ?: raw
                    if (decoded.contains("[Interface]", ignoreCase = true)) {
                        awgConfigText = decoded
                        Toast.makeText(context, "Конфигурация AmneziaWG успешно считана через сканер!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "QR-код считан, но не содержит заголовка [Interface]", Toast.LENGTH_LONG).show()
                    }
                }
                .addOnCanceledListener {
                    // User dismissed scanner
                }
                .addOnFailureListener { e ->
                    Log.w("AddServerDialog", "Google Code Scanner failure, opening gallery fallback", e)
                    Toast.makeText(context, "Google Scanner недоступен. Выберите скриншот из галереи", Toast.LENGTH_SHORT).show()
                    galleryQrPickerLauncher.launch("image/*")
                }
        } catch (e: Exception) {
            Log.e("AddServerDialog", "Scanner initialization error", e)
            galleryQrPickerLauncher.launch("image/*")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Добавить шлюз вручную",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Закрыть", tint = TextSecondary)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Segmented Pill Switch (AmneziaWG / OpenConnect)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BackgroundInput)
                        .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Pill 1: AmneziaWG
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selectedTab == 0) NeonAmber.copy(alpha = 0.22f) else Color.Transparent)
                            .border(
                                width = if (selectedTab == 0) 1.dp else 0.dp,
                                color = if (selectedTab == 0) NeonAmber else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { selectedTab = 0 }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "AmneziaWG",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium,
                                color = if (selectedTab == 0) NeonAmber else TextSecondary
                            )
                        )
                    }

                    // Pill 2: OpenConnect
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selectedTab == 1) NeonCyan.copy(alpha = 0.22f) else Color.Transparent)
                            .border(
                                width = if (selectedTab == 1) 1.dp else 0.dp,
                                color = if (selectedTab == 1) NeonCyan else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { selectedTab = 1 }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "OpenConnect",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium,
                                color = if (selectedTab == 1) NeonCyan else TextSecondary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (selectedTab == 0) {
                    // AMNEZIAWG TAB
                    OutlinedTextField(
                        value = awgName,
                        onValueChange = { awgName = it },
                        label = { Text("Название шлюза") },
                        placeholder = { Text("Мой AmneziaWG Шлюз", color = TextMuted, fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = outlinedColors()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Buttons Row: File Picker + QR Scanner (NO EMOJIS, pure vector icons)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { filePickerLauncher.launch("*/*") },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan)
                        ) {
                            Icon(imageVector = Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Файл .conf", fontSize = 12.sp, maxLines = 1)
                        }

                        OutlinedButton(
                            onClick = { showQrSourceDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonEmerald)
                        ) {
                            Icon(imageVector = Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("QR-код", fontSize = 12.sp, maxLines = 1)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = awgConfigText,
                        onValueChange = { awgConfigText = it },
                        label = { Text("Текст конфигурации WireGuard / AWG") },
                        placeholder = { Text("[Interface]\nPrivateKey = ...\nAddress = ...\nDNS = 10.8.0.1\n\n[Peer]\nPublicKey = ...\nEndpoint = ...", color = TextMuted, fontSize = 13.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp),
                        colors = outlinedColors()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Validation Box
                    ValidationIndicator(awgValidation)

                } else {
                    // OPENCONNECT TAB
                    OutlinedTextField(
                        value = ocName,
                        onValueChange = { ocName = it },
                        label = { Text("Название шлюза") },
                        placeholder = { Text("Мой OpenConnect Шлюз", color = TextMuted, fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = outlinedColors()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = ocHost,
                            onValueChange = { ocHost = it },
                            label = { Text("Сервер / IP") },
                            placeholder = { Text("vpn.example.com", color = TextMuted, fontSize = 13.sp) },
                            modifier = Modifier.weight(2f),
                            singleLine = true,
                            colors = outlinedColors()
                        )
                        OutlinedTextField(
                            value = ocPort,
                            onValueChange = { ocPort = it },
                            label = { Text("Порт") },
                            placeholder = { Text("443", color = TextMuted, fontSize = 13.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = outlinedColors()
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = ocSecret,
                        onValueChange = { ocSecret = it },
                        label = { Text("Секрет камуфляжа сервера (?secret)") },
                        placeholder = { Text("split", color = TextMuted, fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = outlinedColors()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = ocUsername,
                        onValueChange = { ocUsername = it },
                        label = { Text("Имя пользователя / Логин") },
                        placeholder = { Text("user", color = TextMuted, fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = outlinedColors()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = ocPassword,
                        onValueChange = { ocPassword = it },
                        label = { Text("Пароль") },
                        placeholder = { Text("••••••••", color = TextMuted, fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = outlinedColors()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Validation Box
                    ValidationIndicator(ocValidation)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (selectedTab == 0 && awgValidation is ValidationResult.SuccessAwg) {
                        val v = awgValidation
                        val newServer = ServerProfile(
                            id = "custom-awg-${System.currentTimeMillis()}",
                            name = awgName.ifBlank { "Мой AmneziaWG" },
                            location = "Пользовательский",
                            countryCode = "DE",
                            flagEmoji = "⚡",
                            protocol = VpnProtocol.AMNEZIA_WG,
                            serverAddress = v.serverAddress,
                            serverPort = v.serverPort,
                            dnsServers = v.dnsServers,
                            configText = awgConfigText,
                            isCustom = true,
                            rawConfigJson = org.json.JSONObject().apply {
                                put("protocol", "AMNEZIA_WG")
                                put("server", v.serverAddress)
                                put("port", v.serverPort)
                                put("config_text", awgConfigText)
                                v.obfuscationParams.forEach { (k, valNum) -> put(k.lowercase(), valNum) }
                            }.toString()
                        )
                        onAddServer(newServer)
                    } else if (selectedTab == 1 && ocValidation is ValidationResult.SuccessOcserv) {
                        val v = ocValidation
                        val secretVal = ocSecret.ifBlank { "split" }
                        val newServer = ServerProfile(
                            id = "custom-oc-${System.currentTimeMillis()}",
                            name = ocName.ifBlank { "Мой OpenConnect" },
                            location = "Пользовательский",
                            countryCode = "NL",
                            flagEmoji = "🛡️",
                            protocol = VpnProtocol.OPENCONNECT,
                            serverAddress = v.host,
                            serverPort = v.port,
                            username = v.username,
                            password = ocPassword,
                            group = secretVal,
                            secret = secretVal,
                            isCustom = true,
                            rawConfigJson = org.json.JSONObject().apply {
                                put("protocol", "OPENCONNECT")
                                put("server", v.host)
                                put("port", v.port)
                                put("username", v.username)
                                put("password", ocPassword)
                                put("group", secretVal)
                                put("secret", secretVal)
                            }.toString()
                        )
                        onAddServer(newServer)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = BackgroundMain),
                shape = RoundedCornerShape(10.dp),
                enabled = (selectedTab == 0 && awgValidation is ValidationResult.SuccessAwg) ||
                        (selectedTab == 1 && ocValidation is ValidationResult.SuccessOcserv)
            ) {
                Text("Сохранить шлюз", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = TextSecondary)
            }
        },
        containerColor = BackgroundCard,
        shape = RoundedCornerShape(18.dp)
    )

    // Modal Dialog to choose between Camera Scanner & Gallery
    if (showQrSourceDialog) {
        AlertDialog(
            onDismissRequest = { showQrSourceDialog = false },
            title = {
                Text("Импорт QR-кода AmneziaWG", fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Выберите способ считывания QR-кода с конфигурацией туннеля:",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = {
                            showQrSourceDialog = false
                            startCameraQrScan()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = BackgroundMain),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Сканировать камерой", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            showQrSourceDialog = false
                            galleryQrPickerLauncher.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan)
                    ) {
                        Icon(imageVector = Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Выбрать из галереи (скриншот)")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showQrSourceDialog = false }) {
                    Text("Отмена", color = TextSecondary)
                }
            },
            containerColor = BackgroundCard,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun ValidationIndicator(result: ValidationResult) {
    val (borderColor, bgColor, textColor) = when (result) {
        is ValidationResult.SuccessAwg, is ValidationResult.SuccessOcserv ->
            Triple(NeonEmerald, NeonEmerald.copy(alpha = 0.12f), NeonEmerald)
        is ValidationResult.Error ->
            Triple(NeonRed, NeonRed.copy(alpha = 0.12f), NeonRed)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Column {
            when (result) {
                is ValidationResult.SuccessAwg -> {
                    Text(text = "✓ Конфигурация AmneziaWG валидна", color = textColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(text = "Шлюз: ${result.endpoint} (${result.clientAddress})", color = TextSecondary, fontSize = 11.sp)
                    if (result.dnsServers.isNotEmpty()) {
                        Text(text = "DNS туннеля: ${result.dnsServers.joinToString(", ")}", color = NeonCyan, fontSize = 11.sp)
                    }
                    if (result.hasObfuscation) {
                        Text(text = "✓ Защищенный протокол AWG активен", color = NeonCyan, fontSize = 10.sp)
                    }
                }
                is ValidationResult.SuccessOcserv -> {
                    Text(text = "✓ Параметры OpenConnect корректны", color = textColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(text = "Сервер: ${result.host}:${result.port} (Группа: ${result.group})", color = TextSecondary, fontSize = 11.sp)
                    Text(text = "Пользователь: ${result.username}", color = NeonCyan, fontSize = 11.sp)
                }
                is ValidationResult.Error -> {
                    Text(text = "✕ ${result.message}", color = textColor, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun outlinedColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = BackgroundInput,
    unfocusedContainerColor = BackgroundInput,
    focusedBorderColor = NeonCyan,
    unfocusedBorderColor = CardBorder,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedLabelColor = NeonCyan,
    unfocusedLabelColor = TextSecondary,
    focusedPlaceholderColor = TextMuted,
    unfocusedPlaceholderColor = TextMuted
)
