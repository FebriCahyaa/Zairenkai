// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Battery state for the monitor: Android's BatteryManager (no root) merged
 * with zperfd's read-only power-supply snapshot when root is available.
 * The math (units, signs, health) is pure and unit tested.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.power

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.zairenkai.app.data.BatteryReport
import kotlin.math.abs

enum class ChargeStatus { CHARGING, DISCHARGING, FULL, NOT_CHARGING, UNKNOWN }

enum class PlugType { NONE, AC, USB, WIRELESS, DOCK }

enum class BatteryHealth { GOOD, OVERHEAT, DEAD, OVER_VOLTAGE, COLD, FAILURE, UNKNOWN }

/** What Android reports without root. Null fields were not reported. */
data class AndroidBattery(
    val levelPct: Int? = null,
    val status: ChargeStatus = ChargeStatus.UNKNOWN,
    val plug: PlugType = PlugType.NONE,
    val health: BatteryHealth = BatteryHealth.UNKNOWN,
    val technology: String? = null,
    val tempC: Float? = null,
    val voltageV: Float? = null,
    /** Raw BATTERY_PROPERTY_CURRENT_NOW (µA on most devices, mA on some). */
    val currentRaw: Long? = null,
    val chargeCounterUah: Long? = null,
    val cycleCount: Int? = null,
    val chargeTimeRemainingMs: Long? = null,
)

/** One merged reading, in display units; + current means charging. */
data class BatteryReading(
    val tMs: Long,
    val levelPct: Int?,
    val status: ChargeStatus,
    val plug: PlugType,
    val health: BatteryHealth,
    val technology: String?,
    val tempC: Float?,
    val voltageV: Float?,
    val currentMa: Float?,
    val powerW: Float?,
    val cycleCount: Int?,
    /** Full-charge capacity as a share of design capacity (battery wear). */
    val healthPct: Float?,
    val fullMah: Int?,
    val designMah: Int?,
    val remainingMah: Int?,
    val timeToFullMin: Int?,
    val chargeType: String?,
    /** Charger type as the kernel names it (USB_PD, USB_DCP, Wireless...). */
    val chargerType: String?,
    val chargerVoltageV: Float?,
    val chargerCurrentA: Float?,
    val chargerPowerW: Float?,
)

object BatteryMath {
    /**
     * mA with + = into the battery. Most devices report µA, some mA: values
     * at or above 20 000 are taken as µA (no phone draws 20 A). The sign
     * convention also varies, so it is aligned with the charging state.
     */
    fun currentMa(raw: Long?, status: ChargeStatus): Float? {
        if (raw == null || raw == Long.MIN_VALUE || raw == 0L) return if (raw == 0L) 0f else null
        val ma = if (abs(raw) >= 20_000) raw / 1000f else raw.toFloat()
        return when (status) {
            ChargeStatus.CHARGING -> abs(ma)
            ChargeStatus.DISCHARGING, ChargeStatus.NOT_CHARGING -> -abs(ma)
            else -> ma
        }
    }

    /** Full-charge capacity as % of design; null when either is missing or implausible. */
    fun healthPct(fullUah: Long?, designUah: Long?): Float? {
        if (fullUah == null || designUah == null || fullUah <= 0 || designUah <= 0) return null
        val pct = fullUah * 100f / designUah
        return pct.takeIf { it in 30f..120f }
    }

    fun powerW(voltageV: Float?, currentMa: Float?): Float? =
        if (voltageV == null || currentMa == null) null else voltageV * currentMa / 1000f

    fun status(s: String?): ChargeStatus = when (s?.lowercase()) {
        "charging" -> ChargeStatus.CHARGING
        "discharging" -> ChargeStatus.DISCHARGING
        "full" -> ChargeStatus.FULL
        "not charging" -> ChargeStatus.NOT_CHARGING
        else -> ChargeStatus.UNKNOWN
    }

    /** Merge Android's reading with zperfd's sysfs snapshot (which wins for what only sysfs has). */
    fun merge(tMs: Long, a: AndroidBattery, z: BatteryReport?): BatteryReading {
        val b = z?.battery
        val status = if (a.status != ChargeStatus.UNKNOWN) a.status else status(b?.status)
        val voltage = a.voltageV?.takeIf { it > 0f } ?: b?.voltageNowUv?.let { it / 1_000_000f }
        val current = currentMa(a.currentRaw, status) ?: currentMa(b?.currentNowUa, status)
        val charger = z?.chargers?.firstOrNull { it.online }
        val chargerV = charger?.voltageNowUv?.takeIf { it > 0 }?.let { it / 1_000_000f }
        val chargerA = charger?.currentNowUa?.takeIf { it > 0 }?.let { it / 1_000_000f }
        val fullUah = b?.chargeFullUah
        val designUah = b?.chargeFullDesignUah
        val remainingUah = a.chargeCounterUah?.takeIf { it > 0 } ?: b?.chargeCounterUah
        val ttf = a.chargeTimeRemainingMs?.takeIf { it > 0 }?.let { (it / 60_000).toInt() }
            ?: b?.timeToFullS?.takeIf { it > 0 }?.let { (it / 60).toInt() }
        return BatteryReading(
            tMs = tMs,
            levelPct = a.levelPct ?: b?.capacity,
            status = status,
            plug = a.plug,
            health = a.health,
            technology = a.technology ?: b?.technology,
            tempC = a.tempC ?: b?.tempDecic?.let { it / 10f },
            voltageV = voltage,
            currentMa = current,
            powerW = powerW(voltage, current),
            cycleCount = a.cycleCount ?: b?.cycleCount,
            healthPct = healthPct(fullUah, designUah),
            fullMah = fullUah?.takeIf { it > 0 }?.let { (it / 1000).toInt() },
            designMah = designUah?.takeIf { it > 0 }?.let { (it / 1000).toInt() },
            remainingMah = remainingUah?.let { (it / 1000).toInt() },
            timeToFullMin = if (status == ChargeStatus.CHARGING) ttf else null,
            chargeType = b?.chargeType,
            chargerType = charger?.realType ?: charger?.type,
            chargerVoltageV = chargerV,
            chargerCurrentA = chargerA,
            chargerPowerW = if (chargerV != null && chargerA != null) chargerV * chargerA else null,
        )
    }
}

/** Reads [AndroidBattery] from the sticky ACTION_BATTERY_CHANGED intent and BatteryManager. */
class AndroidBatteryReader(context: Context) {
    private val app = context.applicationContext
    private val bm = app.getSystemService(BatteryManager::class.java)

    fun read(): AndroidBattery {
        val i: Intent? = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        fun int(key: String): Int? = i?.getIntExtra(key, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
        val level = int(BatteryManager.EXTRA_LEVEL)
        val scale = int(BatteryManager.EXTRA_SCALE)
        val status = when (int(BatteryManager.EXTRA_STATUS)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> ChargeStatus.CHARGING
            BatteryManager.BATTERY_STATUS_DISCHARGING -> ChargeStatus.DISCHARGING
            BatteryManager.BATTERY_STATUS_FULL -> ChargeStatus.FULL
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> ChargeStatus.NOT_CHARGING
            else -> ChargeStatus.UNKNOWN
        }
        val plug = when (int(BatteryManager.EXTRA_PLUGGED)) {
            BatteryManager.BATTERY_PLUGGED_AC -> PlugType.AC
            BatteryManager.BATTERY_PLUGGED_USB -> PlugType.USB
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> PlugType.WIRELESS
            BatteryManager.BATTERY_PLUGGED_DOCK -> PlugType.DOCK
            else -> PlugType.NONE
        }
        val health = when (int(BatteryManager.EXTRA_HEALTH)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> BatteryHealth.GOOD
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> BatteryHealth.OVERHEAT
            BatteryManager.BATTERY_HEALTH_DEAD -> BatteryHealth.DEAD
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> BatteryHealth.OVER_VOLTAGE
            BatteryManager.BATTERY_HEALTH_COLD -> BatteryHealth.COLD
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> BatteryHealth.FAILURE
            else -> BatteryHealth.UNKNOWN
        }
        fun prop(id: Int): Long? = runCatching { bm.getLongProperty(id) }.getOrNull()
            ?.takeIf { it != Long.MIN_VALUE && it != Int.MIN_VALUE.toLong() }
        val cycles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            int(BatteryManager.EXTRA_CYCLE_COUNT)?.takeIf { it >= 0 }
        } else null
        return AndroidBattery(
            levelPct = if (level != null && scale != null && scale > 0) level * 100 / scale else null,
            status = status,
            plug = plug,
            health = health,
            technology = i?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() },
            tempC = int(BatteryManager.EXTRA_TEMPERATURE)?.let { it / 10f },
            voltageV = int(BatteryManager.EXTRA_VOLTAGE)?.takeIf { it > 0 }?.let { it / 1000f },
            currentRaw = prop(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            chargeCounterUah = prop(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf { it > 0 },
            cycleCount = cycles,
            chargeTimeRemainingMs = runCatching { bm.computeChargeTimeRemaining() }.getOrNull()?.takeIf { it > 0 },
        )
    }
}
