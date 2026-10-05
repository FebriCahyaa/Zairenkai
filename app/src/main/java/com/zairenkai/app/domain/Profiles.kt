// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Built-in optimization profiles. Each profile is a set of tweak id -> value
 * pairs plus an optional input-boost intent. Values are applied best-effort;
 * tweaks a device lacks are skipped.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.domain

enum class ProfileId { GAME, DAILY, MEDIA, BATTERY, BALANCED, AUTO }

data class Profile(
    val id: ProfileId,
    val name: String,
    val tagline: String,
    val icon: String,              // material icon name key, resolved in UI
    val tweaks: Map<String, String>,
    val inputBoost: Boolean,
    val lite: Boolean = false,
)

object Profiles {
    val GAME = Profile(
        ProfileId.GAME, "Game", "Performa maksimal, latensi input terendah", "sports_esports",
        tweaks = mapOf(
            "cpu_governor" to "performance",
            "io_scheduler" to "none",
            "swappiness" to "60",
            "vfs_cache_pressure" to "80",
            "sched_migration_cost_ns" to "500000",
            "tcp_congestion_control" to "bbr",
            "gpu_governor" to "performance",
        ),
        inputBoost = true,
    )
    val DAILY = Profile(
        ProfileId.DAILY, "Harian", "Mulus untuk sosmed & aplikasi sehari-hari", "smartphone",
        tweaks = mapOf(
            "cpu_governor" to "schedutil",
            "io_scheduler" to "mq-deadline",
            "swappiness" to "100",
            "vfs_cache_pressure" to "120",
            "tcp_congestion_control" to "bbr",
            "gpu_governor" to "msm-adreno-tz",
        ),
        inputBoost = true,
    )
    val MEDIA = Profile(
        ProfileId.MEDIA, "Media", "Streaming lancar & efisien", "movie",
        tweaks = mapOf(
            "cpu_governor" to "schedutil",
            "io_scheduler" to "bfq",
            "swappiness" to "120",
            "dirty_ratio" to "40",
            "dirty_background_ratio" to "10",
        ),
        inputBoost = false,
    )
    val BATTERY = Profile(
        ProfileId.BATTERY, "Hemat Baterai", "Dingin & irit daya", "battery_saver",
        tweaks = mapOf(
            "cpu_governor" to "powersave",
            "io_scheduler" to "mq-deadline",
            "swappiness" to "160",
            "vfs_cache_pressure" to "200",
            "gpu_governor" to "powersave",
        ),
        inputBoost = false,
        lite = true,
    )
    val BALANCED = Profile(
        ProfileId.BALANCED, "Seimbang", "Default yang pas untuk semua", "balance",
        tweaks = mapOf(
            "cpu_governor" to "schedutil",
            "io_scheduler" to "mq-deadline",
            "swappiness" to "100",
            "vfs_cache_pressure" to "100",
        ),
        inputBoost = true,
    )
    val AUTO = Profile(
        ProfileId.AUTO, "Otomatis", "Menyesuaikan sendiri dengan kondisi perangkat", "auto_mode",
        tweaks = emptyMap(),
        inputBoost = true,
    )

    val all = listOf(GAME, DAILY, MEDIA, BATTERY, BALANCED, AUTO)
    fun byId(id: ProfileId) = all.first { it.id == id }

    /**
     * Auto mode: pick a concrete profile from live conditions.
     * charging + cool -> GAME; low battery or hot -> BATTERY; else DAILY.
     */
    fun resolveAuto(charging: Boolean, batteryPct: Int, hottestC: Float): Profile = when {
        batteryPct in 1..20 || hottestC >= 46f -> BATTERY
        charging && hottestC < 42f -> GAME
        else -> DAILY
    }
}
