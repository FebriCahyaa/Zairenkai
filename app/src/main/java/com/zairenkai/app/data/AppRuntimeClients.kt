// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
package com.zairenkai.app.data

/** Shared application boundary for runtime clients. */
data class AppRuntimeClients(
    val repository: ZkfcRepository,
    val zperf: ZperfClient,
)
