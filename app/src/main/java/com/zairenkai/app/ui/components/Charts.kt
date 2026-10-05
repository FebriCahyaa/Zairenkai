// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Reusable chart + stat components drawn with Compose Canvas. Theme-aware and
 * legible in light and dark.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.zairenkai.app.ui.theme.LocalGlass
import com.zairenkai.app.ui.theme.glassContainerColor
import com.zairenkai.app.ui.theme.glassEdge
import androidx.compose.ui.unit.dp
import kotlin.math.max

/** Area line chart for a rolling series normalized to [minV, maxV]. */
@Composable
fun LineChart(
    data: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    minV: Float = 0f,
    maxV: Float = 100f,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier) {
        drawGrid(track)
        if (data.size < 2) return@Canvas
        val range = max(1f, maxV - minV)
        val stepX = size.width / (data.size - 1)
        fun y(v: Float) = size.height * (1f - ((v.coerceIn(minV, maxV) - minV) / range))
        val line = Path()
        val area = Path()
        data.forEachIndexed { i, v ->
            val px = i * stepX
            val py = y(v)
            if (i == 0) { line.moveTo(px, py); area.moveTo(px, size.height); area.lineTo(px, py) }
            else { line.lineTo(px, py); area.lineTo(px, py) }
        }
        area.lineTo((data.size - 1) * stepX, size.height)
        area.close()
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f))))
        drawPath(line, color, style = Stroke(width = 3f, cap = StrokeCap.Round))
    }
}

/** FPS graph: line plus red dots where a drop (jank) occurred. */
@Composable
fun FpsGraph(
    fps: List<Float>,
    drops: List<Boolean>,
    target: Float,
    lineColor: Color,
    dropColor: Color,
    modifier: Modifier = Modifier,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val targetColor = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        drawGrid(track)
        val maxV = max(target * 1.1f, (fps.maxOrNull() ?: target))
        fun y(v: Float) = size.height * (1f - (v.coerceIn(0f, maxV) / maxV))
        // target refresh line
        val ty = y(target)
        drawLine(targetColor, Offset(0f, ty), Offset(size.width, ty), strokeWidth = 1.5f)
        if (fps.size < 2) return@Canvas
        val stepX = size.width / (fps.size - 1)
        val path = Path()
        fps.forEachIndexed { i, v ->
            val px = i * stepX; val py = y(v)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, lineColor, style = Stroke(width = 3f, cap = StrokeCap.Round))
        fps.forEachIndexed { i, v ->
            if (drops.getOrNull(i) == true)
                drawCircle(dropColor, radius = 4f, center = Offset(i * stepX, y(v)))
        }
    }
}

private fun DrawScope.drawGrid(color: Color) {
    val rows = 4
    for (r in 0..rows) {
        val y = size.height * r / rows
        drawLine(color.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
    }
}

@Composable
fun StatTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
    sub: String? = null,
) {
    val glass = LocalGlass.current
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = modifier.glassEdge(glass, shape),
        shape = shape,
        color = glassContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.primary, glass),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, color = accent, fontWeight = FontWeight.Bold)
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun MeterBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(4.dp))
                .background(color),
        )
    }
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val glass = LocalGlass.current
    val shape = RoundedCornerShape(24.dp)
    Surface(
        modifier = modifier.fillMaxWidth().glassEdge(glass, shape),
        shape = shape,
        color = glassContainerColor(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.primary, glass),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
