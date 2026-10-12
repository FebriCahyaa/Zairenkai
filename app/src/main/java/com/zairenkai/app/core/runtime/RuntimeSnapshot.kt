// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
package com.zairenkai.app.core.runtime

import com.zairenkai.app.R
import com.zairenkai.app.core.capability.CapabilityMatrix
import com.zairenkai.app.core.text.UiText
import com.zairenkai.app.core.text.uiText
import com.zairenkai.app.data.InfoResult
import com.zairenkai.app.data.LicenseResult
import com.zairenkai.app.data.SecurityResult
import com.zairenkai.app.data.ThermalStatus
import com.zairenkai.app.data.ZperfProbe
import com.zairenkai.app.data.ZperfStatus

/** One coherent observation boundary used by the application layer. */
data class RuntimeSnapshot(
    val observedAtEpochMs: Long,
    val rootAvailable: Boolean,
    val info: InfoResult?,
    val license: LicenseResult?,
    val security: SecurityResult?,
    val probe: ZperfProbe?,
    val status: ZperfStatus?,
    val thermal: ThermalStatus?,
    val capabilities: CapabilityMatrix,
)

val RuntimeSnapshot.kernelLabel: UiText
    get() = listOf(info?.kernelType, info?.kernelRelease)
        .filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
        .takeIf { it.isNotBlank() }?.let { UiText.Raw(it) } ?: uiText(R.string.runtime_kernel_unidentified)

val RuntimeSnapshot.platformLabel: UiText
    get() = listOf(security?.device?.model, security?.device?.compatible)
        .filterNotNull().filter { it.isNotBlank() }.firstOrNull()?.let { UiText.Raw(it) }
        ?: uiText(R.string.runtime_platform_unidentified)
