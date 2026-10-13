// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * First-run setup, in order:
 *   1. Welcome     the mascot pops in; "Halo" types itself into
 *                  "Kenali Zairenkai" and a gradient tagline (ColorOS-style).
 *   2. Legal       terms, risks, privacy, licences, trademarks; the user
 *                  must tick the consent box to continue.
 *   3. Appearance  theme mode, accent and dynamic colour with a live preview
 *                  of the app, and the features as swipeable cards.
 *   4. Check       root, ZKFC, zperfd and the token, probed read-only, with
 *                  animated status badges and plain alerts.
 *   5. Permissions notifications, usage access, battery optimisation.
 *   6. Done        the celebrating mascot inside the signature line
 *                  sequence, a summary, and Start.
 *
 * Steps slide and fade (emphasized decelerate); content rises in, in order.
 * Language and device info sit at the bottom of the welcome page.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.annotation.RawRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import com.zairenkai.app.AppContainer
import com.zairenkai.app.R
import com.zairenkai.app.core.text.asString
import com.zairenkai.app.ui.VmFactory
import com.zairenkai.app.ui.device.DeviceHero
import com.zairenkai.app.ui.device.rememberDeviceInfo
import com.zairenkai.app.ui.icons.ZkIcons
import com.zairenkai.app.ui.legal.LegalSections
import com.zairenkai.app.ui.legal.OssLicensesContent
import com.zairenkai.app.ui.legal.rememberOssCatalog
import com.zairenkai.app.ui.locale.LanguageSheet
import com.zairenkai.app.ui.mascot.Mascot
import com.zairenkai.app.ui.mascot.MascotState
import com.zairenkai.app.ui.theme.GreetingStyle

internal const val PAGE_MS = 360

@Composable
fun Onboarding(container: AppContainer, onDone: () -> Unit) {
    val factory = remember(container) { VmFactory(container) }
    val vm: OnboardingViewModel = viewModel(factory = factory)
    val ui by vm.ui.collectAsState()
    var showLanguage by rememberSaveable { mutableStateOf(false) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    val (permissions, onPermission) = rememberPermissions()

    SetupFlow(
        ui = ui,
        permissions = permissions,
        onLanguage = { showLanguage = true },
        onInfo = { showInfo = true },
        onBack = { vm.back() },
        onNext = { vm.next() },
        onLegalChecked = vm::setLegalChecked,
        onOpenLicenses = { showLicenses = true },
        onThemeMode = { vm.setThemeMode(it) },
        onAccent = { vm.setAccent(it) },
        onDynamic = { vm.setDynamicColor(it) },
        onRecheck = vm::runChecks,
        onPermission = onPermission,
        onFinish = { vm.finish(onDone) },
    )

    if (showLanguage) LanguageSheet(onDismiss = { showLanguage = false })
    if (showInfo) InfoSheet(onDismiss = { showInfo = false })
    if (showLicenses) LicensesDialog(onDismiss = { showLicenses = false })
}

/**
 * The setup screen for a given state. Stateless: every action is a callback,
 * so the real ViewModel drives it in the app and tests drive it directly.
 */
@Composable
internal fun SetupFlow(
    ui: OnboardingUi,
    permissions: Map<AppPermission, Boolean>,
    onLanguage: () -> Unit,
    onInfo: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onLegalChecked: (Boolean) -> Unit,
    onOpenLicenses: () -> Unit,
    onThemeMode: (String) -> Unit,
    onAccent: (String) -> Unit,
    onDynamic: (Boolean) -> Unit,
    onRecheck: () -> Unit,
    onPermission: (AppPermission) -> Unit,
    onFinish: () -> Unit,
) {
    val checks = ui.checks
    // System back follows the on-screen Back button (every step between welcome and done).
    BackHandler(enabled = ui.step != SetupStep.WELCOME && ui.step != SetupStep.DONE) { onBack() }

    SetupBackdrop(ui.step, Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SetupHeader(step = ui.step)
            AnimatedContent(
                targetState = ui.step,
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally(tween(PAGE_MS, easing = EmphasizedDecelerate)) { w -> dir * w / 6 } + fadeIn(tween(PAGE_MS, delayMillis = 60)))
                        .togetherWith(slideOutHorizontally(tween(PAGE_MS - 100)) { w -> -dir * w / 10 } + fadeOut(tween(150)))
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "setupStep",
            ) { step ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (step) {
                        SetupStep.WELCOME -> WelcomeStep()
                        SetupStep.LEGAL -> LegalStep(ui.legalChecked, onLegalChecked, onOpenLicenses)
                        SetupStep.THEME -> ThemeStep(ui, onThemeMode, onAccent, onDynamic)
                        SetupStep.CHECK -> CheckStep(ui.probe, checks, onRecheck = onRecheck)
                        SetupStep.PERMISSIONS -> PermissionsStep(permissions, onPermission)
                        SetupStep.DONE -> DoneStep(checks, permissions)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            SetupActions(ui, onLanguage, onInfo, onBack, onNext, onFinish)
        }
    }
}

/* ---------------- frame ---------------- */

/** Progress segments and the step's name (hidden on the welcome page, like a greeting card). */
@Composable
private fun SetupHeader(step: SetupStep) {
    val total = SetupStep.entries.size
    val label = stringResource(R.string.setup_step_label, step.ordinal + 1, total, stringResource(step.title))
    Column(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = 24.dp, end = 24.dp, top = 14.dp)
            .heightIn(min = 44.dp),
    ) {
        AnimatedVisibility(step != SetupStep.WELCOME, enter = fadeIn(tween(300)), exit = fadeOut(tween(150))) {
            Column(Modifier.semantics(mergeDescendants = true) { contentDescription = label }) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SetupStep.entries.forEach { s ->
                        val weight by animateFloatAsState(if (s == step) 2.6f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "segW")
                        val color by animateColorAsState(
                            if (s.ordinal <= step.ordinal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                            tween(250), label = "segC",
                        )
                        Box(Modifier.weight(weight).height(4.dp).clip(CircleShape).background(color))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(stringResource(step.title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Pill actions. Welcome: language and info on the left, the start pill on the right; done: one centred Start. */
@Composable
private fun SetupActions(
    ui: OnboardingUi,
    onLanguage: () -> Unit,
    onInfo: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (ui.step == SetupStep.DONE) Arrangement.Center else Arrangement.Start,
    ) {
        val colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        )
        when (ui.step) {
            SetupStep.WELCOME -> {
                BottomIcon(ZkIcons.Globe, stringResource(R.string.setup_language), onLanguage)
                Spacer(Modifier.width(4.dp))
                BottomIcon(ZkIcons.Info, stringResource(R.string.setup_info), onInfo)
                Spacer(Modifier.weight(1f))
                PillButton(stringResource(R.string.setup_start), Modifier.height(56.dp), colors, onClick = onNext)
            }
            SetupStep.DONE -> PillButton(stringResource(R.string.setup_start_app), Modifier.height(56.dp).widthIn(min = 200.dp), colors, icon = ZkIcons.Check, onClick = onFinish)
            else -> {
                OutlinedButton(onClick = onBack, shape = CircleShape, modifier = Modifier.height(56.dp), contentPadding = PaddingValues(horizontal = 22.dp)) {
                    Text(stringResource(R.string.action_back))
                }
                Spacer(Modifier.width(12.dp))
                val checking = ui.step == SetupStep.CHECK && !ui.checks.settled()
                PillButton(
                    stringResource(
                        when {
                            checking -> R.string.setup_checking
                            ui.step == SetupStep.LEGAL -> R.string.setup_agree_continue
                            else -> R.string.action_next
                        },
                    ),
                    Modifier.weight(1f).height(56.dp), colors, enabled = ui.canContinue, busy = checking, onClick = onNext,
                )
            }
        }
    }
}

/** Round, quiet button for the welcome page's language and info actions (52 dp target). */
@Composable
private fun BottomIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(52.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) { Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp)) }
}

@Composable
internal fun PillButton(
    label: String,
    modifier: Modifier,
    colors: ButtonColors,
    enabled: Boolean = true,
    busy: Boolean = false,
    arrow: Boolean = true,
    icon: ImageVector = ZkIcons.ArrowForward,
    onClick: () -> Unit,
) {
    // Pressed feedback: a quick, small squeeze (120 ms), read in graphicsLayer.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(if (pressed) 120 else 200), label = "press")
    Button(
        onClick = onClick, enabled = enabled, shape = CircleShape, colors = colors,
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale },
        contentPadding = PaddingValues(horizontal = 22.dp),
        interactionSource = interaction,
    ) {
        // Fixed-size slots: a spinner while busy, an arrow otherwise, so the label never jumps.
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
            Spacer(Modifier.width(10.dp))
        }
        Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (arrow && !busy) {
            Spacer(Modifier.width(10.dp))
            Icon(icon, null, Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun StepTitle(title: String, body: String, order: Int = 0) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }.reveal(order),
        )
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.reveal(order + 1))
    }
}

/* ---------------- line art (Lottie, coloured from the scheme) ---------------- */

/** Colour roles named in the animations (tools/lottie/gen_setup_animations.py) mapped to the active scheme. */
@Composable
private fun rememberSchemeColors() = MaterialTheme.colorScheme.let { cs ->
    listOf(
        "primary" to cs.primary, "primaryContainer" to cs.primaryContainer,
        "secondary" to cs.secondary, "secondaryContainer" to cs.secondaryContainer,
        "tertiary" to cs.tertiary, "tertiaryContainer" to cs.tertiaryContainer,
        "onPrimary" to cs.onPrimary, "onTertiary" to cs.onTertiary, "outline" to cs.outline,
    )
}

/** A brand line animation (res/raw), recoloured to the scheme; shared with the home screen. */
@Composable
internal fun SetupAnimation(@RawRes res: Int, loop: Boolean, modifier: Modifier = Modifier) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(res))
    val still = reducedMotion()
    val progress by animateLottieCompositionAsState(composition, iterations = if (loop) LottieConstants.IterateForever else 1, isPlaying = !still)
    val roles = rememberSchemeColors()
    val properties = roles.flatMap { (role, color) ->
        listOf(
            rememberLottieDynamicProperty(LottieProperty.COLOR, color.toArgb(), "**", role),
            rememberLottieDynamicProperty(LottieProperty.STROKE_COLOR, color.toArgb(), "**", role),
        )
    }
    val gradient = brandGradient(isDarkSurface()).map { it.toArgb() }.toTypedArray()
    val dynamic = rememberLottieDynamicProperties(
        *properties.toTypedArray(),
        rememberLottieDynamicProperty(LottieProperty.GRADIENT_COLOR, gradient, "**", "brandGradient"),
    )
    LottieAnimation(
        composition = composition,
        // Reduced motion: a representative still frame (the settled end of one-shots).
        progress = { if (still) (if (loop) 0f else 1f) else progress },
        dynamicProperties = dynamic,
        modifier = modifier,
    )
}

/* ---------------- 1. welcome ---------------- */

@Composable
private fun WelcomeStep() {
    var greeted by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
        Mascot(MascotState.WELCOME, size = 220.dp)
    }
    TypewriterGreeting(
        hello = stringResource(R.string.setup_greeting_hello),
        brand = stringResource(R.string.setup_welcome_title),
        tagline = stringResource(R.string.setup_greeting_tagline),
        style = GreetingStyle,
        onFinished = { greeted = true },
    )
    Text(
        stringResource(R.string.setup_welcome_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.reveal(0, start = greeted),
    )
    Text(
        stringResource(R.string.setup_welcome_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.reveal(1, start = greeted),
    )
}

/* ---------------- 2. legal ---------------- */

@Composable
private fun LegalStep(checked: Boolean, onChecked: (Boolean) -> Unit, onOpenLicenses: () -> Unit) {
    StepTitle(stringResource(R.string.legal_title), stringResource(R.string.legal_intro))
    LegalSections(onOpenLicenses, Modifier.reveal(2))
    val cs = MaterialTheme.colorScheme
    val border by animateColorAsState(if (checked) cs.primary else cs.outlineVariant, tween(200), label = "consent")
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = if (checked) cs.primaryContainer.copy(alpha = 0.6f) else cs.surfaceContainerLow.copy(alpha = 0.94f),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, border),
        modifier = Modifier.reveal(3),
    ) {
        Row(
            Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChecked).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.legal_consent), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
        }
    }
}

/** The open-source licences over the setup, full screen. */
@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.IconButton(onClick = onDismiss) { Icon(ZkIcons.ArrowBack, stringResource(R.string.action_back)) }
                    Text(stringResource(R.string.oss_title), style = MaterialTheme.typography.titleLarge)
                }
                OssLicensesContent(rememberOssCatalog())
            }
        }
    }
}

/* ---------------- 6. done ---------------- */

@Composable
private fun DoneStep(checks: List<SetupCheck>, permissions: Map<AppPermission, Boolean>) {
    val allOk = checks.all { it.state == CheckState.OK }
    val state = doneMascotState(checks)
    // The celebrating mascot inside the signature line sequence (rays, bundle, orbit, coil).
    Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
        if (allOk) SetupAnimation(R.raw.setup_success, loop = false, modifier = Modifier.size(260.dp).graphicsLayer { alpha = 0.9f })
        Mascot(state, size = 210.dp)
    }
    TypewriterLine(
        stringResource(if (allOk) R.string.setup_done_ready_title else R.string.setup_done_partial_title),
        MaterialTheme.typography.displaySmall,
        modifier = Modifier.fillMaxWidth(),
        caretWhenDone = false,
    )
    Text(
        stringResource(if (allOk) R.string.setup_done_ready_body else R.string.setup_done_partial_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.reveal(1),
    )
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.reveal(2),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            checks.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(c.state, size = 26.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(c.id.title), style = MaterialTheme.typography.bodyMedium)
                }
            }
            val granted = permissions.count { it.value }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(if (granted == permissions.size) CheckState.OK else CheckState.WARN, size = 26.dp)
                Spacer(Modifier.width(10.dp))
                Text(pluralStringResource(R.plurals.setup_done_permissions, permissions.size, granted, permissions.size), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    val todo = checks.mapNotNull { it.fix }
    if (todo.isNotEmpty()) {
        Column(Modifier.reveal(3), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.setup_next_steps), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            todo.forEach { Text("• " + it.asString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Completion: celebrate a full setup, acknowledge a usable one, flag one with work left. */
internal fun doneMascotState(checks: List<SetupCheck>): MascotState = when {
    !checks.settled() -> MascotState.INSPECTING
    checks.all { it.state == CheckState.OK } -> MascotState.CELEBRATING
    checks.profilesReady() -> MascotState.READY
    else -> MascotState.WARNING
}

/* ---------------- info sheet ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Read off the main thread (getprop); the hero shows a spinner until then.
    val info = rememberDeviceInfo()
    fun size(bytes: Long) = Formatter.formatFileSize(context, bytes)
    val rows = info?.let {
        listOf(
            Triple(ZkIcons.Info, stringResource(R.string.info_app_version),
                stringResource(R.string.info_app_version_value, it.appVersion, it.appBuild, it.buildType)),
            Triple(ZkIcons.Android, stringResource(R.string.info_android),
                stringResource(R.string.info_android_value, it.androidRelease, it.sdk, it.securityPatch)),
            Triple(ZkIcons.Chip, stringResource(R.string.info_soc),
                pluralStringResource(R.plurals.info_soc_value, it.cores, it.soc, it.cores, it.abis.firstOrNull().orEmpty())),
            Triple(ZkIcons.Memory, stringResource(R.string.info_ram),
                stringResource(R.string.info_ram_value, size(it.ramTotal), size(it.ramAvailable))),
            Triple(ZkIcons.Storage, stringResource(R.string.info_storage),
                stringResource(R.string.info_storage_value, size(it.storageTotal), size(it.storageFree))),
            Triple(ZkIcons.Terminal, stringResource(R.string.info_kernel), it.kernel),
        )
    }.orEmpty()
    var copied by remember { mutableStateOf(false) }
    val title = stringResource(R.string.info_title)
    val deviceLabel = stringResource(R.string.info_device)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                FilledTonalButton(enabled = info != null, onClick = {
                    val device = info?.let { "$deviceLabel: ${it.deviceLine} (${it.model}, ${it.codename})" }
                    val text = (listOfNotNull(title, device) + rows.map { "${it.second}: ${it.third}" }).joinToString("\n")
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(title, text))
                    copied = true
                }) {
                    Icon(ZkIcons.Copy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.info_copy))
                }
            }
            if (copied) {
                Text(stringResource(R.string.info_copied), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            // The brand-aware hero: who made this phone and what it is called.
            DeviceHero(info)
            rows.forEachIndexed { i, (icon, label, value) -> InfoRow(icon, label, value, accent = i % 3) }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String, accent: Int) {
    val cs = MaterialTheme.colorScheme
    val (container, content) = when (accent) {
        0 -> cs.primaryContainer to cs.onPrimaryContainer
        1 -> cs.secondaryContainer to cs.onSecondaryContainer
        else -> cs.tertiaryContainer to cs.onTertiaryContainer
    }
    Surface(shape = RoundedCornerShape(20.dp), color = cs.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(14.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = container) {
                Icon(icon, null, Modifier.padding(10.dp).size(22.dp), tint = content)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
