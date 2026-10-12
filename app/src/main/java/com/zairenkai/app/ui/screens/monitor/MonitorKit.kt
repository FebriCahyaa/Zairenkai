// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Building blocks shared by the Monitor hub and its detail screens.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.screens.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zairenkai.app.R
import com.zairenkai.app.ui.components.CollapsingScaffold
import com.zairenkai.app.ui.icons.ChevronLeft
import com.zairenkai.app.ui.icons.SensorsOff
import com.zairenkai.app.ui.theme.LocalGlass
import com.zairenkai.app.ui.theme.ZkBad
import com.zairenkai.app.ui.theme.ZkGood
import com.zairenkai.app.ui.theme.ZkWarn
import com.zairenkai.app.ui.theme.glassContainerColor
import com.zairenkai.app.ui.theme.glassEdge

/** Expressive card shapes: one corner tighter than the rest. */
internal val HeroShape = RoundedCornerShape(36.dp, 36.dp, 36.dp, 16.dp)
internal val CardShape = RoundedCornerShape(28.dp, 28.dp, 28.dp, 14.dp)
internal val TileShape = RoundedCornerShape(20.dp)

/** Detail page: collapsing large title over a lazy list (see CollapsingScaffold). */
@Composable
internal fun MonitorDetailScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    CollapsingScaffold(title, onBack = onBack, actions = actions) { inner ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = inner.calculateTopPadding() + 4.dp,
                bottom = inner.calculateBottomPadding() + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/** Surface with the app's glass treatment. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MonitorCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = CardShape,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: (() -> Unit)? = null,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = LocalGlass.current
    val c = glassContainerColor(color, MaterialTheme.colorScheme.primary, glass)
    val inner: @Composable () -> Unit = { Column(Modifier.padding(padding), content = content) }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.glassEdge(glass, shape), shape = shape, color = c, content = inner)
    } else {
        Surface(modifier = modifier.glassEdge(glass, shape), shape = shape, color = c, content = inner)
    }
}

@Composable
internal fun IconBadge(icon: ImageVector, accent: Color, size: Dp = 20.dp, shape: Shape = RoundedCornerShape(14.dp)) {
    Surface(shape = shape, color = accent.copy(alpha = 0.15f)) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(9.dp).size(size), tint = accent)
    }
}

@Composable
internal fun CardHeader(
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(icon, accent)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

/** Big value + unit, the expressive headline of a card. */
@Composable
internal fun BigValue(
    value: String,
    unit: String? = null,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.displayMedium,
) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            value,
            style = style.copy(letterSpacing = (-1).sp),
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
        )
        if (unit != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                unit,
                modifier = Modifier.padding(bottom = 6.dp),
                style = MaterialTheme.typography.titleMedium,
                color = color.copy(alpha = 0.72f),
            )
        }
    }
}

/** Small tonal stat tile used in grids. */
@Composable
internal fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    sub: String? = null,
) {
    Surface(modifier = modifier, shape = TileShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accent ?: MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Lays [cells] out [columns] per row with equal widths. */
@Composable
internal fun StatGrid(cells: List<Triple<String, String, Color?>>, columns: Int = 2) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cells.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (l, v, a) -> StatCell(l, v, Modifier.weight(1f), a) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun InfoLine(label: String, value: String, valueColor: Color? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun Pill(text: String, container: Color, content: Color, dot: Color? = null) {
    Surface(shape = CircleShape, color = container) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (dot != null) Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = content)
        }
    }
}

/** Compact "needs the Zairenkai module" state inside a card. */
@Composable
internal fun ModuleNeeded(modifier: Modifier = Modifier, detail: String? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Rounded.SensorsOff,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.mon_needs_module),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Small, dismissable banner for missing root telemetry. */
@Composable
internal fun ModuleBanner(onDismiss: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Rounded.SensorsOff,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                stringResource(R.string.mon_banner_module),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.mon_banner_dismiss),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/** Short empty-chart placeholder. */
@Composable
internal fun ChartPlaceholder(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun loadColor(pct: Int?): Color = when {
    pct == null -> Color.Gray
    pct >= 85 -> ZkBad
    pct >= 60 -> ZkWarn
    else -> ZkGood
}

internal fun tempColor(c: Float?): Color = when {
    c == null -> Color.Gray
    c >= 48f -> ZkBad
    c >= 42f -> ZkWarn
    else -> ZkGood
}

/** 1 804 800 kHz → "1.80 GHz", 980 000 kHz → "980 MHz". */
internal fun freqLabel(khz: Long?): String = when {
    khz == null || khz <= 0 -> "—"
    khz >= 1_000_000 -> "%.2f GHz".format(khz / 1_000_000f)
    else -> "${khz / 1000} MHz"
}

internal fun mhzLabel(mhz: Float?): String = freqLabel(mhz?.let { (it * 1000).toLong() })

internal fun fmt1(v: Float?): String = v?.let { "%.1f".format(it) } ?: "—"
internal fun fmt0(v: Float?): String = v?.let { "%.0f".format(it) } ?: "—"
