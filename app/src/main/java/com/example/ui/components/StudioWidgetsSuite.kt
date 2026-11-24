package com.example.ui.components

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.catalog.WallpaperCatalog
import com.example.data.model.StackedWallpaperDeckConfig
import com.example.data.model.StudioWidgetItem
import com.example.data.model.StudioWidgetType
import com.example.data.model.UiStyleMode
import com.example.data.model.Wallpaper
import com.example.engine.notifications.BackdropNotificationListenerService
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalThemeCustomization
import com.example.ui.theme.LocalUiStyleMode
import com.example.ui.theme.rememberStudioHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure mathematical core for HyperOS 4-inspired Stacked Wallpaper Widget bidirectional 3D Y-axis card flipping.
 */
object StackedWallpaperFlipMath {

    fun advanceIndex(currentIndex: Int, delta: Int, count: Int): Int {
        if (count <= 0) return 0
        return ((currentIndex + delta) % count + count) % count
    }

    fun computeDragFraction(dragOffsetPx: Float, cardWidthPx: Float): Float {
        val safeW = cardWidthPx.coerceAtLeast(80f)
        return (dragOffsetPx / safeW).coerceIn(-1f, 1f)
    }

    fun computeYAxisRotationDeg(dragFraction: Float): Float =
        (dragFraction.coerceIn(-1f, 1f) * 180f)

    fun shouldShowBackFace(rotationDeg: Float): Boolean =
        abs(rotationDeg) > 90f

    fun computeDepthShadowAlpha(rotationDeg: Float, visualDepth: Float): Float {
        val norm = abs(rotationDeg).coerceIn(0f, 180f) / 180f
        val edgeCurve = sin(norm * PI).toFloat().coerceIn(0f, 1f)
        return (0.16f + edgeCurve * 0.42f) * visualDepth.coerceIn(0.3f, 1f)
    }
}

@Immutable
data class CalendarEventEntry(
    val id: Long,
    val title: String,
    val timeLabel: String
)

@Immutable
data class ActiveMediaPlaybackState(
    val hasSession: Boolean = false,
    val isPlaying: Boolean = false,
    val title: String = "No active media",
    val artist: String = "Tap play in your music app",
    val controller: MediaController? = null
)

@Immutable
data class DeviceDiagnosticsSummary(
    val ramUsedPercent: Int,
    val ramSummary: String,
    val storageUsedPercent: Int,
    val storageFreeGb: Int,
    val tierLabel: String
)

object StudioWidgetSystemBridge {

    fun hasCalendarPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun loadUpcomingCalendarEvents(context: Context, limit: Int = 3): List<CalendarEventEntry> {
        if (!hasCalendarPermission(context)) return emptyList()
        return runCatching {
            val now = System.currentTimeMillis()
            val end = now + 7L * 24L * 60L * 60L * 1000L
            val results = mutableListOf<CalendarEventEntry>()
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART
            )
            val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
            val args = arrayOf(now.toString(), end.toString())
            val fmt = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                args,
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(CalendarContract.Events._ID)
                val titleIdx = cursor.getColumnIndex(CalendarContract.Events.TITLE)
                val startIdx = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
                while (cursor.moveToNext() && results.size < limit) {
                    val id = if (idIdx >= 0) cursor.getLong(idIdx) else results.size.toLong()
                    val title = if (titleIdx >= 0) cursor.getString(titleIdx).orEmpty().ifBlank { "Event" } else "Event"
                    val startMs = if (startIdx >= 0) cursor.getLong(startIdx) else now
                    results += CalendarEventEntry(
                        id = id,
                        title = title,
                        timeLabel = fmt.format(Date(startMs))
                    )
                }
            }
            results
        }.getOrDefault(emptyList())
    }

    fun readActiveMediaState(context: Context): ActiveMediaPlaybackState {
        return runCatching {
            val mgr = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return ActiveMediaPlaybackState()
            val component = ComponentName(context, BackdropNotificationListenerService::class.java)
            val controllers = mgr.getActiveSessions(component)
            val active = controllers.firstOrNull { c ->
                val st = c.playbackState?.state
                st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_PAUSED || st == PlaybackState.STATE_BUFFERING
            } ?: controllers.firstOrNull() ?: return ActiveMediaPlaybackState()
            val meta = active.metadata
            val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifBlank { "Playing Media" }
            val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
                .ifBlank { meta?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty().ifBlank { active.packageName } }
            val isPlaying = active.playbackState?.state == PlaybackState.STATE_PLAYING
            ActiveMediaPlaybackState(
                hasSession = true,
                isPlaying = isPlaying,
                title = title,
                artist = artist,
                controller = active
            )
        }.getOrDefault(ActiveMediaPlaybackState())
    }

    fun readDiagnostics(context: Context, tierLabel: String): DeviceDiagnosticsSummary {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(memInfo) }
        val totalMb = (memInfo.totalMem / (1024L * 1024L)).coerceAtLeast(1024L)
        val availMb = (memInfo.availMem / (1024L * 1024L)).coerceAtLeast(128L)
        val usedMb = (totalMb - availMb).coerceAtLeast(0L)
        val ramPct = ((usedMb * 100f) / totalMb).roundToInt().coerceIn(5, 98)

        val stat = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        val totalBytes = stat?.totalBytes ?: (64L * 1024 * 1024 * 1024)
        val availBytes = stat?.availableBytes ?: (24L * 1024 * 1024 * 1024)
        val usedPct = (((totalBytes - availBytes).toFloat() / totalBytes.coerceAtLeast(1L)) * 100f)
            .roundToInt()
            .coerceIn(4, 98)
        val freeGb = (availBytes / (1024L * 1024 * 1024)).toInt().coerceAtLeast(0)
        return DeviceDiagnosticsSummary(
            ramUsedPercent = ramPct,
            ramSummary = "${(usedMb / 1024f).let { String.format(Locale.US, "%.1f", it) }}/${(totalMb / 1024f).roundToInt()}GB",
            storageUsedPercent = usedPct,
            storageFreeGb = freeGb,
            tierLabel = tierLabel
        )
    }
}

@Composable
fun StudioWidgetsLockStrip(
    widgets: List<StudioWidgetItem>,
    stackedDeck: StackedWallpaperDeckConfig,
    telemetry: ClockWidgetTelemetry,
    isCharging: Boolean,
    is24HourFormat: Boolean,
    onUpdateStackedDeck: (StackedWallpaperDeckConfig) -> Unit,
    onOpenWallpaper: ((Wallpaper) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val activeWidgets = remember(widgets) { widgets.filter { it.enabled }.take(4) }
    if (activeWidgets.isEmpty()) return

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .testTag("lockscreen_widgets_strip"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(activeWidgets, key = { it.type.name }) { item ->
            StudioWidgetCard(
                item = item,
                stackedDeck = stackedDeck,
                telemetry = telemetry,
                isCharging = isCharging,
                is24HourFormat = is24HourFormat,
                onUpdateStackedDeck = onUpdateStackedDeck,
                onOpenWallpaper = onOpenWallpaper,
                onDarkSurface = true
            )
        }
    }
}

@Composable
fun StudioWidgetCard(
    item: StudioWidgetItem,
    stackedDeck: StackedWallpaperDeckConfig,
    telemetry: ClockWidgetTelemetry,
    isCharging: Boolean = false,
    is24HourFormat: Boolean = true,
    onUpdateStackedDeck: (StackedWallpaperDeckConfig) -> Unit = {},
    onOpenWallpaper: ((Wallpaper) -> Unit)? = null,
    onDarkSurface: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiStyle = LocalUiStyleMode.current
    val customTokens = LocalThemeCustomization.current
    val haptics = rememberStudioHaptics()
    val isGlass = uiStyle == UiStyleMode.FROSTED_GLASS_OS || onDarkSurface

    val cardBg = if (onDarkSurface) {
        Color(0xFF101522).copy(alpha = if (uiStyle == UiStyleMode.FROSTED_GLASS_OS) 0.56f else 0.72f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val cardFg = if (onDarkSurface) Color.White else MaterialTheme.colorScheme.onSurface
    val cardSub = if (onDarkSurface) Color.White.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary

    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(item.type) {
        if (item.type == StudioWidgetType.DIGITAL_CLOCK || item.type == StudioWidgetType.ANALOG_CLOCK) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(1000L)
            }
        }
    }

    var calendarGranted by remember { mutableStateOf(StudioWidgetSystemBridge.hasCalendarPermission(context)) }
    val calendarPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        calendarGranted = granted
    }

    if (item.type == StudioWidgetType.STACKED_WALLPAPER) {
        StackedWallpaper3DWidget(
            deckConfig = stackedDeck,
            onUpdateDeck = onUpdateStackedDeck,
            onOpenWallpaper = onOpenWallpaper,
            onDarkSurface = onDarkSurface,
            modifier = modifier
        )
        return
    }

    Surface(
        color = cardBg,
        contentColor = cardFg,
        shape = RoundedCornerShape(22.dp),
        border = if (isGlass) BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)) else null,
        tonalElevation = if (onDarkSurface) 0.dp else 3.dp,
        modifier = modifier
            .width(
                when (item.type) {
                    StudioWidgetType.MUSIC_PLAYER,
                    StudioWidgetType.CALENDAR,
                    StudioWidgetType.QUICK_INFO -> 228.dp
                    else -> 166.dp
                }
            )
            .height(114.dp)
            .testTag("studio_widget_${item.type.name.lowercase()}")
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            when (item.type) {
                StudioWidgetType.DIGITAL_CLOCK -> {
                    val d = Date(nowMs)
                    val hhmm = SimpleDateFormat(if (is24HourFormat) "HH:mm" else "h:mm a", Locale.getDefault()).format(d)
                    val sec = SimpleDateFormat(":ss", Locale.getDefault()).format(d)
                    val date = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(d)
                    Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(Icons.Rounded.Schedule, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
                            Text(date, style = MaterialTheme.typography.labelSmall, color = cardSub, maxLines = 1)
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = hhmm,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 26.sp,
                                color = cardFg
                            )
                            Text(
                                text = sec,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = accent,
                                modifier = Modifier.padding(bottom = 3.dp, start = 2.dp)
                            )
                        }
                    }
                }

                StudioWidgetType.ANALOG_CLOCK -> {
                    val cal = remember(nowMs) { Calendar.getInstance().apply { timeInMillis = nowMs } }
                    val hr = cal.get(Calendar.HOUR)
                    val min = cal.get(Calendar.MINUTE)
                    val sec = cal.get(Calendar.SECOND)
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Canvas(modifier = Modifier.size(72.dp)) {
                            val r = size.minDimension * 0.45f
                            val c = Offset(size.width / 2f, size.height / 2f)
                            drawCircle(color = cardSub.copy(alpha = 0.4f), radius = r, center = c, style = Stroke(2.dp.toPx()))
                            val hAngle = ((hr + min / 60f) * 30f - 90f) * PI / 180.0
                            val mAngle = (min * 6f - 90f) * PI / 180.0
                            val sAngle = (sec * 6f - 90f) * PI / 180.0
                            drawLine(cardFg, c, Offset(c.x + (r * 0.52f * cos(hAngle)).toFloat(), c.y + (r * 0.52f * sin(hAngle)).toFloat()), 3.dp.toPx(), StrokeCap.Round)
                            drawLine(cardFg, c, Offset(c.x + (r * 0.78f * cos(mAngle)).toFloat(), c.y + (r * 0.78f * sin(mAngle)).toFloat()), 2.2.dp.toPx(), StrokeCap.Round)
                            drawLine(accent, c, Offset(c.x + (r * 0.84f * cos(sAngle)).toFloat(), c.y + (r * 0.84f * sin(sAngle)).toFloat()), 1.4.dp.toPx(), StrokeCap.Round)
                            drawCircle(color = accent, radius = 3.dp.toPx(), center = c)
                        }
                        Column {
                            Text("Chrono", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = cardFg)
                            Text("${cal.get(Calendar.DAY_OF_MONTH)} ${SimpleDateFormat("MMM", Locale.getDefault()).format(Date(nowMs))}", style = MaterialTheme.typography.labelSmall, color = cardSub)
                        }
                    }
                }

                StudioWidgetType.WEATHER -> {
                    Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("Weather", style = MaterialTheme.typography.labelSmall, color = cardSub)
                            Icon(Icons.Rounded.WbSunny, contentDescription = "Weather condition", tint = Color(0xFFFFCA62), modifier = Modifier.size(20.dp))
                        }
                        Text(
                            text = telemetry.weatherText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = cardFg,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (telemetry.isWeatherAvailable) "Open-Meteo Live" else "Sync in Settings",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent
                        )
                    }
                }

                StudioWidgetType.CALENDAR -> {
                    val events = remember(calendarGranted, nowMs / 60_000L) {
                        if (calendarGranted) StudioWidgetSystemBridge.loadUpcomingCalendarEvents(context, limit = 2)
                        else emptyList()
                    }
                    val cal = Calendar.getInstance()
                    val dayStr = SimpleDateFormat("EEEE • MMM d", Locale.getDefault()).format(cal.time)
                    Column(
                        verticalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(enabled = !calendarGranted) {
                                calendarPermLauncher.launch(Manifest.permission.READ_CALENDAR)
                            }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.CalendarMonth, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                            Text(dayStr, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = cardFg, maxLines = 1)
                        }
                        if (!calendarGranted) {
                            Text(
                                text = "Tap to sync device calendar events",
                                style = MaterialTheme.typography.bodySmall,
                                color = accent
                            )
                        } else if (events.isEmpty()) {
                            Text(
                                text = "No upcoming events in the next 7 days",
                                style = MaterialTheme.typography.bodySmall,
                                color = cardSub,
                                maxLines = 2
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                events.forEach { ev ->
                                    Text(
                                        text = "${ev.timeLabel} • ${ev.title}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = cardFg,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                StudioWidgetType.BATTERY -> {
                    val pct = telemetry.batteryPercent.coerceIn(0, 100)
                    Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(if (isCharging) "Charging" else "Battery", style = MaterialTheme.typography.labelSmall, color = cardSub)
                            Icon(Icons.Rounded.BatteryChargingFull, contentDescription = null, tint = if (isCharging) Color(0xFF5CF28E) else accent, modifier = Modifier.size(18.dp))
                        }
                        Text(
                            text = "$pct%",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = cardFg
                        )
                        LinearProgressIndicator(
                            progress = { pct / 100f },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                            color = if (isCharging) Color(0xFF5CF28E) else accent,
                            trackColor = cardSub.copy(alpha = 0.25f)
                        )
                    }
                }

                StudioWidgetType.MUSIC_PLAYER -> {
                    var mediaState by remember { mutableStateOf(StudioWidgetSystemBridge.readActiveMediaState(context)) }
                    Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                color = accent.copy(alpha = 0.24f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(mediaState.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = cardFg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(mediaState.artist, style = MaterialTheme.typography.labelSmall, color = cardSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    haptics.performTick(16L)
                                    mediaState.controller?.transportControls?.skipToPrevious()
                                    mediaState = StudioWidgetSystemBridge.readActiveMediaState(context)
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous track", tint = cardFg, modifier = Modifier.size(20.dp))
                            }
                            Surface(
                                onClick = {
                                    haptics.performTick(20L)
                                    val tc = mediaState.controller?.transportControls
                                    if (mediaState.isPlaying) tc?.pause() else tc?.play()
                                    mediaState = StudioWidgetSystemBridge.readActiveMediaState(context)
                                },
                                shape = CircleShape,
                                color = accent,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (mediaState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = if (mediaState.isPlaying) "Pause" else "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    haptics.performTick(16L)
                                    mediaState.controller?.transportControls?.skipToNext()
                                    mediaState = StudioWidgetSystemBridge.readActiveMediaState(context)
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(Icons.Rounded.SkipNext, contentDescription = "Next track", tint = cardFg, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }

                StudioWidgetType.QUICK_INFO -> {
                    val dateStr = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date())
                    Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
                        Text("Quick Telemetry", style = MaterialTheme.typography.labelSmall, color = accent, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "$dateStr • ${telemetry.weatherText}",
                            style = MaterialTheme.typography.labelLarge,
                            color = cardFg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Battery ${telemetry.batteryPercent}% • ${customTokens.liquidGlassTier.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = cardSub,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                StudioWidgetType.PHOTO_FRAME -> {
                    val customUri = item.customPhotoUri
                    val fallbackWp = WallpaperCatalog.allWallpapers.first()
                    val customBmp by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = customUri) {
                        if (customUri.isNotBlank()) {
                            value = withContext(Dispatchers.IO) {
                                runCatching {
                                    context.contentResolver.openInputStream(Uri.parse(customUri))?.use {
                                        BitmapFactory.decodeStream(it)
                                    }
                                }.getOrNull()
                            }
                        }
                    }
                    Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))) {
                        if (customBmp != null) {
                            Image(
                                bitmap = customBmp!!.asImageBitmap(),
                                contentDescription = "Photo Frame",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            WallpaperAsyncImage(
                                wallpaper = fallbackWp,
                                useFullRes = false,
                                contentDescription = "Curated Photo Frame",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                StudioWidgetType.SYSTEM_INFO -> {
                    val diag = remember(customTokens.liquidGlassTier) {
                        StudioWidgetSystemBridge.readDiagnostics(context, customTokens.liquidGlassTier.label)
                    }
                    Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(Icons.Rounded.Memory, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
                            Text("System", style = MaterialTheme.typography.labelSmall, color = cardSub)
                        }
                        Text("RAM ${diag.ramUsedPercent}% (${diag.ramSummary})", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = cardFg, maxLines = 1)
                        Text("${diag.storageFreeGb}GB free • ${diag.tierLabel}", style = MaterialTheme.typography.labelSmall, color = cardSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                StudioWidgetType.STACKED_WALLPAPER -> Unit
            }
        }
    }
}

/**
 * HyperOS 4-inspired Stacked Wallpaper Widget with interactive 3D Y-axis bidirectional card flipping.
 * Swiping left or right rotates the card in 3D space, progressively narrowing toward 90°, swaps at ±90°,
 * and settles onto the next/previous wallpaper cleanly.
 */
@Composable
fun StackedWallpaper3DWidget(
    deckConfig: StackedWallpaperDeckConfig,
    onUpdateDeck: (StackedWallpaperDeckConfig) -> Unit,
    onOpenWallpaper: ((Wallpaper) -> Unit)? = null,
    onDarkSurface: Boolean = false,
    modifier: Modifier = Modifier
) {
    val sanitized = remember(deckConfig) { deckConfig.sanitized() }
    val deckWallpapers = remember(sanitized.wallpaperIds, sanitized.cardCount) {
        val resolved = sanitized.wallpaperIds.mapNotNull { WallpaperCatalog.getById(it) }
        if (resolved.size >= 2) resolved else WallpaperCatalog.allWallpapers.take(sanitized.cardCount.coerceIn(3, 8))
    }
    val count = deckWallpapers.size.coerceAtLeast(1)
    val safeFront = ((sanitized.frontIndex % count) + count) % count
    val nextIdx = StackedWallpaperFlipMath.advanceIndex(safeFront, 1, count)
    val prevIdx = StackedWallpaperFlipMath.advanceIndex(safeFront, -1, count)

    val haptics = rememberStudioHaptics()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val animRotationY = remember { Animatable(0f) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(sanitized.autoCycle, safeFront, count) {
        if (sanitized.autoCycle && count > 1) {
            delay(5000L)
            if (!isDragging) {
                animRotationY.snapTo(0f)
                animRotationY.animateTo(-180f, tween(340, easing = FastOutSlowInEasing))
                onUpdateDeck(sanitized.copy(frontIndex = nextIdx))
                animRotationY.snapTo(0f)
            }
        }
    }

    val currentRotation = animRotationY.value
    val showBack = StackedWallpaperFlipMath.shouldShowBackFace(currentRotation)
    val backTargetIdx = if (currentRotation <= 0f) nextIdx else prevIdx
    val frontWallpaper = deckWallpapers[safeFront]
    val backWallpaper = deckWallpapers[backTargetIdx]
    val displayWallpaper = if (showBack) backWallpaper else frontWallpaper
    val shadowAlpha = StackedWallpaperFlipMath.computeDepthShadowAlpha(currentRotation, sanitized.visualDepth)
    val cardWidthDp = 152.dp
    val cardWidthPx = with(density) { cardWidthDp.toPx() }

    Box(
        modifier = modifier
            .width(cardWidthDp + (sanitized.cardSpacingDp * 2f).dp)
            .height(118.dp)
            .pointerInput(count, safeFront, sanitized) {
                if (count <= 1) return@pointerInput
                var totalDx = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        isDragging = true
                        totalDx = 0f
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        totalDx += dragAmount
                        val frac = StackedWallpaperFlipMath.computeDragFraction(totalDx, cardWidthPx)
                        val deg = StackedWallpaperFlipMath.computeYAxisRotationDeg(frac)
                        scope.launch { animRotationY.snapTo(deg) }
                    },
                    onDragEnd = {
                        isDragging = false
                        val deg = animRotationY.value
                        scope.launch {
                            if (abs(deg) > 48f) {
                                val delta = if (deg < 0f) 1 else -1
                                val targetDeg = if (deg < 0f) -180f else 180f
                                animRotationY.animateTo(targetDeg, tween(210, easing = FastOutSlowInEasing))
                                haptics.performSnapDetent()
                                onUpdateDeck(sanitized.copy(frontIndex = StackedWallpaperFlipMath.advanceIndex(safeFront, delta, count)))
                                animRotationY.snapTo(0f)
                            } else {
                                animRotationY.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
                            }
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                        scope.launch { animRotationY.animateTo(0f, tween(180)) }
                    }
                )
            }
            .testTag("stacked_wallpaper_3d_widget"),
        contentAlignment = Alignment.CenterStart
    ) {
        // Layered deck backing cards (HyperOS 4 stack depth)
        for (layer in 2 downTo 1) {
            val layerIdx = StackedWallpaperFlipMath.advanceIndex(safeFront, layer, count)
            val wp = deckWallpapers[layerIdx]
            val offsetDp = (layer * sanitized.cardSpacingDp).dp
            val scaleFactor = 1f - layer * 0.06f
            Surface(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                modifier = Modifier
                    .offset(x = offsetDp)
                    .size(width = cardWidthDp * scaleFactor, height = 112.dp * scaleFactor)
                    .graphicsLayer { alpha = (1f - layer * 0.22f * sanitized.visualDepth).coerceIn(0.35f, 0.92f) }
            ) {
                WallpaperAsyncImage(
                    wallpaper = wp,
                    useFullRes = false,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Front 3D rotating card
        Surface(
            onClick = {
                if (onOpenWallpaper != null) {
                    onOpenWallpaper(displayWallpaper)
                } else {
                    haptics.performTick(18L)
                    onUpdateDeck(sanitized.copy(frontIndex = nextIdx))
                }
            },
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.2.dp, Color.White.copy(alpha = 0.42f)),
            shadowElevation = (6f + shadowAlpha * 14f).dp,
            modifier = Modifier
                .size(width = cardWidthDp, height = 116.dp)
                .graphicsLayer {
                    rotationY = currentRotation
                    cameraDistance = 14f * density.density
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                WallpaperAsyncImage(
                    wallpaper = displayWallpaper,
                    useFullRes = false,
                    contentDescription = "${displayWallpaper.category.displayName} card",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // Un-mirror back face when rotated past ±90° so text/art reads naturally
                            if (showBack) scaleX = -1f
                        }
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.52f))
                            )
                        )
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .graphicsLayer { if (showBack) scaleX = -1f },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = displayWallpaper.category.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        color = Color.Black.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = "${safeFront + 1}/$count",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Interactive configurator for the 10-Widget Suite and Stacked Wallpaper Deck inside Lock Screen Mode / Studio.
 */
@Composable
fun StudioWidgetsConfiguratorCard(
    widgets: List<StudioWidgetItem>,
    stackedDeck: StackedWallpaperDeckConfig,
    onUpdateWidgets: (List<StudioWidgetItem>) -> Unit,
    onUpdateStackedDeck: (StackedWallpaperDeckConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptics = rememberStudioHaptics()
    val calendarPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* UI automatically refreshes when granted */ }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val next = widgets.map {
                if (it.type == StudioWidgetType.PHOTO_FRAME) it.copy(customPhotoUri = uri.toString(), enabled = true)
                else it
            }
            onUpdateWidgets(next)
        }
    }

    TactileSurface(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("studio_widgets_configurator_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Style, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text("Studio & Lock Screen Widgets", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Enable up to 4 active widgets on Lock Screen or add to your Home Screen",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            widgets.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("widget_toggle_row_${item.type.name}"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text("${item.type.title} (${item.type.gridSpanLabel})", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(item.type.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item.type == StudioWidgetType.PHOTO_FRAME && item.enabled) {
                        FilledTonalButton(
                            onClick = {
                                photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Photo", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Switch(
                        checked = item.enabled,
                        onCheckedChange = { enable ->
                            haptics.performTick(18L)
                            if (enable && item.type == StudioWidgetType.CALENDAR && !StudioWidgetSystemBridge.hasCalendarPermission(context)) {
                                calendarPermLauncher.launch(Manifest.permission.READ_CALENDAR)
                            }
                            onUpdateWidgets(widgets.map {
                                if (it.type == item.type) it.copy(enabled = enable) else it
                            })
                        },
                        modifier = Modifier.testTag("widget_switch_${item.type.name}")
                    )
                }
            }

            val stackedEnabled = widgets.firstOrNull { it.type == StudioWidgetType.STACKED_WALLPAPER }?.enabled == true
            AnimatedVisibility(visible = stackedEnabled) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(18.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Stacked Wallpaper Deck Settings", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    val deckWallpapers = remember(stackedDeck.wallpaperIds) {
                        val list = stackedDeck.wallpaperIds.mapNotNull { WallpaperCatalog.getById(it) }
                        if (list.isEmpty()) WallpaperCatalog.allWallpapers.take(stackedDeck.cardCount.coerceIn(3, 8)) else list
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(deckWallpapers, key = { it.id }) { wp ->
                            Box(modifier = Modifier.size(56.dp, 76.dp).clip(RoundedCornerShape(12.dp))) {
                                WallpaperAsyncImage(
                                    wallpaper = wp,
                                    useFullRes = false,
                                    contentDescription = wp.category.displayName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (deckWallpapers.size > 3) {
                                    Surface(
                                        onClick = {
                                            onUpdateStackedDeck(
                                                stackedDeck.copy(wallpaperIds = deckWallpapers.filterNot { it.id == wp.id }.map { it.id })
                                            )
                                        },
                                        shape = CircleShape,
                                        color = Color.Black.copy(alpha = 0.6f),
                                        modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(18.dp)
                                    ) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Remove card", tint = Color.White, modifier = Modifier.padding(2.dp))
                                    }
                                }
                            }
                        }
                        if (deckWallpapers.size < 8) {
                            item {
                                Surface(
                                    onClick = {
                                        val nextWp = WallpaperCatalog.allWallpapers.firstOrNull { cand ->
                                            deckWallpapers.none { it.id == cand.id }
                                        }
                                        if (nextWp != null) {
                                            onUpdateStackedDeck(
                                                stackedDeck.copy(
                                                    wallpaperIds = (deckWallpapers.map { it.id } + nextWp.id).take(8),
                                                    cardCount = (deckWallpapers.size + 1).coerceIn(3, 8)
                                                )
                                            )
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(56.dp, 76.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Rounded.Add, contentDescription = "Add wallpaper to stack", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Auto-Cycle Deck (5s)", style = MaterialTheme.typography.labelMedium)
                        Switch(
                            checked = stackedDeck.autoCycle,
                            onCheckedChange = { onUpdateStackedDeck(stackedDeck.copy(autoCycle = it)) }
                        )
                    }

                    Text("Card Spacing (${stackedDeck.cardSpacingDp.roundToInt()}dp)", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = stackedDeck.cardSpacingDp,
                        onValueChange = { onUpdateStackedDeck(stackedDeck.copy(cardSpacingDp = it)) },
                        valueRange = 4f..16f
                    )
                }
            }
        }
    }
}
