// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Zairenkai Core semantic authority model.
 * This is a client-side representation only; the kernel remains the hard
 * permission boundary and zperfd remains the runtime policy authority.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.core.authority

enum class CorePermission(val wireName: String) {
    READ_DEVICE("read.device"),
    READ_KERNEL("read.kernel"),
    READ_THERMAL("read.thermal"),
    READ_PERFORMANCE("read.performance"),
    READ_LOGS("read.logs"),
    TUNE_CPU("tune.cpu"),
    TUNE_GPU("tune.gpu"),
    TUNE_MEMORY("tune.memory"),
    TUNE_IO("tune.io"),
    TUNE_POWER("tune.power"),
    TUNE_THERMAL("tune.thermal"),
    MANAGE_LICENSE("security.license"),
    MANAGE_POLICY("security.policy"),
    MANAGE_HOOKS("control.hooks"),
    RESET_RUNTIME("control.reset"),
    EXPERIMENTAL("experimental"),
}

@JvmInline
value class CorePermissionSet(private val values: Set<CorePermission>) {
    operator fun contains(permission: CorePermission): Boolean = permission in values
    fun asWireNames(): List<String> = values.map(CorePermission::wireName).sorted()
    companion object {
        fun of(vararg permissions: CorePermission) = CorePermissionSet(permissions.toSet())
        fun readOnly() = of(
            CorePermission.READ_DEVICE,
            CorePermission.READ_KERNEL,
            CorePermission.READ_THERMAL,
            CorePermission.READ_PERFORMANCE,
        )
    }
}

fun CorePermission.requiredForTweak(id: String): CorePermission {
    val key = id.lowercase()
    return when {
        "gpu" in key -> CorePermission.TUNE_GPU
        "zram" in key || "swappiness" in key || "dirty" in key || "vfs" in key || "mem" in key -> CorePermission.TUNE_MEMORY
        "sched" in key || "io" in key || "tcp" in key -> CorePermission.TUNE_IO
        "charge" in key || "battery" in key -> CorePermission.TUNE_POWER
        "thermal" in key || "kcal" in key || "color" in key -> CorePermission.TUNE_THERMAL
        else -> CorePermission.TUNE_CPU
    }
}
