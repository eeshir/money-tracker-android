package com.moneytracker.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moneytracker.app.R
import com.moneytracker.app.ui.theme.PennyWiseText
import com.moneytracker.app.ui.theme.Spacing
import com.moneytracker.app.utils.CurrencyFormatter
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

data class BalancePoint(
    val timestamp: LocalDateTime,
    val balance: BigDecimal,
    val currency: String = "INR"
)

/**
 * A line chart of one series. Touching a dot (within 24dp) shows its date and amount; touching
 * anywhere else shows nothing. Each x label sits under its own dot.
 */
@Composable
fun BalanceChart(
    primaryCurrency: String,
    balanceHistory: List<BalancePoint>,
    modifier: Modifier = Modifier,
    height: Int = 200,
    /** Names the line in the legend; defaults to "Balance Trend". */
    seriesLabel: String = stringResource(R.string.balance_chart_label)
) {
    if (balanceHistory.isEmpty()) return

    val points = remember(balanceHistory) { smoothBalanceData(balanceHistory.sortedBy { it.timestamp }) }
    val values = remember(points) { points.map { it.balance.toDouble() } }
    val labels = remember(points) {
        val isYearly = points.size > 1 && points.all { it.timestamp.dayOfYear == 1 }
        val isMonthly = !isYearly && points.all { it.timestamp.dayOfMonth == 1 }
        val multiYear = points.first().timestamp.year != points.last().timestamp.year
        val pattern = when {
            isYearly -> "yyyy"
            isMonthly && multiYear -> "MMM yy"
            isMonthly -> "MMM"
            else -> "dd MMM"
        }
        points.map { it.timestamp.format(DateTimeFormatter.ofPattern(pattern)) }
    }

    val colors = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()
    val chartLabel = PennyWiseText.chartLabel
    val axisStyle = chartLabel.copy(color = colors.onSurfaceVariant, textAlign = TextAlign.End)
    val xStyle = chartLabel.copy(color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
    val tipStyle = chartLabel.copy(color = colors.onSurface)
    val line = colors.primary
    val grid = colors.onSurface.copy(alpha = 0.1f)
    val density = LocalDensity.current
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val reveal = remember(points) { Animatable(0f) }
    LaunchedEffect(points) { reveal.animateTo(1f, tween(700)) }

    // Geometry shared by drawing and touch handling.
    val leftPx = with(density) { 52.dp.toPx() }
    val rightPx = with(density) { 16.dp.toPx() }
    val topPx = with(density) { 16.dp.toPx() }
    val bottomPx = with(density) { 28.dp.toPx() }
    val hitPx = with(density) { 24.dp.toPx() }
    val maxV = (values.maxOrNull() ?: 0.0).coerceAtLeast(0.0)
    val minV = (values.minOrNull() ?: 0.0).coerceAtMost(0.0)
    val span = (maxV - minV).takeIf { it > 0.0 } ?: 1.0

    fun xOf(i: Int, w: Float): Float =
        if (values.size == 1) leftPx + (w - leftPx - rightPx) / 2f
        else leftPx + (w - leftPx - rightPx) * i / (values.size - 1)

    fun yOf(v: Double, h: Float): Float = topPx + ((maxV - v) / span).toFloat() * (h - topPx - bottomPx)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Spacing.sm)) {
            Canvas(Modifier.size(8.dp)) { drawCircle(line) }
            Spacer(Modifier.width(Spacing.xs))
            Text(seriesLabel, style = chartLabel.copy(color = colors.onSurface))
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height.dp)
                .pointerInput(points) {
                    detectTapGestures { tap ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        selected = values.indices
                            .map { i -> i to hypot(xOf(i, w) - tap.x, yOf(values[i], h) - tap.y) }
                            .filter { it.second <= hitPx }
                            .minByOrNull { it.second }?.first
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
            for (g in 0..4) {
                val v = minV + span * g / 4
                val y = yOf(v, h)
                drawLine(grid, Offset(leftPx, y), Offset(w - rightPx, y), strokeWidth = 1f, pathEffect = dash)
                val t = textMeasurer.measure(CurrencyFormatter.formatAbbreviated(abs(v), primaryCurrency), axisStyle)
                drawText(t, topLeft = Offset(leftPx - t.size.width - 6.dp.toPx(), y - t.size.height / 2f))
            }

            // X labels, thinned so they never overlap, each under its own dot.
            val widest = textMeasurer.measure(labels.maxByOrNull { it.length } ?: "", xStyle).size.width
            val fit = ((w - leftPx - rightPx) / (widest + 12.dp.toPx())).toInt().coerceAtLeast(1)
            val step = ceil(values.size.toFloat() / fit).toInt().coerceAtLeast(1)
            for (i in values.indices) {
                if (i % step != 0) continue
                // Keep the last label clear of the one before it.
                if (i != values.lastIndex && values.lastIndex - i < step / 2) continue
                val t = textMeasurer.measure(labels[i], xStyle)
                val x = (xOf(i, w) - t.size.width / 2f).coerceIn(0f, w - t.size.width)
                drawText(t, topLeft = Offset(x, h - bottomPx + 8.dp.toPx()))
            }
            if (values.lastIndex % step != 0 && values.size > 1) {
                val i = values.lastIndex
                val prevShown = (i / step) * step
                val gap = xOf(i, w) - xOf(prevShown, w)
                if (gap >= widest + 12.dp.toPx()) {
                    val t = textMeasurer.measure(labels[i], xStyle)
                    val x = (xOf(i, w) - t.size.width / 2f).coerceIn(0f, w - t.size.width)
                    drawText(t, topLeft = Offset(x, h - bottomPx + 8.dp.toPx()))
                }
            }

            val shown = reveal.value
            val base = yOf(minV, h)
            val pts = values.indices.map { Offset(xOf(it, w), yOf(values[it], h) * shown + base * (1 - shown)) }
            if (pts.size > 1) {
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size) {
                        val mx = (pts[i - 1].x + pts[i].x) / 2f
                        cubicTo(mx, pts[i - 1].y, mx, pts[i].y, pts[i].x, pts[i].y)
                    }
                }
                val fill = Path().apply {
                    addPath(path)
                    lineTo(pts.last().x, h - bottomPx)
                    lineTo(pts.first().x, h - bottomPx)
                    close()
                }
                drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.3f), Color.Transparent), topPx, h - bottomPx))
                drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
            pts.forEachIndexed { i, p ->
                val on = selected == i
                drawCircle(line, radius = (if (on) 6.dp else 4.dp).toPx(), center = p)
                drawCircle(colors.surface, radius = (if (on) 3.dp else 2.dp).toPx(), center = p)
            }
            selected?.let { i ->
                val p = pts[i]
                val t = textMeasurer.measure(
                    "${labels[i]}  ${CurrencyFormatter.formatCurrency(points[i].balance, primaryCurrency)}",
                    tipStyle
                )
                val padX = 10.dp.toPx()
                val padY = 6.dp.toPx()
                val boxW = t.size.width + 2 * padX
                val boxH = t.size.height + 2 * padY
                val bx = (p.x - boxW / 2f).coerceIn(0f, w - boxW)
                val by = if (p.y - boxH - 12.dp.toPx() < 0f) p.y + 12.dp.toPx() else p.y - boxH - 12.dp.toPx()
                drawLine(line.copy(alpha = 0.4f), Offset(p.x, topPx), Offset(p.x, h - bottomPx), strokeWidth = 1.dp.toPx())
                drawRoundRect(colors.surfaceContainerHighest, Offset(bx, by), Size(boxW, boxH), CornerRadius(8.dp.toPx()))
                drawText(t, topLeft = Offset(bx + padX, by + padY))
            }
        }
    }
}

/**
 * Smooth balance data to reduce noise in the chart
 * Applies time-based aggregation and moving average smoothing
 */
private fun smoothBalanceData(
    balanceHistory: List<BalancePoint>,
    maxPoints: Int = 50
): List<BalancePoint> {
    if (balanceHistory.size <= maxPoints) {
        return balanceHistory
    }

    val timeSpan = balanceHistory.last().timestamp.toLocalDate()
        .toEpochDay() - balanceHistory.first().timestamp.toLocalDate().toEpochDay()

    val intervalDays = maxOf(1, (timeSpan / maxPoints).toInt())

    val groupedData = balanceHistory.groupBy { point ->
        val dayIndex = (point.timestamp.toLocalDate().toEpochDay() -
            balanceHistory.first().timestamp.toLocalDate().toEpochDay()) / intervalDays
        dayIndex
    }

    return groupedData.map { (_, group) ->
        val avgBalance = group.map { it.balance }.reduce { acc, balance -> acc + balance } /
            BigDecimal(group.size.toLong())
        val middleIndex = group.size / 2
        val representativePoint = group[middleIndex]

        BalancePoint(
            timestamp = representativePoint.timestamp,
            balance = avgBalance,
            currency = representativePoint.currency
        )
    }.sortedBy { it.timestamp }
}
