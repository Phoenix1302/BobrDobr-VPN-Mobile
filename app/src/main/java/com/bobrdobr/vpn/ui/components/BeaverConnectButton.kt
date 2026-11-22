package com.bobrdobr.vpn.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bobrdobr.vpn.core.model.VpnState
import com.bobrdobr.vpn.ui.theme.BackgroundCard
import com.bobrdobr.vpn.ui.theme.BackgroundCardActive
import com.bobrdobr.vpn.ui.theme.BackgroundMain
import com.bobrdobr.vpn.ui.theme.CardBorder
import com.bobrdobr.vpn.ui.theme.NeonAmber
import com.bobrdobr.vpn.ui.theme.NeonAmberGlow
import com.bobrdobr.vpn.ui.theme.NeonEmerald
import com.bobrdobr.vpn.ui.theme.NeonEmeraldGlow
import com.bobrdobr.vpn.ui.theme.NeonRed
import com.bobrdobr.vpn.ui.theme.TextPrimary
import com.bobrdobr.vpn.ui.theme.TextSecondary
import com.bobrdobr.vpn.ui.theme.circularNeonGlow

/**
 * BeaverConnectButton: Signature connection button matching the mature desktop client style.
 *
 * Visual specification:
 * - NO images or mascots inside.
 * - Center typography:
 *   * Disconnected: "ПОДКЛЮЧИТЬ" / "НАЖМИТЕ ДЛЯ ЗАЩИТЫ"
 *   * Connecting: "ПОДКЛЮЧЕНИЕ..." / "УСТАНОВЛЕНИЕ СВЯЗИ"
 *   * Connected: "ПОДКЛЮЧЕНО" / "ЗАЩИЩЕНО"
 * - Perimeter animations:
 *   * Connecting: rapid orbital neon comet beam with glowing head and decaying trail.
 *   * Connected: vibrant solid neon-emerald ring with soft ambient glow.
 *   * Disconnected: clean dark graphite outline.
 */
@Composable
fun BeaverConnectButton(
    vpnState: VpnState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    isBlocked: Boolean = false
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1.0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "press_scale"
    )

    val isConnected = vpnState is VpnState.Connected
    val isConnecting = vpnState is VpnState.Connecting ||
            vpnState is VpnState.Reconnecting ||
            vpnState is VpnState.Disconnecting

    val infiniteTransition = rememberInfiniteTransition(label = "comet_transition")

    // Rapid rotating neon comet beam angle during connection
    val cometRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "comet_angle"
    )

    // Gentle breathing pulse for connected state
    val auraScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isConnected) 1.15f else if (isConnecting) 1.18f else 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isConnecting) 800 else 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "aura_scale"
    )

    val auraAlpha by infiniteTransition.animateFloat(
        initialValue = if (isConnected) 0.35f else if (isConnecting) 0.45f else 0f,
        targetValue = 0.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isConnecting) 800 else 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "aura_alpha"
    )

    val glowColor = when {
        isConnected -> NeonEmerald
        isConnecting -> NeonAmber
        vpnState is VpnState.Error || isBlocked -> NeonRed
        else -> Color.Transparent
    }

    val animatedGlowColor by animateColorAsState(
        targetValue = glowColor,
        animationSpec = tween(400),
        label = "glow_color"
    )

    Box(
        modifier = modifier
            .size(240.dp)
            .scale(pressScale),
        contentAlignment = Alignment.Center
    ) {
        // Layer 1: Ambient breathing aura (only when connected or connecting)
        if (isConnected || isConnecting) {
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .scale(auraScale)
                    .clip(CircleShape)
                    .background(animatedGlowColor.copy(alpha = auraAlpha))
            )
        }

        // Layer 2: Circular neon glow
        Box(
            modifier = Modifier
                .size(196.dp)
                .circularNeonGlow(
                    color = animatedGlowColor,
                    radius = if (isConnected) 24.dp else if (isConnecting) 18.dp else 0.dp,
                    alpha = if (isConnected) 0.5f else if (isConnecting) 0.4f else 0f
                )
        )

        // Layer 3: Perimeter Border / Beam
        when {
            isConnecting -> {
                // Static base outline
                Canvas(modifier = Modifier.size(190.dp)) {
                    drawCircle(
                        color = CardBorder,
                        radius = (size.minDimension - 2.dp.toPx()) / 2f,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }

                // Rapidly running orbital neon comet beam
                Canvas(
                    modifier = Modifier
                        .size(190.dp)
                        .rotate(cometRotation)
                ) {
                    val strokeWidth = 4.dp.toPx()
                    val sweep = Brush.sweepGradient(
                        0.0f to Color.Transparent,
                        0.55f to Color.Transparent,
                        0.75f to NeonAmber.copy(alpha = 0.3f),
                        0.92f to NeonAmber,
                        1.0f to NeonAmberGlow
                    )
                    drawCircle(
                        brush = sweep,
                        radius = (size.minDimension - strokeWidth) / 2f,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }
            }
            isConnected -> {
                // Continuous rich solid emerald neon ring
                Canvas(modifier = Modifier.size(190.dp)) {
                    val strokeWidth = 3.5.dp.toPx()
                    drawCircle(
                        color = NeonEmerald,
                        radius = (size.minDimension - strokeWidth) / 2f,
                        style = Stroke(width = strokeWidth)
                    )
                }
            }
            else -> {
                // Clean mature graphite contour
                Canvas(modifier = Modifier.size(190.dp)) {
                    val strokeWidth = 2.dp.toPx()
                    drawCircle(
                        color = CardBorder,
                        radius = (size.minDimension - strokeWidth) / 2f,
                        style = Stroke(width = strokeWidth)
                    )
                }
            }
        }

        // Layer 4: Inner Clickable Button Surface
        Box(
            modifier = Modifier
                .size(178.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            if (isConnected) BackgroundCardActive else BackgroundCard,
                            BackgroundMain
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    color = if (isConnected) NeonEmerald.copy(alpha = 0.5f) else CardBorder.copy(alpha = 0.6f),
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle()
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp)
            ) {
                when {
                    isConnecting -> {
                        Text(
                            text = "ПОДКЛЮЧЕНИЕ...",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = NeonAmber,
                                fontSize = 14.sp,
                                letterSpacing = 0.sp
                            ),
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "УСТАНОВЛЕНИЕ СВЯЗИ",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = NeonAmber.copy(alpha = 0.8f),
                                fontSize = 9.sp,
                                letterSpacing = 0.3.sp
                            ),
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    isConnected -> {
                        Text(
                            text = "ПОДКЛЮЧЕНО",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = NeonEmerald,
                                fontSize = 16.sp,
                                letterSpacing = 0.8.sp
                            ),
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "ЗАЩИЩЕНО",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = NeonEmerald.copy(alpha = 0.85f),
                                fontSize = 10.sp,
                                letterSpacing = 0.8.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    isBlocked -> {
                        Text(
                            text = "НЕДОСТУПНО",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = NeonRed,
                                fontSize = 15.sp,
                                letterSpacing = 0.5.sp
                            ),
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "ПОДПИСКА ТРЕБУЕТ ВНИМАНИЯ",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = NeonRed.copy(alpha = 0.85f),
                                fontSize = 8.5.sp,
                                letterSpacing = 0.2.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    else -> {
                        Text(
                            text = "ПОДКЛЮЧИТЬ",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary,
                                fontSize = 15.sp,
                                letterSpacing = 0.5.sp
                            ),
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "НАЖМИТЕ ДЛЯ ЗАЩИТЫ",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = TextSecondary,
                                fontSize = 9.sp,
                                letterSpacing = 0.3.sp
                            ),
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
