// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
package com.zairenkai.app.data

/** One supported GKI KMI target shared by image patching and module installation. */
internal data class KernelKmiTarget(val id: String, val label: String)

/** Keep in sync with kernel/gki/kmi_matrix.txt; CI checks the complete matrix. */
internal val SUPPORTED_KERNEL_KMIS = listOf(
    KernelKmiTarget("android12-5.10", "Android 12 · 5.10"),
    KernelKmiTarget("android13-5.10", "Android 13 · 5.10"),
    KernelKmiTarget("android13-5.15", "Android 13 · 5.15"),
    KernelKmiTarget("android14-5.15", "Android 14 · 5.15"),
    KernelKmiTarget("android14-6.1", "Android 14 · 6.1"),
    KernelKmiTarget("android15-6.6", "Android 15 · 6.6"),
    KernelKmiTarget("android16-6.12", "Android 16 · 6.12"),
    KernelKmiTarget("android17-6.18", "Android 17 · 6.18"),
)
