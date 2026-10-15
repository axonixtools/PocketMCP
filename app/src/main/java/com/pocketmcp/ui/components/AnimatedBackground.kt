package com.pocketmcp.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.pocketmcp.ui.theme.NeuralBackground
import com.pocketmcp.ui.theme.NeuralGlowCyan
import com.pocketmcp.ui.theme.NeuralGlowViolet
import com.pocketmcp.ui.theme.NeuralSurface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun AnimatedBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "background")

    val orb1X by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(15000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb1x"
    )
    val orb1Y by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb1y"
    )

    val orb2X by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(18000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb2x"
    )
    val orb2Y by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb2y"
    )

    val orb3X by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb3x"
    )
    val orb3Y by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(16000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb3y"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NeuralBackground)
            .drawBehind {
                drawAmbientOrbs(orb1X, orb1Y, orb2X, orb2Y, orb3X, orb3Y)
            }
    ) {
        content()
    }
}

private fun DrawScope.drawAmbientOrbs(
    orb1X: Float, orb1Y: Float,
    orb2X: Float, orb2Y: Float,
    orb3X: Float, orb3Y: Float
) {
    val width = size.width
    val height = size.height

    // Large cyan orb
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                NeuralGlowCyan.copy(alpha = 0.08f),
                NeuralGlowCyan.copy(alpha = 0.02f),
                Color.Transparent
            ),
            center = Offset(width * orb1X, height * orb1Y),
            radius = width * 0.6f
        ),
        radius = width * 0.6f,
        center = Offset(width * orb1X, height * orb1Y)
    )

    // Violet orb
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                NeuralGlowViolet.copy(alpha = 0.07f),
                NeuralGlowViolet.copy(alpha = 0.02f),
                Color.Transparent
            ),
            center = Offset(width * orb2X, height * orb2Y),
            radius = width * 0.5f
        ),
        radius = width * 0.5f,
        center = Offset(width * orb2X, height * orb2Y)
    )

    // Small accent orb
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                NeuralGlowCyan.copy(alpha = 0.05f),
                Color.Transparent
            ),
            center = Offset(width * orb3X, height * orb3Y),
            radius = width * 0.4f
        ),
        radius = width * 0.4f,
        center = Offset(width * orb3X, height * orb3Y)
    )

    // Subtle grid pattern overlay
    val gridSpacing = 60.dp.toPx()
    val gridAlpha = 0.015f
    for (x in 0..(width / gridSpacing).toInt()) {
        drawLine(
            color = NeuralGlowCyan.copy(alpha = gridAlpha),
            start = Offset(x * gridSpacing, 0f),
            end = Offset(x * gridSpacing, height),
            strokeWidth = 0.5f
        )
    }
    for (y in 0..(height / gridSpacing).toInt()) {
        drawLine(
            color = NeuralGlowCyan.copy(alpha = gridAlpha),
            start = Offset(0f, y * gridSpacing),
            end = Offset(width, y * gridSpacing),
            strokeWidth = 0.5f
        )
    }
}
