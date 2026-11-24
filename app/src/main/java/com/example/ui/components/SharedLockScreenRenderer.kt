package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.data.model.ChargingAnimationConfig
import com.example.data.model.ChargingAnimationPreset
import com.example.data.model.ChargingPosition
import com.example.data.model.LockNotificationStyleConfig
import com.example.data.model.StackedWallpaperDeckConfig
import com.example.data.model.StudioWidgetItem
import com.example.data.model.UiStyleMode
import com.example.data.model.UnlockButtonStyle
import com.example.data.model.UnlockButtonThemeConfig
import com.example.data.model.UserPreferencesDoc
import com.example.data.model.Wallpaper
import com.example.engine.notifications.LockNotificationGroup
import com.example.engine.notifications.LockNotificationItem
import com.example.ui.screens.CanvasLookFilter
import com.example.ui.theme.LocalUiStyleMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Unified Lock Screen Canvas Renderer shared identically by:
 * - Live Lock Screen Mode (`BackdropLockActivity` & `LockScreenOverlaySurface`)
 * - Lock Screen Mode interactive preview (`LockScreenModeScreen`)
 * - Preview Studio's Lock Screen tab (`PreviewStudioScreen`)
 *
 * Guarantees exact 1:1 visual parity across clock placement (`placementY`, `offsetXFraction`),
 * color customization, Glow & Liquid Glass materials, non-overlapping notifications,
 * charging animations, active Lock Screen widgets, and themeable Unlock actions.
 */
@Composable
fun SharedLockScreenRenderer(
    wallpaper: Wallpaper,
    customBitmap: Bitmap?,
    backdropBitmap: Bitmap?,
    foregroundSubjectCutout: Bitmap? = null,
    clockConfig: ClockDesignConfig,
    telemetry: ClockWidgetTelemetry,
    preferences: UserPreferencesDoc = UserPreferencesDoc(),
    zoomScale: Float = 1.0f,
    panOffset: Offset = Offset.Zero,
    rotationDegrees: Float = 0f,
    dimFraction: Float = 0f,
    lookFilter: CanvasLookFilter = CanvasLookFilter.ORIGINAL,
    notificationGroups: List<LockNotificationGroup> = emptyList(),
    expandedNotificationKeys: MutableMap<String, Boolean> = remember { mutableMapOf() },
    hideSensitiveNotifications: Boolean = false,
    isCharging: Boolean = false,
    previewChargingOverlay: Boolean = false,
    showChromeControls: Boolean = true,
    isPreviewMode: Boolean = false,
    useLiveTime: Boolean = true,
    onUnlockClick: () -> Unit = {},
    onOpenNotification: (LockNotificationItem) -> Unit = {},
    onDismissNotification: (String) -> Unit = {},
    onClearAllNotifications: () -> Unit = {},
    onUpdateStackedDeck: (StackedWallpaperDeckConfig) -> Unit = {},
    onOpenWallpaper: ((Wallpaper) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val uiStyle = LocalUiStyleMode.current
    val isGlass = uiStyle == UiStyleMode.FROSTED_GLASS_OS

    val primaryColor = clockConfig.customPrimaryArgb?.let { Color(it.toInt()) } ?: Color(0xFFF6F8FC)
    val secondaryColor = clockConfig.customSecondaryArgb?.let { Color(it.toInt()) }
        ?: wallpaper.dominantColors.firstOrNull()?.let { Color(it) }
        ?: MaterialTheme.colorScheme.primary

    val notificationConfig = remember(preferences.notificationStyleJson) {
        preferences.parsedNotificationStyle
    }
    val chargingConfig = remember(preferences.chargingAnimationJson) {
        preferences.parsedChargingAnimation
    }
    val unlockTheme = remember(preferences.unlockButtonThemeJson) {
        preferences.parsedUnlockButtonTheme
    }
    val studioWidgets = remember(preferences.studioWidgetsJson) {
        preferences.parsedStudioWidgets
    }
    val stackedDeck = remember(preferences.stackedWallpaperDeckJson) {
        preferences.parsedStackedWallpaperDeck
    }

    var clockUnscaledSize by remember { mutableStateOf(IntSize(420, 180)) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val viewportPx = IntSize(
            width = with(density) { maxWidth.toPx().roundToInt().coerceAtLeast(1) },
            height = with(density) { maxHeight.toPx().roundToInt().coerceAtLeast(1) }
        )
        val marginPx = with(density) { 10.dp.toPx() }
        val resolvedClockBox = remember(clockUnscaledSize, viewportPx, clockConfig, marginPx) {
            ClockSpatialMath.computeClockBoxRect(
                unscaledWidthPx = clockUnscaledSize.width.toFloat(),
                unscaledHeightPx = clockUnscaledSize.height.toFloat(),
                viewportSize = viewportPx,
                config = clockConfig,
                minMarginPx = marginPx
            )
        }

        // 1. Background Wallpaper + Transform + Look Filter
        val artworkModifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                if (customBitmap == null) {
                    val s = zoomScale.coerceIn(0.85f, 4.0f)
                    scaleX = s
                    scaleY = s
                    translationX = panOffset.x
                    translationY = panOffset.y
                    rotationZ = rotationDegrees
                }
            }

        if (customBitmap != null) {
            Image(
                bitmap = customBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = lookFilter.toComposeColorFilter(),
                modifier = artworkModifier
            )
        } else {
            WallpaperAsyncImage(
                wallpaper = wallpaper,
                useFullRes = true,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = lookFilter.toComposeColorFilter(),
                modifier = artworkModifier
            )
        }

        // Subtle vignette & legibility dim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = (0.24f + dimFraction * 0.5f).coerceAtMost(0.88f)),
                            Color.Black.copy(alpha = dimFraction.coerceAtMost(0.75f)),
                            Color.Black.copy(alpha = (0.48f + dimFraction * 0.45f).coerceAtMost(0.92f))
                        )
                    )
                )
        )

        // 2. Clock positioned accurately via ClockSpatialMath.computeClockBoxRect (placementY + offsetXFraction)
        val clockLeftPx = resolvedClockBox.leftPx.roundToInt()
        val clockTopPx = resolvedClockBox.topPx.roundToInt()
        Box(
            modifier = Modifier
                .offset { IntOffset(x = clockLeftPx, y = clockTopPx) }
        ) {
            HyperOsLockClock(
                face = clockConfig.face,
                font = clockConfig.font,
                finish = clockConfig.finish,
                primaryColor = primaryColor,
                secondaryColor = secondaryColor,
                weightLevel = clockConfig.weightLevel,
                scaleFactor = clockConfig.scale,
                widthScale = clockConfig.widthScale,
                heightScale = clockConfig.heightScale,
                opacity = clockConfig.opacity,
                alignment = clockConfig.alignment,
                useLiveTime = useLiveTime,
                is24HourFormat = clockConfig.is24HourFormat,
                showDateAndWeather = clockConfig.showDateAndWeather,
                widgetsEnabled = clockConfig.widgetsEnabled,
                showDateWidget = clockConfig.showDateWidget,
                showWeatherWidget = clockConfig.showWeatherWidget,
                showBatteryWidget = clockConfig.showBatteryWidget,
                showDecorativeWidget = clockConfig.showDecorativeWidget,
                telemetry = telemetry,
                signatureMode = clockConfig.signatureMode,
                signatureText = clockConfig.signatureText,
                liquidGlassConfig = clockConfig.liquidGlass,
                backdropBitmap = backdropBitmap,
                viewportSize = viewportPx,
                clockBoxRect = resolvedClockBox,
                zoomScale = if (customBitmap == null) zoomScale else 1f,
                panOffset = if (customBitmap == null) panOffset else Offset.Zero,
                dimFraction = dimFraction,
                lookFilter = lookFilter,
                rotationDegrees = if (customBitmap == null) rotationDegrees else 0f,
                scaleTextWithClock = clockConfig.scaleTextWithClock,
                glowConfig = clockConfig.glowConfig,
                onMeasuredUnscaledSize = { measured ->
                    if (measured.width > 0 && measured.height > 0) {
                        clockUnscaledSize = measured
                    }
                }
            )
        }

        // Optional foreground multi-subject depth cutout layer over clock digits
        if (clockConfig.depthOverlapEnabled && foregroundSubjectCutout != null && !foregroundSubjectCutout.isRecycled) {
            Image(
                bitmap = foregroundSubjectCutout.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = artworkModifier
            )
        }

        // 3. Top Lock Protection Status (no top-right battery percentage widget per Backdrop 1.3 spec)
        if (showChromeControls && preferences.hasLockCredential) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 14.dp)
                    .testTag("lockscreen_status_bar"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = "Backdrop Lock Protected",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = preferences.parsedLockCredentialType.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.82f)
                )
            }
        }

        // 4. Widgets + Dynamic Non-Overlapping Notifications Zone
        val clockBottomFrac = (resolvedClockBox.bottomPx / viewportPx.height.coerceAtLeast(1).toFloat())
            .coerceIn(0.16f, 0.72f)
        val hasEnabledWidgets = studioWidgets.any { it.enabled } && clockConfig.widgetsEnabled
        val widgetReserveDp = if (hasEnabledWidgets) 128f else 0f
        val showChargingNow = chargingConfig.enabled &&
            chargingConfig.preset != ChargingAnimationPreset.OFF &&
            (isCharging || previewChargingOverlay)
        val bottomReserveDp = if (showChromeControls) {
            if (showChargingNow && chargingConfig.position == ChargingPosition.BOTTOM) 186f else 132f
        } else {
            92f
        }
        val notifZone = LockScreenNotificationLayoutSolver.solveAvailableZone(
            viewportHeightDp = maxHeight.value,
            clockBottomFraction = clockBottomFrac,
            activeWidgetHeightDp = widgetReserveDp,
            bottomReserveDp = bottomReserveDp,
            styleConfig = notificationConfig
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = (maxHeight * clockBottomFrac) + 8.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (hasEnabledWidgets) {
                StudioWidgetsLockStrip(
                    widgets = studioWidgets,
                    stackedDeck = stackedDeck,
                    telemetry = telemetry,
                    isCharging = isCharging,
                    is24HourFormat = clockConfig.is24HourFormat,
                    onUpdateStackedDeck = onUpdateStackedDeck,
                    onOpenWallpaper = onOpenWallpaper
                )
            }

            if (notificationGroups.isNotEmpty()) {
                LockScreenNotificationsSection(
                    groups = notificationGroups,
                    styleConfig = notificationConfig,
                    expandedKeys = expandedNotificationKeys,
                    hideSensitive = hideSensitiveNotifications,
                    maxVisibleOverride = notifZone.effectiveMaxGroups,
                    forceCompact = notifZone.forceCompact,
                    accentColor = secondaryColor,
                    onOpenItem = onOpenNotification,
                    onDismissItem = onDismissNotification,
                    onClearAll = onClearAllNotifications,
                    modifier = Modifier.heightIn(max = notifZone.maxListHeightDp)
                )
            }
        }

        // 5. Animated Charging Overlay (Center or Bottom above Unlock Bar)
        AnimatedVisibility(
            visible = showChargingNow,
            enter = fadeIn(tween(260)),
            exit = fadeOut(tween(220)),
            modifier = Modifier
                .align(if (chargingConfig.position == ChargingPosition.CENTER) Alignment.Center else Alignment.BottomCenter)
                .then(
                    if (chargingConfig.position == ChargingPosition.BOTTOM) {
                        Modifier.navigationBarsPadding().padding(bottom = if (showChromeControls) 114.dp else 24.dp)
                    } else {
                        Modifier
                    }
                )
        ) {
            LockScreenChargingAnimationOverlay(
                config = chargingConfig,
                batteryPercent = telemetry.batteryPercent,
                dynamicAccent = secondaryColor
            )
        }

        // 6. Themeable Unlock Button + Clean Bottom Hint ("Swipe up or tap unlock")
        if (showChromeControls) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.42f)
                ) {
                    Text(
                        text = "Swipe up or tap unlock",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.88f),
                        modifier = Modifier
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .testTag("lockscreen_unlock_hint")
                    )
                }

                ThemedUnlockButton(
                    themeConfig = unlockTheme,
                    dynamicAccent = secondaryColor,
                    isGlassMode = isGlass,
                    label = when {
                        isPreviewMode -> "Exit Preview"
                        preferences.hasLockCredential -> "Unlock with ${preferences.parsedLockCredentialType.label}"
                        else -> "Unlock Device"
                    },
                    onClick = onUnlockClick
                )
            }
        }
    }
}

@Composable
fun ThemedUnlockButton(
    themeConfig: UnlockButtonThemeConfig,
    dynamicAccent: Color,
    isGlassMode: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = when (themeConfig.style) {
        UnlockButtonStyle.DYNAMIC_ACCENT -> dynamicAccent.copy(alpha = themeConfig.transparency)
        UnlockButtonStyle.SOLID -> Color.White.copy(alpha = themeConfig.transparency.coerceAtLeast(0.85f))
        UnlockButtonStyle.FROSTED -> Color(0xFF141824).copy(alpha = (themeConfig.transparency * 0.68f).coerceIn(0.32f, 0.85f))
        UnlockButtonStyle.TRANSPARENT -> Color.Black.copy(alpha = 0.24f)
        UnlockButtonStyle.M3_TONAL -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = themeConfig.transparency)
        UnlockButtonStyle.CUSTOM -> Color(themeConfig.customContainerArgb.toInt()).copy(alpha = themeConfig.transparency)
    }
    val contentColor = when (themeConfig.style) {
        UnlockButtonStyle.DYNAMIC_ACCENT -> Color.White
        UnlockButtonStyle.SOLID -> Color(0xFF10131C)
        UnlockButtonStyle.FROSTED,
        UnlockButtonStyle.TRANSPARENT -> Color.White
        UnlockButtonStyle.M3_TONAL -> MaterialTheme.colorScheme.onSecondaryContainer
        UnlockButtonStyle.CUSTOM -> Color(themeConfig.customContentArgb.toInt())
    }
    val border = when {
        themeConfig.style == UnlockButtonStyle.FROSTED || (isGlassMode && themeConfig.style != UnlockButtonStyle.SOLID) ->
            BorderStroke(1.dp, Color.White.copy(alpha = 0.44f))
        themeConfig.style == UnlockButtonStyle.TRANSPARENT ->
            BorderStroke(1.dp, Color.White.copy(alpha = 0.55f))
        else -> null
    }

    Button(
        onClick = onClick,
        shape = RoundedCornerShape(themeConfig.cornerRadiusDp.dp),
        border = border,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        modifier = modifier
            .height(48.dp)
            .testTag("lockscreen_unlock_button")
    ) {
        Icon(Icons.Rounded.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun LockScreenChargingAnimationOverlay(
    config: ChargingAnimationConfig,
    batteryPercent: Int,
    dynamicAccent: Color,
    modifier: Modifier = Modifier
) {
    val accent = if (config.dynamicAccentColor) dynamicAccent else Color(config.customAccentArgb.toInt())
    val safePct = batteryPercent.coerceIn(0, 100)
    val durationMs = (1900f / config.speedMultiplier.coerceIn(0.5f, 2f)).roundToInt().coerceIn(700, 4200)
    val transition = rememberInfiniteTransition(label = "lockscreen_charging")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMs, easing = LinearEasing), RepeatMode.Restart),
        label = "charging_phase"
    )
    val pulseScale = 0.94f + 0.08f * sin(phase * 2.0 * PI).toFloat()
    val sizeDp = (108f * config.sizeScale.coerceIn(0.65f, 1.5f)).dp

    when (config.preset) {
        ChargingAnimationPreset.OFF -> Unit

        ChargingAnimationPreset.MINIMAL_BEAM -> {
            Surface(
                shape = RoundedCornerShape(50),
                color = Color(0xFF0E131E).copy(alpha = 0.72f),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.65f)),
                modifier = modifier.testTag("lockscreen_charging_overlay")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BatteryChargingFull,
                        contentDescription = "Charging",
                        tint = accent,
                        modifier = Modifier.size(18.dp).graphicsLayer { scaleX = pulseScale; scaleY = pulseScale }
                    )
                    if (config.showPercentage) {
                        Text("$safePct%", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    if (config.showStatusText) {
                        Text("Fast Charging", style = MaterialTheme.typography.labelSmall, color = accent)
                    }
                }
            }
        }

        else -> {
            Box(
                modifier = modifier
                    .size(sizeDp)
                    .testTag("lockscreen_charging_overlay"),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val c = Offset(size.width * 0.5f, size.height * 0.5f)
                    val radius = size.minDimension * 0.42f
                    when (config.preset) {
                        ChargingAnimationPreset.PULSE_RING -> {
                            drawCircle(color = Color.Black.copy(alpha = 0.45f), radius = radius, center = c)
                            drawCircle(
                                color = accent.copy(alpha = (0.35f * (1f - phase)).coerceIn(0.05f, 0.45f)),
                                radius = radius * (0.85f + phase * 0.30f),
                                center = c,
                                style = Stroke(width = 3.dp.toPx())
                            )
                            drawArc(
                                color = accent,
                                startAngle = -90f,
                                sweepAngle = (safePct / 100f) * 360f,
                                useCenter = false,
                                topLeft = Offset(c.x - radius, c.y - radius),
                                size = Size(radius * 2f, radius * 2f),
                                style = Stroke(width = 5.5.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                        ChargingAnimationPreset.LIQUID_WAVE -> {
                            drawCircle(color = Color(0xFF0E131D).copy(alpha = 0.65f), radius = radius, center = c)
                            val fillTop = c.y + radius - (safePct / 100f) * (radius * 2f)
                            val wavePath = Path().apply {
                                val startX = c.x - radius
                                val endX = c.x + radius
                                moveTo(startX, c.y + radius)
                                lineTo(startX, fillTop)
                                val steps = 16
                                for (i in 0..steps) {
                                    val t = i / steps.toFloat()
                                    val x = startX + (endX - startX) * t
                                    val wave = sin(t * 2f * PI + phase * 2f * PI).toFloat() * 4.dp.toPx()
                                    lineTo(x, fillTop + wave)
                                }
                                lineTo(endX, c.y + radius)
                                close()
                            }
                            drawPath(path = wavePath, color = accent.copy(alpha = 0.45f))
                            drawCircle(color = accent.copy(alpha = 0.78f), radius = radius, center = c, style = Stroke(width = 2.5.dp.toPx()))
                        }
                        ChargingAnimationPreset.ORB_HALO -> {
                            drawCircle(color = Color.Black.copy(alpha = 0.42f), radius = radius, center = c)
                            drawArc(
                                color = accent,
                                startAngle = phase * 360f,
                                sweepAngle = 220f,
                                useCenter = false,
                                topLeft = Offset(c.x - radius, c.y - radius),
                                size = Size(radius * 2f, radius * 2f),
                                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                        else -> Unit
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.BatteryChargingFull,
                        contentDescription = "Charging",
                        tint = accent,
                        modifier = Modifier.size((18f * config.sizeScale).dp)
                    )
                    if (config.showPercentage) {
                        Text(
                            text = "$safePct%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    if (config.showStatusText) {
                        Text(
                            text = "Charging",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.78f)
                        )
                    }
                }
            }
        }
    }
}
