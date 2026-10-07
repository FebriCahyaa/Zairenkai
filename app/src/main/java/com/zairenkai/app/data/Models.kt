// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Kotlin models mirroring zkfctl JSON output.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiVersion(
    val major: Int = 0,
    val minor: Int = 0,
    val patch: Int = 0,
    val outdated: Boolean = false,
)

@Serializable
data class InfoResult(
    val ok: Boolean = false,
    val error: String? = null,
    @SerialName("zkfctl_version") val zkfctlVersion: String = "",
    val api: ApiVersion = ApiVersion(),
    val arch: String = "unknown",
    @SerialName("hook_mode") val hookMode: String = "none",
    @SerialName("kernel_type") val kernelType: String = "",
    @SerialName("kernel_release") val kernelRelease: String = "",
    @SerialName("build_id") val buildId: String = "",
    @SerialName("license_state") val licenseState: String = "missing",
    val features: Long = 0,
    @SerialName("features_enabled") val featuresEnabled: Long = 0,
)

@Serializable
data class TokenInfo(
    val licensee: String = "",
    @SerialName("license_id") val licenseId: Long = 0,
    @SerialName("expires_at") val expiresAt: Long = 0,
    val features: Long = 0,
)

@Serializable
data class LicenseResult(
    val ok: Boolean = false,
    val error: String? = null,
    val state: String = "missing",
    @SerialName("has_token") val hasToken: Boolean = false,
    @SerialName("owner_key_provisioned") val ownerKeyProvisioned: Boolean = false,
    @SerialName("clock_trusted") val clockTrusted: Boolean = false,
    @SerialName("owner_key_fingerprint") val ownerKeyFingerprint: String = "",
    @SerialName("kernel_binding") val kernelBinding: String = "",
    val token: TokenInfo? = null,
)

@Serializable
data class SysSecurity(
    val flags: Long = 0,
    @SerialName("taint_mask") val taintMask: Long = 0,
    @SerialName("config_digest") val configDigest: String = "",
    val selinux: Boolean = false,
    @SerialName("module_sig_enforced") val moduleSigEnforced: Boolean = false,
    val lockdown: Boolean = false,
)

@Serializable
data class UserSecurity(
    val uid: Int = -1, val euid: Int = -1, val gid: Int = -1, val egid: Int = -1,
    val caps: Long = 0,
    @SerialName("cap_sys_admin") val capSysAdmin: Boolean = false,
    @SerialName("session_id") val sessionId: Long = 0,
)

@Serializable
data class DevSecurity(
    val model: String = "", val compatible: String = "",
    @SerialName("licensee_tag") val licenseeTag: String = "",
    @SerialName("cpu_count") val cpuCount: Int = 0,
    @SerialName("kernel_binding") val kernelBinding: String = "",
)

@Serializable
data class SecurityResult(
    val ok: Boolean = false,
    val error: String? = null,
    val system: SysSecurity? = null,
    val user: UserSecurity? = null,
    val device: DevSecurity? = null,
)

@Serializable
data class Tweak(
    val id: String,
    val title: String = id,
    val category: String = "other",
    val available: Boolean = false,
    @SerialName("lite_hidden") val liteHidden: Boolean = false,
    val min: Long? = null,
    val max: Long? = null,
    val value: String? = null,
)

@Serializable
data class TweakList(
    val ok: Boolean = false,
    val error: String? = null,
    @SerialName("lite_mode") val liteMode: Boolean = false,
    val tweaks: List<Tweak> = emptyList(),
)

@Serializable
data class CpuSample(
    val cpu: Int = 0,
    val online: Boolean = true,
    @SerialName("load_pct") val loadPct: Int = -1,
    @SerialName("cur_khz") val curKhz: Long = -1,
)

@Serializable
data class GpuSample(
    @SerialName("busy_pct") val busyPct: Int = -1,
    @SerialName("cur_freq") val curFreq: Long = -1,
)

@Serializable
data class ThermalSample(
    val zone: String = "",
    val type: String = "",
    @SerialName("temp_mdeg") val tempMdeg: Long = 0,
) {
    val celsius: Float get() = tempMdeg / 1000f
}

@Serializable
data class ZkfcPerf(
    @SerialName("input_boost_active") val inputBoostActive: Boolean = false,
    @SerialName("boosted_tasks") val boostedTasks: Int = 0,
    @SerialName("thermal_tripped") val thermalTripped: Boolean = false,
)

@Serializable
data class MonitorResult(
    val ok: Boolean = false,
    val error: String? = null,
    @SerialName("interval_ms") val intervalMs: Int = 0,
    val cpu: List<CpuSample> = emptyList(),
    val gpu: GpuSample = GpuSample(),
    val thermal: List<ThermalSample> = emptyList(),
    val zkfc: ZkfcPerf? = null,
)

@Serializable
data class LogRecord(
    val seq: Long = 0,
    @SerialName("ts_ns") val tsNs: Long = 0,
    val level: String = "info",
    val pid: Int = 0,
    val uid: Int = 0,
    val msg: String = "",
)

@Serializable
data class LogResult(
    val ok: Boolean = false,
    val error: String? = null,
    @SerialName("next_seq") val nextSeq: Long = 0,
    val dropped: Long = 0,
    val records: List<LogRecord> = emptyList(),
)

@Serializable
data class SulogRecord(
    val seq: Long = 0,
    @SerialName("ts_ns") val tsNs: Long = 0,
    val event: String = "",
    val uid: Int = 0,
    val pid: Int = 0,
    val result: Int = 0,
    val comm: String = "",
    val path: String = "",
)

@Serializable
data class SulogResult(
    val ok: Boolean = false,
    val error: String? = null,
    @SerialName("next_seq") val nextSeq: Long = 0,
    val records: List<SulogRecord> = emptyList(),
)

@Serializable
data class PolicyEntry(
    val type: String = "",
    val id: Int = 0,
    val caps: Long = 0,
    val deny: Boolean = false,
    val builtin: Boolean = false,
)

@Serializable
data class PolicyResult(
    val ok: Boolean = false,
    val error: String? = null,
    val entries: List<PolicyEntry> = emptyList(),
)

@Serializable
data class SafeStatus(
    val ok: Boolean = false,
    @SerialName("safe_mode") val safeMode: Boolean = false,
    val pending: Long = 0,
)

@Serializable
data class BoostStatus(
    val ok: Boolean = false,
    val error: String? = null,
    @SerialName("input_boost_enabled") val inputBoostEnabled: Boolean = false,
    @SerialName("input_boost_active") val inputBoostActive: Boolean = false,
    @SerialName("boosted_tasks") val boostedTasks: Int = 0,
    @SerialName("cpufreq_requests") val cpufreqRequests: Int = 0,
    @SerialName("thermal_tripped") val thermalTripped: Boolean = false,
    @SerialName("thermal_last_mdeg") val thermalLastMdeg: Int = 0,
)

