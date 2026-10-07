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

@Serializable
data class ThermalStatus(
    val band: String = "Unknown",
    @SerialName("boost_permille") val boostPermille: Int = 0,
    @SerialName("max_perf_cap_pct") val maxPerfCapPct: Int? = null,
    @SerialName("hottest_mdeg") val hottestMdeg: Int? = null,
    @SerialName("control_temp_mdeg") val controlTempMdeg: Int? = null,
    @SerialName("performance_trip_mdeg") val performanceTripMdeg: Int? = null,
    @SerialName("critical_trip_mdeg") val criticalTripMdeg: Int? = null,
    @SerialName("control_zone") val controlZone: String? = null,
    @SerialName("release_mdeg") val releaseMdeg: Int? = null,
    @SerialName("headroom_mdeg") val headroomMdeg: Int? = null,
    @SerialName("headroom_permille") val headroomPermille: Int? = null,
    @SerialName("critical_reached") val criticalReached: Boolean = false,
    @SerialName("telemetry_complete") val telemetryComplete: Boolean = false,
    @SerialName("battery_pct") val batteryPct: Int? = null,
    @SerialName("external_power") val externalPower: Boolean = false,
    val reason: String = "",
) {
    val temperatureCelsius: Float? get() = hottestMdeg?.div(1000f)
    val headroomFraction: Float? get() = headroomPermille?.coerceIn(0, 1000)?.div(1000f)
}
