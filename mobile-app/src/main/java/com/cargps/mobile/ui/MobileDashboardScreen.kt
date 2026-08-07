package com.cargps.mobile.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cargps.DashboardState
import com.cargps.DashboardViewModel
import com.cargps.FixStatus
import com.cargps.TripMode
import com.cargps.storage.CompletedTripRecord
import kotlinx.coroutines.delay
import java.util.Locale

private val PaceGreen = Color(0xFF34D399)
private val WarningAmber = Color(0xFFFBBF24)

@Composable
fun MobileDashboardScreen(
    state: DashboardState,
    onRequestPermission: () -> Unit,
    onToggleTrip: () -> Unit,
    onEndTrip: () -> Unit,
    onToggleTheme: () -> Unit,
    onTick: (Long) -> Unit,
) {
    var showEndConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            onTick(System.currentTimeMillis())
            delay(1_000L)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            MobileHeader(state = state, onToggleTheme = onToggleTheme)
            LocationHealth(state)
            // 主仪表固定为紧凑高度，避免停车或无数据状态吞掉整页剩余空间。
            SpeedTripPanel(state = state, modifier = Modifier.height(220.dp))
            StatsStrip(state)
            TelemetryStrip(state)
            RecentTripsCard(state.recentTrips)
            MobileControlBar(
                state = state,
                onRequestPermission = onRequestPermission,
                onToggleTrip = onToggleTrip,
                onRequestEnd = { showEndConfirmation = true },
            )
        }
    }

    if (showEndConfirmation) {
        AlertDialog(
            onDismissRequest = { showEndConfirmation = false },
            title = { Text("结束当前行程？") },
            text = { Text("结束后本次统计会保留在仪表上，直到开始下一段行程。") },
            confirmButton = {
                Button(
                    onClick = {
                        showEndConfirmation = false
                        onEndTrip()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White,
                    ),
                ) {
                    Text("确认结束")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndConfirmation = false }) {
                    Text("继续记录")
                }
            },
        )
    }
}

@Composable
private fun MobileHeader(state: DashboardState, onToggleTheme: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "CAR GPS",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.4.sp,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "${DashboardViewModel.formatDate(state.nowMillis)} ${DashboardViewModel.formatTime(state.nowMillis)}",
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        IconButton(onClick = onToggleTheme, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = if (state.darkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                contentDescription = if (state.darkTheme) "切换日间模式" else "切换夜间模式",
            )
        }
    }
}

@Composable
private fun LocationHealth(state: DashboardState) {
    val (statusText, statusColor) = fixStatusPresentation(state.fixStatus)
    Surface(
        modifier = Modifier.fillMaxWidth().height(60.dp),
        color = statusColor.copy(alpha = 0.10f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.34f)),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(9.dp).background(statusColor, RoundedCornerShape(99.dp)))
            Spacer(Modifier.width(9.dp))
            Column {
                Text(statusText, color = statusColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = locationHealthDetail(state),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            TechnicalValue("卫星", satelliteText(state))
            Spacer(Modifier.width(12.dp))
            TechnicalValue("精度", state.accuracyMeters?.let { "±${it.toInt()}m" } ?: "--")
        }
    }
}

@Composable
private fun SpeedTripPanel(state: DashboardState, modifier: Modifier = Modifier) {
    val speed = state.speedKmh
    val stats = state.tripStats
    val progress by animateFloatAsState(
        targetValue = ((speed ?: 0.0) / 200.0).coerceIn(0.0, 1.0).toFloat(),
        label = "mobile-speed-gauge",
    )
    val (_, statusColor) = fixStatusPresentation(state.fixStatus)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.62f)),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .weight(0.47f)
                    .fillMaxHeight()
                    .semantics {
                        contentDescription = "瞬时速度 ${speed?.let { String.format(Locale.US, "%.0f 公里每小时", it) } ?: "暂无数据"}"
                    },
            ) {
                Canvas(Modifier.fillMaxSize().padding(horizontal = 2.dp, vertical = 8.dp)) {
                    val stroke = 9.dp.toPx()
                    val arcSize = minOf(size.width, size.height * 0.92f)
                    val topLeft = Offset((size.width - arcSize) / 2f, (size.height - arcSize) / 2f)
                    drawArc(
                        color = statusColor.copy(alpha = 0.12f),
                        startAngle = 150f,
                        sweepAngle = 240f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = Size(arcSize, arcSize),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    if (progress > 0f) {
                        drawArc(
                            color = statusColor,
                            startAngle = 150f,
                            sweepAngle = 240f * progress,
                            useCenter = false,
                            topLeft = topLeft,
                            size = Size(arcSize, arcSize),
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                }
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("瞬时速度", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f), fontSize = 11.sp)
                    Text(
                        text = speed?.let { String.format(Locale.US, "%.0f", it) } ?: "--",
                        color = when (state.fixStatus) {
                            FixStatus.FIXED -> MaterialTheme.colorScheme.primary
                            FixStatus.STALE -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        fontSize = 72.sp,
                        lineHeight = 74.sp,
                        fontWeight = FontWeight.Black,
                    )
                    Text("km/h", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = state.bearingDegrees?.let { "${bearingName(it)} ${it.toInt()}°" } ?: "航向 --",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)))

            Column(
                modifier = Modifier.weight(0.53f).fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text("当前行程", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f))
                        Text(tripStatusText(state.tripMode), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = tripStatusColor(state.tripMode))
                        if (state.restoredTrip) {
                            Text("已恢复", fontSize = 10.sp, color = PaceGreen, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Text(
                        DashboardViewModel.formatDuration(stats.elapsedMillis),
                        fontSize = 17.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        distanceNumber(stats.distanceMeters),
                        fontSize = 38.sp,
                        lineHeight = 40.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        color = PaceGreen,
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(distanceUnit(stats.distanceMeters), modifier = Modifier.padding(bottom = 4.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TripTimeValue("移动", stats.movingMillis)
                    TripTimeValue("停车", stats.stoppedMillis)
                }
            }
        }
    }
}

@Composable
private fun TripTimeValue(label: String, millis: Long) {
    Column {
        Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f))
        Text(DashboardViewModel.formatDuration(millis), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RecentTripsCard(trips: List<CompletedTripRecord>) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(128.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.52f)),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("最近行程", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    "本地保存 ${trips.size} 段",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f),
                )
            }
            Spacer(Modifier.height(4.dp))
            if (trips.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    Text("结束第一段行程后显示本地记录", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
                }
            } else {
                trips.take(3).forEachIndexed { index, trip ->
                    RecentTripRow(trip)
                    if (index < minOf(trips.size, 3) - 1) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentTripRow(trip: CompletedTripRecord) {
    Row(
        modifier = Modifier.fillMaxWidth().height(27.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${DashboardViewModel.formatDate(trip.startedAtMillis)} ${DashboardViewModel.formatTime(trip.startedAtMillis).take(5)}",
            modifier = Modifier.weight(1f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(
            DashboardViewModel.formatDuration(trip.stats.elapsedMillis),
            modifier = Modifier.width(68.dp),
            textAlign = TextAlign.End,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.54f),
            maxLines = 1,
        )
        Text(
            "${distanceNumber(trip.stats.distanceMeters)} ${distanceUnit(trip.stats.distanceMeters)}",
            modifier = Modifier.width(62.dp),
            textAlign = TextAlign.End,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = PaceGreen,
            maxLines = 1,
        )
    }
}

@Composable
private fun StatsStrip(state: DashboardState) {
    val stats = state.tripStats
    Surface(
        modifier = Modifier.fillMaxWidth().height(84.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.52f)),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 10.dp)) {
            MetricCell("行程平均", DashboardViewModel.formatSpeed(stats.tripAverageMps), "km/h", Modifier.weight(1f))
            MetricDivider()
            MetricCell("移动平均", DashboardViewModel.formatSpeed(stats.movingAverageMps), "km/h", Modifier.weight(1f))
            MetricDivider()
            MetricCell("最高速度", DashboardViewModel.formatSpeed(stats.maxSpeedMps), "km/h", Modifier.weight(1f))
            MetricDivider()
            MetricCell("海拔", state.altitudeMeters?.let { it.toInt().toString() } ?: "--", "m", Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f), maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 22.sp, lineHeight = 24.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.width(2.dp))
            Text(unit, modifier = Modifier.padding(bottom = 2.dp), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f), maxLines = 1)
        }
    }
}

@Composable
private fun MetricDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 5.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)))
}

@Composable
private fun TelemetryStrip(state: DashboardState) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(96.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.52f)),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    "经纬度",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.46f),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    coordinateText(state),
                    modifier = Modifier.weight(1f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                TelemetryValue("更新", locationAgeText(state))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TelemetryValue("位置因子", formatDecimal(state.pdop))
                TelemetryValue("水平因子", formatDecimal(state.hdop))
                TelemetryValue("垂直因子", formatDecimal(state.vdop))
                TelemetryValue("报文类型", state.lastNmeaType?.toString() ?: "--")
            }
        }
    }
}

@Composable
private fun TelemetryValue(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label ", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.46f))
        Text(value, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun MobileControlBar(
    state: DashboardState,
    onRequestPermission: () -> Unit,
    onToggleTrip: () -> Unit,
    onRequestEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().height(72.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.44f)),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.fixStatus == FixStatus.PERMISSION_REQUIRED) {
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("授权定位并开始", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onToggleTrip,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) {
                    Icon(
                        imageVector = if (state.tripMode == TripMode.RECORDING) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        when (state.tripMode) {
                            TripMode.IDLE -> "开始行程"
                            TripMode.RECORDING -> "暂停行程"
                            TripMode.PAUSED -> "继续行程"
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (state.tripMode != TripMode.IDLE) {
                    FilledTonalButton(
                        onClick = onRequestEnd,
                        modifier = Modifier.width(112.dp).fillMaxHeight(),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("结束", fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun TechnicalValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.46f))
        Text(value, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun fixStatusPresentation(status: FixStatus): Pair<String, Color> = when (status) {
    FixStatus.PERMISSION_REQUIRED -> "需要定位权限" to MaterialTheme.colorScheme.error
    FixStatus.LOCATION_DISABLED -> "系统定位已关闭" to MaterialTheme.colorScheme.error
    FixStatus.SEARCHING -> "正在搜索卫星" to WarningAmber
    FixStatus.FIXED -> "定位状态良好" to PaceGreen
    FixStatus.POOR_ACCURACY -> "定位精度较差" to WarningAmber
    FixStatus.STALE -> "定位数据已过期" to WarningAmber
    FixStatus.LOST -> "定位信号丢失" to MaterialTheme.colorScheme.error
}

@Composable
private fun tripStatusColor(mode: TripMode): Color = when (mode) {
    TripMode.IDLE -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f)
    TripMode.RECORDING -> PaceGreen
    TripMode.PAUSED -> WarningAmber
}

private fun locationHealthDetail(state: DashboardState): String = when (state.fixStatus) {
    FixStatus.PERMISSION_REQUIRED -> "允许精确定位后才能读取车速与行程"
    FixStatus.LOCATION_DISABLED -> "请在系统设置中开启位置信息"
    FixStatus.SEARCHING -> "请移动到开阔位置，首次定位可能需要稍候"
    FixStatus.FIXED -> "定位数据持续更新中"
    FixStatus.POOR_ACCURACY -> "当前数据仅供参考，暂停累计异常位移"
    FixStatus.STALE -> "统计已冻结，等待新的有效定位"
    FixStatus.LOST -> "速度与里程停止更新"
}

private fun tripStatusText(mode: TripMode): String = when (mode) {
    TripMode.IDLE -> "等待开始"
    TripMode.RECORDING -> "记录中"
    TripMode.PAUSED -> "已暂停"
}

private fun satelliteText(state: DashboardState): String = when {
    state.satellitesUsed == null && state.satellitesInView == null -> "--"
    else -> "${state.satellitesUsed ?: "-"}/${state.satellitesInView ?: "-"}"
}

private fun coordinateText(state: DashboardState): String =
    if (state.latitude != null && state.longitude != null) {
        String.format(Locale.US, "%.6f, %.6f", state.latitude, state.longitude)
    } else {
        "未提供"
    }

private fun locationAgeText(state: DashboardState): String = state.lastFixAtMillis?.let {
    "${((state.nowMillis - it).coerceAtLeast(0L) / 100L) / 10.0}s"
} ?: "--"

private fun distanceNumber(meters: Double): String =
    if (meters < 1_000.0) meters.toInt().toString() else String.format(Locale.US, "%.2f", meters / 1_000.0)

private fun distanceUnit(meters: Double): String = if (meters < 1_000.0) "m" else "km"

private fun bearingName(degrees: Float): String {
    val directions = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
    return directions[((degrees + 22.5f) / 45f).toInt() % directions.size]
}

private fun formatDecimal(value: Double?): String = value?.let { String.format(Locale.US, "%.1f", it) } ?: "--"
