// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Where UI tests write their screenshots (build/outputs/roborazzi, set by the
 * Gradle test task), so they never land in the source tree.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app

import java.io.File

fun shotPath(name: String): String {
    val dir = File(System.getProperty("roborazzi.output.dir") ?: "build/outputs/roborazzi")
    dir.mkdirs()
    return File(dir, name).path
}
