package com.bobrdobr.vpn.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bobrdobr.vpn.core.model.VpnState
import com.bobrdobr.vpn.ui.theme.BackgroundCard
import com.bobrdobr.vpn.ui.theme.CardBorder
import com.bobrdobr.vpn.ui.theme.CardBorderAmber
import com.bobrdobr.vpn.ui.theme.CardBorderEmerald
import com.bobrdobr.vpn.ui.theme.NeonAmber
import com.bobrdobr.vpn.ui.theme.NeonCyan
import com.bobrdobr.vpn.ui.theme.NeonEmerald
import com.bobrdobr.vpn.ui.theme.NeonRed
import com.bobrdobr.vpn.ui.theme.TextMuted
import com.bobrdobr.vpn.ui.theme.TextPrimary
import com.bobrdobr.vpn.ui.theme.TextSecondary
import com.bobrdobr.vpn.ui.theme.neonGlow

/**
 * Top cyberpunk status banner representing the state of the BobrDobr Dam.
 */
@Composable
fun DamStatusBar(
    vpnState: VpnState,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "dam_rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spin_angle"
    )

    val (title, subtitle, statusColor, borderColor, icon: ImageVector, isSpinning) = when (vpnState) {
        is VpnState.Connected -> StatusDisplay(
            title = "ПЛОТИНА ЗАЩИЩЕНА",
            subtitle = "Шлюзы закрыты • Трафик в безопасности",
            statusColor = NeonEmerald,
            borderColor = CardBorderEmerald,
            icon = Icons.Default.Security,
            isSpinning = false
        )
        is VpnState.Connecting -> StatusDisplay(
            title = "ВОЗВЕДЕНИЕ ПЛОТИНЫ...",
            subtitle = "Укрепление створок шлюза...",
            statusColor = NeonAmber,
            borderColor = CardBorderAmber,
            icon = Icons.Default.Refresh,
            isSpinning = true
        )
        is VpnState.Reconnecting -> StatusDisplay(
            title = "ВОССТАНОВЛЕНИЕ ШЛЮЗОВ",
            subtitle = "Попытка #${vpnState.attempt} • Роуминг сети",
            statusColor = NeonAmber,
            borderColor = CardBorderAmber,
            icon = Icons.Default.Refresh,
            isSpinning = true
        )
        is VpnState.Disconnecting -> StatusDisplay(
            title = "ОТКРЫТИЕ ШЛЮЗОВ...",
            subtitle = "Завершение защищенной сессии",
            statusColor = NeonAmber,
            borderColor = CardBorderAmber,
            icon = Icons.Default.Refresh,
            isSpinning = true
        )
        is VpnState.Error -> StatusDisplay(
            title = "ПРОРЫВ ПЛОТИНЫ!",
            subtitle = vpnState.message,
            statusColor = NeonRed,
            borderColor = NeonRed,
            icon = Icons.Default.Warning,
            isSpinning = false
        )
        VpnState.Disconnected -> StatusDisplay(
            title = "ШЛЮЗЫ ОТКРЫТЫ",
            subtitle = "Трафик не защищен • Нажмите «Подключить»",
            statusColor = NeonRed,
            borderColor = CardBorder,
            icon = Icons.Default.LockOpen,
            isSpinning = false
        )
    }

    val animatedBorderColor by animateColorAsState(
        targetValue = borderColor,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "border_color_anim"
    )

    val animatedStatusColor by animateColorAsState(
        targetValue = statusColor,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "status_color_anim"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .neonGlow(
                color = animatedStatusColor,
                radius = if (vpnState is VpnState.Connected || vpnState is VpnState.Connecting) 12.dp else 0.dp,
                alpha = 0.25f
            )
            .clip(RoundedCornerShape(16.dp))
            .background(BackgroundCard)
            .border(1.dp, animatedBorderColor, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Status icon circle
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(animatedStatusColor.copy(alpha = 0.15f))
                        .border(1.dp, animatedStatusColor.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = animatedStatusColor,
                        modifier = Modifier
                            .size(22.dp)
                            .then(if (isSpinning) Modifier.rotate(rotation) else Modifier)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = animatedStatusColor,
                            fontSize = 14.sp
                        )
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextSecondary,
                            fontSize = 11.5.sp
                        ),
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            // Status Pulsing Dot
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(animatedStatusColor)
            )
        }
    }
}

private data class StatusDisplay(
    val title: String,
    val subtitle: String,
    val statusColor: Color,
    val borderColor: Color,
    val icon: ImageVector,
    val isSpinning: Boolean
)
