package com.bobrdobr.vpn.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class EasterParticleData(
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
fun EasterParticleItem(
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

data class BigEmojiPopupData(
    val id: Long,
    val emoji: String
)

data class EasterBannerData(
    val title: String,
    val subtitle: String,
    val emoji: String,
    val glowColor: Color,
    val borderColor: Color
)

@Composable
fun BigEmojiOverlay(
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
