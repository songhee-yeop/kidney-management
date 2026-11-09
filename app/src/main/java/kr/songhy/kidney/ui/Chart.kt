@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package kr.songhy.kidney.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class Pt(val x: LocalDateTime, val y: Double)
data class Series(val label: String, val color: Color, val pts: List<Pt>)
data class RefLine(val y: Double, val label: String)

private fun LocalDateTime.sec() = toEpochSecond(ZoneOffset.UTC).toDouble()

private fun niceTicks(lo: Double, hi: Double, n: Int = 4): List<Double> {
    val raw = (hi - lo) / n
    val mag = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
    val start = floor(lo / step) * step
    val end = ceil(hi / step) * step
    val out = mutableListOf<Double>()
    var t = start
    while (t <= end + step * 0.001) { out += t; t += step }
    return out
}

/** 날짜별 선 그래프. 점을 누르면 그 날짜의 값이 위에 표시된다. */
@Composable
fun LineChart(
    series: List<Series>,
    refs: List<RefLine> = emptyList(),
    decimals: Int = 1,
    height: Dp = 180.dp,
) {
    val all = series.flatMap { it.pts }
    if (all.isEmpty()) {
        Muted("아직 기록이 없습니다")
        return
    }
    var x0 = all.minOf { it.x.sec() }
    var x1 = all.maxOf { it.x.sec() }
    if (x1 - x0 < 86400.0 * 2) { x0 -= 15 * 86400.0; x1 += 15 * 86400.0 }
    var y0 = all.minOf { it.y }
    var y1 = all.maxOf { it.y }
    val margin = max(y1 - y0, abs(y1) * 0.25)
    val shownRefs = refs.filter { it.y >= y0 - margin && it.y <= y1 + margin }
    shownRefs.forEach { y0 = min(y0, it.y); y1 = max(y1, it.y) }
    val span = (y1 - y0).takeIf { it > 0 } ?: max(abs(y1), 1.0)
    val ticks = niceTicks((y0 - span * 0.08).let { if (y0 >= 0) max(0.0, it) else it }, y1 + span * 0.08)
    val lo = ticks.first()
    val hi = ticks.last()
    val step = if (ticks.size > 1) ticks[1] - ticks[0] else 1.0
    val tickDec = if (step >= 1) 0 else if (step >= 0.1) 1 else 2

    var sel by remember(series) { mutableStateOf(all.maxBy { it.x }.x) }

    val cs = MaterialTheme.colorScheme
    val gridColor = cs.outlineVariant
    val refColor = cs.tertiary
    val density = LocalDensity.current
    val textPaint = remember { Paint().apply { isAntiAlias = true } }
    textPaint.textSize = with(density) { 10.sp.toPx() }
    textPaint.color = cs.onSurfaceVariant.toArgb()
    val refPaint = remember { Paint().apply { isAntiAlias = true; textAlign = Paint.Align.RIGHT } }
    refPaint.textSize = with(density) { 10.sp.toPx() }
    refPaint.color = refColor.toArgb()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(sel.toLocalDate().format(SHORT_DATE), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            series.forEach { s ->
                val p = s.pts.find { it.x == sel }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(8.dp).background(s.color, CircleShape))
                    Text(
                        "${s.label} ${p?.let { fmtNum(it.y, decimals) } ?: "–"}",
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(series, x0, x1) {
                    detectTapGestures { o ->
                        val padL = 36.dp.toPx()
                        val w = size.width - padL - 10.dp.toPx()
                        val xv = x0 + ((o.x - padL) / w).coerceIn(0f, 1f) * (x1 - x0)
                        sel = all.minBy { abs(it.x.sec() - xv) }.x
                    }
                },
        ) {
            val padL = 36.dp.toPx(); val padR = 10.dp.toPx(); val padT = 6.dp.toPx(); val padB = 18.dp.toPx()
            val w = size.width - padL - padR
            val h = size.height - padT - padB
            fun px(x: LocalDateTime) = padL + ((x.sec() - x0) / (x1 - x0)).toFloat() * w
            fun py(y: Double) = padT + (1 - ((y - lo) / (hi - lo))).toFloat() * h

            ticks.forEach { t ->
                drawLine(gridColor, Offset(padL, py(t)), Offset(padL + w, py(t)), 1f)
                drawIntoCanvas { it.nativeCanvas.drawText(fmtNum(t, tickDec), 0f, py(t) + 3.dp.toPx(), textPaint) }
            }
            shownRefs.forEach { r ->
                drawLine(
                    refColor.copy(alpha = 0.8f), Offset(padL, py(r.y)), Offset(padL + w, py(r.y)), 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                )
                drawIntoCanvas { it.nativeCanvas.drawText(r.label, padL + w, py(r.y) - 3.dp.toPx(), refPaint) }
            }
            // x축: 처음·끝 날짜
            val first = all.minBy { it.x }.x
            val last = all.maxBy { it.x }.x
            drawIntoCanvas {
                it.nativeCanvas.drawText(first.toLocalDate().format(SHORT_DATE), padL, size.height - 2.dp.toPx(), textPaint)
                if (last != first) {
                    val label = last.toLocalDate().format(SHORT_DATE)
                    it.nativeCanvas.drawText(label, padL + w - textPaint.measureText(label), size.height - 2.dp.toPx(), textPaint)
                }
            }
            drawLine(gridColor, Offset(px(sel), padT), Offset(px(sel), padT + h), 2f)
            series.forEach { s ->
                val pts = s.pts.sortedBy { it.x }
                for (i in 1 until pts.size) {
                    drawLine(s.color, Offset(px(pts[i - 1].x), py(pts[i - 1].y)), Offset(px(pts[i].x), py(pts[i].y)), 2.5.dp.toPx())
                }
                pts.forEach { p ->
                    drawCircle(s.color, radius = if (p.x == sel) 5.dp.toPx() else 3.dp.toPx(), center = Offset(px(p.x), py(p.y)))
                }
            }
        }
    }
}
