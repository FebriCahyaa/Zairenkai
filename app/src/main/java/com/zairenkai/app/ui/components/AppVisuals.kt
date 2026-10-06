// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * App-icon loading for the usage scenario list, plus small formatting helpers.
 * Icons are rendered to an ImageBitmap once and cached by package name.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private val iconCache = ConcurrentHashMap<String, ImageBitmap>()

private fun Drawable.toImageBitmap(px: Int = 108): ImageBitmap {
    (this as? BitmapDrawable)?.bitmap?.let { return it.asImageBitmap() }
    val w = if (intrinsicWidth > 0) intrinsicWidth else px
    val h = if (intrinsicHeight > 0) intrinsicHeight else px
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bmp.asImageBitmap()
}

@Composable
fun AppIcon(pkg: String, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val context = LocalContext.current
    val bmp by produceState<ImageBitmap?>(initialValue = iconCache[pkg], pkg) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val d = context.packageManager.getApplicationIcon(pkg)
                d.toImageBitmap().also { iconCache[pkg] = it }
            }.getOrNull()
        }
    }
    val b = bmp
    if (b != null) {
        Image(bitmap = b, contentDescription = pkg, modifier = modifier.size(size))
    } else {
        Icon(
            Icons.Rounded.Android, contentDescription = pkg,
            modifier = modifier.size(size),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h}h${m}m"
        m > 0 -> "${m}m${s}s"
        else -> "${s}s"
    }
}

fun formatClock(epochMs: Long): String {
    val d = java.util.Date(epochMs)
    return java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(d)
}
