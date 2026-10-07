// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
package com.zairenkai.app.core.capability

import com.zairenkai.app.core.identity.CapabilityId
import com.zairenkai.app.data.InfoResult
import com.zairenkai.app.data.ThermalStatus
import com.zairenkai.app.data.ZperfProbe
import com.zairenkai.app.data.ZperfStatus

/**
 * Capability evidence is intentionally tri-state. UNOBSERVED never means
 * unsupported: the runtime simply did not provide enough evidence yet.
 */
enum class CapabilityState { AVAILABLE, UNAVAILABLE, UNOBSERVED }

data class RuntimeCapability(
    val id: CapabilityId,
    val state: CapabilityState,
    val source: String,
    val detail: String,
) {
    val isAvailable: Boolean get() = state == CapabilityState.AVAILABLE
}

data class CapabilityMatrix(
    val capabilities: List<RuntimeCapability>,
) {
    operator fun get(id: CapabilityId): RuntimeCapability? = capabilities.firstOrNull { it.id == id }
    fun available(id: CapabilityId): Boolean = get(id)?.isAvailable == true
    fun byCategory(): Map<String, List<RuntimeCapability>> = capabilities.groupBy { it.id.category }
}

private fun observed(value: Boolean?, source: String, detail: String): RuntimeCapabilityStateValue = when (value) {
    true -> RuntimeCapabilityStateValue(CapabilityState.AVAILABLE, source, detail)
    false -> RuntimeCapabilityStateValue(CapabilityState.UNAVAILABLE, source, detail)
    null -> RuntimeCapabilityStateValue(CapabilityState.UNOBSERVED, source, detail)
}

private data class RuntimeCapabilityStateValue(
    val state: CapabilityState,
    val source: String,
    val detail: String,
)

fun buildCapabilityMatrix(
    root: Boolean,
    info: InfoResult?,
    probe: ZperfProbe?,
    status: ZperfStatus?,
    thermal: ThermalStatus?,
): CapabilityMatrix {
    val items = buildList {
        add(RuntimeCapability(CapabilityId.ROOT, if (root) CapabilityState.AVAILABLE else CapabilityState.UNAVAILABLE, "su", if (root) "UID 0 available" else "root unavailable"))
        add(RuntimeCapability(CapabilityId.ZKFC, observed(info?.ok, "zkfctl", info?.kernelRelease?.ifBlank { "kernel unavailable" } ?: "ZKFC not observed").state, "zkfctl", info?.kernelRelease?.ifBlank { "kernel unavailable" } ?: "ZKFC not observed"))
        add(RuntimeCapability(CapabilityId.ZPERFD, observed(status?.ok, "zperfd", if (status?.ok == true) "runtime engine reachable" else "runtime engine not observed").state, "zperfd", if (status?.ok == true) "runtime engine reachable" else "runtime engine not observed"))

        val thermalTelemetry = observed(thermal?.telemetryComplete, "zperfd/thermal", if (thermal == null) "thermal status not observed" else if (thermal.telemetryComplete) "runtime sensors complete" else "telemetry incomplete")
        add(RuntimeCapability(CapabilityId.THERMAL_TELEMETRY, thermalTelemetry.state, thermalTelemetry.source, thermalTelemetry.detail))
        val tripObserved = when {
            thermal == null -> CapabilityState.UNOBSERVED
            thermal.performanceTripMdeg != null && thermal.criticalTripMdeg != null -> CapabilityState.AVAILABLE
            else -> CapabilityState.UNAVAILABLE
        }
        add(RuntimeCapability(CapabilityId.THERMAL_TRIPS, tripObserved, "Linux thermal framework", thermal?.controlZone?.ifBlank { "no control zone" } ?: "no runtime trip topology"))
        val headroomObserved = if (thermal?.headroomPermille != null) CapabilityState.AVAILABLE else if (thermal == null) CapabilityState.UNOBSERVED else CapabilityState.UNAVAILABLE
        add(RuntimeCapability(CapabilityId.THERMAL_HEADROOM, headroomObserved, "zperfd thermal authority", thermal?.headroomPermille?.let { "$it‰" } ?: "unknown"))

        add(RuntimeCapability(CapabilityId.CPU_CPUFREQ, observed(probe?.policies?.isNotEmpty(), "runtime cpufreq", "${probe?.policies?.size ?: 0} policies").state, "runtime cpufreq", "${probe?.policies?.size ?: 0} policies"))
        add(RuntimeCapability(CapabilityId.CPU_UCLAMP, observed(probe?.boost?.uclamp, "runtime scheduler", if (probe?.boost?.uclamp == true) "available" else "not exposed").state, "runtime scheduler", if (probe?.boost?.uclamp == true) "available" else "not exposed"))
        add(RuntimeCapability(CapabilityId.CPU_SCHEDTUNE, observed(probe?.boost?.schedtune, "runtime scheduler", if (probe?.boost?.schedtune == true) "available" else "not exposed").state, "runtime scheduler", if (probe?.boost?.schedtune == true) "available" else "not exposed"))
        add(RuntimeCapability(CapabilityId.INPUT_BOOST, observed(probe?.boost?.cpuBoost, "runtime input boost", if (probe?.boost?.cpuBoost == true) "available" else "not exposed").state, "runtime input boost", if (probe?.boost?.cpuBoost == true) "available" else "not exposed"))
        add(RuntimeCapability(CapabilityId.GPU_DEVFREQ, observed(probe?.gpu != null, "runtime devfreq", probe?.gpu?.let { "${it.kind} · ${it.opps} OPP" } ?: "not exposed").state, "runtime devfreq", probe?.gpu?.let { "${it.kind} · ${it.opps} OPP" } ?: "not exposed"))

        // These primitives require their dedicated inventory observation path.
        // Until that path is observed, expose UNOBSERVED rather than guessing.
        add(RuntimeCapability(CapabilityId.MEMORY, CapabilityState.UNOBSERVED, "runtime inventory", "memory inventory not queried"))
        add(RuntimeCapability(CapabilityId.ZRAM, CapabilityState.UNOBSERVED, "runtime inventory", "ZRAM inventory not queried"))
        add(RuntimeCapability(CapabilityId.STORAGE, CapabilityState.UNOBSERVED, "runtime inventory", "storage inventory not queried"))
        add(RuntimeCapability(CapabilityId.NETWORK, CapabilityState.UNOBSERVED, "runtime inventory", "network inventory not queried"))
        add(RuntimeCapability(CapabilityId.PROPERTIES, CapabilityState.UNOBSERVED, "Zairenkai broker", "broker capability not queried"))
    }
    return CapabilityMatrix(items)
}
