// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Charts for the Monitor detail screens, drawn on Canvas: an axis-labelled
 * time series (several lines, gaps, shaded bands, a reference line), a ring
 * gauge, a histogram and a bare sparkline.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/** One line of a [TimeSeriesChart]; null values are gaps. */
data class ChartSeries(
    val values: List<Float?>,
    val color: Color,
    val fill: Boolean = true,
    val dashed: Boolean = false,
)

/**
 * Line chart with Y-axis labels. [capacity] fixes the X slots so a rolling
 * history fills from the right; [bands] shade index ranges (e.g. FPS drops).
 */
@Composable
fun TimeSeriesChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    minV: Float = 0f,
    maxV: Float = 100f,
    yTicks: Int = 4,
    formatY: (Float) -> String = { "%.0f".format(it) },
    capacity: Int? = null,
    bands: List<IntRange> = emptyList(),
    bandColor: Color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
    refLine: Float? = null,
    refColor: Color = MaterialTheme.colorScheme.outline,
    strokeWidth: Dp = 2.5.dp,
) {
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(modifier) {
        val range = max(1e-3f, maxV - minV)
        val labels = (0..yTicks).map { i -> formatY(minV + range * i / yTicks) }
        val gutter = labels.maxOf { measurer.measure(it, labelStyle).size.width } + 6.dp.toPx()
        val top = 6.dp.toPx()
        val bottom = size.height - 6.dp.toPx()
        val left = gutter
        val w = size.width - left
        val h = bottom - top
        fun y(v: Float) = bottom - h * ((v.coerceIn(minV, maxV) - minV) / range)

        for (i in 0..yTicks) {
            val gy = bottom - h * i / yTicks
            drawLine(grid, Offset(left, gy), Offset(size.width, gy), 1f)
            val m = measurer.measure(labels[i], labelStyle)
            drawText(m, topLeft = Offset(0f, (gy - m.size.height / 2f).coerceIn(0f, size.height - m.size.height)))
        }

        val n = capacity ?: series.maxOfOrNull { it.values.size } ?: 0
        if (n < 2) return@Canvas
        val step = w / (n - 1)
        fun x(i: Int, size: Int) = left + (i + (n - size)) * step

        val bandsSize = series.firstOrNull()?.values?.size ?: n
        for (b in bands) {
            val x0 = x(b.first, bandsSize) - step / 2
            val x1 = x(b.last, bandsSize) + step / 2
            drawRect(bandColor, Offset(x0.coerceAtLeast(left), top), Size((x1 - x0).coerceAtLeast(2f), h))
        }
        refLine?.let {
            val ry = y(it)
            drawLine(
                refColor, Offset(left, ry), Offset(size.width, ry), 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            )
        }

        for (s in series) {
            val line = Path()
            var started = false
            val segments = mutableListOf<Pair<Float, Float>>()
            val stroke = Stroke(
                strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 10f)) else null,
            )
            fun flushFill() {
                if (s.fill && segments.size >= 2) {
                    val area = Path().apply {
                        moveTo(segments.first().first, bottom)
                        segments.forEach { lineTo(it.first, it.second) }
                        lineTo(segments.last().first, bottom)
                        close()
                    }
                    drawPath(area, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.26f), s.color.copy(alpha = 0f)), top, bottom))
                }
                segments.clear()
            }
            s.values.forEachIndexed { i, v ->
                if (v == null) {
                    started = false
                    flushFill()
                    return@forEachIndexed
                }
                val px = x(i, s.values.size)
                val py = y(v)
                if (!started) line.moveTo(px, py) else line.lineTo(px, py)
                started = true
                segments += px to py
            }
            flushFill()
            drawPath(line, s.color, style = stroke)
            // A lone point (or the last one) gets a dot so a short series still shows.
            s.values.indexOfLast { it != null }.takeIf { it >= 0 }?.let { i ->
                drawCircle(s.color, radius = strokeWidth.toPx() * 1.4f, center = Offset(x(i, s.values.size), y(s.values[i]!!)))
            }
        }
    }
}

/** Circular gauge; [fraction] animates with a spring. */
@Composable
fun RingGauge(
    fraction: Float?,
    color: Color,
    modifier: Modifier = Modifier,
    stroke: Dp = 12.dp,
    track: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val animated by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), spring(stiffness = 300f), label = "ring")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = stroke.toPx()
            val d = size.minDimension - sw
            val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
            drawArc(track, 135f, 270f, false, tl, Size(d, d), style = Stroke(sw, cap = StrokeCap.Round))
            if (fraction != null && animated > 0f) {
                drawArc(color, 135f, 270f * animated, false, tl, Size(d, d), style = Stroke(sw, cap = StrokeCap.Round))
            }
        }
        content()
    }
}

/** Vertical bars with a label under each; [highlight] bars use the accent. */
@Composable
fun Histogram(
    values: List<Float>,
    labels: List<String>,
    color: Color,
    modifier: Modifier = Modifier,
    highlight: (Int) -> Boolean = { true },
) {
    val measurer = rememberTextMeasurer()
    val muted = MaterialTheme.colorScheme.surfaceContainerHighest
    val style: TextStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val labelH = measurer.measure("0", style).size.height + 4.dp.toPx()
        val h = size.height - labelH
        val slot = size.width / values.size
        val bw = slot * 0.66f
        val top = values.maxOrNull()?.takeIf { it > 0f } ?: 1f
        values.forEachIndexed { i, v ->
            val bh = (h * v / top).coerceAtLeast(if (v > 0f) 3.dp.toPx() else 0f)
            val x = i * slot + (slot - bw) / 2
            drawRoundRect(
                if (highlight(i)) color else muted,
                Offset(x, h - bh), Size(bw, bh),
                androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
            )
            labels.getOrNull(i)?.let { l ->
                val m = measurer.measure(l, style)
                drawText(m, topLeft = Offset(i * slot + (slot - m.size.width) / 2, size.height - m.size.height))
            }
        }
    }
}

/** Grid-free mini line for cards. */
@Composable
fun Sparkline(
    data: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    minV: Float = 0f,
    maxV: Float = 100f,
    capacity: Int? = null,
) {
    Canvas(modifier) {
        val n = capacity ?: data.size
        if (data.size < 2 || n < 2) return@Canvas
        val range = max(1e-3f, maxV - minV)
        val step = size.width / (n - 1)
        val off = n - data.size
        fun y(v: Float) = size.height * (1f - (v.coerceIn(minV, maxV) - minV) / range)
        val line = Path()
        val area = Path()
        data.forEachIndexed { i, v ->
            val px = (i + off) * step
            val py = y(v)
            if (i == 0) {
                line.moveTo(px, py); area.moveTo(px, size.height); area.lineTo(px, py)
            } else {
                line.lineTo(px, py); area.lineTo(px, py)
            }
        }
        area.lineTo((data.size - 1 + off) * step, size.height)
        area.close()
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.24f), color.copy(alpha = 0f))))
        drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
