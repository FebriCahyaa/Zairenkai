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

object ZairenkaiIdentity {
    const val PRODUCT_ID = "zairenkai"
    const val PRODUCT_NAME = "Zairenkai"
    const val APP_ID = "com.zairenkai.app"
    const val CORE_ID = "zairenkai.core"
    const val CORE_API = 1
}

enum class SubsystemId(val wireId: String, val displayName: String, val role: String) {
    ATLAS("zairenkai.atlas", "Zairenkai Atlas", "device intelligence"),
    SENTINEL("zairenkai.sentinel", "Zairenkai Sentinel", "safety and security"),
    THERMOGUARD("zairenkai.thermoguard", "Zairenkai ThermoGuard", "thermal safety"),
    ZPERFD("zairenkai.zperfd", "Zairenkai Runtime Performance Engine", "runtime orchestration"),
    ZKFC("zairenkai.zkfc", "Zairenkai Kernel Framework Controller", "kernel authority"),
}

enum class CapabilityId(val wireId: String, val title: String, val category: String) {
    ROOT("runtime.root", "Root authority", "Runtime"),
    ZKFC("runtime.zkfc", "ZKFC kernel authority", "Runtime"),
    ZPERFD("runtime.zperfd", "zperfd runtime engine", "Runtime"),
    THERMAL_TELEMETRY("thermal.telemetry", "Thermal telemetry", "Thermal"),
    THERMAL_TRIPS("thermal.trip_topology", "Runtime trip topology", "Thermal"),
    THERMAL_HEADROOM("thermal.headroom", "Thermal headroom", "Thermal"),
    CPU_CPUFREQ("cpu.cpufreq", "CPU frequency policy", "CPU"),
    CPU_UCLAMP("cpu.uclamp", "CPU uclamp", "CPU"),
    CPU_SCHEDTUNE("cpu.schedtune", "CPU schedtune", "CPU"),
    INPUT_BOOST("cpu.input_boost", "Input boost", "CPU"),
    GPU_DEVFREQ("gpu.devfreq", "GPU devfreq", "GPU"),
    MEMORY("memory.runtime", "Memory telemetry", "Memory"),
    ZRAM("memory.zram", "ZRAM", "Memory"),
    STORAGE("storage.runtime", "Storage topology", "Storage"),
    NETWORK("network.runtime", "Network controls", "Network"),
    PROPERTIES("system.properties", "Zairenkai property broker", "System"),
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
