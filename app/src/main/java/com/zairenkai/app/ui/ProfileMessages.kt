// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * User-facing wording for profile state and outcomes. Pure functions so the
 * hold reasons, failure classes and mode summaries are unit tested.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui

import com.zairenkai.app.data.ModeApplyResult
import com.zairenkai.app.data.ModeSummary

/** One line describing what an engine mode requests on this device. */
fun modeSummaryLine(mode: ModeSummary): String {
    val parts = mutableListOf<String>()
    val cpu = mode.cpu
    cpu.minFreq?.takeIf { it != "min" }?.let { parts += "CPU min $it" }
    when {
        cpu.maxPerfPct != null -> parts += "CPU maks ${cpu.maxPerfPct}%"
        cpu.maxFreq != null && cpu.maxFreq != "max" -> parts += "CPU maks ${cpu.maxFreq}"
    }
    cpu.uclampMinPct?.takeIf { it > 0 }?.let { parts += "uclamp $it%" }
    val boostPct = cpu.inputBoostPct
    val boostMs = cpu.inputBoostMs
    if (boostPct != null && boostPct > 0 && boostMs != null && boostMs > 0) {
        parts += "boost input $boostPct% ${boostMs} ms"
    } else if (boostMs == 0) {
        parts += "tanpa boost input"
    }
    val gpu = mode.gpu
    gpu.minFreq?.takeIf { it != "min" }?.let { parts += "GPU min $it" }
    when {
        gpu.maxPerfPct != null -> parts += "GPU maks ${gpu.maxPerfPct}%"
        gpu.maxFreq != null && gpu.maxFreq != "max" -> parts += "GPU maks ${gpu.maxFreq}"
    }
    mode.mem.swappiness?.let { parts += "swappiness $it" }
    return parts.joinToString(" · ").ifEmpty { "Nilai default kernel" }
}

/** Why the engine holds the stock baseline, phrased for the user. */
fun holdMessage(reason: String?, savedName: String?): String? = when (reason) {
    "revalidation_required" ->
        "Kernel atau perangkat berubah sejak profil ${savedName ?: "tersimpan"} dipilih. " +
            "Engine menahan baseline stok sampai Anda mengonfirmasi ulang."
    "safe_mode" -> "SAFE MODE aktif: baseline stok ditahan dan profil tidak diterapkan."
    "stock" -> "Direset ke stok: engine tidak menerapkan profil apa pun."
    null -> null
    else -> "Engine menahan baseline stok ($reason)."
}

/** What the engine actually did, by outcome class, for a successful apply. */
fun profileSuccessMessage(name: String, result: ModeApplyResult): String {
    val parts = mutableListOf("${result.applied.size} terverifikasi")
    if (result.constrained.isNotEmpty()) parts += "${result.constrained.size} dibatasi kernel"
    if (result.unverified.isNotEmpty()) parts += "${result.unverified.size} tidak dapat diverifikasi"
    if (result.skipped.isNotEmpty()) parts += "${result.skipped.size} tidak tersedia di perangkat"
    return "Profil $name diterapkan: ${parts.joinToString(", ")}."
}

fun profileFailureMessage(result: ModeApplyResult): String {
    val reason = when (result.kind) {
        "engine_unavailable" -> "zperfd tidak tersedia; profil tidak diterapkan."
        "framework_unavailable" -> "ZKFC tidak termuat di kernel; profil dikunci."
        "framework_incompatible" -> "Versi API ZKFC tidak kompatibel dengan engine; profil dikunci."
        "license" -> "Token API ZKFC belum valid; profil performa dikunci."
        "unauthorized" -> "Operasi ditolak oleh Core Authority."
        "sentinel" -> "Diblokir Sentinel (keamanan termal/daya/penyimpanan); tidak ada perubahan."
        "thermal_guard" -> "Guard termal kernel tidak dapat dikonfigurasi; profil tidak diterapkan."
        "blocked" -> "Diblokir oleh kernel (ZKFC/token), Sentinel, atau guard termal; tidak ada perubahan."
        "rejected" -> "Mode tidak tersedia di profil perangkat ini."
        "not_observed" -> "Kernel tidak mempertahankan nilai yang ditulis; engine melakukan rollback."
        "failed" -> "Penerapan gagal; engine melakukan rollback dan status aktif tidak berubah."
        "rollback_failed" -> "Penerapan gagal dan rollback tidak tuntas; pemulihan akan dijalankan."
        "state" -> "State engine tidak dapat dipulihkan; tidak ada perubahan."
        "timeout" -> "Engine tidak merespons tepat waktu; status tidak pasti, muat ulang."
        else -> "Respons engine tidak dikenali; anggap tidak diterapkan."
    }
    val detail = (listOfNotNull(result.error?.takeIf { it.isNotBlank() }) + result.failed.take(3))
        .joinToString("\n")
    return if (detail.isNotEmpty()) "$reason\n$detail" else reason
}
