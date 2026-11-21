package com.bobrdobr.vpn.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CellWifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bobrdobr.vpn.R
import org.bobrdobr.vpn.core.model.VpnProtocol
import com.bobrdobr.vpn.ui.model.ServerProfile
import com.bobrdobr.vpn.ui.theme.BackgroundCard
import com.bobrdobr.vpn.ui.theme.BackgroundCardActive
import com.bobrdobr.vpn.ui.theme.CardBorder
import com.bobrdobr.vpn.ui.theme.CardBorderActive
import com.bobrdobr.vpn.ui.theme.NeonAmber
import com.bobrdobr.vpn.ui.theme.NeonCyan
import com.bobrdobr.vpn.ui.theme.NeonEmerald
import com.bobrdobr.vpn.ui.theme.NeonPurple
import com.bobrdobr.vpn.ui.theme.NeonRed
import com.bobrdobr.vpn.ui.theme.TextMuted
import com.bobrdobr.vpn.ui.theme.TextPrimary
import com.bobrdobr.vpn.ui.theme.TextSecondary
import com.bobrdobr.vpn.ui.theme.neonGlow

/**
 * CyberServerCard: High-tech server selection card.
 * Displays country flag, location, endpoint, protocol badge, ping latency, and neon selection glow.
 */
@Composable
fun CyberServerCard(
    server: ServerProfile,
    isConnected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pingColor = when {
        server.pingMs < 45 -> NeonEmerald
        server.pingMs < 100 -> NeonAmber
        else -> NeonRed
    }

    val isEmpty = server.serverAddress.isEmpty() || server.id == "empty"

    val targetBorderColor = when {
        isConnected -> NeonEmerald
        isEmpty -> CardBorder
        server.protocol == VpnProtocol.AMNEZIA_WG -> NeonAmber.copy(alpha = 0.55f)
        server.protocol == VpnProtocol.OPENCONNECT -> NeonCyan.copy(alpha = 0.55f)
        server.protocol == VpnProtocol.WIREGUARD -> Color(0xFF818CF8).copy(alpha = 0.55f)
        else -> CardBorder
    }

    val (glowColor, glowRadius, glowAlpha) = when {
        isConnected -> Triple(NeonEmerald, 12.dp, 0.3f)
        isEmpty -> Triple(Color.Transparent, 0.dp, 0f)
        server.protocol == VpnProtocol.AMNEZIA_WG -> Triple(NeonAmber, 6.dp, 0.2f)
        server.protocol == VpnProtocol.OPENCONNECT -> Triple(NeonCyan, 6.dp, 0.2f)
        server.protocol == VpnProtocol.WIREGUARD -> Triple(Color(0xFF818CF8), 6.dp, 0.2f)
        else -> Triple(Color.Transparent, 0.dp, 0f)
    }

    val animatedBorderColor by animateColorAsState(
        targetValue = targetBorderColor,
        animationSpec = tween(300),
        label = "card_border"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (glowAlpha > 0f) {
                    Modifier.neonGlow(
                        color = glowColor,
                        radius = glowRadius,
                        alpha = glowAlpha
                    )
                } else Modifier
            )
            .clip(RoundedCornerShape(20.dp))
            .background(if (isConnected) BackgroundCardActive else BackgroundCard)
            .border(1.dp, animatedBorderColor, RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Flag & Server Details
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                if (server.serverAddress.isEmpty() || server.id == "empty") {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(BackgroundCard)
                            .border(1.dp, CardBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CellWifi,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "Шлюз не выбран",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                        Text(
                            text = "Нажмите, чтобы добавить или выбрать шлюз",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        )
                    }
                } else {
                    val (protocolIcon, protocolColor) = when (server.protocol) {
                        VpnProtocol.AMNEZIA_WG -> Pair(R.drawable.ic_amnezia, NeonAmber)
                        VpnProtocol.WIREGUARD -> Pair(R.drawable.ic_amnezia, Color(0xFF818CF8))
                        VpnProtocol.OPENCONNECT -> Pair(R.drawable.ic_openconnect, NeonCyan)
                    }

                    // Protocol Emblem Circle (replaces generic globe)
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(protocolColor.copy(alpha = 0.12f))
                            .border(1.dp, protocolColor.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = protocolIcon),
                            contentDescription = server.protocol.displayName,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = server.name,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            ),
                            maxLines = 1
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = "${server.serverAddress}:${server.serverPort}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = TextSecondary
                            )
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Protocol, Tag & Ping Badges Row
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Protocol badge
                            ProtocolBadge(protocol = server.protocol)

                            if (server.isFromSubscription) {
                                Spacer(modifier = Modifier.width(6.dp))
                                DesktopTag(label = "sub", color = NeonPurple)
                            } else if (server.isCustom) {
                                Spacer(modifier = Modifier.width(6.dp))
                                DesktopTag(label = "custom", color = NeonAmber)
                            }

                            // Ping badge (only when ping is measured > 0)
                            if (server.pingMs > 0) {
                                Spacer(modifier = Modifier.width(8.dp))
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
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Right: Chevron Icon
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Выбрать сервер",
                tint = TextSecondary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
fun ProtocolBadge(protocol: VpnProtocol, modifier: Modifier = Modifier) {
    val (badgeColor, label) = when (protocol) {
        VpnProtocol.AMNEZIA_WG -> Pair(NeonAmber, "AmneziaWG")
        VpnProtocol.WIREGUARD -> Pair(Color(0xFF818CF8), "WireGuard")
        VpnProtocol.OPENCONNECT -> Pair(NeonCyan, "OpenConnect")
    }

    Box(
        modifier = modifier
            .wrapContentWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(badgeColor.copy(alpha = 0.15f))
            .border(0.5.dp, badgeColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                color = badgeColor,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            ),
            maxLines = 1,
            softWrap = false
        )
    }
}
