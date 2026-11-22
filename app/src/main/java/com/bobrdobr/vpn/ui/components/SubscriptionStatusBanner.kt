package com.bobrdobr.vpn.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bobrdobr.vpn.R
import com.bobrdobr.vpn.subscription.SubscriptionInfo
import com.bobrdobr.vpn.subscription.SubscriptionStatus
import com.bobrdobr.vpn.subscription.canConnect
import com.bobrdobr.vpn.ui.theme.BackgroundCard
import com.bobrdobr.vpn.ui.theme.BackgroundCardActive
import com.bobrdobr.vpn.ui.theme.NeonAmber
import com.bobrdobr.vpn.ui.theme.NeonCyan
import com.bobrdobr.vpn.ui.theme.NeonEmerald
import com.bobrdobr.vpn.ui.theme.NeonRed
import com.bobrdobr.vpn.ui.theme.TextPrimary
import com.bobrdobr.vpn.ui.theme.TextSecondary
import com.bobrdobr.vpn.ui.theme.neonGlow

/**
 * Visual metadata tuple for subscription status banner rendering.
 */
private data class BannerVisualConfig(
    val bgColor: Color,
    val borderColor: Color,
    val glowColor: Color,
    val title: String,
    val subtitle: String,
    val badgeText: String,
    val showRenewButton: Boolean = false,
    val showRefreshButton: Boolean = false
)

/**
 * SubscriptionStatusBanner: Cyberpunk styled reactive banner for dashboard.
 * Displays green when active (> 3 days), amber when expiring (<= 3 days),
 * red when expired or blocked, and offers one-click renewal.
 */
@Composable
fun SubscriptionStatusBanner(
    subInfo: SubscriptionInfo,
    onRenew: () -> Unit,
    onNavigateToSubscription: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!subInfo.hasSubscription) return

    val status = subInfo.currentStatus

    val config = when (status) {
        is SubscriptionStatus.Active -> {
            if (status.isWarningPeriod) {
                BannerVisualConfig(
                    bgColor = NeonAmber.copy(alpha = 0.12f),
                    borderColor = NeonAmber.copy(alpha = 0.6f),
                    glowColor = NeonAmber,
                    title = "Подписка истекает скоро",
                    subtitle = "Осталось: ${status.formattedRemaining}. Продлите доступ.",
                    badgeText = "ВНИМАНИЕ",
                    showRenewButton = true
                )
            } else {
                BannerVisualConfig(
                    bgColor = NeonEmerald.copy(alpha = 0.08f),
                    borderColor = NeonEmerald.copy(alpha = 0.35f),
                    glowColor = NeonEmerald,
                    title = "Подписка активна",
                    subtitle = "Осталось: ${status.formattedRemaining}",
                    badgeText = "АКТИВНА",
                    showRenewButton = false
                )
            }
        }
        is SubscriptionStatus.Expired -> {
            BannerVisualConfig(
                bgColor = NeonRed.copy(alpha = 0.14f),
                borderColor = NeonRed.copy(alpha = 0.7f),
                glowColor = NeonRed,
                title = "Срок подписки истек",
                subtitle = "Подключение к защищенным шлюзам приостановлено.",
                badgeText = "ИСТЕКЛА",
                showRenewButton = true
            )
        }
        is SubscriptionStatus.BlockedByAdmin -> {
            BannerVisualConfig(
                bgColor = NeonRed.copy(alpha = 0.14f),
                borderColor = NeonRed.copy(alpha = 0.7f),
                glowColor = NeonRed,
                title = "Аккаунт заблокирован",
                subtitle = "Доступ заблокирован администратором панели.",
                badgeText = "БЛОКИРОВКА",
                showRenewButton = false
            )
        }
        is SubscriptionStatus.InvalidToken -> {
            BannerVisualConfig(
                bgColor = NeonAmber.copy(alpha = 0.14f),
                borderColor = NeonAmber.copy(alpha = 0.7f),
                glowColor = NeonAmber,
                title = "Токен недействителен",
                subtitle = "Токен подписки отозван или не найден на сервере.",
                badgeText = "ОТОЗВАН",
                showRefreshButton = true
            )
        }
        is SubscriptionStatus.RateLimited -> {
            BannerVisualConfig(
                bgColor = NeonAmber.copy(alpha = 0.10f),
                borderColor = NeonAmber.copy(alpha = 0.45f),
                glowColor = NeonAmber,
                title = "Превышен лимит запросов",
                subtitle = "Частые запросы к панели временно ограничены.",
                badgeText = "ЛИМИТ"
            )
        }
        is SubscriptionStatus.NetworkError -> {
            BannerVisualConfig(
                bgColor = BackgroundCard,
                borderColor = Color.Gray.copy(alpha = 0.4f),
                glowColor = Color.Transparent,
                title = "Ошибка синхронизации",
                subtitle = status.message,
                badgeText = "СБОЙ"
            )
        }
        is SubscriptionStatus.None -> return
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .neonGlow(color = config.glowColor, radius = 8.dp, alpha = 0.2f)
            .clip(RoundedCornerShape(16.dp))
            .background(config.bgColor)
            .border(1.dp, config.borderColor, RoundedCornerShape(16.dp))
            .clickable { onNavigateToSubscription() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(config.borderColor.copy(alpha = 0.2f))
                        .border(1.dp, config.borderColor.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_subscription),
                        contentDescription = "Subscription Status",
                        tint = config.borderColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = config.title,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 13.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(config.borderColor.copy(alpha = 0.25f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = config.badgeText,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = config.borderColor,
                                    fontSize = 9.sp
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = config.subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextSecondary,
                            fontSize = 11.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (config.showRenewButton) {
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onRenew,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = config.borderColor,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.height(32.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = "Продлить",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    )
                }
            } else if (config.showRefreshButton) {
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onNavigateToSubscription,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonAmber,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.height(32.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = "Обновить",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    )
                }
            }
        }
    }
}

/**
 * SubscriptionBlockedExplanationDialog: Dialog shown when user attempts to connect
 * while subscription is expired, blocked by admin, or token revoked.
 */
@Composable
fun SubscriptionBlockedExplanationDialog(
    status: SubscriptionStatus,
    onDismiss: () -> Unit,
    onRenew: () -> Unit,
    onNavigateToSubscription: () -> Unit
) {
    val (title, message, confirmText, isRenew) = when (status) {
        is SubscriptionStatus.Expired -> Tuple4(
            "Подписка истекла",
            "Срок действия вашей подписки BobrDobr VPN подошел к концу. Для возобновления доступа к защищенным шлюзам продлите подписку в личном кабинете.",
            "Продлить подписку",
            true
        )
        is SubscriptionStatus.BlockedByAdmin -> Tuple4(
            "Аккаунт заблокирован",
            "Ваш аккаунт был заблокирован администратором панели управления. Подключение к серверным шлюзам временно приостановлено. Пожалуйста, обратитесь к администратору для разблокировки.",
            "Понятно",
            false
        )
        is SubscriptionStatus.InvalidToken -> Tuple4(
            "Токен недействителен",
            "Используемый токен подписки был отозван или удален на сервере. Пожалуйста, обновите ссылку на подписку в настройках.",
            "Обновить ссылку",
            false
        )
        else -> Tuple4(
            "Подключение недоступно",
            "Ваша подписка в данный момент неактивна. Пожалуйста, проверьте статус подписки в соответствующем разделе.",
            "Перейти",
            false
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = NeonRed
                    )
                )
            }
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = TextPrimary,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp
                )
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    if (isRenew) {
                        onRenew()
                    } else if (status is SubscriptionStatus.InvalidToken) {
                        onNavigateToSubscription()
                    }
                },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRenew) NeonAmber else NeonCyan,
                    contentColor = Color.Black
                )
            ) {
                Text(text = confirmText, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            if (isRenew || status is SubscriptionStatus.InvalidToken) {
                TextButton(onClick = onDismiss) {
                    Text(text = "Закрыть", color = TextSecondary)
                }
            }
        },
        containerColor = BackgroundCard,
        shape = RoundedCornerShape(18.dp)
    )
}

private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
