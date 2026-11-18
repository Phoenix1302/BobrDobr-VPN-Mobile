package com.bobrdobr.vpn.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Neon Glow Modifier for rounded rectangular components.
 * Fully hardware-compatible with safe pure-Compose fallback to prevent Skia SIGSEGV
 * or UnsupportedOperationException in emulators and Firebase Test Lab.
 */
fun Modifier.neonGlow(
    color: Color,
    radius: Dp = 14.dp,
    cornerRadius: Dp = 16.dp,
    alpha: Float = 0.35f
): Modifier = this.drawBehind {
    if (radius <= 0.dp || alpha <= 0f) return@drawBehind

    var nativeRendered = false
    try {
        var isHw = false
        drawIntoCanvas { canvas ->
            isHw = canvas.nativeCanvas.isHardwareAccelerated
            if (!isHw) {
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    this.color = color.copy(alpha = alpha).toArgb()
                    this.maskFilter = android.graphics.BlurMaskFilter(
                        radius.toPx().coerceAtLeast(1f),
                        android.graphics.BlurMaskFilter.Blur.NORMAL
                    )
                }
                canvas.nativeCanvas.drawRoundRect(
                    0f, 0f, size.width, size.height,
                    cornerRadius.toPx(), cornerRadius.toPx(),
                    paint
                )
                nativeRendered = true
            }
        }
    } catch (_: Throwable) {
        nativeRendered = false
    }

    if (!nativeRendered) {
        // Safe Pure-Compose Multi-layer Concentric Fallback
        val layers = 5
        val spreadStep = (radius.toPx() / layers).coerceAtLeast(1f)
        val baseAlpha = alpha / layers
        for (i in 1..layers) {
            val expand = i * spreadStep
            val layerAlpha = (baseAlpha * (layers - i + 1) / layers).coerceIn(0f, 1f)
            drawRoundRect(
                color = color.copy(alpha = layerAlpha),
                topLeft = Offset(-expand, -expand),
                size = Size(
                    size.width + 2 * expand,
                    size.height + 2 * expand
                ),
                cornerRadius = CornerRadius(
                    cornerRadius.toPx() + expand,
                    cornerRadius.toPx() + expand
                )
            )
        }
    }
}

/**
 * Neon Glow Modifier for circular components like BeaverConnectButton.
 * Fully hardware-compatible with safe pure-Compose fallback.
 */
fun Modifier.circularNeonGlow(
    color: Color,
    radius: Dp = 20.dp,
    alpha: Float = 0.45f
): Modifier = this.drawBehind {
    if (radius <= 0.dp || alpha <= 0f) return@drawBehind
    val center = Offset(size.width / 2f, size.height / 2f)
    val circleRadius = minOf(size.width, size.height) / 2f

    var nativeRendered = false
    try {
        var isHw = false
        drawIntoCanvas { canvas ->
            isHw = canvas.nativeCanvas.isHardwareAccelerated
            if (!isHw) {
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    this.color = color.copy(alpha = alpha).toArgb()
                    this.maskFilter = android.graphics.BlurMaskFilter(
                        radius.toPx().coerceAtLeast(1f),
                        android.graphics.BlurMaskFilter.Blur.NORMAL
                    )
                }
                canvas.nativeCanvas.drawCircle(
                    center.x, center.y, circleRadius,
                    paint
                )
                nativeRendered = true
            }
        }
    } catch (_: Throwable) {
        nativeRendered = false
    }

    if (!nativeRendered) {
        // Safe Pure-Compose Multi-layer Concentric Fallback
        val layers = 5
        val spreadStep = (radius.toPx() / layers).coerceAtLeast(1f)
        val baseAlpha = alpha / layers
        for (i in 1..layers) {
            val layerAlpha = (baseAlpha * (layers - i + 1) / layers).coerceIn(0f, 1f)
            drawCircle(
                color = color.copy(alpha = layerAlpha),
                radius = circleRadius + (i * spreadStep),
                center = center
            )
        }
    }
}

/**
 * Glassmorphic surface styled with cyber borders.
 */
fun Modifier.glassmorphicCard(
    borderColor: Color = CardBorder,
    backgroundColor: Color = BackgroundCard,
    cornerRadius: Dp = 16.dp,
    borderWidth: Dp = 1.dp
): Modifier = this
    .clip(RoundedCornerShape(cornerRadius))
    .background(backgroundColor)
    .border(borderWidth, borderColor, RoundedCornerShape(cornerRadius))
