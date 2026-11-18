package com.bobrdobr.vpn.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bobrdobr.vpn.core.model.TunnelStats
import com.bobrdobr.vpn.ui.theme.BackgroundCard
import com.bobrdobr.vpn.ui.theme.CardBorder
import com.bobrdobr.vpn.ui.theme.NeonAmber
import com.bobrdobr.vpn.ui.theme.NeonCyan
import com.bobrdobr.vpn.ui.theme.NeonEmerald
import com.bobrdobr.vpn.ui.theme.TextMuted
import com.bobrdobr.vpn.ui.theme.TextPrimary
import com.bobrdobr.vpn.ui.theme.TextSecondary
import com.bobrdobr.vpn.ui.theme.neonGlow
import kotlin.math.sin

/**
 * HydroTelemetryBar: Real-time traffic, throughput, and link latency monitor.
 * Displays RX (incoming flood) and TX (outgoing dam discharge) with cyber sparklines.
 */
@Composable
fun HydroTelemetryBar(
    stats: TunnelStats,
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sparkline_anim")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .neonGlow(
                color = NeonCyan,
                radius = if (isConnected) 8.dp else 0.dp,
                alpha = 0.2f
            )
            .clip(RoundedCornerShape(18.dp))
            .background(BackgroundCard)
            .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header: Title & Ping
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ГИДРОТЕЛЕМЕТРИЯ ТУННЕЛЯ",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = TextMuted,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                )

                // Ping gauge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(NeonCyan.copy(alpha = 0.1f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Ping",
                        tint = NeonCyan,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isConnected && stats.pingMs > 0) "${stats.pingMs} мс" else "-- мс",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = NeonCyan,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Metrics Row: RX & TX
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Incoming RX stream
                TelemetryMetricItem(
                    label = "ВХОДЯЩИЙ (RX)",
                    currentSpeed = if (isConnected) stats.formattedRxSpeed else "0 bps",
                    totalBytes = if (isConnected) stats.formattedRxTotal else "0 B",
                    color = NeonCyan,
                    isIncoming = true,
                    isActive = isConnected && stats.rxSpeedBytesPerSec > 0,
                    wavePhase = wavePhase,
                    modifier = Modifier.weight(1f)
                )

                // Divider line
                Box(
                    modifier = Modifier
                        .height(48.dp)
                        .width(1.dp)
                        .background(CardBorder)
                )

                // Outgoing TX stream
                TelemetryMetricItem(
                    label = "ИСХОДЯЩИЙ (TX)",
                    currentSpeed = if (isConnected) stats.formattedTxSpeed else "0 bps",
                    totalBytes = if (isConnected) stats.formattedTxTotal else "0 B",
                    color = NeonEmerald,
                    isIncoming = false,
                    isActive = isConnected && stats.txSpeedBytesPerSec > 0,
                    wavePhase = wavePhase,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TelemetryMetricItem(
    label: String,
    currentSpeed: String,
    totalBytes: String,
    color: Color,
    isIncoming: Boolean,
    isActive: Boolean,
    wavePhase: Float,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon Circle
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.12f))
                .border(1.dp, color.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isIncoming) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    color = TextSecondary
                )
            )
            Text(
                text = currentSpeed,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = color,
                    fontSize = 15.sp
                )
            )
            Text(
                text = "Всего: $totalBytes",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 10.sp,
                    color = TextMuted
                )
            )

            // Dynamic mini sparkline
            if (isActive) {
                Spacer(modifier = Modifier.height(2.dp))
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                ) {
                    val path = Path()
                    val points = 16
                    val dx = size.width / (points - 1)
                    for (i in 0 until points) {
                        val x = i * dx
                        val y = (size.height / 2f) + (sin(wavePhase + i * 0.5f) * (size.height / 3f)).toFloat()
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path = path,
                        color = color.copy(alpha = 0.8f),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }
        }
    }
}
