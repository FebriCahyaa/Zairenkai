// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Battery / power sampler. Reads instantaneous voltage, current, power, temp,
 * level and remaining energy from the platform BatteryManager + the sticky
 * ACTION_BATTERY_CHANGED intent. No root needed.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.abs

data class PowerReading(
    val levelPct: Int = -1,
    val voltageV: Float = 0f,
    val currentMa: Float = 0f,      // signed as the device reports it
    val powerW: Float = 0f,         // abs(V * I)
    val charging: Boolean = false,
    val tempC: Float = 0f,
    val capacityWh: Float = 0f,     // remaining energy, best effort
)

class PowerSampler(context: Context) {
    private val app = context.applicationContext
    private val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    fun read(): PowerReading {
        val sticky = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val levelPct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val tempC = (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val voltageV = (sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000f

        // current_now is reported in µA on almost all devices (some in mA — we
        // only use magnitude so a scale error shows up as an obviously wrong W).
        val currentUa = runCatching { bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }
            .getOrDefault(0L)
        val currentMa = currentUa / 1000f
        val powerW = abs(voltageV * (currentMa / 1000f))

        // Remaining energy: prefer ENERGY_COUNTER (nWh); else CHARGE_COUNTER (µAh) × V.
        val energyNwh = runCatching { bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER) }
            .getOrDefault(Long.MIN_VALUE)
        val capacityWh = if (energyNwh != Long.MIN_VALUE && energyNwh > 0) {
            energyNwh / 1_000_000_000f
        } else {
            val chargeUah = runCatching { bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) }
                .getOrDefault(0L)
            if (chargeUah > 0 && voltageV > 0) (chargeUah / 1_000_000f) * voltageV else 0f
        }
        return PowerReading(levelPct, voltageV, currentMa, powerW, charging, tempC, capacityWh)
    }
}
