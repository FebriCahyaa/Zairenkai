// SPDX-License-Identifier: Apache-2.0
/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * The Rounded icons the app uses that are not in material-icons-core, copied
 * unchanged from androidx.compose.material:material-icons-extended 1.7.8.
 * Depending on the whole extended set put ~2,000 icon classes per style into
 * every unminified build (the debug APK was ~64 MB, ~45 MB of it dex), so
 * only these are kept. Add an icon by copying its file from the same release.
 */
package com.zairenkai.app.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Rounded.Android: ImageVector
    get() {
        if (_android != null) {
            return _android!!
        }
        _android = materialIcon(name = "Rounded.Android") {
            materialPath {
                moveTo(17.6f, 9.48f)
                lineToRelative(1.84f, -3.18f)
                curveToRelative(0.16f, -0.31f, 0.04f, -0.69f, -0.26f, -0.85f)
                curveToRelative(-0.29f, -0.15f, -0.65f, -0.06f, -0.83f, 0.22f)
                lineToRelative(-1.88f, 3.24f)
                curveToRelative(-2.86f, -1.21f, -6.08f, -1.21f, -8.94f, 0.0f)
                lineTo(5.65f, 5.67f)
                curveToRelative(-0.19f, -0.29f, -0.58f, -0.38f, -0.87f, -0.2f)
                curveTo(4.5f, 5.65f, 4.41f, 6.01f, 4.56f, 6.3f)
                lineTo(6.4f, 9.48f)
                curveTo(3.3f, 11.25f, 1.28f, 14.44f, 1.0f, 18.0f)
                horizontalLineToRelative(22.0f)
                curveTo(22.72f, 14.44f, 20.7f, 11.25f, 17.6f, 9.48f)
                close()
                moveTo(7.0f, 15.25f)
                curveToRelative(-0.69f, 0.0f, -1.25f, -0.56f, -1.25f, -1.25f)
                curveToRelative(0.0f, -0.69f, 0.56f, -1.25f, 1.25f, -1.25f)
                reflectiveCurveTo(8.25f, 13.31f, 8.25f, 14.0f)
                curveTo(8.25f, 14.69f, 7.69f, 15.25f, 7.0f, 15.25f)
                close()
                moveTo(17.0f, 15.25f)
                curveToRelative(-0.69f, 0.0f, -1.25f, -0.56f, -1.25f, -1.25f)
                curveToRelative(0.0f, -0.69f, 0.56f, -1.25f, 1.25f, -1.25f)
                reflectiveCurveToRelative(1.25f, 0.56f, 1.25f, 1.25f)
                curveTo(18.25f, 14.69f, 17.69f, 15.25f, 17.0f, 15.25f)
                close()
            }
        }
        return _android!!
    }

private var _android: ImageVector? = null

public val Icons.Rounded.AutoAwesome: ImageVector
    get() {
        if (_autoAwesome != null) {
            return _autoAwesome!!
        }
        _autoAwesome = materialIcon(name = "Rounded.AutoAwesome") {
            materialPath {
                moveTo(19.46f, 8.0f)
                lineToRelative(0.79f, -1.75f)
                lineTo(22.0f, 5.46f)
                curveToRelative(0.39f, -0.18f, 0.39f, -0.73f, 0.0f, -0.91f)
                lineToRelative(-1.75f, -0.79f)
                lineTo(19.46f, 2.0f)
                curveToRelative(-0.18f, -0.39f, -0.73f, -0.39f, -0.91f, 0.0f)
                lineToRelative(-0.79f, 1.75f)
                lineTo(16.0f, 4.54f)
                curveToRelative(-0.39f, 0.18f, -0.39f, 0.73f, 0.0f, 0.91f)
                lineToRelative(1.75f, 0.79f)
                lineTo(18.54f, 8.0f)
                curveTo(18.72f, 8.39f, 19.28f, 8.39f, 19.46f, 8.0f)
                close()
                moveTo(11.5f, 9.5f)
                lineTo(9.91f, 6.0f)
                curveTo(9.56f, 5.22f, 8.44f, 5.22f, 8.09f, 6.0f)
                lineTo(6.5f, 9.5f)
                lineTo(3.0f, 11.09f)
                curveToRelative(-0.78f, 0.36f, -0.78f, 1.47f, 0.0f, 1.82f)
                lineToRelative(3.5f, 1.59f)
                lineTo(8.09f, 18.0f)
                curveToRelative(0.36f, 0.78f, 1.47f, 0.78f, 1.82f, 0.0f)
                lineToRelative(1.59f, -3.5f)
                lineToRelative(3.5f, -1.59f)
                curveToRelative(0.78f, -0.36f, 0.78f, -1.47f, 0.0f, -1.82f)
                lineTo(11.5f, 9.5f)
                close()
                moveTo(18.54f, 16.0f)
                lineToRelative(-0.79f, 1.75f)
                lineTo(16.0f, 18.54f)
                curveToRelative(-0.39f, 0.18f, -0.39f, 0.73f, 0.0f, 0.91f)
                lineToRelative(1.75f, 0.79f)
                lineTo(18.54f, 22.0f)
                curveToRelative(0.18f, 0.39f, 0.73f, 0.39f, 0.91f, 0.0f)
                lineToRelative(0.79f, -1.75f)
                lineTo(22.0f, 19.46f)
                curveToRelative(0.39f, -0.18f, 0.39f, -0.73f, 0.0f, -0.91f)
                lineToRelative(-1.75f, -0.79f)
                lineTo(19.46f, 16.0f)
                curveTo(19.28f, 15.61f, 18.72f, 15.61f, 18.54f, 16.0f)
                close()
            }
        }
        return _autoAwesome!!
    }

private var _autoAwesome: ImageVector? = null

public val Icons.Rounded.AutoMode: ImageVector
    get() {
        if (_autoMode != null) {
            return _autoMode!!
        }
        _autoMode = materialIcon(name = "Rounded.AutoMode") {
            materialPath {
                moveTo(18.06f, 2.83f)
                curveToRelative(-1.15f, -0.77f, -2.46f, -1.32f, -3.86f, -1.61f)
                curveTo(13.58f, 1.1f, 13.0f, 1.57f, 13.0f, 2.21f)
                verticalLineToRelative(0.0f)
                curveToRelative(0.0f, 0.46f, 0.31f, 0.88f, 0.76f, 0.97f)
                curveToRelative(1.17f, 0.23f, 2.26f, 0.7f, 3.21f, 1.34f)
                curveToRelative(0.39f, 0.26f, 0.9f, 0.19f, 1.23f, -0.14f)
                lineToRelative(0.0f, 0.0f)
                curveTo(18.66f, 3.93f, 18.59f, 3.18f, 18.06f, 2.83f)
                close()
            }
            materialPath {
                moveTo(11.0f, 2.21f)
                lineTo(11.0f, 2.21f)
                curveToRelative(0.0f, -0.64f, -0.58f, -1.11f, -1.2f, -0.99f)
                curveToRelative(-1.4f, 0.29f, -2.71f, 0.84f, -3.86f, 1.61f)
                curveToRelative(-0.52f, 0.35f, -0.59f, 1.1f, -0.15f, 1.54f)
                lineToRelative(0.0f, 0.0f)
                curveToRelative(0.33f, 0.33f, 0.84f, 0.4f, 1.23f, 0.14f)
                curveToRelative(0.96f, -0.64f, 2.04f, -1.1f, 3.21f, -1.34f)
                curveTo(10.69f, 3.09f, 11.0f, 2.67f, 11.0f, 2.21f)
                close()
            }
            materialPath {
                moveTo(4.38f, 5.79f)
                lineTo(4.38f, 5.79f)
                curveTo(3.93f, 5.34f, 3.18f, 5.42f, 2.84f, 5.94f)
                curveTo(2.07f, 7.09f, 1.51f, 8.39f, 1.23f, 9.8f)
                curveTo(1.1f, 10.42f, 1.58f, 11.0f, 2.21f, 11.0f)
                horizontalLineToRelative(0.0f)
                curveToRelative(0.46f, 0.0f, 0.88f, -0.31f, 0.97f, -0.76f)
                curveToRelative(0.23f, -1.17f, 0.7f, -2.26f, 1.34f, -3.22f)
                curveTo(4.77f, 6.64f, 4.7f, 6.12f, 4.38f, 5.79f)
                close()
            }
            materialPath {
                moveTo(21.79f, 11.0f)
                lineTo(21.79f, 11.0f)
                curveToRelative(0.63f, 0.0f, 1.11f, -0.58f, 0.98f, -1.2f)
                curveToRelative(-0.29f, -1.4f, -0.84f, -2.7f, -1.61f, -3.86f)
                curveToRelative(-0.35f, -0.52f, -1.1f, -0.6f, -1.54f, -0.15f)
                lineToRelative(0.0f, 0.0f)
                curveToRelative(-0.33f, 0.33f, -0.4f, 0.84f, -0.14f, 1.23f)
                curveToRelative(0.64f, 0.96f, 1.1f, 2.05f, 1.34f, 3.22f)
                curveTo(20.91f, 10.69f, 21.33f, 11.0f, 21.79f, 11.0f)
                close()
            }
            materialPath {
                moveTo(8.0f, 12.46f)
                lineToRelative(2.44f, 1.11f)
                lineTo(11.54f, 16.0f)
                curveToRelative(0.18f, 0.39f, 0.73f, 0.39f, 0.91f, 0.0f)
                lineToRelative(1.11f, -2.44f)
                lineTo(16.0f, 12.46f)
                curveToRelative(0.39f, -0.18f, 0.39f, -0.73f, 0.0f, -0.91f)
                lineToRelative(-2.44f, -1.11f)
                lineTo(12.46f, 8.0f)
                curveToRelative(-0.18f, -0.39f, -0.73f, -0.39f, -0.91f, 0.0f)
                lineToRelative(-1.11f, 2.44f)
                lineTo(8.0f, 11.54f)
                curveTo(7.61f, 11.72f, 7.61f, 12.28f, 8.0f, 12.46f)
                close()
            }
            materialPath {
                moveTo(12.0f, 21.0f)
                curveToRelative(-3.11f, 0.0f, -5.85f, -1.59f, -7.46f, -4.0f)
                horizontalLineTo(6.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(0.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineTo(2.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(4.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(0.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(-1.7f)
                curveToRelative(1.99f, 2.84f, 5.27f, 4.7f, 9.0f, 4.7f)
                curveToRelative(4.45f, 0.0f, 8.27f, -2.64f, 10.0f, -6.43f)
                curveToRelative(0.26f, -0.57f, -0.08f, -1.25f, -0.69f, -1.39f)
                lineToRelative(0.0f, 0.0f)
                curveToRelative(-0.45f, -0.1f, -0.93f, 0.11f, -1.12f, 0.54f)
                curveTo(18.77f, 18.83f, 15.64f, 21.0f, 12.0f, 21.0f)
                close()
            }
        }
        return _autoMode!!
    }

private var _autoMode: ImageVector? = null

public val Icons.Rounded.Balance: ImageVector
    get() {
        if (_balance != null) {
            return _balance!!
        }
        _balance = materialIcon(name = "Rounded.Balance") {
            materialPath {
                moveTo(13.0f, 19.0f)
                verticalLineTo(7.83f)
                curveToRelative(0.85f, -0.3f, 1.53f, -0.98f, 1.83f, -1.83f)
                horizontalLineTo(18.0f)
                lineToRelative(-2.78f, 6.49f)
                curveToRelative(-0.17f, 0.39f, -0.23f, 0.84f, -0.11f, 1.25f)
                curveToRelative(0.39f, 1.3f, 1.76f, 2.26f, 3.39f, 2.26f)
                reflectiveCurveToRelative(3.01f, -0.96f, 3.39f, -2.26f)
                curveToRelative(0.12f, -0.41f, 0.06f, -0.86f, -0.11f, -1.25f)
                lineTo(19.0f, 6.0f)
                horizontalLineToRelative(1.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineToRelative(-5.17f)
                curveTo(14.42f, 2.83f, 13.31f, 2.0f, 12.0f, 2.0f)
                reflectiveCurveTo(9.58f, 2.83f, 9.17f, 4.0f)
                lineTo(4.0f, 4.0f)
                curveTo(3.45f, 4.0f, 3.0f, 4.45f, 3.0f, 5.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(1.0f)
                lineToRelative(-2.78f, 6.49f)
                curveToRelative(-0.17f, 0.39f, -0.23f, 0.84f, -0.11f, 1.25f)
                curveTo(2.49f, 15.04f, 3.87f, 16.0f, 5.5f, 16.0f)
                reflectiveCurveToRelative(3.01f, -0.96f, 3.39f, -2.26f)
                curveToRelative(0.12f, -0.41f, 0.06f, -0.86f, -0.11f, -1.25f)
                lineTo(6.0f, 6.0f)
                horizontalLineToRelative(3.17f)
                curveToRelative(0.3f, 0.85f, 0.98f, 1.53f, 1.83f, 1.83f)
                verticalLineTo(19.0f)
                moveTo(11.0f, 19.0f)
                horizontalLineTo(3.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveToRelative(0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(18.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineToRelative(-8.0f)
                moveTo(20.37f, 13.0f)
                horizontalLineToRelative(-3.74f)
                lineToRelative(1.87f, -4.36f)
                lineTo(20.37f, 13.0f)
                close()
                moveTo(7.37f, 13.0f)
                horizontalLineTo(3.63f)
                lineTo(5.5f, 8.64f)
                lineTo(7.37f, 13.0f)
                close()
                moveTo(12.0f, 6.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
                reflectiveCurveToRelative(1.0f, 0.45f, 1.0f, 1.0f)
                curveTo(13.0f, 5.55f, 12.55f, 6.0f, 12.0f, 6.0f)
                close()
            }
        }
        return _balance!!
    }

private var _balance: ImageVector? = null

public val Icons.Rounded.BatterySaver: ImageVector
    get() {
        if (_batterySaver != null) {
            return _batterySaver!!
        }
        _batterySaver = materialIcon(name = "Rounded.BatterySaver") {
            materialPath {
                moveTo(16.0f, 4.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineTo(3.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineToRelative(-2.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(1.0f)
                horizontalLineTo(8.0f)
                curveTo(7.45f, 4.0f, 7.0f, 4.45f, 7.0f, 5.0f)
                verticalLineToRelative(16.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(8.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineTo(5.0f)
                curveTo(17.0f, 4.45f, 16.55f, 4.0f, 16.0f, 4.0f)
                close()
                moveTo(14.0f, 14.0f)
                horizontalLineToRelative(-1.0f)
                verticalLineToRelative(1.0f)
                curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
                horizontalLineToRelative(0.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                verticalLineToRelative(-1.0f)
                horizontalLineToRelative(-1.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                verticalLineToRelative(0.0f)
                curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
                horizontalLineToRelative(1.0f)
                verticalLineToRelative(-1.0f)
                curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
                horizontalLineToRelative(0.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                verticalLineToRelative(1.0f)
                horizontalLineToRelative(1.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                verticalLineToRelative(0.0f)
                curveTo(15.0f, 13.55f, 14.55f, 14.0f, 14.0f, 14.0f)
                close()
            }
        }
        return _batterySaver!!
    }

private var _batterySaver: ImageVector? = null

public val Icons.Rounded.Bolt: ImageVector
    get() {
        if (_bolt != null) {
            return _bolt!!
        }
        _bolt = materialIcon(name = "Rounded.Bolt") {
            materialPath {
                moveTo(10.67f, 21.0f)
                lineTo(10.67f, 21.0f)
                curveToRelative(-0.35f, 0.0f, -0.62f, -0.31f, -0.57f, -0.66f)
                lineTo(11.0f, 14.0f)
                horizontalLineTo(7.5f)
                curveToRelative(-0.88f, 0.0f, -0.33f, -0.75f, -0.31f, -0.78f)
                curveToRelative(1.26f, -2.23f, 3.15f, -5.53f, 5.65f, -9.93f)
                curveToRelative(0.1f, -0.18f, 0.3f, -0.29f, 0.5f, -0.29f)
                horizontalLineToRelative(0.0f)
                curveToRelative(0.35f, 0.0f, 0.62f, 0.31f, 0.57f, 0.66f)
                lineTo(13.01f, 10.0f)
                horizontalLineToRelative(3.51f)
                curveToRelative(0.4f, 0.0f, 0.62f, 0.19f, 0.4f, 0.66f)
                curveToRelative(-3.29f, 5.74f, -5.2f, 9.09f, -5.75f, 10.05f)
                curveTo(11.07f, 20.89f, 10.88f, 21.0f, 10.67f, 21.0f)
                close()
            }
        }
        return _bolt!!
    }

private var _bolt: ImageVector? = null

public val Icons.Rounded.ChevronLeft: ImageVector
    get() {
        if (_chevronLeft != null) {
            return _chevronLeft!!
        }
        _chevronLeft = materialIcon(name = "Rounded.ChevronLeft") {
            materialPath {
                moveTo(14.71f, 6.71f)
                curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, 0.0f)
                lineTo(8.71f, 11.3f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.02f, 0.0f, 1.41f)
                lineToRelative(4.59f, 4.59f)
                curveToRelative(0.39f, 0.39f, 1.02f, 0.39f, 1.41f, 0.0f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0.0f, -1.41f)
                lineTo(10.83f, 12.0f)
                lineToRelative(3.88f, -3.88f)
                curveToRelative(0.39f, -0.39f, 0.38f, -1.03f, 0.0f, -1.41f)
                close()
            }
        }
        return _chevronLeft!!
    }

private var _chevronLeft: ImageVector? = null

public val Icons.Rounded.ChevronRight: ImageVector
    get() {
        if (_chevronRight != null) {
            return _chevronRight!!
        }
        _chevronRight = materialIcon(name = "Rounded.ChevronRight") {
            materialPath {
                moveTo(9.29f, 6.71f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.02f, 0.0f, 1.41f)
                lineTo(13.17f, 12.0f)
                lineToRelative(-3.88f, 3.88f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.02f, 0.0f, 1.41f)
                curveToRelative(0.39f, 0.39f, 1.02f, 0.39f, 1.41f, 0.0f)
                lineToRelative(4.59f, -4.59f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0.0f, -1.41f)
                lineTo(10.7f, 6.7f)
                curveToRelative(-0.38f, -0.38f, -1.02f, -0.38f, -1.41f, 0.01f)
                close()
            }
        }
        return _chevronRight!!
    }

private var _chevronRight: ImageVector? = null

public val Icons.Rounded.Dashboard: ImageVector
    get() {
        if (_dashboard != null) {
            return _dashboard!!
        }
        _dashboard = materialIcon(name = "Rounded.Dashboard") {
            materialPath {
                moveTo(4.0f, 13.0f)
                horizontalLineToRelative(6.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                lineTo(11.0f, 4.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(4.0f, 3.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(8.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                close()
                moveTo(4.0f, 21.0f)
                horizontalLineToRelative(6.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(-4.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(4.0f, 15.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(4.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                close()
                moveTo(14.0f, 21.0f)
                horizontalLineToRelative(6.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(-8.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineToRelative(-6.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(8.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                close()
                moveTo(13.0f, 4.0f)
                verticalLineToRelative(4.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(6.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                lineTo(21.0f, 4.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineToRelative(-6.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                close()
            }
        }
        return _dashboard!!
    }

private var _dashboard: ImageVector? = null

public val Icons.Rounded.DeleteSweep: ImageVector
    get() {
        if (_deleteSweep != null) {
            return _deleteSweep!!
        }
        _deleteSweep = materialIcon(name = "Rounded.DeleteSweep") {
            materialPath {
                moveTo(16.0f, 16.0f)
                horizontalLineToRelative(2.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                reflectiveCurveToRelative(-0.45f, 1.0f, -1.0f, 1.0f)
                horizontalLineToRelative(-2.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                reflectiveCurveToRelative(0.45f, -1.0f, 1.0f, -1.0f)
                close()
                moveTo(16.0f, 8.0f)
                horizontalLineToRelative(5.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                reflectiveCurveToRelative(-0.45f, 1.0f, -1.0f, 1.0f)
                horizontalLineToRelative(-5.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                reflectiveCurveToRelative(0.45f, -1.0f, 1.0f, -1.0f)
                close()
                moveTo(16.0f, 12.0f)
                horizontalLineToRelative(4.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                reflectiveCurveToRelative(-0.45f, 1.0f, -1.0f, 1.0f)
                horizontalLineToRelative(-4.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                reflectiveCurveToRelative(0.45f, -1.0f, 1.0f, -1.0f)
                close()
                moveTo(3.0f, 18.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(6.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(13.0f, 8.0f)
                lineTo(3.0f, 8.0f)
                verticalLineToRelative(10.0f)
                close()
                moveTo(13.0f, 5.0f)
                horizontalLineToRelative(-2.0f)
                lineToRelative(-0.71f, -0.71f)
                curveToRelative(-0.18f, -0.18f, -0.44f, -0.29f, -0.7f, -0.29f)
                lineTo(6.41f, 4.0f)
                curveToRelative(-0.26f, 0.0f, -0.52f, 0.11f, -0.7f, 0.29f)
                lineTo(5.0f, 5.0f)
                lineTo(3.0f, 5.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveToRelative(0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(10.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                close()
            }
        }
        return _deleteSweep!!
    }

private var _deleteSweep: ImageVector? = null

public val Icons.Rounded.DeveloperBoard: ImageVector
    get() {
        if (_developerBoard != null) {
            return _developerBoard!!
        }
        _developerBoard = materialIcon(name = "Rounded.DeveloperBoard") {
            materialPath {
                moveTo(22.0f, 8.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineToRelative(-1.0f)
                lineTo(20.0f, 5.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                lineTo(4.0f, 3.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(1.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineToRelative(-1.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(1.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineToRelative(-1.0f)
                lineTo(20.0f, 9.0f)
                horizontalLineToRelative(1.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                close()
                moveTo(17.0f, 19.0f)
                lineTo(5.0f, 19.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                lineTo(4.0f, 6.0f)
                curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
                horizontalLineToRelative(12.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                verticalLineToRelative(12.0f)
                curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
                close()
                moveTo(6.5f, 13.0f)
                horizontalLineToRelative(4.0f)
                curveToRelative(0.28f, 0.0f, 0.5f, 0.22f, 0.5f, 0.5f)
                verticalLineToRelative(3.0f)
                curveToRelative(0.0f, 0.28f, -0.22f, 0.5f, -0.5f, 0.5f)
                horizontalLineToRelative(-4.0f)
                curveToRelative(-0.28f, 0.0f, -0.5f, -0.22f, -0.5f, -0.5f)
                verticalLineToRelative(-3.0f)
                curveToRelative(0.0f, -0.28f, 0.22f, -0.5f, 0.5f, -0.5f)
                close()
                moveTo(12.5f, 7.0f)
                horizontalLineToRelative(3.0f)
                curveToRelative(0.28f, 0.0f, 0.5f, 0.22f, 0.5f, 0.5f)
                verticalLineToRelative(2.0f)
                curveToRelative(0.0f, 0.28f, -0.22f, 0.5f, -0.5f, 0.5f)
                horizontalLineToRelative(-3.0f)
                curveToRelative(-0.28f, 0.0f, -0.5f, -0.22f, -0.5f, -0.5f)
                verticalLineToRelative(-2.0f)
                curveToRelative(0.0f, -0.28f, 0.22f, -0.5f, 0.5f, -0.5f)
                close()
                moveTo(6.5f, 7.0f)
                horizontalLineToRelative(4.0f)
                curveToRelative(0.28f, 0.0f, 0.5f, 0.22f, 0.5f, 0.5f)
                verticalLineToRelative(4.0f)
                curveToRelative(0.0f, 0.28f, -0.22f, 0.5f, -0.5f, 0.5f)
                horizontalLineToRelative(-4.0f)
                curveToRelative(-0.28f, 0.0f, -0.5f, -0.22f, -0.5f, -0.5f)
                verticalLineToRelative(-4.0f)
                curveToRelative(0.0f, -0.28f, 0.22f, -0.5f, 0.5f, -0.5f)
                close()
                moveTo(12.5f, 11.0f)
                horizontalLineToRelative(3.0f)
                curveToRelative(0.28f, 0.0f, 0.5f, 0.22f, 0.5f, 0.5f)
                verticalLineToRelative(5.0f)
                curveToRelative(0.0f, 0.28f, -0.22f, 0.5f, -0.5f, 0.5f)
                horizontalLineToRelative(-3.0f)
                curveToRelative(-0.28f, 0.0f, -0.5f, -0.22f, -0.5f, -0.5f)
                verticalLineToRelative(-5.0f)
                curveToRelative(0.0f, -0.28f, 0.22f, -0.5f, 0.5f, -0.5f)
                close()
            }
        }
        return _developerBoard!!
    }

private var _developerBoard: ImageVector? = null

public val Icons.Rounded.DeviceThermostat: ImageVector
    get() {
        if (_deviceThermostat != null) {
            return _deviceThermostat!!
        }
        _deviceThermostat = materialIcon(name = "Rounded.DeviceThermostat") {
            materialPath {
                moveTo(15.0f, 13.0f)
                verticalLineTo(5.0f)
                curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
                reflectiveCurveTo(9.0f, 3.34f, 9.0f, 5.0f)
                verticalLineToRelative(8.0f)
                curveToRelative(-1.21f, 0.91f, -2.0f, 2.37f, -2.0f, 4.0f)
                curveToRelative(0.0f, 2.76f, 2.24f, 5.0f, 5.0f, 5.0f)
                reflectiveCurveToRelative(5.0f, -2.24f, 5.0f, -5.0f)
                curveTo(17.0f, 15.37f, 16.21f, 13.91f, 15.0f, 13.0f)
                close()
                moveTo(11.0f, 11.0f)
                verticalLineTo(5.0f)
                curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
                reflectiveCurveToRelative(1.0f, 0.45f, 1.0f, 1.0f)
                verticalLineToRelative(1.0f)
                horizontalLineToRelative(-1.0f)
                verticalLineToRelative(1.0f)
                horizontalLineToRelative(1.0f)
                verticalLineToRelative(1.0f)
                verticalLineToRelative(1.0f)
                horizontalLineToRelative(-1.0f)
                verticalLineToRelative(1.0f)
                horizontalLineToRelative(1.0f)
                verticalLineToRelative(1.0f)
                horizontalLineTo(11.0f)
                close()
            }
        }
        return _deviceThermostat!!
    }

private var _deviceThermostat: ImageVector? = null

public val Icons.Rounded.ExpandLess: ImageVector
    get() {
        if (_expandLess != null) {
            return _expandLess!!
        }
        _expandLess = materialIcon(name = "Rounded.ExpandLess") {
            materialPath {
                moveTo(11.29f, 8.71f)
                lineTo(6.7f, 13.3f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.02f, 0.0f, 1.41f)
                curveToRelative(0.39f, 0.39f, 1.02f, 0.39f, 1.41f, 0.0f)
                lineTo(12.0f, 10.83f)
                lineToRelative(3.88f, 3.88f)
                curveToRelative(0.39f, 0.39f, 1.02f, 0.39f, 1.41f, 0.0f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0.0f, -1.41f)
                lineTo(12.7f, 8.71f)
                curveToRelative(-0.38f, -0.39f, -1.02f, -0.39f, -1.41f, 0.0f)
                close()
            }
        }
        return _expandLess!!
    }

private var _expandLess: ImageVector? = null

public val Icons.Rounded.ExpandMore: ImageVector
    get() {
        if (_expandMore != null) {
            return _expandMore!!
        }
        _expandMore = materialIcon(name = "Rounded.ExpandMore") {
            materialPath {
                moveTo(15.88f, 9.29f)
                lineTo(12.0f, 13.17f)
                lineTo(8.12f, 9.29f)
                curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, 0.0f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.02f, 0.0f, 1.41f)
                lineToRelative(4.59f, 4.59f)
                curveToRelative(0.39f, 0.39f, 1.02f, 0.39f, 1.41f, 0.0f)
                lineToRelative(4.59f, -4.59f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0.0f, -1.41f)
                curveToRelative(-0.39f, -0.38f, -1.03f, -0.39f, -1.42f, 0.0f)
                close()
            }
        }
        return _expandMore!!
    }

private var _expandMore: ImageVector? = null

public val Icons.Rounded.FiberManualRecord: ImageVector
    get() {
        if (_fiberManualRecord != null) {
            return _fiberManualRecord!!
        }
        _fiberManualRecord = materialIcon(name = "Rounded.FiberManualRecord") {
            materialPath {
                moveTo(12.0f, 12.0f)
                moveToRelative(-8.0f, 0.0f)
                arcToRelative(8.0f, 8.0f, 0.0f, true, true, 16.0f, 0.0f)
                arcToRelative(8.0f, 8.0f, 0.0f, true, true, -16.0f, 0.0f)
            }
        }
        return _fiberManualRecord!!
    }

private var _fiberManualRecord: ImageVector? = null

public val Icons.Rounded.GppMaybe: ImageVector
    get() {
        if (_gppMaybe != null) {
            return _gppMaybe!!
        }
        _gppMaybe = materialIcon(name = "Rounded.GppMaybe") {
            materialPath {
                moveTo(11.3f, 2.26f)
                lineToRelative(-6.0f, 2.25f)
                curveTo(4.52f, 4.81f, 4.0f, 5.55f, 4.0f, 6.39f)
                verticalLineToRelative(4.7f)
                curveToRelative(0.0f, 5.05f, 3.41f, 9.76f, 8.0f, 10.91f)
                curveToRelative(4.59f, -1.15f, 8.0f, -5.86f, 8.0f, -10.91f)
                verticalLineToRelative(-4.7f)
                curveToRelative(0.0f, -0.83f, -0.52f, -1.58f, -1.3f, -1.87f)
                lineToRelative(-6.0f, -2.25f)
                curveTo(12.25f, 2.09f, 11.75f, 2.09f, 11.3f, 2.26f)
                close()
                moveTo(12.0f, 16.0f)
                lineTo(12.0f, 16.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                verticalLineToRelative(0.0f)
                curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
                horizontalLineToRelative(0.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                verticalLineToRelative(0.0f)
                curveTo(13.0f, 15.55f, 12.55f, 16.0f, 12.0f, 16.0f)
                close()
                moveTo(12.0f, 12.0f)
                lineTo(12.0f, 12.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                verticalLineTo(8.0f)
                curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
                horizontalLineToRelative(0.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                verticalLineToRelative(3.0f)
                curveTo(13.0f, 11.55f, 12.55f, 12.0f, 12.0f, 12.0f)
                close()
            }
        }
        return _gppMaybe!!
    }

private var _gppMaybe: ImageVector? = null

public val Icons.Rounded.History: ImageVector
    get() {
        if (_history != null) {
            return _history!!
        }
        _history = materialIcon(name = "Rounded.History") {
            materialPath {
                moveTo(13.26f, 3.0f)
                curveTo(8.17f, 2.86f, 4.0f, 6.95f, 4.0f, 12.0f)
                lineTo(2.21f, 12.0f)
                curveToRelative(-0.45f, 0.0f, -0.67f, 0.54f, -0.35f, 0.85f)
                lineToRelative(2.79f, 2.8f)
                curveToRelative(0.2f, 0.2f, 0.51f, 0.2f, 0.71f, 0.0f)
                lineToRelative(2.79f, -2.8f)
                curveToRelative(0.31f, -0.31f, 0.09f, -0.85f, -0.36f, -0.85f)
                lineTo(6.0f, 12.0f)
                curveToRelative(0.0f, -3.9f, 3.18f, -7.05f, 7.1f, -7.0f)
                curveToRelative(3.72f, 0.05f, 6.85f, 3.18f, 6.9f, 6.9f)
                curveToRelative(0.05f, 3.91f, -3.1f, 7.1f, -7.0f, 7.1f)
                curveToRelative(-1.61f, 0.0f, -3.1f, -0.55f, -4.28f, -1.48f)
                curveToRelative(-0.4f, -0.31f, -0.96f, -0.28f, -1.32f, 0.08f)
                curveToRelative(-0.42f, 0.42f, -0.39f, 1.13f, 0.08f, 1.49f)
                curveTo(9.0f, 20.29f, 10.91f, 21.0f, 13.0f, 21.0f)
                curveToRelative(5.05f, 0.0f, 9.14f, -4.17f, 9.0f, -9.26f)
                curveToRelative(-0.13f, -4.69f, -4.05f, -8.61f, -8.74f, -8.74f)
                close()
                moveTo(12.75f, 8.0f)
                curveToRelative(-0.41f, 0.0f, -0.75f, 0.34f, -0.75f, 0.75f)
                verticalLineToRelative(3.68f)
                curveToRelative(0.0f, 0.35f, 0.19f, 0.68f, 0.49f, 0.86f)
                lineToRelative(3.12f, 1.85f)
                curveToRelative(0.36f, 0.21f, 0.82f, 0.09f, 1.03f, -0.26f)
                curveToRelative(0.21f, -0.36f, 0.09f, -0.82f, -0.26f, -1.03f)
                lineToRelative(-2.88f, -1.71f)
                verticalLineToRelative(-3.4f)
                curveToRelative(0.0f, -0.4f, -0.34f, -0.74f, -0.75f, -0.74f)
                close()
            }
        }
        return _history!!
    }

private var _history: ImageVector? = null

public val Icons.Rounded.Memory: ImageVector
    get() {
        if (_memory != null) {
            return _memory!!
        }
        _memory = materialIcon(name = "Rounded.Memory") {
            materialPath {
                moveTo(14.0f, 9.0f)
                horizontalLineToRelative(-4.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(4.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(4.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(-4.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                close()
                moveTo(13.0f, 13.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(21.0f, 10.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineToRelative(-1.0f)
                lineTo(19.0f, 7.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                horizontalLineToRelative(-2.0f)
                lineTo(15.0f, 4.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                reflectiveCurveToRelative(-1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(1.0f)
                horizontalLineToRelative(-2.0f)
                lineTo(11.0f, 4.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                reflectiveCurveToRelative(-1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(1.0f)
                lineTo(7.0f, 5.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(2.0f)
                lineTo(4.0f, 9.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveToRelative(0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(1.0f)
                verticalLineToRelative(2.0f)
                lineTo(4.0f, 13.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveToRelative(0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(1.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(1.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                reflectiveCurveToRelative(1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(-1.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(1.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                reflectiveCurveToRelative(1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(-1.0f)
                horizontalLineToRelative(2.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(1.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineToRelative(-1.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(1.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                close()
                moveTo(16.0f, 17.0f)
                lineTo(8.0f, 17.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                lineTo(7.0f, 8.0f)
                curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
                horizontalLineToRelative(8.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                verticalLineToRelative(8.0f)
                curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
                close()
            }
        }
        return _memory!!
    }

private var _memory: ImageVector? = null

public val Icons.Rounded.Palette: ImageVector
    get() {
        if (_palette != null) {
            return _palette!!
        }
        _palette = materialIcon(name = "Rounded.Palette") {
            materialPath {
                moveTo(12.0f, 2.0f)
                curveTo(6.49f, 2.0f, 2.0f, 6.49f, 2.0f, 12.0f)
                reflectiveCurveToRelative(4.49f, 10.0f, 10.0f, 10.0f)
                curveToRelative(1.38f, 0.0f, 2.5f, -1.12f, 2.5f, -2.5f)
                curveToRelative(0.0f, -0.61f, -0.23f, -1.2f, -0.64f, -1.67f)
                curveToRelative(-0.08f, -0.1f, -0.13f, -0.21f, -0.13f, -0.33f)
                curveToRelative(0.0f, -0.28f, 0.22f, -0.5f, 0.5f, -0.5f)
                horizontalLineTo(16.0f)
                curveToRelative(3.31f, 0.0f, 6.0f, -2.69f, 6.0f, -6.0f)
                curveTo(22.0f, 6.04f, 17.51f, 2.0f, 12.0f, 2.0f)
                close()
                moveTo(17.5f, 13.0f)
                curveToRelative(-0.83f, 0.0f, -1.5f, -0.67f, -1.5f, -1.5f)
                curveToRelative(0.0f, -0.83f, 0.67f, -1.5f, 1.5f, -1.5f)
                reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
                curveTo(19.0f, 12.33f, 18.33f, 13.0f, 17.5f, 13.0f)
                close()
                moveTo(14.5f, 9.0f)
                curveTo(13.67f, 9.0f, 13.0f, 8.33f, 13.0f, 7.5f)
                curveTo(13.0f, 6.67f, 13.67f, 6.0f, 14.5f, 6.0f)
                reflectiveCurveTo(16.0f, 6.67f, 16.0f, 7.5f)
                curveTo(16.0f, 8.33f, 15.33f, 9.0f, 14.5f, 9.0f)
                close()
                moveTo(5.0f, 11.5f)
                curveTo(5.0f, 10.67f, 5.67f, 10.0f, 6.5f, 10.0f)
                reflectiveCurveTo(8.0f, 10.67f, 8.0f, 11.5f)
                curveTo(8.0f, 12.33f, 7.33f, 13.0f, 6.5f, 13.0f)
                reflectiveCurveTo(5.0f, 12.33f, 5.0f, 11.5f)
                close()
                moveTo(11.0f, 7.5f)
                curveTo(11.0f, 8.33f, 10.33f, 9.0f, 9.5f, 9.0f)
                reflectiveCurveTo(8.0f, 8.33f, 8.0f, 7.5f)
                curveTo(8.0f, 6.67f, 8.67f, 6.0f, 9.5f, 6.0f)
                reflectiveCurveTo(11.0f, 6.67f, 11.0f, 7.5f)
                close()
            }
        }
        return _palette!!
    }

private var _palette: ImageVector? = null

public val Icons.Rounded.Public: ImageVector
    get() {
        if (_public != null) {
            return _public!!
        }
        _public = materialIcon(name = "Rounded.Public") {
            materialPath {
                moveTo(12.0f, 2.0f)
                curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
                reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
                reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
                close()
                moveTo(11.0f, 19.93f)
                curveToRelative(-3.95f, -0.49f, -7.0f, -3.85f, -7.0f, -7.93f)
                curveToRelative(0.0f, -0.62f, 0.08f, -1.21f, 0.21f, -1.79f)
                lineTo(9.0f, 15.0f)
                verticalLineToRelative(1.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                verticalLineToRelative(1.93f)
                close()
                moveTo(17.9f, 17.39f)
                curveToRelative(-0.26f, -0.81f, -1.0f, -1.39f, -1.9f, -1.39f)
                horizontalLineToRelative(-1.0f)
                verticalLineToRelative(-3.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(8.0f, 12.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                lineTo(11.0f, 7.0f)
                horizontalLineToRelative(2.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineToRelative(-0.41f)
                curveToRelative(2.93f, 1.19f, 5.0f, 4.06f, 5.0f, 7.41f)
                curveToRelative(0.0f, 2.08f, -0.8f, 3.97f, -2.1f, 5.39f)
                close()
            }
        }
        return _public!!
    }

private var _public: ImageVector? = null

public val Icons.Rounded.Rocket: ImageVector
    get() {
        if (_rocket != null) {
            return _rocket!!
        }
        _rocket = materialIcon(name = "Rounded.Rocket") {
            materialPath {
                moveTo(11.41f, 2.87f)
                curveToRelative(0.35f, -0.26f, 0.82f, -0.26f, 1.18f, 0.0f)
                curveTo(13.81f, 3.75f, 16.5f, 6.46f, 16.5f, 13.0f)
                curveToRelative(0.0f, 2.16f, -0.78f, 4.76f, -1.36f, 6.35f)
                curveTo(15.0f, 19.74f, 14.63f, 20.0f, 14.21f, 20.0f)
                lineToRelative(-4.41f, 0.0f)
                curveToRelative(-0.42f, 0.0f, -0.8f, -0.26f, -0.94f, -0.65f)
                curveTo(8.28f, 17.76f, 7.5f, 15.16f, 7.5f, 13.0f)
                curveTo(7.5f, 6.46f, 10.19f, 3.75f, 11.41f, 2.87f)
                close()
                moveTo(14.0f, 11.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                reflectiveCurveToRelative(-2.0f, 0.9f, -2.0f, 2.0f)
                reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
                reflectiveCurveTo(14.0f, 12.1f, 14.0f, 11.0f)
                close()
                moveTo(7.69f, 20.52f)
                curveToRelative(-0.48f, -1.23f, -1.52f, -4.17f, -1.67f, -6.87f)
                lineToRelative(-1.13f, 0.75f)
                curveTo(4.33f, 14.78f, 4.0f, 15.4f, 4.0f, 16.07f)
                verticalLineToRelative(4.45f)
                curveToRelative(0.0f, 0.71f, 0.71f, 1.19f, 1.37f, 0.93f)
                lineTo(7.69f, 20.52f)
                close()
                moveTo(20.0f, 20.52f)
                verticalLineToRelative(-4.45f)
                curveToRelative(0.0f, -0.67f, -0.33f, -1.29f, -0.89f, -1.66f)
                lineToRelative(-1.13f, -0.75f)
                curveToRelative(-0.15f, 2.69f, -1.2f, 5.64f, -1.67f, 6.87f)
                lineToRelative(2.32f, 0.93f)
                curveTo(19.29f, 21.71f, 20.0f, 21.23f, 20.0f, 20.52f)
                close()
            }
        }
        return _rocket!!
    }

private var _rocket: ImageVector? = null

public val Icons.Rounded.Security: ImageVector
    get() {
        if (_security != null) {
            return _security!!
        }
        _security = materialIcon(name = "Rounded.Security") {
            materialPath {
                moveTo(11.19f, 1.36f)
                lineToRelative(-7.0f, 3.11f)
                curveTo(3.47f, 4.79f, 3.0f, 5.51f, 3.0f, 6.3f)
                verticalLineTo(11.0f)
                curveToRelative(0.0f, 5.55f, 3.84f, 10.74f, 9.0f, 12.0f)
                curveToRelative(5.16f, -1.26f, 9.0f, -6.45f, 9.0f, -12.0f)
                verticalLineTo(6.3f)
                curveToRelative(0.0f, -0.79f, -0.47f, -1.51f, -1.19f, -1.83f)
                lineToRelative(-7.0f, -3.11f)
                curveToRelative(-0.51f, -0.23f, -1.11f, -0.23f, -1.62f, 0.0f)
                close()
                moveTo(12.0f, 11.99f)
                horizontalLineToRelative(7.0f)
                curveToRelative(-0.53f, 4.12f, -3.28f, 7.79f, -7.0f, 8.94f)
                verticalLineTo(12.0f)
                horizontalLineTo(5.0f)
                verticalLineTo(6.3f)
                lineToRelative(7.0f, -3.11f)
                verticalLineToRelative(8.8f)
                close()
            }
        }
        return _security!!
    }

private var _security: ImageVector? = null

public val Icons.Rounded.SensorsOff: ImageVector
    get() {
        if (_sensorsOff != null) {
            return _sensorsOff!!
        }
        _sensorsOff = materialIcon(name = "Rounded.SensorsOff") {
            materialPath {
                moveTo(5.68f, 18.32f)
                curveToRelative(-0.42f, 0.42f, -1.12f, 0.39f, -1.5f, -0.08f)
                curveTo(2.82f, 16.53f, 2.0f, 14.36f, 2.0f, 12.0f)
                curveToRelative(0.0f, -2.04f, 0.61f, -3.93f, 1.66f, -5.51f)
                lineTo(2.1f, 4.93f)
                curveToRelative(-0.39f, -0.39f, -0.39f, -1.02f, 0.0f, -1.41f)
                curveToRelative(0.39f, -0.39f, 1.02f, -0.39f, 1.41f, 0.0f)
                lineToRelative(16.97f, 16.97f)
                curveToRelative(0.39f, 0.39f, 0.39f, 1.02f, 0.0f, 1.41f)
                reflectiveCurveToRelative(-1.02f, 0.39f, -1.41f, 0.0f)
                lineTo(8.14f, 10.96f)
                curveTo(8.05f, 11.29f, 8.0f, 11.64f, 8.0f, 12.0f)
                curveToRelative(0.0f, 0.8f, 0.24f, 1.55f, 0.64f, 2.17f)
                curveToRelative(0.27f, 0.41f, 0.24f, 0.94f, -0.1f, 1.29f)
                curveToRelative(-0.43f, 0.43f, -1.17f, 0.4f, -1.51f, -0.11f)
                curveTo(6.38f, 14.4f, 6.0f, 13.24f, 6.0f, 12.0f)
                curveToRelative(0.0f, -0.93f, 0.21f, -1.8f, 0.58f, -2.59f)
                lineTo(5.11f, 7.94f)
                curveTo(4.4f, 9.13f, 4.0f, 10.52f, 4.0f, 12.0f)
                curveToRelative(0.0f, 1.89f, 0.66f, 3.63f, 1.76f, 5.0f)
                curveTo(6.08f, 17.39f, 6.04f, 17.96f, 5.68f, 18.32f)
                close()
                moveTo(15.46f, 8.54f)
                curveToRelative(-0.35f, 0.35f, -0.37f, 0.88f, -0.11f, 1.29f)
                curveTo(15.76f, 10.45f, 16.0f, 11.2f, 16.0f, 12.0f)
                curveToRelative(0.0f, 0.36f, -0.05f, 0.71f, -0.14f, 1.04f)
                lineToRelative(1.55f, 1.55f)
                curveTo(17.79f, 13.8f, 18.0f, 12.93f, 18.0f, 12.0f)
                curveToRelative(0.0f, -1.24f, -0.38f, -2.4f, -1.03f, -3.36f)
                curveTo(16.63f, 8.14f, 15.9f, 8.1f, 15.46f, 8.54f)
                close()
                moveTo(18.32f, 5.68f)
                curveToRelative(-0.36f, 0.36f, -0.4f, 0.92f, -0.08f, 1.32f)
                curveToRelative(1.1f, 1.37f, 1.76f, 3.11f, 1.76f, 5.0f)
                curveToRelative(0.0f, 1.48f, -0.4f, 2.87f, -1.11f, 4.06f)
                lineToRelative(1.45f, 1.45f)
                curveTo(21.39f, 15.93f, 22.0f, 14.04f, 22.0f, 12.0f)
                curveToRelative(0.0f, -2.36f, -0.82f, -4.53f, -2.18f, -6.24f)
                curveTo(19.44f, 5.29f, 18.74f, 5.26f, 18.32f, 5.68f)
                close()
            }
        }
        return _sensorsOff!!
    }

private var _sensorsOff: ImageVector? = null

public val Icons.Rounded.Shield: ImageVector
    get() {
        if (_shield != null) {
            return _shield!!
        }
        _shield = materialIcon(name = "Rounded.Shield") {
            materialPath {
                moveTo(11.3f, 2.26f)
                lineToRelative(-6.0f, 2.25f)
                curveTo(4.52f, 4.81f, 4.0f, 5.55f, 4.0f, 6.39f)
                verticalLineToRelative(4.7f)
                curveToRelative(0.0f, 4.83f, 3.13f, 9.37f, 7.43f, 10.75f)
                curveToRelative(0.37f, 0.12f, 0.77f, 0.12f, 1.14f, 0.0f)
                curveToRelative(4.3f, -1.38f, 7.43f, -5.91f, 7.43f, -10.75f)
                verticalLineToRelative(-4.7f)
                curveToRelative(0.0f, -0.83f, -0.52f, -1.58f, -1.3f, -1.87f)
                lineToRelative(-6.0f, -2.25f)
                curveTo(12.25f, 2.09f, 11.75f, 2.09f, 11.3f, 2.26f)
                close()
            }
        }
        return _shield!!
    }

private var _shield: ImageVector? = null

public val Icons.Rounded.Speed: ImageVector
    get() {
        if (_speed != null) {
            return _speed!!
        }
        _speed = materialIcon(name = "Rounded.Speed") {
            materialPath {
                moveTo(19.46f, 10.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, false, false, -0.07f, 1.0f)
                arcToRelative(7.55f, 7.55f, 0.0f, false, true, 0.52f, 1.81f)
                arcToRelative(8.0f, 8.0f, 0.0f, false, true, -0.69f, 4.73f)
                arcToRelative(1.0f, 1.0f, 0.0f, false, true, -0.89f, 0.53f)
                horizontalLineTo(5.68f)
                arcToRelative(1.0f, 1.0f, 0.0f, false, true, -0.89f, -0.54f)
                arcTo(8.0f, 8.0f, 0.0f, false, true, 13.0f, 6.06f)
                arcToRelative(7.69f, 7.69f, 0.0f, false, true, 2.11f, 0.56f)
                arcToRelative(1.0f, 1.0f, 0.0f, false, false, 1.0f, -0.07f)
                arcToRelative(1.0f, 1.0f, 0.0f, false, false, -0.17f, -1.76f)
                arcTo(10.0f, 10.0f, 0.0f, false, false, 3.35f, 19.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, false, false, 1.72f, 1.0f)
                horizontalLineToRelative(13.85f)
                arcToRelative(2.0f, 2.0f, 0.0f, false, false, 1.74f, -1.0f)
                arcToRelative(10.0f, 10.0f, 0.0f, false, false, 0.55f, -8.89f)
                arcToRelative(1.0f, 1.0f, 0.0f, false, false, -1.75f, -0.11f)
                close()
            }
            materialPath {
                moveTo(10.59f, 12.59f)
                arcToRelative(2.0f, 2.0f, 0.0f, false, false, 2.83f, 2.83f)
                lineToRelative(5.66f, -8.49f)
                close()
            }
        }
        return _speed!!
    }

private var _speed: ImageVector? = null

public val Icons.Rounded.SportsEsports: ImageVector
    get() {
        if (_sportsEsports != null) {
            return _sportsEsports!!
        }
        _sportsEsports = materialIcon(name = "Rounded.SportsEsports") {
            materialPath {
                moveTo(21.58f, 16.09f)
                lineToRelative(-1.09f, -7.66f)
                curveTo(20.21f, 6.46f, 18.52f, 5.0f, 16.53f, 5.0f)
                horizontalLineTo(7.47f)
                curveTo(5.48f, 5.0f, 3.79f, 6.46f, 3.51f, 8.43f)
                lineToRelative(-1.09f, 7.66f)
                curveTo(2.2f, 17.63f, 3.39f, 19.0f, 4.94f, 19.0f)
                horizontalLineToRelative(0.0f)
                curveToRelative(0.68f, 0.0f, 1.32f, -0.27f, 1.8f, -0.75f)
                lineTo(9.0f, 16.0f)
                horizontalLineToRelative(6.0f)
                lineToRelative(2.25f, 2.25f)
                curveToRelative(0.48f, 0.48f, 1.13f, 0.75f, 1.8f, 0.75f)
                horizontalLineToRelative(0.0f)
                curveTo(20.61f, 19.0f, 21.8f, 17.63f, 21.58f, 16.09f)
                close()
                moveTo(11.0f, 11.0f)
                horizontalLineTo(9.0f)
                verticalLineToRelative(2.0f)
                horizontalLineTo(8.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineTo(6.0f)
                verticalLineToRelative(-1.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(8.0f)
                horizontalLineToRelative(1.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(11.0f)
                close()
                moveTo(15.0f, 10.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
                reflectiveCurveToRelative(1.0f, 0.45f, 1.0f, 1.0f)
                curveTo(16.0f, 9.55f, 15.55f, 10.0f, 15.0f, 10.0f)
                close()
                moveTo(17.0f, 13.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
                reflectiveCurveToRelative(1.0f, 0.45f, 1.0f, 1.0f)
                curveTo(18.0f, 12.55f, 17.55f, 13.0f, 17.0f, 13.0f)
                close()
            }
        }
        return _sportsEsports!!
    }

private var _sportsEsports: ImageVector? = null

public val Icons.Rounded.Stop: ImageVector
    get() {
        if (_stop != null) {
            return _stop!!
        }
        _stop = materialIcon(name = "Rounded.Stop") {
            materialPath {
                moveTo(8.0f, 6.0f)
                horizontalLineToRelative(8.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, 0.9f, 2.0f, 2.0f)
                verticalLineToRelative(8.0f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                horizontalLineTo(8.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, -0.9f, -2.0f, -2.0f)
                verticalLineTo(8.0f)
                curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
                close()
            }
        }
        return _stop!!
    }

private var _stop: ImageVector? = null

public val Icons.Rounded.Storage: ImageVector
    get() {
        if (_storage != null) {
            return _storage!!
        }
        _storage = materialIcon(name = "Rounded.Storage") {
            materialPath {
                moveTo(4.0f, 20.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
                lineTo(4.0f, 16.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
                close()
                moveTo(4.0f, 17.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                lineTo(4.0f, 19.0f)
                verticalLineToRelative(-2.0f)
                close()
                moveTo(2.0f, 6.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
                lineTo(4.0f, 4.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                close()
                moveTo(6.0f, 7.0f)
                lineTo(4.0f, 7.0f)
                lineTo(4.0f, 5.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(4.0f, 14.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
                lineTo(4.0f, 10.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
                close()
                moveTo(4.0f, 11.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                lineTo(4.0f, 13.0f)
                verticalLineToRelative(-2.0f)
                close()
            }
        }
        return _storage!!
    }

private var _storage: ImageVector? = null

public val Icons.Rounded.Sync: ImageVector
    get() {
        if (_sync != null) {
            return _sync!!
        }
        _sync = materialIcon(name = "Rounded.Sync") {
            materialPath {
                moveTo(12.0f, 4.0f)
                lineTo(12.0f, 2.21f)
                curveToRelative(0.0f, -0.45f, -0.54f, -0.67f, -0.85f, -0.35f)
                lineToRelative(-2.8f, 2.79f)
                curveToRelative(-0.2f, 0.2f, -0.2f, 0.51f, 0.0f, 0.71f)
                lineToRelative(2.79f, 2.79f)
                curveToRelative(0.32f, 0.31f, 0.86f, 0.09f, 0.86f, -0.36f)
                lineTo(12.0f, 6.0f)
                curveToRelative(3.31f, 0.0f, 6.0f, 2.69f, 6.0f, 6.0f)
                curveToRelative(0.0f, 0.79f, -0.15f, 1.56f, -0.44f, 2.25f)
                curveToRelative(-0.15f, 0.36f, -0.04f, 0.77f, 0.23f, 1.04f)
                curveToRelative(0.51f, 0.51f, 1.37f, 0.33f, 1.64f, -0.34f)
                curveToRelative(0.37f, -0.91f, 0.57f, -1.91f, 0.57f, -2.95f)
                curveToRelative(0.0f, -4.42f, -3.58f, -8.0f, -8.0f, -8.0f)
                close()
                moveTo(12.0f, 18.0f)
                curveToRelative(-3.31f, 0.0f, -6.0f, -2.69f, -6.0f, -6.0f)
                curveToRelative(0.0f, -0.79f, 0.15f, -1.56f, 0.44f, -2.25f)
                curveToRelative(0.15f, -0.36f, 0.04f, -0.77f, -0.23f, -1.04f)
                curveToRelative(-0.51f, -0.51f, -1.37f, -0.33f, -1.64f, 0.34f)
                curveTo(4.2f, 9.96f, 4.0f, 10.96f, 4.0f, 12.0f)
                curveToRelative(0.0f, 4.42f, 3.58f, 8.0f, 8.0f, 8.0f)
                verticalLineToRelative(1.79f)
                curveToRelative(0.0f, 0.45f, 0.54f, 0.67f, 0.85f, 0.35f)
                lineToRelative(2.79f, -2.79f)
                curveToRelative(0.2f, -0.2f, 0.2f, -0.51f, 0.0f, -0.71f)
                lineToRelative(-2.79f, -2.79f)
                curveToRelative(-0.31f, -0.31f, -0.85f, -0.09f, -0.85f, 0.36f)
                lineTo(12.0f, 18.0f)
                close()
            }
        }
        return _sync!!
    }

private var _sync: ImageVector? = null

public val Icons.Rounded.Thermostat: ImageVector
    get() {
        if (_thermostat != null) {
            return _thermostat!!
        }
        _thermostat = materialIcon(name = "Rounded.Thermostat") {
            materialPath {
                moveTo(15.0f, 13.0f)
                lineTo(15.0f, 5.0f)
                curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
                reflectiveCurveTo(9.0f, 3.34f, 9.0f, 5.0f)
                verticalLineToRelative(8.0f)
                curveToRelative(-1.21f, 0.91f, -2.0f, 2.37f, -2.0f, 4.0f)
                curveToRelative(0.0f, 2.76f, 2.24f, 5.0f, 5.0f, 5.0f)
                reflectiveCurveToRelative(5.0f, -2.24f, 5.0f, -5.0f)
                curveToRelative(0.0f, -1.63f, -0.79f, -3.09f, -2.0f, -4.0f)
                close()
                moveTo(13.0f, 11.0f)
                horizontalLineToRelative(-2.0f)
                lineTo(11.0f, 5.0f)
                curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
                reflectiveCurveToRelative(1.0f, 0.45f, 1.0f, 1.0f)
                horizontalLineToRelative(-0.5f)
                curveToRelative(-0.28f, 0.0f, -0.5f, 0.22f, -0.5f, 0.5f)
                reflectiveCurveToRelative(0.22f, 0.5f, 0.5f, 0.5f)
                horizontalLineToRelative(0.5f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-0.5f)
                curveToRelative(-0.28f, 0.0f, -0.5f, 0.22f, -0.5f, 0.5f)
                reflectiveCurveToRelative(0.22f, 0.5f, 0.5f, 0.5f)
                horizontalLineToRelative(0.5f)
                verticalLineToRelative(2.0f)
                close()
            }
        }
        return _thermostat!!
    }

private var _thermostat: ImageVector? = null

public val Icons.Rounded.Timeline: ImageVector
    get() {
        if (_timeline != null) {
            return _timeline!!
        }
        _timeline = materialIcon(name = "Rounded.Timeline") {
            materialPath {
                moveTo(23.0f, 8.0f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                curveToRelative(-0.18f, 0.0f, -0.35f, -0.02f, -0.51f, -0.07f)
                lineToRelative(-3.56f, 3.55f)
                curveTo(16.98f, 13.64f, 17.0f, 13.82f, 17.0f, 14.0f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                reflectiveCurveToRelative(-2.0f, -0.9f, -2.0f, -2.0f)
                curveToRelative(0.0f, -0.18f, 0.02f, -0.36f, 0.07f, -0.52f)
                lineToRelative(-2.55f, -2.55f)
                curveTo(10.36f, 10.98f, 10.18f, 11.0f, 10.0f, 11.0f)
                curveToRelative(-0.18f, 0.0f, -0.36f, -0.02f, -0.52f, -0.07f)
                lineToRelative(-4.55f, 4.56f)
                curveTo(4.98f, 15.65f, 5.0f, 15.82f, 5.0f, 16.0f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                reflectiveCurveToRelative(-2.0f, -0.9f, -2.0f, -2.0f)
                reflectiveCurveToRelative(0.9f, -2.0f, 2.0f, -2.0f)
                curveToRelative(0.18f, 0.0f, 0.35f, 0.02f, 0.51f, 0.07f)
                lineToRelative(4.56f, -4.55f)
                curveTo(8.02f, 9.36f, 8.0f, 9.18f, 8.0f, 9.0f)
                curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
                reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
                curveToRelative(0.0f, 0.18f, -0.02f, 0.36f, -0.07f, 0.52f)
                lineToRelative(2.55f, 2.55f)
                curveTo(14.64f, 12.02f, 14.82f, 12.0f, 15.0f, 12.0f)
                curveToRelative(0.18f, 0.0f, 0.36f, 0.02f, 0.52f, 0.07f)
                lineToRelative(3.55f, -3.56f)
                curveTo(19.02f, 8.35f, 19.0f, 8.18f, 19.0f, 8.0f)
                curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
                reflectiveCurveTo(23.0f, 6.9f, 23.0f, 8.0f)
                close()
                moveTo(23.0f, 8.0f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                curveToRelative(-0.18f, 0.0f, -0.35f, -0.02f, -0.51f, -0.07f)
                lineToRelative(-3.56f, 3.55f)
                curveTo(16.98f, 13.64f, 17.0f, 13.82f, 17.0f, 14.0f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                reflectiveCurveToRelative(-2.0f, -0.9f, -2.0f, -2.0f)
                curveToRelative(0.0f, -0.18f, 0.02f, -0.36f, 0.07f, -0.52f)
                lineToRelative(-2.55f, -2.55f)
                curveTo(10.36f, 10.98f, 10.18f, 11.0f, 10.0f, 11.0f)
                curveToRelative(-0.18f, 0.0f, -0.36f, -0.02f, -0.52f, -0.07f)
                lineToRelative(-4.55f, 4.56f)
                curveTo(4.98f, 15.65f, 5.0f, 15.82f, 5.0f, 16.0f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                reflectiveCurveToRelative(-2.0f, -0.9f, -2.0f, -2.0f)
                reflectiveCurveToRelative(0.9f, -2.0f, 2.0f, -2.0f)
                curveToRelative(0.18f, 0.0f, 0.35f, 0.02f, 0.51f, 0.07f)
                lineToRelative(4.56f, -4.55f)
                curveTo(8.02f, 9.36f, 8.0f, 9.18f, 8.0f, 9.0f)
                curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
                reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
                curveToRelative(0.0f, 0.18f, -0.02f, 0.36f, -0.07f, 0.52f)
                lineToRelative(2.55f, 2.55f)
                curveTo(14.64f, 12.02f, 14.82f, 12.0f, 15.0f, 12.0f)
                curveToRelative(0.18f, 0.0f, 0.36f, 0.02f, 0.52f, 0.07f)
                lineToRelative(3.55f, -3.56f)
                curveTo(19.02f, 8.35f, 19.0f, 8.18f, 19.0f, 8.0f)
                curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
                reflectiveCurveTo(23.0f, 6.9f, 23.0f, 8.0f)
                close()
            }
        }
        return _timeline!!
    }

private var _timeline: ImageVector? = null

public val Icons.Rounded.Tune: ImageVector
    get() {
        if (_tune != null) {
            return _tune!!
        }
        _tune = materialIcon(name = "Rounded.Tune") {
            materialPath {
                moveTo(3.0f, 18.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(5.0f)
                verticalLineToRelative(-2.0f)
                lineTo(4.0f, 17.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                close()
                moveTo(3.0f, 6.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(9.0f)
                lineTo(13.0f, 5.0f)
                lineTo(4.0f, 5.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                close()
                moveTo(13.0f, 20.0f)
                verticalLineToRelative(-1.0f)
                horizontalLineToRelative(7.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineToRelative(-7.0f)
                verticalLineToRelative(-1.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                reflectiveCurveToRelative(-1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(4.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                reflectiveCurveToRelative(1.0f, -0.45f, 1.0f, -1.0f)
                close()
                moveTo(7.0f, 10.0f)
                verticalLineToRelative(1.0f)
                lineTo(4.0f, 11.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveToRelative(0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(1.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                reflectiveCurveToRelative(1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(-4.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                reflectiveCurveToRelative(-1.0f, 0.45f, -1.0f, 1.0f)
                close()
                moveTo(21.0f, 12.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineToRelative(-9.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(9.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                close()
                moveTo(16.0f, 9.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                lineTo(17.0f, 7.0f)
                horizontalLineToRelative(3.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineToRelative(-3.0f)
                lineTo(17.0f, 4.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                reflectiveCurveToRelative(-1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(4.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                close()
            }
        }
        return _tune!!
    }

private var _tune: ImageVector? = null

public val Icons.Rounded.VerifiedUser: ImageVector
    get() {
        if (_verifiedUser != null) {
            return _verifiedUser!!
        }
        _verifiedUser = materialIcon(name = "Rounded.VerifiedUser") {
            materialPath {
                moveTo(11.19f, 1.36f)
                lineToRelative(-7.0f, 3.11f)
                curveTo(3.47f, 4.79f, 3.0f, 5.51f, 3.0f, 6.3f)
                lineTo(3.0f, 11.0f)
                curveToRelative(0.0f, 5.55f, 3.84f, 10.74f, 9.0f, 12.0f)
                curveToRelative(5.16f, -1.26f, 9.0f, -6.45f, 9.0f, -12.0f)
                lineTo(21.0f, 6.3f)
                curveToRelative(0.0f, -0.79f, -0.47f, -1.51f, -1.19f, -1.83f)
                lineToRelative(-7.0f, -3.11f)
                curveToRelative(-0.51f, -0.23f, -1.11f, -0.23f, -1.62f, 0.0f)
                close()
                moveTo(9.29f, 16.29f)
                lineTo(6.7f, 13.7f)
                curveToRelative(-0.39f, -0.39f, -0.39f, -1.02f, 0.0f, -1.41f)
                curveToRelative(0.39f, -0.39f, 1.02f, -0.39f, 1.41f, 0.0f)
                lineTo(10.0f, 14.17f)
                lineToRelative(5.88f, -5.88f)
                curveToRelative(0.39f, -0.39f, 1.02f, -0.39f, 1.41f, 0.0f)
                curveToRelative(0.39f, 0.39f, 0.39f, 1.02f, 0.0f, 1.41f)
                lineToRelative(-6.59f, 6.59f)
                curveToRelative(-0.38f, 0.39f, -1.02f, 0.39f, -1.41f, 0.0f)
                close()
            }
        }
        return _verifiedUser!!
    }

private var _verifiedUser: ImageVector? = null

public val Icons.Rounded.ViewCarousel: ImageVector
    get() {
        if (_viewCarousel != null) {
            return _viewCarousel!!
        }
        _viewCarousel = materialIcon(name = "Rounded.ViewCarousel") {
            materialPath {
                moveTo(3.0f, 7.0f)
                horizontalLineToRelative(2.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                verticalLineToRelative(8.0f)
                curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
                horizontalLineTo(3.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                verticalLineTo(8.0f)
                curveTo(2.0f, 7.45f, 2.45f, 7.0f, 3.0f, 7.0f)
                close()
                moveTo(8.0f, 19.0f)
                horizontalLineToRelative(8.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineTo(6.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineTo(8.0f)
                curveTo(7.45f, 5.0f, 7.0f, 5.45f, 7.0f, 6.0f)
                verticalLineToRelative(12.0f)
                curveTo(7.0f, 18.55f, 7.45f, 19.0f, 8.0f, 19.0f)
                close()
                moveTo(19.0f, 7.0f)
                horizontalLineToRelative(2.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                verticalLineToRelative(8.0f)
                curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
                horizontalLineToRelative(-2.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                verticalLineTo(8.0f)
                curveTo(18.0f, 7.45f, 18.45f, 7.0f, 19.0f, 7.0f)
                close()
            }
        }
        return _viewCarousel!!
    }

private var _viewCarousel: ImageVector? = null
