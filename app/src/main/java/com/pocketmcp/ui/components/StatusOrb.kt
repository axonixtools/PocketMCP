package com.pocketmcp.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pocketmcp.ui.theme.*

@Composable
fun StatusOrb(
    isRunning: Boolean,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 120.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRunning) 1.6f else 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isRunning) 1500 else 3000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = if (isRunning) 0.5f else 0.2f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isRunning) 1500 else 3000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    val secondRingScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRunning) 2.0f else 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isRunning) 2000 else 4000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Restart
        ),
        label = "secondRingScale"
    )

    val secondRingAlpha by infiniteTransition.animateFloat(
        initialValue = if (isRunning) 0.3f else 0.1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isRunning) 2000 else 4000, easing = EaseOutSine),
            repeatMode = RepeatMode.Restart
        ),
        label = "secondRingAlpha"
    )

    val coreColor = if (isRunning) NeuralSuccess else NeuralError
    val glowColor = if (isRunning) NeuralGlowGreen else NeuralError.copy(alpha = 0.5f)

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // Outer pulse ring 2
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawOrbRing(
                scale = secondRingScale,
                alpha = secondRingAlpha,
                color = glowColor
            )
        }

        // Outer pulse ring 1
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawOrbRing(
                scale = pulseScale,
                alpha = pulseAlpha,
                color = coreColor
            )
        }

        // Core orb
        Canvas(modifier = Modifier.fillMaxSize(0.45f)) {
            val radius = size.toPx() * 0.225f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        coreColor.copy(alpha = 0.9f),
                        coreColor.copy(alpha = 0.4f)
                    ),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        }
    }
}

private fun DrawScope.drawOrbRing(scale: Float, alpha: Float, color: Color) {
    val baseRadius = size.minDimension * 0.225f
    val radius = baseRadius * scale
    drawCircle(
        color = color.copy(alpha = alpha.coerceIn(0f, 1f)),
        radius = radius,
        center = center
    )
}

@Preview
@Composable
private fun StatusOrbRunningPreview() {
    PocketMCPTheme {
        Box(modifier = Modifier.padding(32.dp)) {
            StatusOrb(isRunning = true)
        }
    }
}

@Preview
@Composable
private fun StatusOrbStoppedPreview() {
    PocketMCPTheme {
        Box(modifier = Modifier.padding(32.dp)) {
            StatusOrb(isRunning = false)
        }
    }
}
