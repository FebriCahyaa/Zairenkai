// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * User-facing wording for tweak write outcomes. Kept pure so every failure
 * class reported by zperfd is covered by unit tests.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui

import com.zairenkai.app.data.TweakWriteResult

fun tweakSuccessMessage(result: TweakWriteResult): String {
    val value = result.value ?: "—"
    return if (result.targets > 1) {
        "${result.id} = $value diterapkan dan terverifikasi pada ${result.targets} target."
    } else {
        "${result.id} = $value diterapkan dan terverifikasi."
    }
}

fun tweakFailureMessage(result: TweakWriteResult): String {
    val reason = when (result.kind) {
        "engine_unavailable" -> "zperfd tidak tersedia; tidak ada perubahan."
        "framework_unavailable" -> "ZKFC tidak termuat di kernel; tweak dikunci."
        "framework_incompatible" -> "Versi API ZKFC tidak kompatibel dengan engine; tweak dikunci."
        "license" -> "Token API ZKFC belum valid; tweak performa dikunci."
        "unauthorized" -> "Operasi ditolak oleh Core Authority."
        "sentinel" -> "Diblokir Sentinel (keamanan termal/daya/penyimpanan)."
        "lite" -> "Tweak ini dinonaktifkan dalam Mode Lite."
        "unknown" -> "Tweak tidak dikenal oleh engine."
        "rejected" -> "Nilai ditolak; perubahan dibatalkan."
        "not_observed" -> "Kernel tidak mempertahankan nilai; perubahan dibatalkan."
        "rollback_failed" -> "Penerapan gagal dan rollback tidak tuntas; pemulihan akan dijalankan."
        "state" -> "State engine tidak dapat dipulihkan; tidak ada perubahan."
        "timeout" -> "Engine tidak merespons tepat waktu; status tidak pasti, muat ulang."
        else -> "Respons engine tidak dikenali; anggap tidak diterapkan."
    }
    val detail = result.error?.takeIf { it.isNotBlank() }
    return if (detail != null) "$reason\n$detail" else reason
}
