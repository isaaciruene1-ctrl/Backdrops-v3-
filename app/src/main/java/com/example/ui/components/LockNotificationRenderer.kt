package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.LockNotificationStyleConfig
import com.example.data.model.NotificationLayoutMode
import com.example.data.model.NotificationStylePreset
import com.example.engine.notifications.LockNotificationGroup
import com.example.engine.notifications.LockNotificationItem
import java.text.DateFormat
import java.util.Date

@Immutable
data class NotificationLayoutBounds(
    val topStartDp: Dp,
    val maxListHeightDp: Dp,
    val effectiveMaxGroups: Int,
    val forceCompact: Boolean
)

/**
 * Spatial lock-screen notification solver ensuring notification stacks never overlap clock glyphs,
 * date pills, active Studio widgets, or the bottom unlock button bar.
 */
object LockScreenNotificationLayoutSolver {

    fun solveAvailableZone(
        viewportHeightDp: Float,
        clockBottomFraction: Float,
        activeWidgetHeightDp: Float,
        bottomReserveDp: Float,
        styleConfig: LockNotificationStyleConfig
    ): NotificationLayoutBounds {
        val safeH = viewportHeightDp.coerceAtLeast(360f)
        val clockBottomDp = (safeH * clockBottomFraction.coerceIn(0.12f, 0.75f))
        val topStart = (clockBottomDp + activeWidgetHeightDp + 10f).coerceAtLeast(132f)
        val maxAvail = (safeH - topStart - bottomReserveDp).coerceAtLeast(72f)
        val approxRowHeight = when {
            styleConfig.layoutMode == NotificationLayoutMode.COMPACT_PILLS -> 44f
            styleConfig.compactTextHierarchy -> 64f
            else -> 76f
        }
        val fitGroups = (maxAvail / (approxRowHeight + styleConfig.cardSpacingDp))
            .toInt()
            .coerceIn(1, styleConfig.maxVisibleGroups)
        return NotificationLayoutBounds(
            topStartDp = topStart.dp,
            maxListHeightDp = maxAvail.dp,
            effectiveMaxGroups = fitGroups,
            forceCompact = maxAvail < 140f
        )
    }
}

@Composable
fun LockScreenNotificationsSection(
    groups: List<LockNotificationGroup>,
    styleConfig: LockNotificationStyleConfig,
    expandedKeys: MutableMap<String, Boolean>,
    hideSensitive: Boolean,
    maxVisibleOverride: Int = styleConfig.maxVisibleGroups,
    forceCompact: Boolean = false,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    onOpenItem: (LockNotificationItem) -> Unit,
    onDismissItem: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (groups.isEmpty()) return
    val safeCfg = remember(styleConfig) { styleConfig.sanitized() }
    val visibleGroups = remember(groups, maxVisibleOverride) {
        groups.take(maxVisibleOverride.coerceIn(1, safeCfg.maxVisibleGroups))
    }
    val effectiveLayout = if (forceCompact) NotificationLayoutMode.COMPACT_PILLS else safeCfg.layoutMode

    LazyColumn(
        modifier = modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .testTag("lockscreen_notifications_list"),
        verticalArrangement = Arrangement.spacedBy(safeCfg.cardSpacingDp.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        item(key = "notif_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Notifications,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(16.dp)
                    )
                    val totalCount = groups.sumOf { it.count }
                    Text(
                        text = "$totalCount Notification${if (totalCount == 1) "" else "s"} • ${safeCfg.preset.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.88f)
                    )
                }
                if (groups.any { g -> g.notifications.any { it.isClearable } }) {
                    TextButton(
                        onClick = onClearAll,
                        modifier = Modifier.testTag("lockscreen_clear_notifications_btn")
                    ) {
                        Text("Clear all", color = Color.White.copy(alpha = 0.92f))
                    }
                }
            }
        }

        items(visibleGroups, key = { it.packageName }) { group ->
            val lead = group.leadItem ?: return@items
            val isExpanded = when (effectiveLayout) {
                NotificationLayoutMode.EXPANDED_CARDS -> true
                NotificationLayoutMode.COMPACT_PILLS -> false
                NotificationLayoutMode.STACKED_BY_APP -> expandedKeys[group.packageName] == true
            }
            PresetStyledNotificationCard(
                group = group,
                lead = lead,
                styleConfig = safeCfg,
                layoutMode = effectiveLayout,
                expanded = isExpanded,
                hideSensitive = hideSensitive,
                accentColor = accentColor,
                onToggleExpand = {
                    if (effectiveLayout != NotificationLayoutMode.EXPANDED_CARDS) {
                        expandedKeys[group.packageName] = !isExpanded
                    }
                },
                onOpenItem = onOpenItem,
                onDismissItem = onDismissItem
            )
        }
    }
}

@Composable
fun PresetStyledNotificationCard(
    group: LockNotificationGroup,
    lead: LockNotificationItem,
    styleConfig: LockNotificationStyleConfig,
    layoutMode: NotificationLayoutMode,
    expanded: Boolean,
    hideSensitive: Boolean,
    accentColor: Color,
    onToggleExpand: () -> Unit,
    onOpenItem: (LockNotificationItem) -> Unit,
    onDismissItem: (String) -> Unit
) {
    val formattedTime = remember(lead.postTime) {
        if (lead.postTime > 0L) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lead.postTime)) else ""
    }
    val corner = styleConfig.cornerRadiusDp.dp
    val shape = RoundedCornerShape(corner)
    val baseBg = styleConfig.customBackgroundArgb?.let { Color(it.toInt()) } ?: when (styleConfig.preset) {
        NotificationStylePreset.APPLE_IOS -> Color(0xFF151923)
        NotificationStylePreset.XIAOMI_HYPEROS -> Color(0xFF171D28)
        NotificationStylePreset.SAMSUNG_ONE_UI -> Color(0xFF141820)
        NotificationStylePreset.GOOGLE_PIXEL -> lerp(Color(0xFF1C2029), accentColor, 0.24f)
        NotificationStylePreset.MINIMAL -> Color(0xFF0E1117)
        NotificationStylePreset.GLASS -> Color(0xFF1D2434)
        NotificationStylePreset.FLOATING -> Color(0xFF121622)
        NotificationStylePreset.COMPACT -> Color(0xFF10131C)
    }
    val containerColor = baseBg.copy(alpha = styleConfig.transparency)
    val borderColor = when (styleConfig.preset) {
        NotificationStylePreset.GLASS,
        NotificationStylePreset.APPLE_IOS -> Color.White.copy(alpha = 0.26f * (0.4f + styleConfig.blurIntensity * 0.6f))
        NotificationStylePreset.XIAOMI_HYPEROS -> accentColor.copy(alpha = 0.34f)
        NotificationStylePreset.GOOGLE_PIXEL -> accentColor.copy(alpha = 0.44f)
        NotificationStylePreset.MINIMAL -> Color.White.copy(alpha = 0.10f)
        else -> Color.White.copy(alpha = 0.18f)
    }

    val isLayeredStack = !expanded && group.count > 1 &&
        (styleConfig.preset == NotificationStylePreset.APPLE_IOS ||
            styleConfig.preset == NotificationStylePreset.XIAOMI_HYPEROS)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = shape,
            color = containerColor,
            border = BorderStroke(
                width = if (styleConfig.preset == NotificationStylePreset.GLASS) 1.25.dp else 0.9.dp,
                color = borderColor
            ),
            shadowElevation = (styleConfig.shadowIntensity * 12f).dp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .clickable(onClick = onToggleExpand)
                .testTag("lockscreen_notif_group_${group.packageName}")
        ) {
            val hPad = if (layoutMode == NotificationLayoutMode.COMPACT_PILLS || styleConfig.preset == NotificationStylePreset.COMPACT) 12.dp else 14.dp
            val vPad = if (layoutMode == NotificationLayoutMode.COMPACT_PILLS || styleConfig.preset == NotificationStylePreset.COMPACT) 8.dp else 11.dp
            Column(modifier = Modifier.padding(horizontal = hPad, vertical = vPad)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val iconShape = when (styleConfig.preset) {
                        NotificationStylePreset.SAMSUNG_ONE_UI -> RoundedCornerShape(10.dp)
                        NotificationStylePreset.APPLE_IOS -> RoundedCornerShape(8.dp)
                        else -> CircleShape
                    }
                    if (group.appIconBitmap != null) {
                        Image(
                            bitmap = group.appIconBitmap.asImageBitmap(),
                            contentDescription = group.appName,
                            modifier = Modifier
                                .size(styleConfig.iconSizeDp.dp)
                                .clip(iconShape)
                        )
                    } else {
                        Surface(
                            color = accentColor.copy(alpha = 0.30f),
                            shape = iconShape,
                            modifier = Modifier.size(styleConfig.iconSizeDp.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Notifications,
                                    contentDescription = group.appName,
                                    tint = Color.White,
                                    modifier = Modifier.size((styleConfig.iconSizeDp * 0.54f).dp)
                                )
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = group.appName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.78f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (group.count > 1) {
                                    Surface(
                                        color = accentColor.copy(alpha = 0.38f),
                                        shape = RoundedCornerShape(50)
                                    ) {
                                        Text(
                                            text = "${group.count}",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                            if (styleConfig.showTimestamp && formattedTime.isNotBlank()) {
                                Text(
                                    text = formattedTime,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.62f)
                                )
                            }
                        }

                        Text(
                            text = lead.senderOrTitle,
                            style = if (styleConfig.compactTextHierarchy) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!expanded && layoutMode != NotificationLayoutMode.COMPACT_PILLS) {
                            val maskedLead = hideSensitive && lead.isSensitive
                            Text(
                                text = if (maskedLead) "Content hidden while locked" else lead.bodyPreview,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.80f),
                                maxLines = if (styleConfig.compactTextHierarchy) 1 else 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (group.count > 1 || layoutMode == NotificationLayoutMode.STACKED_BY_APP) {
                        Icon(
                            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = if (expanded) "Collapse preview" else "Expand preview",
                            tint = Color.White.copy(alpha = 0.76f)
                        )
                    }
                }

                AnimatedVisibility(visible = expanded) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        group.notifications.take(4).forEach { item ->
                            val masked = hideSensitive && item.isSensitive
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Color.White.copy(alpha = 0.07f),
                                        RoundedCornerShape((styleConfig.cornerRadiusDp * 0.6f).dp)
                                    )
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    if (group.notifications.size > 1) {
                                        Text(
                                            text = item.senderOrTitle,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color.White,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        if (masked) {
                                            Icon(
                                                imageVector = Icons.Rounded.Lock,
                                                contentDescription = "Sensitive content hidden",
                                                tint = Color.White.copy(alpha = 0.7f),
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }
                                        Text(
                                            text = if (masked) "Notification content hidden until unlocked" else item.bodyPreview,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White.copy(alpha = 0.88f),
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                if (item.contentIntent != null) {
                                    IconButton(
                                        onClick = { onOpenItem(item) },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(Icons.Rounded.OpenInNew, contentDescription = "Open notification", tint = Color.White, modifier = Modifier.size(16.dp))
                                    }
                                }
                                if (item.isClearable) {
                                    IconButton(
                                        onClick = { onDismissItem(item.key) },
                                        modifier = Modifier
                                            .size(34.dp)
                                            .testTag("dismiss_notif_${item.key}")
                                    ) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Dismiss notification", tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Layered stacked depth shelf (iOS 27 / HyperOS 4 stack illusion)
        if (isLayeredStack) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .height(6.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                baseBg.copy(alpha = (styleConfig.transparency * 0.72f).coerceIn(0.2f, 0.85f)),
                                baseBg.copy(alpha = (styleConfig.transparency * 0.45f).coerceIn(0.15f, 0.7f))
                            )
                        ),
                        RoundedCornerShape(bottomStart = corner, bottomEnd = corner)
                    )
            )
            if (group.count > 2) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.84f)
                        .height(5.dp)
                        .background(
                            baseBg.copy(alpha = (styleConfig.transparency * 0.48f).coerceIn(0.12f, 0.65f)),
                            RoundedCornerShape(bottomStart = corner, bottomEnd = corner)
                        )
                )
            }
        }
    }
}
