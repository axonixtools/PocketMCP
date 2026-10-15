package com.pocketmcp.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketmcp.ui.theme.*

enum class NavPage(val title: String, val icon: @Composable () -> Unit) {
    Server("Server", { Icon(Icons.Rounded.Dns, null) }),
    Logs("Logs", { Icon(Icons.Rounded.Terminal, null) }),
    Tools("Tools", { Icon(Icons.Rounded.Extension, null) }),
    Settings("Settings", { Icon(Icons.Rounded.Settings, null) })
}

@Composable
fun AnimatedBottomNav(
    selectedPage: NavPage,
    onPageSelected: (NavPage) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = NeuralSurface.copy(alpha = 0.85f),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavPage.entries.forEach { page ->
                    val isSelected = page == selectedPage
                    NavItem(
                        page = page,
                        isSelected = isSelected,
                        onClick = { onPageSelected(page) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NavItem(
    page: NavPage,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.1f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 400f),
        label = "navScale"
    )

    Box(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AnimatedContent(
                targetState = isSelected,
                transitionSpec = {
                    fadeIn(animationSpec = tween(200)) togetherWith
                    fadeOut(animationSpec = tween(200))
                },
                label = "navIcon"
            ) { selected ->
                Icon(
                    imageVector = when (page) {
                        NavPage.Server -> androidx.compose.material.icons.Icons.Rounded.Dns
                        NavPage.Logs -> androidx.compose.material.icons.Icons.Rounded.Terminal
                        NavPage.Tools -> androidx.compose.material.icons.Icons.Rounded.Extension
                        NavPage.Settings -> androidx.compose.material.icons.Icons.Rounded.Settings
                    },
                    contentDescription = page.title,
                    tint = if (selected) NeuralPrimary else NeuralOnSurfaceMuted,
                    modifier = Modifier.size(24.dp)
                )
            }

            AnimatedVisibility(
                visible = isSelected,
                enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                exit = fadeOut(tween(150)) + shrinkVertically(tween(150))
            ) {
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(3.dp)
                        .background(NeuralPrimary, RoundedCornerShape(2.dp))
                )
            }

            Text(
                text = page.title,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) NeuralPrimary else NeuralOnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
