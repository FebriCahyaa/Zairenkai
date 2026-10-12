// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/**
 * Canonical Zairenkai identity namespace.
 *
 * UI, runtime and native layers must reference these IDs rather than inventing
 * product names or capability strings locally. The IDs intentionally remain
 * stable while implementation versions can evolve independently.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.core.identity

import androidx.annotation.StringRes
import com.zairenkai.app.R

object ZairenkaiIdentity {
    const val PRODUCT_ID = "zairenkai"
    const val PRODUCT_NAME = "Zairenkai"
    const val APP_ID = "com.zairenkai.app"
    const val CORE_ID = "zairenkai.core"
    const val CORE_API = 1
}

/** [displayName] is a product name (not translated); [roleRes] is the localized role shown in the UI. */
enum class SubsystemId(val wireId: String, val displayName: String, @StringRes val roleRes: Int) {
    ATLAS("zairenkai.atlas", "Zairenkai Atlas", R.string.subsystem_role_atlas),
    SENTINEL("zairenkai.sentinel", "Zairenkai Sentinel", R.string.subsystem_role_sentinel),
    THERMOGUARD("zairenkai.thermoguard", "Zairenkai ThermoGuard", R.string.subsystem_role_thermoguard),
    ZPERFD("zairenkai.zperfd", "Zairenkai Runtime Performance Engine", R.string.subsystem_role_zperfd),
    ZKFC("zairenkai.zkfc", "Zairenkai Kernel Framework Controller", R.string.subsystem_role_zkfc),
}

/**
 * [category] is a stable grouping key (not shown); [titleRes] and
 * [categoryRes] are the localized labels shown in the UI.
 */
enum class CapabilityId(
    val wireId: String,
    @StringRes val titleRes: Int,
    val category: String,
    @StringRes val categoryRes: Int,
) {
    ROOT("runtime.root", R.string.capability_root, "Runtime", R.string.capability_category_runtime),
    ZKFC("runtime.zkfc", R.string.capability_zkfc, "Runtime", R.string.capability_category_runtime),
    ZPERFD("runtime.zperfd", R.string.capability_zperfd, "Runtime", R.string.capability_category_runtime),
    THERMAL_TELEMETRY("thermal.telemetry", R.string.capability_thermal_telemetry, "Thermal", R.string.capability_category_thermal),
    THERMAL_TRIPS("thermal.trip_topology", R.string.capability_thermal_trips, "Thermal", R.string.capability_category_thermal),
    THERMAL_HEADROOM("thermal.headroom", R.string.capability_thermal_headroom, "Thermal", R.string.capability_category_thermal),
    CPU_CPUFREQ("cpu.cpufreq", R.string.capability_cpu_cpufreq, "CPU", R.string.capability_category_cpu),
    CPU_UCLAMP("cpu.uclamp", R.string.capability_cpu_uclamp, "CPU", R.string.capability_category_cpu),
    CPU_SCHEDTUNE("cpu.schedtune", R.string.capability_cpu_schedtune, "CPU", R.string.capability_category_cpu),
    INPUT_BOOST("cpu.input_boost", R.string.capability_input_boost, "CPU", R.string.capability_category_cpu),
    GPU_DEVFREQ("gpu.devfreq", R.string.capability_gpu_devfreq, "GPU", R.string.capability_category_gpu),
    MEMORY("memory.runtime", R.string.capability_memory, "Memory", R.string.capability_category_memory),
    ZRAM("memory.zram", R.string.capability_zram, "Memory", R.string.capability_category_memory),
    STORAGE("storage.runtime", R.string.capability_storage, "Storage", R.string.capability_category_storage),
    NETWORK("network.runtime", R.string.capability_network, "Network", R.string.capability_category_network),
    PROPERTIES("system.properties", R.string.capability_properties, "System", R.string.capability_category_system),
}

enum class OperationId(val wireName: String) {
    READ_DEVICE("read.device"),
    READ_THERMAL("read.thermal"),
    READ_PERFORMANCE("read.performance"),
    READ_SECURITY("read.security"),
    READ_STORAGE("read.storage"),
    READ_NETWORK("read.network"),
    READ_MEMORY("read.memory"),
    TUNE_CPU("tune.cpu"),
    TUNE_GPU("tune.gpu"),
    TUNE_MEMORY("tune.memory"),
    TUNE_IO("tune.io"),
    TUNE_POWER("tune.power"),
    TUNE_THERMAL("tune.thermal"),
    TUNE_STORAGE("tune.storage"),
    TUNE_NETWORK("tune.network"),
    TUNE_ZRAM("tune.zram"),
    TUNE_PROPERTIES("tune.properties"),
    CONTROL_RESET("control.reset"),
}
