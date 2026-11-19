package com.bobrdobr.vpn.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bobrdobr.vpn.split.SplitTunnelManager
import com.bobrdobr.vpn.ui.components.UpdateDialog
import com.bobrdobr.vpn.ui.theme.BackgroundCard
import com.bobrdobr.vpn.ui.theme.BackgroundCardActive
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
import com.bobrdobr.vpn.ui.viewmodel.MainViewModel
import com.bobrdobr.vpn.update.AppUpdateManager
import com.bobrdobr.vpn.update.UpdateState

/**
 * SettingsScreen: Configuration parameters of the BobrDobr Dam.
 * Includes Kill Switch, Per-App Split Tunneling entry, Bypass LAN, and Auto-Connect.
 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val killSwitchEnabled by viewModel.killSwitchEnabled.collectAsStateWithLifecycle()
    val autoConnectEnabled by viewModel.autoConnectEnabled.collectAsStateWithLifecycle()
    val bypassLocalEnabled by viewModel.bypassLocalEnabled.collectAsStateWithLifecycle()
    val splitManager = remember { SplitTunnelManager.getInstance(context) }
    val splitEnabled by splitManager.isEnabled.collectAsStateWithLifecycle()
    val selectedApps by splitManager.selectedApps.collectAsStateWithLifecycle()

    val updateManager = remember { AppUpdateManager.getInstance(context) }
    val updateState by updateManager.updateState.collectAsStateWithLifecycle()
    var showUpdateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        updateManager.manualCheckEvent.collect { result ->
            when (result) {
                is AppUpdateManager.ManualCheckResult.UpToDate -> {
                    Toast.makeText(context, "У вас установлена последняя версия (v0.8.0-prerelease)", Toast.LENGTH_SHORT).show()
                }
                is AppUpdateManager.ManualCheckResult.Available -> {
                    showUpdateDialog = true
                }
                is AppUpdateManager.ManualCheckResult.Failed -> {
                    Toast.makeText(context, "Не удалось проверить обновления: ${result.error}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundMain)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Title
        Text(
            text = "Параметры гидроузла",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        )
        Text(
            text = "Настройки безопасности, протоколов и маршрутизации",
            style = MaterialTheme.typography.bodySmall.copy(
                color = TextSecondary
            )
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Section 1: Security
        SettingsSectionHeader(title = "БЕЗОПАСНОСТЬ И ШЛЮЗЫ")

        SettingsCard {
            SettingsToggleItem(
                icon = Icons.Default.Shield,
                title = "Экстренный затвор (Kill Switch)",
                description = "Блокировать незащищенный трафик при падении туннеля",
                checked = killSwitchEnabled,
                onCheckedChange = { viewModel.toggleKillSwitch(it) },
                iconColor = NeonEmerald
            )

            SettingsDivider()

            SettingsToggleItem(
                icon = Icons.Default.PowerSettingsNew,
                title = "Автоподключение при старте",
                description = "Автоматически закрывать шлюзы при включении устройства",
                checked = autoConnectEnabled,
                onCheckedChange = { viewModel.toggleAutoConnect(it) },
                iconColor = NeonCyan
            )

            SettingsDivider()

            SettingsActionItem(
                icon = Icons.Default.Apps,
                title = "Раздельное туннелирование",
                description = "Выбрать приложения, идущие в обход плотины (Split Tunneling)",
                iconColor = NeonAmber,
                badge = if (splitEnabled && selectedApps.isNotEmpty()) "АКТИВНО (${selectedApps.size})" else if (splitEnabled) "ВКЛ" else null,
                onClick = { viewModel.setNavigationTab(com.bobrdobr.vpn.ui.viewmodel.NavigationTab.SPLIT_TUNNEL) }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Section 2: Network Routing
        SettingsSectionHeader(title = "СЕТЕВАЯ МАРШРУТИЗАЦИЯ")

        SettingsCard {
            SettingsToggleItem(
                icon = Icons.Default.Security,
                title = "Обход локальной сети (Bypass LAN)",
                description = "Исключить 192.168.0.0/16, 10.0.0.0/8 (доступ к принтерам и NAS)",
                checked = bypassLocalEnabled,
                onCheckedChange = { viewModel.toggleBypassLocal(it) },
                iconColor = NeonCyan
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Section 3: About App & Author
        SettingsSectionHeader(title = "О ПРИЛОЖЕНИИ И АВТОРЕ")

        SettingsCard {
            SettingsActionItem(
                icon = Icons.Default.Info,
                title = "БобрДобр VPN Mobile",
                description = "v0.8.0-prerelease (Сборка 80) • Android ARM64 • x86_64",
                iconColor = NeonCyan,
                badge = "v0.8.0",
                onClick = {
                    viewModel.setNavigationTab(com.bobrdobr.vpn.ui.viewmodel.NavigationTab.DIAGNOSTICS)
                }
            )

            SettingsDivider()

            // Check Updates Item
            val isChecking = updateState is UpdateState.Checking
            val isAvailable = updateState is UpdateState.UpdateAvailable
            SettingsUpdateItem(
                isChecking = isChecking,
                isAvailable = isAvailable,
                onClick = {
                    if (isAvailable) {
                        showUpdateDialog = true
                    } else {
                        updateManager.check(isManual = true)
                    }
                }
            )

            SettingsDivider()

            // Author Card
            SettingsActionItem(
                icon = Icons.Default.Person,
                title = "Автор и разработчик",
                description = "Phoenix1302 (Egor)",
                iconColor = NeonPurple,
                badge = "GitHub",
                onClick = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdateManager.AUTHOR_PROFILE_URL))
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                }
            )

            SettingsDivider()

            // Open Source Repository
            SettingsActionItem(
                icon = Icons.Default.Code,
                title = "Исходный код (GitHub)",
                description = "Phoenix1302/BobrDobr-VPN-Mobile",
                iconColor = NeonCyan,
                badge = "Open Source",
                onClick = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdateManager.GITHUB_REPO_URL))
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                }
            )

            SettingsDivider()

            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Защищенный туннельный протокол нового поколения.\nНадежная защита данных и конфиденциальность сети.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextSecondary,
                        lineHeight = 16.sp
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
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

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall.copy(
            color = TextMuted,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        ),
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BackgroundCard)
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
    ) {
        Column {
            content()
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        color = CardBorder.copy(alpha = 0.6f),
        thickness = 1.dp,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@Composable
private fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    iconColor: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(iconColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 14.sp
                    )
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BackgroundMain,
                checkedTrackColor = NeonEmerald,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = BackgroundCardActive
            )
        )
    }
}

@Composable
private fun SettingsActionItem(
    icon: ImageVector,
    title: String,
    description: String,
    iconColor: androidx.compose.ui.graphics.Color,
    badge: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(iconColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 14.sp
                    )
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (badge != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(iconColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = iconColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun SettingsUpdateItem(
    isChecking: Boolean,
    isAvailable: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isChecking) { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background((if (isAvailable) NeonEmerald else NeonCyan).copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = NeonCyan,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = if (isAvailable) Icons.Default.SystemUpdate else Icons.Default.Refresh,
                        contentDescription = null,
                        tint = if (isAvailable) NeonEmerald else NeonCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = if (isAvailable) "Доступно обновление!" else "Проверить обновления",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = if (isAvailable) NeonEmerald else TextPrimary,
                        fontSize = 14.sp
                    )
                )
                Text(
                    text = if (isChecking) "Проверка GitHub Releases..."
                    else if (isAvailable) "Нажмите для загрузки новой версии"
                    else "Поиск новых версий на GitHub",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (isAvailable) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(NeonEmerald.copy(alpha = 0.15f))
                    .border(1.dp, NeonEmerald.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "NEW",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = NeonEmerald,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                )
            }
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
