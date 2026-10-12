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

/**
 * Result line of `zperfd tweak set`. zperfd prints one JSON object on stdout
 * for both outcomes; [kind] classifies a failure (framework_unavailable,
 * framework_incompatible, license, sentinel, unauthorized, rejected,
 * not_observed, rollback_failed, state, lite, unknown, usage). The client adds
 * engine_unavailable, timeout and protocol for failures zperfd never reported.
 */
@Serializable
data class TweakWriteResult(
    val ok: Boolean = false,
    val id: String = "",
    val value: String? = null,
    val targets: Int = 0,
    val kind: String? = null,
    val error: String? = null,
)

@Serializable
data class ZperfStatus(
    val ok: Boolean = false,
    val desired: String = "balance",
    val effective: String = "balance",
    val auto: Boolean = false,
    /** True when `desired` is a persisted user choice, false when it is the engine default. */
    val saved: Boolean = false,
    /** Why the engine holds the stock baseline: safe_mode, stock or revalidation_required. */
    val hold: String? = null,
    @SerialName("revalidation_required") val revalidationRequired: Boolean = false,
    @SerialName("pending_transaction") val pendingTransaction: Boolean = false,
    @SerialName("safe_mode") val safeMode: Boolean = false,
)

/** One mode of the device profile resolved by zperfd (`zperfd --json modes`). */
@Serializable
data class ModeCpu(
    @SerialName("min_freq") val minFreq: String? = null,
    @SerialName("max_freq") val maxFreq: String? = null,
    @SerialName("max_perf_pct") val maxPerfPct: Int? = null,
    val governors: List<String> = emptyList(),
    @SerialName("uclamp_min_pct") val uclampMinPct: Int? = null,
    @SerialName("input_boost_pct") val inputBoostPct: Int? = null,
    @SerialName("input_boost_ms") val inputBoostMs: Int? = null,
)

@Serializable
data class ModeGpu(
    @SerialName("min_freq") val minFreq: String? = null,
    @SerialName("max_freq") val maxFreq: String? = null,
    @SerialName("max_perf_pct") val maxPerfPct: Int? = null,
    val governors: List<String> = emptyList(),
)

@Serializable
data class ModeMem(
    val swappiness: Int? = null,
    @SerialName("vfs_cache_pressure") val vfsCachePressure: Int? = null,
)

@Serializable
data class ModeIo(
    val schedulers: List<String> = emptyList(),
    @SerialName("read_ahead_kb") val readAheadKb: Int? = null,
)

@Serializable
data class ModeSummary(
    val id: String = "",
    /** A user-defined mode (`custom-*`) derived from [base]; see CustomProfiles.kt. */
    val custom: Boolean = false,
    val label: String? = null,
    val base: String? = null,
    val cpu: ModeCpu = ModeCpu(),
    val gpu: ModeGpu = ModeGpu(),
    val mem: ModeMem = ModeMem(),
    val io: ModeIo = ModeIo(),
)

@Serializable
data class ModesResult(
    val ok: Boolean = false,
    val profile: String = "",
    val default: String = "",
    val modes: List<ModeSummary> = emptyList(),
)

/**
 * Outcome of `zperfd --json set <mode>`. On success every list item was
 * written: [applied] read back as requested, [constrained] reads back a
 * kernel-adjusted value (QoS/thermal), [unverified] cannot be compared.
 * [skipped] controls do not exist on this device. On failure [kind]
 * classifies why and [failed] names the writes that caused the rollback.
 */
@Serializable
data class ModeApplyResult(
    val ok: Boolean = false,
    val kind: String? = null,
    val error: String? = null,
    val applied: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val constrained: List<String> = emptyList(),
    val unverified: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
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
