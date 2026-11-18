package com.bobrdobr.vpn.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bobrdobr.vpn.R
import com.bobrdobr.vpn.ui.components.UpdateDialog
import com.bobrdobr.vpn.ui.components.UpdateNotificationBanner
import com.bobrdobr.vpn.ui.screens.DashboardScreen
import com.bobrdobr.vpn.ui.screens.ServerListScreen
import com.bobrdobr.vpn.ui.screens.SettingsScreen
import com.bobrdobr.vpn.ui.screens.SplitTunnelScreen
import com.bobrdobr.vpn.ui.screens.SubscriptionScreen
import com.bobrdobr.vpn.ui.theme.BackgroundCard
import com.bobrdobr.vpn.ui.theme.BackgroundMain
import com.bobrdobr.vpn.ui.theme.BackgroundSurface
import com.bobrdobr.vpn.ui.theme.CardBorder
import com.bobrdobr.vpn.ui.theme.NeonAmber
import com.bobrdobr.vpn.ui.theme.NeonCyan
import com.bobrdobr.vpn.ui.theme.NeonEmerald
import com.bobrdobr.vpn.ui.theme.NeonPurple
import com.bobrdobr.vpn.ui.theme.TextMuted
import com.bobrdobr.vpn.ui.theme.TextPrimary
import com.bobrdobr.vpn.ui.theme.TextSecondary
import com.bobrdobr.vpn.ui.viewmodel.MainViewModel
import com.bobrdobr.vpn.ui.viewmodel.NavigationTab
import com.bobrdobr.vpn.update.AppUpdateManager
import com.bobrdobr.vpn.update.UpdateState
import kotlinx.coroutines.delay

/**
 * BobrDobrMainApp: Top-level navigation shell with cyberpunk bottom bar.
 */
@Composable
fun BobrDobrMainApp(
    viewModel: MainViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val updateManager = remember { AppUpdateManager.getInstance(context) }
    val updateState by updateManager.updateState.collectAsStateWithLifecycle()
    var showUpdateDialog by remember { mutableStateOf(false) }
    var bannerDismissed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.initRepository(context)
        delay(2500)
        updateManager.check(isManual = false)
    }

    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = BackgroundMain,
        bottomBar = {
            BobrDobrBottomBar(
                currentTab = currentTab,
                onTabSelected = { viewModel.setNavigationTab(it) }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                NavigationTab.DASHBOARD -> DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToServers = { viewModel.setNavigationTab(NavigationTab.SERVERS) }
                )
                NavigationTab.SERVERS -> ServerListScreen(
                    viewModel = viewModel,
                    onBack = { viewModel.setNavigationTab(NavigationTab.DASHBOARD) }
                )
                NavigationTab.SUBSCRIPTION -> SubscriptionScreen(
                    viewModel = viewModel
                )
                NavigationTab.SETTINGS -> SettingsScreen(
                    viewModel = viewModel
                )
                NavigationTab.SPLIT_TUNNEL -> SplitTunnelScreen(
                    onBack = { viewModel.setNavigationTab(NavigationTab.SETTINGS) }
                )
            }

            // Top Floating Update Banner
            val availableRelease = (updateState as? UpdateState.UpdateAvailable)?.release
                ?: (updateState as? UpdateState.Downloading)?.release
                ?: (updateState as? UpdateState.ReadyToInstall)?.release

            UpdateNotificationBanner(
                release = (updateState as? UpdateState.UpdateAvailable)?.release,
                visible = (updateState is UpdateState.UpdateAvailable) && !bannerDismissed,
                onOpenDialog = { showUpdateDialog = true },
                onDismiss = { bannerDismissed = true },
                modifier = Modifier.align(Alignment.TopCenter)
            )

            // Update Dialog
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
    }
}

@Composable
private fun BobrDobrBottomBar(
    currentTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit
) {
    Box(
        modifier = Modifier
            .background(BackgroundSurface)
            .border(width = 1.dp, color = CardBorder.copy(alpha = 0.5f))
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        NavigationBar(
            containerColor = BackgroundSurface,
            tonalElevation = 0.dp
        ) {
            // Tab 1: Dashboard
            NavigationBarItem(
                selected = currentTab == NavigationTab.DASHBOARD,
                onClick = { onTabSelected(NavigationTab.DASHBOARD) },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Главная",
                        modifier = Modifier.size(24.dp)
                    )
                },
                label = {
                    Text(
                        text = "Главная",
                        fontSize = 11.sp,
                        fontWeight = if (currentTab == NavigationTab.DASHBOARD) FontWeight.Bold else FontWeight.Normal
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = NeonCyan,
                    selectedTextColor = NeonCyan,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted,
                    indicatorColor = NeonCyan.copy(alpha = 0.12f)
                )
            )

            // Tab 2: Servers
            NavigationBarItem(
                selected = currentTab == NavigationTab.SERVERS,
                onClick = { onTabSelected(NavigationTab.SERVERS) },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = "Серверы",
                        modifier = Modifier.size(24.dp)
                    )
                },
                label = {
                    Text(
                        text = "Серверы",
                        fontSize = 11.sp,
                        fontWeight = if (currentTab == NavigationTab.SERVERS) FontWeight.Bold else FontWeight.Normal
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = NeonCyan,
                    selectedTextColor = NeonCyan,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted,
                    indicatorColor = NeonCyan.copy(alpha = 0.12f)
                )
            )

            // Tab 3: Subscription
            NavigationBarItem(
                selected = currentTab == NavigationTab.SUBSCRIPTION,
                onClick = { onTabSelected(NavigationTab.SUBSCRIPTION) },
                icon = {
                    Image(
                        painter = painterResource(id = R.drawable.ic_subscription),
                        contentDescription = "Подписка",
                        modifier = Modifier.size(22.dp)
                    )
                },
                label = {
                    Text(
                        text = "Подписка",
                        fontSize = 11.sp,
                        fontWeight = if (currentTab == NavigationTab.SUBSCRIPTION) FontWeight.Bold else FontWeight.Normal
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = NeonCyan,
                    selectedTextColor = NeonCyan,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted,
                    indicatorColor = NeonCyan.copy(alpha = 0.12f)
                )
            )

            // Tab 4: Settings
            NavigationBarItem(
                selected = currentTab == NavigationTab.SETTINGS || currentTab == NavigationTab.SPLIT_TUNNEL,
                onClick = { onTabSelected(NavigationTab.SETTINGS) },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Настройки",
                        modifier = Modifier.size(24.dp)
                    )
                },
                label = {
                    Text(
                        text = "Настройки",
                        fontSize = 11.sp,
                        fontWeight = if (currentTab == NavigationTab.SETTINGS) FontWeight.Bold else FontWeight.Normal
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = NeonCyan,
                    selectedTextColor = NeonCyan,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted,
                    indicatorColor = NeonCyan.copy(alpha = 0.12f)
                )
            )
        }
    }
}
