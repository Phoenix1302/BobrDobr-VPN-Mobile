package com.bobrdobr.vpn.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bobrdobr.vpn.R
import com.bobrdobr.vpn.subscription.SubscriptionManager
import com.bobrdobr.vpn.ui.theme.BackgroundCard
import com.bobrdobr.vpn.ui.theme.BackgroundCardActive
import com.bobrdobr.vpn.ui.theme.BackgroundMain
import com.bobrdobr.vpn.ui.theme.CardBorder
import com.bobrdobr.vpn.ui.theme.NeonAmber
import com.bobrdobr.vpn.ui.theme.NeonCyan
import com.bobrdobr.vpn.ui.theme.NeonEmerald
import com.bobrdobr.vpn.ui.theme.NeonRed
import com.bobrdobr.vpn.ui.theme.TextMuted
import com.bobrdobr.vpn.ui.theme.TextPrimary
import com.bobrdobr.vpn.ui.theme.TextSecondary
import com.bobrdobr.vpn.ui.theme.neonGlow
import com.bobrdobr.vpn.ui.viewmodel.MainViewModel

/**
 * SubscriptionScreen: "Личный кабинет гидрозащиты".
 *
 * Dedicated subscription manager:
 * - Direct token URL input (no QR codes in subscription flow).
 * - Full account status from subscription payload (username, expires_at, server.host).
 * - Dynamic "Личный кабинет" opening https://<domain>/cloud.
 * - Desktop-aligned Electric Cyan & Emerald palette.
 */
@Composable
fun SubscriptionScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val subManager = remember { SubscriptionManager.getInstance(context) }
    val subInfo by subManager.subscriptionInfo.collectAsStateWithLifecycle()
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val isSubscribing by viewModel.isSubscribing.collectAsStateWithLifecycle()
    val subscriptionMessage by viewModel.subscriptionMessage.collectAsStateWithLifecycle()

    var inputUrl by remember { mutableStateOf("") }
    var showDisconnectDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundMain)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(NeonCyan.copy(alpha = 0.15f))
                    .border(1.dp, NeonCyan.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_subscription),
                    contentDescription = "Подписка",
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Подписка БобрДобр",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
                Text(
                    text = "Управление шлюзами и доступом по токену",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextSecondary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (subInfo.hasSubscription) {
            val subServersCount = remember(subInfo.profiles, servers) {
                if (subInfo.profiles.isNotEmpty()) {
                    subInfo.profiles.size
                } else {
                    servers.count { it.isFromSubscription }
                }
            }

            // STATE 1: ACTIVE SUBSCRIPTION
            ActiveSubscriptionCard(
                subInfo = subInfo,
                subServersCount = subServersCount,
                onOpenCabinet = {
                    val target = subInfo.getRenewalUrl()
                    if (target.isNotBlank()) {
                        try {
                            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(target))
                            context.startActivity(browserIntent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Не удалось открыть браузер: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "Ссылка на личный кабинет недоступна", Toast.LENGTH_SHORT).show()
                    }
                },
                onRefresh = { viewModel.refreshSubscription(context) },
                onDisconnect = { showDisconnectDialog = true },
                isSubscribing = isSubscribing,
                statusMessage = subscriptionMessage
            )
        } else {
            // STATE 2: NO SUBSCRIPTION -> INPUT FORM
            NoSubscriptionCard(
                inputUrl = inputUrl,
                onUrlChange = { inputUrl = it },
                onConnect = {
                    if (inputUrl.isNotBlank()) {
                        viewModel.refreshSubscription(context, inputUrl)
                    } else {
                        Toast.makeText(context, "Вставьте ссылку на подписку", Toast.LENGTH_SHORT).show()
                    }
                },
                isSubscribing = isSubscribing,
                statusMessage = subscriptionMessage
            )
        }

        Spacer(modifier = Modifier.height(30.dp))
    }

    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = {
                Text(
                    text = "Отключить подписку?",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Шлюзы подписки будут удалены из списка серверов. Вы сможете подключить подписку снова по вашей ссылке.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearSubscription(context)
                        showDisconnectDialog = false
                    }
                ) {
                    Text(text = "Отключить", color = NeonRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) {
                    Text(text = "Отмена", color = TextSecondary)
                }
            },
            containerColor = BackgroundCard,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun ActiveSubscriptionCard(
    subInfo: com.bobrdobr.vpn.subscription.SubscriptionInfo,
    subServersCount: Int,
    onOpenCabinet: () -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    isSubscribing: Boolean,
    statusMessage: String
) {
    val currentStatus = subInfo.currentStatus

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .neonGlow(color = NeonCyan, radius = 12.dp, alpha = 0.25f)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(BackgroundCardActive, BackgroundCard)
                )
            )
            .border(
                1.dp,
                Brush.horizontalGradient(listOf(NeonCyan, NeonEmerald.copy(alpha = 0.5f))),
                RoundedCornerShape(20.dp)
            )
            .padding(20.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ЛИЧНЫЙ КАБИНЕТ",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = NeonCyan,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subInfo.username.ifEmpty { "Пользователь" },
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                }

                val currentStatus = subInfo.currentStatus
                val (badgeBorder, badgeText, badgeColor) = when (currentStatus) {
                    is com.bobrdobr.vpn.subscription.SubscriptionStatus.Active -> {
                        if (currentStatus.isWarningPeriod) {
                            Triple(NeonAmber.copy(alpha = 0.6f), "ИСТЕКАЕТ СКОРО", NeonAmber)
                        } else {
                            Triple(NeonEmerald.copy(alpha = 0.6f), "АКТИВНА", NeonEmerald)
                        }
                    }
                    is com.bobrdobr.vpn.subscription.SubscriptionStatus.Expired -> Triple(NeonRed.copy(alpha = 0.7f), "ИСТЕКЛА", NeonRed)
                    is com.bobrdobr.vpn.subscription.SubscriptionStatus.BlockedByAdmin -> Triple(NeonRed.copy(alpha = 0.7f), "ЗАБЛОКИРОВАН", NeonRed)
                    is com.bobrdobr.vpn.subscription.SubscriptionStatus.InvalidToken -> Triple(NeonAmber.copy(alpha = 0.7f), "ОТОЗВАН", NeonAmber)
                    is com.bobrdobr.vpn.subscription.SubscriptionStatus.RateLimited -> Triple(NeonAmber.copy(alpha = 0.6f), "ЛИМИТ", NeonAmber)
                    is com.bobrdobr.vpn.subscription.SubscriptionStatus.NetworkError -> Triple(CardBorder, "СБОЙ", TextSecondary)
                    is com.bobrdobr.vpn.subscription.SubscriptionStatus.None -> Triple(CardBorder, "НЕТ ПОДПИСКИ", TextSecondary)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeColor.copy(alpha = 0.15f))
                        .border(1.dp, badgeBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = badgeColor,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (subInfo.serverHost.isNotBlank()) {
                SubscriptionDetailRow(label = "Сервер подписки:", value = subInfo.serverHost)
            }
            SubscriptionDetailRow(label = "Действует до:", value = subInfo.formattedExpirationDate)
            SubscriptionDetailRow(label = "Остаток срока:", value = subInfo.formattedDaysLeft)

            SubscriptionDetailRow(label = "Шлюзов в подписке:", value = "$subServersCount (AWG + OpenConnect)")

            if (statusMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = NeonCyan,
                        fontSize = 11.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Button 1: Dynamic Personal Cabinet / Renewal URL
            val isUrgent = currentStatus is com.bobrdobr.vpn.subscription.SubscriptionStatus.Expired ||
                    (currentStatus as? com.bobrdobr.vpn.subscription.SubscriptionStatus.Active)?.isWarningPeriod == true

            Button(
                onClick = onOpenCabinet,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isUrgent) NeonAmber else NeonCyan,
                    contentColor = BackgroundMain
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isUrgent) "Продлить подписку" else "Личный кабинет",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action row: Refresh & Disconnect (no text wrapping or cutoffs)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = Brush.horizontalGradient(listOf(NeonCyan, NeonEmerald))
                    ),
                    enabled = !isSubscribing
                ) {
                    if (isSubscribing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = NeonCyan,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Обновить",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonRed),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = Brush.horizontalGradient(listOf(NeonRed.copy(alpha = 0.6f), NeonRed))
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Отключить",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun NoSubscriptionCard(
    inputUrl: String,
    onUrlChange: (String) -> Unit,
    onConnect: () -> Unit,
    isSubscribing: Boolean,
    statusMessage: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .neonGlow(color = NeonCyan, radius = 8.dp, alpha = 0.15f)
            .clip(RoundedCornerShape(20.dp))
            .background(BackgroundCard)
            .border(1.dp, CardBorder, RoundedCornerShape(20.dp))
            .padding(20.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    tint = NeonCyan,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Подключение по ссылке",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Вставьте ссылку с токеном вашей подписки для автоматического получения всех доступных шлюзов AmneziaWG и OpenConnect. Ссылка выдается администратором или в личном кабинете.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = TextSecondary,
                    lineHeight = 18.sp
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = inputUrl,
                onValueChange = onUrlChange,
                placeholder = {
                    Text(
                        text = "https://sub.domain.com/api/v1/sub/...",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = BackgroundCardActive,
                    unfocusedContainerColor = BackgroundCardActive,
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = CardBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                singleLine = false,
                maxLines = 3
            )

            if (statusMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = if (statusMessage.contains("Ошибка")) NeonRed else NeonCyan,
                        fontSize = 11.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = onConnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCyan,
                    contentColor = BackgroundMain
                ),
                enabled = !isSubscribing
            ) {
                if (isSubscribing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = BackgroundMain,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Подключить подписку",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
        )
    }
}
