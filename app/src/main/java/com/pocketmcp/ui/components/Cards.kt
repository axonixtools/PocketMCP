package com.pocketmcp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pocketmcp.ui.theme.AppTheme

/** Inset grouped container — the iOS-style rounded card that holds list rows. */
@Composable
fun GroupedCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = AppTheme.colors.card
    ) {
        Column(content = content)
    }
}

/** Hairline separator between list rows. */
@Composable
fun HairlineDivider(
    startInset: Dp = 16.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startInset)
            .height(0.5.dp)
            .background(AppTheme.colors.separator)
    )
}

/**
 * A grouped-list row: optional leading element, title, optional subtitle and
 * trailing content, with an optional hairline separator underneath.
 * Tappable rows get the standard iOS press highlight.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    showDivider: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val colors = AppTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressBackground by animateColorAsState(
        targetValue = if (pressed) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f) else Color.Transparent,
        animationSpec = tween(120),
        label = "rowPress"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(pressBackground)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor ?: colors.secondaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        if (showDivider) {
            HairlineDivider(startInset = if (leading != null) 50.dp else 16.dp)
        }
    }
}

/** Monochrome list icon. */
@Composable
fun RowIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = AppTheme.colors.secondaryLabel,
        modifier = Modifier.size(22.dp)
    )
}

/** iOS Settings-style colored tile icon. */
@Composable
fun RowTile(icon: ImageVector, color: Color) {
    Box(
        modifier = Modifier
            .size(29.dp)
            .background(color, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** Small disclosure chevron for navigation rows. */
@Composable
fun RowChevron() {
    Icon(
        imageVector = Icons.Rounded.ChevronRight,
        contentDescription = null,
        tint = AppTheme.colors.tertiaryLabel,
        modifier = Modifier.size(18.dp)
    )
}

/**
 * A titled group: optional section header, a card, and optional footer text —
 * matching the grouped-list rhythm used throughout the app. Header and footer
 * align with the leading edge of row content.
 */
@Composable
fun GroupedSection(
    title: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colors = AppTheme.colors
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondaryLabel,
                modifier = Modifier.padding(start = 16.dp)
            )
        }
        content()
        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondaryLabel,
                modifier = Modifier.padding(start = 16.dp)
            )
        }
    }
}

/**
 * iOS-style staggered entrance for top-level screen sections: content fades in
 * and lifts up with a small per-index delay, forming a gentle cascade.
 */
fun Modifier.sectionEntrance(index: Int): Modifier = composed {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val delayMillis = index * 45
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 240, delayMillis = delayMillis, easing = FastOutSlowInEasing),
        label = "sectionAlpha"
    )
    val lift by animateDpAsState(
        targetValue = if (appeared) 0.dp else 16.dp,
        animationSpec = tween(durationMillis = 320, delayMillis = delayMillis, easing = FastOutSlowInEasing),
        label = "sectionLift"
    )
    graphicsLayer {
        this.alpha = alpha
        translationY = lift.toPx()
    }
}
