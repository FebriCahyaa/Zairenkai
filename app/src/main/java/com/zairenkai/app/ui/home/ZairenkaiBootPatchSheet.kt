// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
package com.zairenkai.app.ui.home

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zairenkai.app.R
import com.zairenkai.app.data.boot.BootPatchRepository
import com.zairenkai.app.ui.icons.ZkIcons
import java.io.File
import kotlinx.coroutines.launch

private enum class ZairenkaiInstallMode(val title: Int, val subtitle: Int) {
    FILE(R.string.patch_flow_method_file, R.string.patch_flow_method_file_sub),
    ANYKERNEL(R.string.patch_flow_method_anykernel, R.string.patch_flow_method_anykernel_sub),
}

private data class ZairenkaiKmiOption(val id: String, val label: String)

// Keep this matrix aligned with KernelSU-Next's supported LKM KMI build targets
// (kernel/build-all.sh). Choosing a label manually does not prove symbol ABI compatibility.
private val zairenkaiKmiOptions = listOf(
    ZairenkaiKmiOption("android12-5.10", "Android 12 · 5.10"),
    ZairenkaiKmiOption("android13-5.10", "Android 13 · 5.10"),
    ZairenkaiKmiOption("android13-5.15", "Android 13 · 5.15"),
    ZairenkaiKmiOption("android14-5.15", "Android 14 · 5.15"),
    ZairenkaiKmiOption("android14-6.1", "Android 14 · 6.1"),
    ZairenkaiKmiOption("android15-6.6", "Android 15 · 6.6"),
    ZairenkaiKmiOption("android16-6.12", "Android 16 · 6.12"),
    ZairenkaiKmiOption("android17-6.18", "Android 17 · 6.18"),
)

/**
 * KernelSU-Next-inspired install flow, adapted to Zairenkai's own `zkfc.ko`.
 * File mode invokes the standalone GPL boot-image backend and only saves an
 * output image. KMI detection follows KernelSU-Next's kernel-banner then vendor
 * module-vermagic fallback; boot/init_boot/vendor_boot ramdisk selection follows
 * its init_boot-named/unnamed vendor ramdisk order. It never flashes partitions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZairenkaiBootPatchSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(ZairenkaiInstallMode.FILE) }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var kernelReferenceUri by remember { mutableStateOf<Uri?>(null) }
    var kmiReferenceModuleUri by remember { mutableStateOf<Uri?>(null) }
    var moduleUri by remember { mutableStateOf<Uri?>(null) }
    var anyKernelUri by remember { mutableStateOf<Uri?>(null) }
    var imageName by rememberSaveable { mutableStateOf<String?>(null) }
    var kernelReferenceName by rememberSaveable { mutableStateOf<String?>(null) }
    var kmiReferenceModuleName by rememberSaveable { mutableStateOf<String?>(null) }
    var moduleName by rememberSaveable { mutableStateOf<String?>(null) }
    var anyKernelName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedKmi by rememberSaveable { mutableStateOf<String?>(null) }
    var detectedKmi by rememberSaveable { mutableStateOf<String?>(null) }
    var detectedKernelRelease by rememberSaveable { mutableStateOf<String?>(null) }
    var detectedKmiSource by rememberSaveable { mutableStateOf<String?>(null) }
    var detectingKmi by remember { mutableStateOf(false) }
    var showKmiPicker by remember { mutableStateOf(false) }
    var patching by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var log by remember { mutableStateOf<String?>(null) }
    var outputFile by remember { mutableStateOf<File?>(null) }

    val noHandler = stringResource(R.string.install_no_handler)
    val errorPrefix = stringResource(R.string.patch_flow_error)
    val runningText = stringResource(R.string.patch_flow_running)
    val detectingKmiText = stringResource(R.string.patch_flow_kmi_detecting)
    val savingText = stringResource(R.string.patch_flow_saving)
    val successText = stringResource(R.string.patch_flow_success)
    val savedText = stringResource(R.string.patch_flow_saved)
    val kmiMismatch = detectedKmi != null && selectedKmi != null && detectedKmi != selectedKmi
    val kmiVerified = detectedKmi != null &&
        (detectedKmiSource == "kernel-image" || detectedKmiSource == "vendor-module-vermagic")

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            outputFile?.let { previous -> scope.launch { BootPatchRepository.deleteOutput(previous) } }
            imageUri = uri
            imageName = null
            kernelReferenceUri = null
            kernelReferenceName = null
            kmiReferenceModuleUri = null
            kmiReferenceModuleName = null
            selectedKmi = null
            detectedKmi = null
            detectedKernelRelease = null
            detectedKmiSource = null
            detectingKmi = true
            status = detectingKmiText
            log = null
            outputFile = null
            scope.launch {
                try {
                    imageName = BootPatchRepository.displayName(context, uri)
                    val detection = BootPatchRepository.detectKernel(
                        context,
                        uri,
                        kernelReferenceUri,
                        kmiReferenceModuleUri,
                    )
                    val candidate = detection.kmi
                    detectedKmi = candidate
                    detectedKernelRelease = detection.kernelRelease
                    detectedKmiSource = detection.kmiSource
                    if (candidate != null && zairenkaiKmiOptions.any { it.id == candidate }) {
                        selectedKmi = candidate
                    }
                    status = null
                } catch (error: Exception) {
                    // Detection is advisory for ramdisk-only images; the patcher still
                    // checks the selected KMI against module vermagic before repacking.
                    log = "KMI detection: ${error.message.orEmpty()}"
                    status = null
                } finally {
                    detectingKmi = false
                }
            }
        }
    }
    val kernelReferencePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            outputFile?.let { previous -> scope.launch { BootPatchRepository.deleteOutput(previous) } }
            kernelReferenceUri = uri
            kernelReferenceName = null
            selectedKmi = null
            detectedKmi = null
            detectedKernelRelease = null
            detectedKmiSource = null
            outputFile = null
            status = null
            log = null
            scope.launch {
                kernelReferenceName = runCatching { BootPatchRepository.displayName(context, uri) }.getOrNull()
                val target = imageUri
                if (target != null) {
                    detectingKmi = true
                    status = detectingKmiText
                    try {
                        val detection = BootPatchRepository.detectKernel(
                            context,
                            target,
                            uri,
                            kmiReferenceModuleUri,
                        )
                        val candidate = detection.kmi
                        detectedKmi = candidate
                        detectedKernelRelease = detection.kernelRelease
                        detectedKmiSource = detection.kmiSource
                        if (candidate != null && zairenkaiKmiOptions.any { it.id == candidate }) {
                            selectedKmi = candidate
                        }
                        status = null
                    } catch (error: Exception) {
                        log = "KMI detection: ${error.message.orEmpty()}"
                        status = null
                    } finally {
                        detectingKmi = false
                    }
                }
            }
        }
    }
    val kmiReferenceModulePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            outputFile?.let { previous -> scope.launch { BootPatchRepository.deleteOutput(previous) } }
            kmiReferenceModuleUri = uri
            kmiReferenceModuleName = null
            selectedKmi = null
            detectedKmi = null
            detectedKernelRelease = null
            detectedKmiSource = null
            outputFile = null
            status = null
            log = null
            scope.launch {
                kmiReferenceModuleName = runCatching { BootPatchRepository.displayName(context, uri) }.getOrNull()
                val target = imageUri
                if (target != null) {
                    detectingKmi = true
                    status = detectingKmiText
                    try {
                        val detection = BootPatchRepository.detectKernel(
                            context,
                            target,
                            kernelReferenceUri,
                            uri,
                        )
                        val candidate = detection.kmi
                        detectedKmi = candidate
                        detectedKernelRelease = detection.kernelRelease
                        detectedKmiSource = detection.kmiSource
                        if (candidate != null && zairenkaiKmiOptions.any { it.id == candidate }) {
                            selectedKmi = candidate
                        }
                        status = null
                    } catch (error: Exception) {
                        log = "KMI reference detection: ${error.message.orEmpty()}"
                        status = null
                    } finally {
                        detectingKmi = false
                    }
                }
            }
        }
    }
    val modulePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            outputFile?.let { previous -> scope.launch { BootPatchRepository.deleteOutput(previous) } }
            moduleUri = uri
            scope.launch { moduleName = BootPatchRepository.displayName(context, uri) }
            status = null
            log = null
            outputFile = null
        }
    }
    val anyKernelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            anyKernelUri = uri
            scope.launch { anyKernelName = BootPatchRepository.displayName(context, uri) }
            status = null
        }
    }
    val outputPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = outputFile
        if (uri != null && file != null) {
            scope.launch {
                status = savingText
                val saveResult = runCatching { BootPatchRepository.saveOutput(context, file, uri) }
                if (saveResult.isSuccess) {
                    status = savedText
                    BootPatchRepository.deleteOutput(file)
                    outputFile = null
                } else {
                    status = "$errorPrefix: ${saveResult.exceptionOrNull()?.message.orEmpty()}"
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            val pendingOutput = outputFile
            if (pendingOutput == null) onDismiss()
            else scope.launch {
                BootPatchRepository.deleteOutput(pendingOutput)
                onDismiss()
            }
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.install_sheet_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.patch_flow_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ZairenkaiInstallMode.entries.forEach { option ->
                    val selected = mode == option
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().selectable(
                            selected = selected,
                            enabled = !patching,
                            role = Role.RadioButton,
                        ) { mode = option },
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selected, onClick = null, enabled = !patching)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(option.title), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(option.subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            when (mode) {
                ZairenkaiInstallMode.FILE -> {
                    Text(stringResource(R.string.patch_flow_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    FilledTonalButton(
                        onClick = { imagePicker.launch(arrayOf("application/octet-stream", "application/x-android-boot-image", "*/*")) },
                        enabled = !patching && !detectingKmi,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(ZkIcons.Memory, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.patch_flow_pick_image))
                    }
                    imageName?.let { Text(stringResource(R.string.patch_flow_selected_image, it), style = MaterialTheme.typography.bodySmall) }

                    FilledTonalButton(
                        onClick = { kernelReferencePicker.launch(arrayOf("application/octet-stream", "application/x-android-boot-image", "*/*")) },
                        enabled = !patching && !detectingKmi,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(ZkIcons.Memory, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.patch_flow_pick_kernel_reference))
                    }
                    kernelReferenceName?.let {
                        Text(stringResource(R.string.patch_flow_selected_kernel_reference, it), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        stringResource(R.string.patch_flow_kernel_reference_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    FilledTonalButton(
                        onClick = { kmiReferenceModulePicker.launch(arrayOf("application/octet-stream", "*/*")) },
                        enabled = !patching && !detectingKmi,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(ZkIcons.Package, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.patch_flow_pick_kmi_reference_module))
                    }
                    kmiReferenceModuleName?.let {
                        Text(
                            stringResource(R.string.patch_flow_selected_kmi_reference_module, it),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.patch_flow_kmi_title), style = MaterialTheme.typography.titleSmall)
                            Text(
                                selectedKmi?.let { kmi -> zairenkaiKmiOptions.firstOrNull { it.id == kmi }?.let { "${it.label} (${it.id})" } }
                                    ?: stringResource(R.string.patch_flow_kmi_not_selected),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = { showKmiPicker = true }, enabled = !patching && !detectingKmi) {
                                Text(stringResource(R.string.patch_flow_kmi_choose))
                            }
                            when {
                                kmiMismatch -> Text(
                                    stringResource(R.string.patch_flow_kmi_mismatch, selectedKmi.orEmpty(), detectedKmi.orEmpty()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                detectingKmi -> Text(detectingKmiText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                detectedKmi != null -> {
                                    Text(
                                        stringResource(R.string.patch_flow_kmi_detected, detectedKmi!!),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    val sourceLabel = when (detectedKmiSource) {
                                        "kernel-image" -> stringResource(R.string.patch_flow_kmi_source_kernel_image)
                                        "vendor-module-vermagic" -> stringResource(R.string.patch_flow_kmi_source_vendor_module)
                                        else -> stringResource(R.string.patch_flow_kmi_source_manual)
                                    }
                                    Text(
                                        stringResource(R.string.patch_flow_kmi_source, sourceLabel),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                imageUri != null -> Text(
                                    stringResource(R.string.patch_flow_kmi_unverified),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            detectedKernelRelease?.let { release ->
                                Text(
                                    stringResource(R.string.patch_flow_kernel_release_detected, release),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(stringResource(R.string.patch_flow_kmi_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    FilledTonalButton(
                        onClick = { modulePicker.launch(arrayOf("application/octet-stream", "*/*")) },
                        enabled = !patching && !detectingKmi,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(ZkIcons.Package, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.patch_flow_pick_module))
                    }
                    moduleName?.let { Text(stringResource(R.string.patch_flow_selected_module, it), style = MaterialTheme.typography.bodySmall) }
                    if (moduleName == null) {
                        Text(
                            stringResource(R.string.patch_flow_module_bundled_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                            Icon(ZkIcons.Shield, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                stringResource(R.string.patch_flow_warning),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Button(
                        onClick = {
                            val boot = imageUri ?: return@Button
                            val kmi = selectedKmi ?: return@Button
                            patching = true
                            status = runningText
                            log = null
                            scope.launch {
                                try {
                                    val result = BootPatchRepository.patch(
                                        context,
                                        boot,
                                        kernelReferenceUri,
                                        kmiReferenceModuleUri,
                                        moduleUri,
                                        kmi,
                                    )
                                    outputFile = result.outputFile
                                    log = result.log
                                    status = successText
                                } catch (error: Exception) {
                                    status = "$errorPrefix: ${error.message.orEmpty()}"
                                    log = error.message
                                } finally {
                                    patching = false
                                }
                            }
                        },
                        enabled = imageUri != null && selectedKmi != null && kmiVerified && outputFile == null && !patching && !detectingKmi && !kmiMismatch,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (patching) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (patching) runningText else stringResource(R.string.patch_flow_action))
                    }
                    outputFile?.let {
                        Button(onClick = { outputPicker.launch(patchedFileName(imageName)) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.patch_flow_save))
                        }
                    }
                }
                ZairenkaiInstallMode.ANYKERNEL -> {
                    FilledTonalButton(
                        onClick = { anyKernelPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(ZkIcons.Package, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.install_pick))
                    }
                    anyKernelName?.let { Text(stringResource(R.string.install_picked, it), style = MaterialTheme.typography.bodySmall) }
                    Button(
                        onClick = {
                            val uri = anyKernelUri
                            if (uri == null) { status = noHandler; return@Button }
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/zip")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            status = runCatching { context.startActivity(intent); null }.getOrDefault(noHandler)
                        },
                        enabled = anyKernelUri != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.install_continue)) }
                }
            }

            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (it.startsWith(errorPrefix)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            log?.takeIf { it.isNotBlank() }?.let {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(
                        it,
                        Modifier.fillMaxWidth().padding(12.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }

    if (showKmiPicker) {
        AlertDialog(
            onDismissRequest = { showKmiPicker = false },
            title = { Text(stringResource(R.string.patch_flow_kmi_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    zairenkaiKmiOptions.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().selectable(
                                selected = selectedKmi == option.id,
                                role = Role.RadioButton,
                            ) { selectedKmi = option.id },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selectedKmi == option.id, onClick = { selectedKmi = option.id })
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(option.label, style = MaterialTheme.typography.bodyMedium)
                                Text(option.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showKmiPicker = false }) { Text(stringResource(R.string.action_apply)) } },
            dismissButton = { TextButton(onClick = { showKmiPicker = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun patchedFileName(selectedImageName: String?): String {
    val base = selectedImageName?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "boot.img"
    return "zairenkai-patched-$base"
}
