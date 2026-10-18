// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * System, security, license state and confirmed root-module installation.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zairenkai.app.AppContainer
import com.zairenkai.app.data.EmbeddedModuleInstaller
import com.zairenkai.app.data.LicenseServerEnrollmentStatus
import com.zairenkai.app.data.ModuleInstallCode
import com.zairenkai.app.data.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SystemUi(
    val loading: Boolean = true,
    val security: SecurityResult? = null,
    val license: LicenseResult? = null,
    val policy: PolicyResult? = null,
    val info: InfoResult? = null,
    val zperf: ZperfProbe? = null,
    val tokenInstalling: Boolean = false,
    val tokenInstallCode: String? = null,
    val tokenInstallDetail: String? = null,
    val moduleInstalling: Boolean = false,
    val moduleInstallCode: ModuleInstallCode? = null,
    val moduleInstallDetail: String? = null,
    val moduleZipReady: Boolean = false,
    val serverEnrollment: LicenseServerEnrollmentStatus = LicenseServerEnrollmentStatus(configured = false, registered = false),
    val serverEnrollmentBusy: Boolean = false,
    val serverEnrollmentResultCode: String? = null,
    val serverEnrollmentSucceeded: Boolean? = null,
)

class SystemViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(SystemUi())
    val ui: StateFlow<SystemUi> = _ui.asStateFlow()
    private val moduleInstaller by lazy { EmbeddedModuleInstaller(c.appContext) }

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _ui.value = _ui.value.copy(loading = true)
        refreshData()
    }

    private suspend fun refreshData() {
        val security = runCatching { c.repository.security() }.getOrNull()
        val license = runCatching { c.repository.license() }.getOrNull()
        val policy = runCatching { c.repository.policy() }.getOrNull()
        val info = runCatching { c.repository.info() }.getOrNull()
        val zperf = runCatching { c.zperf.probe() }.getOrNull()
        val serverEnrollment = c.licenseServerEnrollment.cachedStatus()
        _ui.value = _ui.value.copy(
            loading = false,
            security = security,
            license = license,
            policy = policy,
            info = info,
            zperf = zperf,
            serverEnrollment = serverEnrollment,
        )
    }

    /** Explicitly enroll this installation; does not install or issue a kernel license token. */
    fun enrollWithLicenseServer() = viewModelScope.launch {
        val current = _ui.value.serverEnrollment
        if (!current.configured || _ui.value.serverEnrollmentBusy) return@launch
        _ui.value = _ui.value.copy(
            serverEnrollmentBusy = true,
            serverEnrollmentResultCode = null,
            serverEnrollmentSucceeded = null,
        )
        val result = c.licenseServerEnrollment.enroll()
        _ui.value = _ui.value.copy(
            serverEnrollment = result.status,
            serverEnrollmentBusy = false,
            serverEnrollmentResultCode = result.errorCode ?: if (result.successful) "registration_success" else "registration_failed",
            serverEnrollmentSucceeded = result.successful,
        )
    }

    /** The user explicitly picks a .zkl file; its bytes are never logged or displayed. */
    fun installToken(uri: Uri) = viewModelScope.launch {
        _ui.value = _ui.value.copy(tokenInstalling = true, tokenInstallCode = null, tokenInstallDetail = null)
        val staged = File(c.appContext.cacheDir, "token-import-${System.currentTimeMillis()}.zkl")
        var code = "error"
        var detail: String? = null
        try {
            withContext(Dispatchers.IO) {
                val input = c.appContext.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("The selected file could not be opened.")
                input.use { source ->
                    staged.outputStream().buffered().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_TOKEN_BYTES) throw IllegalArgumentException("Token file exceeds 1 MiB.")
                            output.write(buffer, 0, count)
                        }
                        if (total == 0L) throw IllegalArgumentException("Token file is empty.")
                    }
                }
            }
            val result = c.repository.installToken(staged.absolutePath)
            code = result.state
            detail = result.error
            c.repository.invalidate()
            _ui.value = _ui.value.copy(license = runCatching { c.repository.license() }.getOrNull())
        } catch (e: Exception) {
            code = "error"
            detail = e.message ?: e.javaClass.simpleName
        } finally {
            staged.delete()
        }
        _ui.value = _ui.value.copy(
            tokenInstalling = false,
            tokenInstallCode = code,
            tokenInstallDetail = detail?.take(500),
        )
    }

    /** Installs the hash-pinned ZIP embedded into official release APKs. */
    fun installEmbeddedModule(targetKmi: String) = viewModelScope.launch {
        _ui.value = _ui.value.copy(
            moduleInstalling = true,
            moduleInstallCode = null,
            moduleInstallDetail = null,
            moduleZipReady = false,
        )
        val outcome = moduleInstaller.install(targetKmi)
        if (outcome.code == ModuleInstallCode.INSTALLED) c.repository.invalidate()
        _ui.value = _ui.value.copy(
            moduleInstalling = false,
            moduleInstallCode = outcome.code,
            moduleInstallDetail = outcome.detail.takeLast(6000).ifBlank { null },
            moduleZipReady = outcome.shareableZip && moduleInstaller.cachedZip().isFile,
        )
    }

    private companion object {
        const val MAX_TOKEN_BYTES = 1024L * 1024
    }
}
