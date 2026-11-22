package com.bobrdobr.vpn.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import com.bobrdobr.vpn.R
import org.bobrdobr.vpn.core.model.VpnProtocol
import org.bobrdobr.vpn.core.model.VpnState
import android.net.Uri
import android.widget.Toast
import com.bobrdobr.vpn.ui.components.BeaverConnectButton
import com.bobrdobr.vpn.ui.components.CyberServerCard
import com.bobrdobr.vpn.ui.components.DamStatusBar
import com.bobrdobr.vpn.ui.components.DesktopTag
import com.bobrdobr.vpn.ui.components.HydroTelemetryBar
import com.bobrdobr.vpn.ui.components.ProtocolBadge
import com.bobrdobr.vpn.ui.components.SubscriptionAlertToast
import com.bobrdobr.vpn.ui.components.UpdateDialog
import com.bobrdobr.vpn.subscription.SubscriptionManager
import com.bobrdobr.vpn.subscription.canConnect
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
    val servers by viewModel.servers.collectAsStateWithLifecycle()

    val subManager = remember { SubscriptionManager.getInstance(context) }
    val subInfo by subManager.subscriptionInfo.collectAsStateWithLifecycle()
    val isBlocked = selectedServer.isFromSubscription && !subInfo.currentStatus.canConnect

    var subscriptionToastDismissed by remember { mutableStateOf(false) }
    var forceShowSubscriptionToast by remember { mutableStateOf(false) }

    val currentSubStatus = subInfo.currentStatus
    val isWarning = (currentSubStatus as? com.bobrdobr.vpn.subscription.SubscriptionStatus.Active)?.isWarningPeriod == true
    val isExpiredOrBlocked = !currentSubStatus.canConnect && subInfo.hasSubscription

    val shouldShowSubscriptionToast = subInfo.hasSubscription && (isWarning || isExpiredOrBlocked)
    val toastVisible = (shouldShowSubscriptionToast && !subscriptionToastDismissed) || forceShowSubscriptionToast

    val onRenewAction = {
        val renewalUrl = subInfo.getRenewalUrl()
        if (renewalUrl.isNotBlank()) {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(renewalUrl))
                context.startActivity(browserIntent)
            } catch (e: Exception) {
                Toast.makeText(context, "Не удалось открыть браузер: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Ссылка на личный кабинет недоступна", Toast.LENGTH_SHORT).show()
        }
    }

    val updateManager = remember { com.bobrdobr.vpn.update.AppUpdateManager.getInstance(context) }
    val updateState by updateManager.updateState.collectAsStateWithLifecycle()
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showQuickSwitchDialog by remember { mutableStateOf(false) }

    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.connectDirectly(context)
        }
    }

    val isConnected = vpnState is VpnState.Connected

    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    val beaverScale = remember { Animatable(1f) }
    val beaverRotation = remember { Animatable(0f) }

    var tapCount by remember { mutableStateOf(0) }
    var tapResetJob by remember { mutableStateOf<Job?>(null) }
    var beaverCenter by remember { mutableStateOf(Offset.Zero) }

    val activeParticles = remember { mutableStateListOf<EasterParticleData>() }
    var bigPopup by remember { mutableStateOf<BigEmojiPopupData?>(null) }
    var easterBanner by remember { mutableStateOf<EasterBannerData?>(null) }
    var bannerJob by remember { mutableStateOf<Job?>(null) }

    fun triggerPoopExplosion() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        coroutineScope.launch {
            beaverRotation.animateTo(
                targetValue = beaverRotation.value + 360f,
                animationSpec = tween(500, easing = FastOutSlowInEasing)
            )
        }
        bigPopup = BigEmojiPopupData(System.nanoTime(), "💩")

        bannerJob?.cancel()
        easterBanner = EasterBannerData(
            title = "Бобр одобряет! 🦫",
            subtitle = "Чистейший речной трафик! 🌊",
            emoji = "💩",
            glowColor = NeonAmber,
            borderColor = NeonAmber
        )
        bannerJob = coroutineScope.launch {
            delay(3200)
            easterBanner = null
        }

        val originX = if (beaverCenter.x > 0f) beaverCenter.x else 500f
        val originY = if (beaverCenter.y > 0f) beaverCenter.y else 150f

        val burstParticles = (0 until 24).map { i ->
            val angle = (i * (360.0 / 24.0) + Random.nextDouble(-12.0, 12.0)) * Math.PI / 180.0
            val speed = Random.nextFloat() * 450f + 350f
            EasterParticleData(
                id = System.nanoTime() + i,
                emoji = if (i % 6 == 0) "🪵" else if (i % 8 == 0) "🌊" else "💩",
                startX = originX,
                startY = originY,
                velocityX = (cos(angle) * speed).toFloat(),
                velocityY = (sin(angle) * speed - 160f).toFloat(),
                gravity = Random.nextFloat() * 450f + 650f,
                startScale = Random.nextFloat() * 0.4f + 0.9f,
                endScale = Random.nextFloat() * 0.3f + 0.6f,
                startRotation = Random.nextFloat() * 360f,
                rotationDelta = Random.nextFloat() * 720f - 360f,
                durationMs = Random.nextInt(1400, 2000)
            )
        }
        activeParticles.addAll(burstParticles)
    }

    fun triggerHeartExplosion() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        coroutineScope.launch {
            beaverScale.animateTo(1.4f, spring(dampingRatio = 0.35f, stiffness = 600f))
            beaverScale.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 400f))
        }
        bigPopup = BigEmojiPopupData(System.nanoTime(), "💖")

        bannerJob?.cancel()
        easterBanner = EasterBannerData(
            title = "Бобр любит вас! 🦫",
            subtitle = "Самый чистый и быстрый интернет! 💖",
            emoji = "💖",
            glowColor = Color(0xFFFF4081),
            borderColor = Color(0xFFFF4081)
        )
        bannerJob = coroutineScope.launch {
            delay(3200)
            easterBanner = null
        }

        val originX = if (beaverCenter.x > 0f) beaverCenter.x else 500f
        val originY = if (beaverCenter.y > 0f) beaverCenter.y else 150f

        val loveEmojis = listOf("❤️", "💖", "💕", "💝", "✨", "🥰", "🪵", "⚡")
        val burstParticles = (0 until 24).map { i ->
            val angle = (i * (360.0 / 24.0) + Random.nextDouble(-12.0, 12.0)) * Math.PI / 180.0
            val speed = Random.nextFloat() * 400f + 300f
            EasterParticleData(
                id = System.nanoTime() + i,
                emoji = loveEmojis.random(),
                startX = originX,
                startY = originY,
                velocityX = (cos(angle) * speed).toFloat(),
                velocityY = (sin(angle) * speed - 180f).toFloat(),
                gravity = Random.nextFloat() * 300f + 400f,
                startScale = Random.nextFloat() * 0.4f + 0.9f,
                endScale = Random.nextFloat() * 0.3f + 0.7f,
                startRotation = Random.nextFloat() * 360f,
                rotationDelta = Random.nextFloat() * 480f - 240f,
                durationMs = Random.nextInt(1500, 2200)
            )
        }
        activeParticles.addAll(burstParticles)
    }

    fun handleBeaverTap() {
        coroutineScope.launch {
            beaverScale.animateTo(1.3f, spring(dampingRatio = 0.35f, stiffness = 700f))
            beaverScale.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 500f))
        }

        tapCount++
        tapResetJob?.cancel()
        tapResetJob = coroutineScope.launch {
            delay(2500)
            tapCount = 0
        }

        if (tapCount >= 7) {
            tapCount = 0
            triggerHeartExplosion()
        } else {
            val originX = if (beaverCenter.x > 0f) beaverCenter.x else 500f
            val originY = if (beaverCenter.y > 0f) beaverCenter.y else 150f
            val emojis = listOf("❤️", "💖", "✨", "🔥", "⚡", "🪵", "🥰")
            val newParticles = (0 until 3).map { i ->
                EasterParticleData(
                    id = System.nanoTime() + i,
                    emoji = emojis.random(),
                    startX = originX + Random.nextFloat() * 40f - 20f,
                    startY = originY,
                    velocityX = Random.nextFloat() * 160f - 80f,
                    velocityY = -(Random.nextFloat() * 260f + 260f),
                    gravity = 50f,
                    startScale = 0.8f,
                    endScale = 1.3f,
                    startRotation = Random.nextFloat() * 40f - 20f,
                    rotationDelta = Random.nextFloat() * 60f - 30f,
                    durationMs = 1200
                )
            }
            activeParticles.addAll(newParticles)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundMain)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Top App Header: Centered Brand with Mascot
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .onGloballyPositioned { coords ->
                            val pos = coords.positionInRoot()
                            beaverCenter = Offset(pos.x + coords.size.width / 2f, pos.y + coords.size.height / 2f)
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    val holdJob = coroutineScope.launch {
                                        delay(400)
                                        while (isActive) {
                                            triggerPoopExplosion()
                                            delay(1500)
                                        }
                                    }
                                    tryAwaitRelease()
                                    holdJob.cancel()
                                },
                                onTap = { handleBeaverTap() }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_beaver),
                        contentDescription = "Logo",
                        modifier = Modifier
                            .size(34.dp)
                            .graphicsLayer {
                                scaleX = beaverScale.value
                                scaleY = beaverScale.value
                                rotationZ = beaverRotation.value
                            }
                    )
                }

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
                    ),
                    modifier = Modifier.clickable { handleBeaverTap() }
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

        Spacer(modifier = Modifier.height(24.dp))

        // Central Beaver Connection Button
        BeaverConnectButton(
            vpnState = vpnState,
            isBlocked = isBlocked,
            onToggle = {
                if (isBlocked) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    forceShowSubscriptionToast = true
                    subscriptionToastDismissed = false
                } else {
                    viewModel.toggleVpn(context) { intent ->
                        vpnPrepareLauncher.launch(intent)
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(30.dp))

        // Current Gateway Card
        CyberServerCard(
            server = selectedServer,
            isConnected = isConnected,
            onClick = {
                if (servers.isNotEmpty()) {
                    showQuickSwitchDialog = true
                } else {
                    onNavigateToServers()
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Real-Time Hydro Telemetry (RX / TX / Ping)
        HydroTelemetryBar(
            stats = tunnelStats,
            isConnected = isConnected
        )

        Spacer(modifier = Modifier.height(24.dp))
    }

    // Easter Egg Floating Particles Layer
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        activeParticles.forEach { particle ->
            key(particle.id) {
                EasterParticleItem(
                    particle = particle,
                    onFinished = {
                        activeParticles.remove(particle)
                    }
                )
            }
        }
    }

    // Easter Egg Big Emoji Animated Popup (Heart or Poop)
    BigEmojiOverlay(
        popup = bigPopup,
        onFinished = { bigPopup = null }
    )

    // Easter Egg Cyber Toast / Banner
    AnimatedVisibility(
        visible = easterBanner != null,
        enter = fadeIn(tween(250)) + slideInVertically(initialOffsetY = { -it }),
        exit = fadeOut(tween(300)) + slideOutVertically(targetOffsetY = { -it }),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 18.dp, start = 20.dp, end = 20.dp)
    ) {
        val banner = easterBanner ?: return@AnimatedVisibility
        Surface(
            color = Color(0xFF1E1812),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, banner.borderColor),
            shadowElevation = 10.dp,
            modifier = Modifier.neonGlow(banner.glowColor, radius = 12.dp, alpha = 0.45f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = banner.emoji,
                    fontSize = 26.sp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = banner.title,
                        color = banner.glowColor,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp
                    )
                    Text(
                        text = banner.subtitle,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    // Floating Cyberpunk Subscription Alert Toast
    SubscriptionAlertToast(
        status = currentSubStatus,
        visible = toastVisible,
        onDetailsClick = {
            subscriptionToastDismissed = true
            forceShowSubscriptionToast = false
            viewModel.setNavigationTab(com.bobrdobr.vpn.ui.viewmodel.NavigationTab.SUBSCRIPTION)
        },
        onDismiss = {
            subscriptionToastDismissed = true
            forceShowSubscriptionToast = false
        },
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 10.dp)
    )
}

    if (showQuickSwitchDialog) {
        QuickServerSwitchDialog(
            servers = servers,
            selectedServer = selectedServer,
            isConnected = isConnected,
            onSelectServer = { server ->
                viewModel.selectServer(server, context = context)
                showQuickSwitchDialog = false
            },
            onNavigateToFullList = {
                showQuickSwitchDialog = false
                onNavigateToServers()
            },
            onDismiss = { showQuickSwitchDialog = false }
        )
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
private fun QuickServerSwitchDialog(
    servers: List<ServerProfile>,
    selectedServer: ServerProfile,
    isConnected: Boolean,
    onSelectServer: (ServerProfile) -> Unit,
    onNavigateToFullList: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Выбор шлюза",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
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
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                servers.forEach { server ->
                    val isCurrent = server.id == selectedServer.id
                    val borderColor = if (isCurrent && isConnected) NeonEmerald else if (isCurrent) NeonCyan else CardBorder

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isCurrent) BackgroundCardActive else BackgroundInput)
                            .border(if (isCurrent) 1.5.dp else 0.5.dp, borderColor, RoundedCornerShape(12.dp))
                            .clickable { onSelectServer(server) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
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

                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(protocolColor.copy(alpha = 0.15f))
                                    .border(1.dp, protocolColor.copy(alpha = 0.4f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(id = protocolIcon),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column {
                                Text(
                                    text = server.name,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    ProtocolBadge(protocol = server.protocol)
                                    if (server.isFromSubscription) {
                                        DesktopTag(label = "sub", color = NeonPurple)
                                    }
                                }
                            }
                        }

                        if (isCurrent) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isConnected) NeonEmerald.copy(alpha = 0.15f) else NeonCyan.copy(alpha = 0.15f))
                                    .border(0.5.dp, if (isConnected) NeonEmerald else NeonCyan, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = if (isConnected) "Активен" else "Выбран",
                                    color = if (isConnected) NeonEmerald else NeonCyan,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onNavigateToFullList,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCyan.copy(alpha = 0.12f),
                    contentColor = NeonCyan
                ),
                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = NeonCyan
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Все серверы и управление", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = BackgroundCard,
        tonalElevation = 6.dp,
        shape = RoundedCornerShape(20.dp)
    )
}

private data class EasterParticleData(
    val id: Long,
    val emoji: String,
    val startX: Float,
    val startY: Float,
    val velocityX: Float,
    val velocityY: Float,
    val gravity: Float = 0f,
    val startScale: Float = 1f,
    val endScale: Float = 1f,
    val startRotation: Float = 0f,
    val rotationDelta: Float = 0f,
    val durationMs: Int = 1200
)

@Composable
private fun EasterParticleItem(
    particle: EasterParticleData,
    onFinished: () -> Unit
) {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(particle.id) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = particle.durationMs,
                easing = LinearEasing
            )
        )
        onFinished()
    }

    val p = progress.value
    val currentX = particle.startX + particle.velocityX * p
    val currentY = particle.startY + particle.velocityY * p + 0.5f * particle.gravity * p * p
    val currentAlpha = (1f - p).coerceIn(0f, 1f)
    val currentScale = particle.startScale + (particle.endScale - particle.startScale) * p
    val currentRotation = particle.startRotation + particle.rotationDelta * p

    Text(
        text = particle.emoji,
        fontSize = (24 * currentScale).sp,
        modifier = Modifier
            .offset { IntOffset(currentX.roundToInt(), currentY.roundToInt()) }
            .graphicsLayer {
                rotationZ = currentRotation
                alpha = currentAlpha
            }
    )
}

private data class BigEmojiPopupData(
    val id: Long,
    val emoji: String
)

private data class EasterBannerData(
    val title: String,
    val subtitle: String,
    val emoji: String,
    val glowColor: Color,
    val borderColor: Color
)

@Composable
private fun BigEmojiOverlay(
    popup: BigEmojiPopupData?,
    onFinished: () -> Unit
) {
    if (popup == null) return

    val scale = remember(popup.id) { Animatable(0.2f) }
    val alpha = remember(popup.id) { Animatable(1f) }
    val rotation = remember(popup.id) { Animatable(-25f) }

    LaunchedEffect(popup.id) {
        launch {
            scale.animateTo(
                targetValue = 2.8f,
                animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f)
            )
        }
        launch {
            rotation.animateTo(
                targetValue = 15f,
                animationSpec = tween(700, easing = FastOutSlowInEasing)
            )
        }
        delay(650)
        alpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(500, easing = LinearEasing)
        )
        onFinished()
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = popup.emoji,
            fontSize = 90.sp,
            modifier = Modifier.graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                rotationZ = rotation.value
                this.alpha = alpha.value
            }
        )
    }
}
