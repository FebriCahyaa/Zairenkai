// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Profiles are the user-facing names of zperfd modes, one to one. What a
 * profile actually does is defined by the device profile zperfd resolves
 * (`zperfd --json modes`) and is constrained at apply time by the live OPP
 * table and the thermal envelope; nothing here duplicates those values.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.domain

import androidx.annotation.StringRes
import com.zairenkai.app.R

enum class ProfileId {
    BATTERY, BALANCED, PERFORMANCE, GAME, AUTO;

    companion object {
        /**
         * Parse a persisted id. DAILY and MEDIA were earlier names that
         * always applied the engine's `balance` mode, so they migrate to
         * BALANCED instead of silently clearing the user's choice.
         */
        fun fromStored(name: String?): ProfileId? = when (name) {
            null -> null
            "DAILY", "MEDIA" -> BALANCED
            else -> entries.firstOrNull { it.name == name }
        }
    }
}

data class Profile(
    val id: ProfileId,
    @StringRes val nameRes: Int,
    @StringRes val taglineRes: Int,
    val icon: String,              // material icon name key, resolved in UI
    /** The zperfd mode this profile selects. */
    val mode: String,
)

object Profiles {
    val BATTERY = Profile(
        ProfileId.BATTERY, R.string.profile_battery_name, R.string.profile_battery_tagline, "battery_saver",
        mode = "powersave",
    )
    val BALANCED = Profile(
        ProfileId.BALANCED, R.string.profile_balanced_name, R.string.profile_balanced_tagline, "balance",
        mode = "balance",
    )
    val PERFORMANCE = Profile(
        ProfileId.PERFORMANCE, R.string.profile_performance_name, R.string.profile_performance_tagline, "speed",
        mode = "performance",
    )
    val GAME = Profile(
        ProfileId.GAME, R.string.profile_game_name, R.string.profile_game_tagline, "sports_esports",
        mode = "fast",
    )
    val AUTO = Profile(
        ProfileId.AUTO, R.string.profile_auto_name, R.string.profile_auto_tagline, "auto_mode",
        mode = "auto",
    )

    val all = listOf(BATTERY, BALANCED, PERFORMANCE, GAME, AUTO)
    fun byId(id: ProfileId) = all.first { it.id == id }

    /** The profile for an engine mode name, or null for modes with no profile (e.g. "stock"). */
    fun byMode(mode: String?): Profile? = all.firstOrNull { it.mode == mode }
}
