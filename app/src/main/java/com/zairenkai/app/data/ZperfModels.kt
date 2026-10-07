// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Models mirroring `zperfd probe --json`.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ZperfPolicy(
    val name: String = "",
    @SerialName("min_hw") val minHw: Long = 0,
    @SerialName("max_hw") val maxHw: Long = 0,
    val opps: Int = 0,
    @SerialName("min_opp") val minOpp: Long = 0,
    @SerialName("max_opp") val maxOpp: Long = 0,
)

@Serializable
data class ZperfGpu(
    val kind: String = "",
    val opps: Int = 0,
    val min: Long = 0,
    val max: Long = 0,
)

@Serializable
data class ZperfBoost(
    val uclamp: Boolean = false,
    val schedtune: Boolean = false,
    @SerialName("cpu_boost") val cpuBoost: Boolean = false,
)

@Serializable
data class ZperfProbe(
    val flavor: String = "",
    val gki: Boolean = false,
    val release: String = "",
    @SerialName("cgroup_v2") val cgroupV2: Boolean = false,
    @SerialName("has_msm_perf") val hasMsmPerf: Boolean = false,
    val boost: ZperfBoost = ZperfBoost(),
    val policies: List<ZperfPolicy> = emptyList(),
    val gpu: ZperfGpu? = null,
)

@Serializable
data class ZperfStatus(
    val ok: Boolean = false,
    val desired: String = "balance",
    val effective: String = "balance",
    val auto: Boolean = false,
    @SerialName("pending_transaction") val pendingTransaction: Boolean = false,
    @SerialName("safe_mode") val safeMode: Boolean = false,
)

@Serializable
data class CoreManifest(
    val id: String = "",
    val product: String = "",
    @SerialName("core_api") val coreApi: Int = 0,
    val architectures: List<String> = emptyList(),
    @SerialName("kernel_models") val kernelModels: List<String> = emptyList(),
    @SerialName("kernel_generations") val kernelGenerations: List<String> = emptyList(),
)

@Serializable
data class CorePermissionsResult(
    @SerialName("core_id") val coreId: String = "",
    val permissions: List<CorePermissionEntry> = emptyList(),
)

@Serializable
data class CorePermissionEntry(
    val operation: String = "",
    val capability: String = "",
    val risk: Int = 0,
    val decision: String = "",
)
