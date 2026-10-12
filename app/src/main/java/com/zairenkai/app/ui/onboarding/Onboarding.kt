// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * First-run setup: welcome, a live system check of every layer the app
 * depends on (root, ZKFC, zperfd, API token), an optional first profile
 * applied through zperfd, and an honest summary.
 *
 * One continuous visual language carries the whole flow: a luminous
 * backdrop and the flowing thread (SetupVisuals.kt) live outside the step
 * transition, so they never restart; only the step content slides and fades.
 * The header (language, device info, progress) and the action bar stay put.
 * Insets: the header owns the status bar/cutout, the action bar owns the
 * navigation bar; the backdrop draws edge to edge behind both.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Resources
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.annotation.RawRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.zairenkai.app.BuildConfig
import com.zairenkai.app.R
import com.zairenkai.app.core.text.asString
import com.zairenkai.app.domain.Profile
import com.zairenkai.app.domain.ProfileId
import com.zairenkai.app.domain.Profiles
import com.zairenkai.app.ui.VmFactory
import com.zairenkai.app.ui.icons.Android
import com.zairenkai.app.ui.icons.AutoMode
import com.zairenkai.app.ui.icons.Balance
import com.zairenkai.app.ui.icons.BatterySaver
import com.zairenkai.app.ui.icons.ContentCopy
import com.zairenkai.app.ui.icons.DeveloperBoard
import com.zairenkai.app.ui.icons.Language
import com.zairenkai.app.ui.icons.Memory
import com.zairenkai.app.ui.icons.PhoneAndroid
import com.zairenkai.app.ui.icons.Shield
import com.zairenkai.app.ui.icons.Speed
import com.zairenkai.app.ui.icons.SportsEsports
import com.zairenkai.app.ui.icons.Storage
import com.zairenkai.app.ui.icons.Terminal
import com.zairenkai.app.ui.icons.Timeline
import com.zairenkai.app.ui.icons.Tune
import com.zairenkai.app.ui.locale.AppLanguage
import com.zairenkai.app.ui.locale.AppLocale
import com.zairenkai.app.ui.locale.findActivity
import com.zairenkai.app.ui.theme.ZkBad
import com.zairenkai.app.ui.theme.ZkGood
import com.zairenkai.app.ui.theme.ZkWarn

/** Shared motion: page changes slide a short distance and fade; no bounce. */
private const val PAGE_MS = 320

@Composable
fun Onboarding(container: AppContainer, onDone: () -> Unit) {
    val factory = remember(container) { VmFactory(container) }
    val vm: OnboardingViewModel = viewModel(factory = factory)
    val ui by vm.ui.collectAsState()
    var showLanguage by rememberSaveable { mutableStateOf(false) }
    var showInfo by rememberSaveable { mutableStateOf(false) }

    SetupFlow(
        ui = ui,
        onLanguage = { showLanguage = true },
        onInfo = { showInfo = true },
        onSkip = { vm.finish(onDone) },
        onBack = { vm.back() },
        onNext = { vm.next() },
        onRecheck = vm::runChecks,
        onChoose = { vm.choose(it) },
        onApply = { ui.choice?.let { vm.applyChoice(Profiles.byId(it)) } },
        onFinish = { vm.finish(onDone) },
    )

    if (showLanguage) LanguageSheet(onDismiss = { showLanguage = false })
    if (showInfo) InfoSheet(onDismiss = { showInfo = false })
}

/**
 * The setup screen for a given state. Stateless: every action is a callback,
 * so the real ViewModel drives it in the app and tests drive it directly.
 */
@Composable
internal fun SetupFlow(
    ui: OnboardingUi,
    onLanguage: () -> Unit,
    onInfo: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onRecheck: () -> Unit,
    onChoose: (ProfileId?) -> Unit,
    onApply: () -> Unit,
    onFinish: () -> Unit,
) {
    val checks = ui.checks
    val settled = checks.settled()
    val ready = checks.profilesReady()
    // System back follows the on-screen Back button: only between the
    // middle steps, and never while a profile is being applied.
    BackHandler(enabled = (ui.step == SetupStep.CHECK || ui.step == SetupStep.PROFILE) && !ui.applying) { onBack() }

    SetupBackdrop(ui.step, Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SetupHeader(
                step = ui.step,
                onLanguage = onLanguage,
                onInfo = onInfo,
                onSkip = if (ui.step == SetupStep.WELCOME) onSkip else null,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // The thread sits behind the step content and survives step changes.
                SetupThread(
                    threadShape(ui.step, settled, ui.choice),
                    Modifier.fillMaxWidth().height(if (ui.step == SetupStep.WELCOME) 300.dp else 190.dp),
                    drawKey = ui.step.takeIf { it == SetupStep.DONE },
                )
                AnimatedContent(
                    targetState = ui.step,
                    transitionSpec = {
                        val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (slideInHorizontally(tween(PAGE_MS)) { w -> dir * w / 8 } + fadeIn(tween(PAGE_MS, delayMillis = 60)))
                            .togetherWith(slideOutHorizontally(tween(PAGE_MS - 80)) { w -> -dir * w / 8 } + fadeOut(tween(160)))
                    },
                    modifier = Modifier.fillMaxSize(),
                    label = "setupStep",
                ) { step ->
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        when (step) {
                            SetupStep.WELCOME -> WelcomeStep()
                            SetupStep.CHECK -> CheckStep(ui.probe, checks, onRecheck = onRecheck)
                            SetupStep.PROFILE -> ProfileStep(ui, checks, ready, onChoose = onChoose)
                            SetupStep.DONE -> DoneStep(ui, checks)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            SetupActions(
                ui = ui,
                checksSettled = settled,
                profilesReady = ready,
                onBack = onBack,
                onNext = onNext,
                onApply = onApply,
                onFinish = onFinish,
            )
        }
    }
}

/* ---------------- frame ---------------- */

@Composable
private fun SetupHeader(step: SetupStep, onLanguage: () -> Unit, onInfo: () -> Unit, onSkip: (() -> Unit)?) {
    val total = SetupStep.entries.size
    val label = stringResource(R.string.setup_step_label, step.ordinal + 1, total, stringResource(step.title))
    Column(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = 20.dp, end = 12.dp, top = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Progress: the current segment stretches, done ones stay filled.
            Row(
                Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = label },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SetupStep.entries.forEach { s ->
                    val weight by animateFloatAsState(
                        if (s == step) 2.6f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "segW",
                    )
                    val color by animateColorAsState(
                        if (s.ordinal <= step.ordinal) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        tween(250), label = "segC",
                    )
                    Box(Modifier.weight(weight).height(4.dp).clip(CircleShape).background(color))
                }
            }
            Spacer(Modifier.width(12.dp))
            HeaderButton(Icons.Rounded.Language, stringResource(R.string.setup_language), onLanguage)
            HeaderButton(Icons.Rounded.Info, stringResource(R.string.setup_info), onInfo)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(step.title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (onSkip != null) TextButton(onClick = onSkip) { Text(stringResource(R.string.action_skip)) }
        }
    }
}

@Composable
private fun HeaderButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Pill actions, OxygenOS-style: a strong inverse primary and a quiet Back. */
@Composable
private fun SetupActions(
    ui: OnboardingUi,
    checksSettled: Boolean,
    profilesReady: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onApply: () -> Unit,
    onFinish: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val showBack = ui.step == SetupStep.CHECK || ui.step == SetupStep.PROFILE
        AnimatedVisibility(
            visible = showBack,
            enter = expandHorizontally(tween(PAGE_MS)) + fadeIn(tween(PAGE_MS)),
            exit = shrinkHorizontally(tween(PAGE_MS)) + fadeOut(tween(150)),
        ) {
            Row {
                OutlinedButton(
                    onClick = onBack,
                    enabled = !ui.applying,
                    shape = CircleShape,
                    modifier = Modifier.height(56.dp),
                    contentPadding = PaddingValues(horizontal = 22.dp),
                ) { Text(stringResource(R.string.action_back)) }
                Spacer(Modifier.width(12.dp))
            }
        }
        val colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        )
        val primary = Modifier.weight(1f).height(56.dp)
        when (ui.step) {
            SetupStep.WELCOME -> PillButton(stringResource(R.string.setup_start), primary, colors, onClick = onNext)
            SetupStep.CHECK -> PillButton(
                stringResource(if (checksSettled) R.string.action_next else R.string.setup_checking),
                primary, colors, enabled = checksSettled, busy = !checksSettled, onClick = onNext,
            )
            SetupStep.PROFILE -> {
                val choice = ui.choice
                if (profilesReady && choice != null) {
                    PillButton(
                        stringResource(
                            if (ui.applying) R.string.setup_applying else R.string.setup_apply_profile,
                            stringResource(Profiles.byId(choice).nameRes),
                        ),
                        primary, colors, enabled = !ui.applying, busy = ui.applying, arrow = false, onClick = onApply,
                    )
                } else {
                    PillButton(stringResource(R.string.setup_skip_for_now), primary, colors, onClick = onNext)
                }
            }
            SetupStep.DONE -> PillButton(stringResource(R.string.setup_enter_app), primary, colors, icon = Icons.Rounded.Check, onClick = onFinish)
        }
    }
}

@Composable
private fun PillButton(
    label: String,
    modifier: Modifier,
    colors: ButtonColors,
    enabled: Boolean = true,
    busy: Boolean = false,
    arrow: Boolean = true,
    icon: ImageVector = Icons.AutoMirrored.Rounded.ArrowForward,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = enabled, shape = CircleShape, colors = colors, modifier = modifier) {
        // Fixed-size leading slot: a spinner while busy, so the label never jumps.
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
        }
        Spacer(Modifier.width(10.dp))
        Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (arrow && !busy) Icon(icon, null, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun StepTitle(title: String, body: String, large: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title,
            style = if (large) MaterialTheme.typography.displaySmall.copy(letterSpacing = (-0.8).sp)
            else MaterialTheme.typography.headlineMedium.copy(letterSpacing = (-0.4).sp),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/* ---------------- emblems (Lottie, coloured from the scheme) ---------------- */

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

@Composable
private fun SetupAnimation(@RawRes res: Int, loop: Boolean, modifier: Modifier = Modifier) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(res))
    val still = reducedMotion()
    val progress by animateLottieCompositionAsState(
        composition,
        iterations = if (loop) LottieConstants.IterateForever else 1,
        isPlaying = !still,
    )
    val roles = rememberSchemeColors()
    val properties = roles.flatMap { (role, color) ->
        listOf(
            rememberLottieDynamicProperty(LottieProperty.COLOR, color.toArgb(), "**", role),
            rememberLottieDynamicProperty(LottieProperty.STROKE_COLOR, color.toArgb(), "**", role),
        )
    }
    val dynamic = rememberLottieDynamicProperties(*properties.toTypedArray())
    LottieAnimation(
        composition = composition,
        // Reduced motion: a representative still frame (the settled end of one-shots).
        progress = { if (still) (if (loop) 0f else 1f) else progress },
        dynamicProperties = dynamic,
        modifier = modifier,
    )
}

/** A small emblem resting on the thread: a soft disc with the step's animation. */
@Composable
private fun Emblem(@RawRes res: Int, loop: Boolean, size: Int = 112) {
    Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 2.dp,
            shadowElevation = 0.dp,
            modifier = Modifier.size(size.dp),
        ) {
            Crossfade(targetState = res to loop, animationSpec = tween(300), label = "emblem") { (r, l) ->
                SetupAnimation(r, l, Modifier.fillMaxSize().padding(10.dp))
            }
        }
    }
}

/* ---------------- 1. welcome ---------------- */

@Composable
private fun WelcomeStep() {
    // The thread owns the upper part of the page; the words sit below it.
    Spacer(Modifier.height(250.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        SetupAnimation(R.raw.setup_welcome, loop = true, modifier = Modifier.size(44.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
    StepTitle(stringResource(R.string.setup_welcome_title), stringResource(R.string.setup_welcome_body), large = true)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 4.dp)) {
        Feature(Icons.Rounded.Timeline, R.string.setup_feature_monitor_title, R.string.setup_feature_monitor_body)
        Feature(Icons.Rounded.Tune, R.string.setup_feature_verified_title, R.string.setup_feature_verified_body)
        Feature(Icons.Rounded.Shield, R.string.setup_feature_safety_title, R.string.setup_feature_safety_body)
    }
    Text(
        stringResource(R.string.setup_welcome_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A light row (no card): icon chip, title, one line of detail. */
@Composable
private fun Feature(icon: ImageVector, title: Int, body: Int) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun IconTile(icon: ImageVector, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(14.dp), color = container) {
        Icon(icon, null, Modifier.padding(10.dp).size(22.dp), tint = content)
    }
}

/* ---------------- 2. system check ---------------- */

@Composable
private fun CheckStep(probe: SetupProbe, checks: List<SetupCheck>, onRecheck: () -> Unit) {
    val settled = checks.settled()
    val ready = checks.profilesReady()
    Emblem(
        when {
            !settled -> R.raw.setup_scan
            ready -> R.raw.setup_success
            else -> R.raw.setup_attention
        },
        loop = !settled,
    )
    StepTitle(stringResource(R.string.setup_check_title), stringResource(R.string.setup_check_body))
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f)) {
        Column(Modifier.padding(vertical = 6.dp)) {
            checks.forEachIndexed { i, c ->
                CheckRow(c, Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                if (i != checks.lastIndex) HorizontalDivider(Modifier.padding(start = 66.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
    AnimatedVisibility(settled, enter = fadeIn(tween(250)) + expandVertically(tween(PAGE_MS)), exit = fadeOut(tween(150)) + shrinkVertically(tween(200))) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(
                    when {
                        checks.all { it.state == CheckState.OK } -> R.string.setup_check_all_ok
                        ready -> R.string.setup_check_ready_with_notes
                        else -> R.string.setup_check_not_ready
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onRecheck, shape = CircleShape) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.setup_recheck))
                }
                if (!checks.all { it.state == CheckState.OK }) TechnicalDetails(probe)
            }
        }
    }
}

/** Raw probe output for support, kept out of the way behind a toggle. */
@Composable
private fun TechnicalDetails(probe: SetupProbe) {
    var open by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.dashboard_details_show)) }
    if (open) {
        val report = diagnosticsReport(probe, BuildConfig.VERSION_NAME)
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.dashboard_details_show)) },
            text = {
                Text(
                    report,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Zairenkai diagnostics", report))
                    open = false
                }) { Text(stringResource(R.string.dashboard_copy_diagnostics)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

private data class StateLook(val icon: ImageVector?, val color: Color, val label: Int)

@Composable
private fun look(state: CheckState): StateLook = when (state) {
    CheckState.CHECKING -> StateLook(null, MaterialTheme.colorScheme.primary, R.string.setup_state_checking)
    CheckState.OK -> StateLook(Icons.Rounded.CheckCircle, ZkGood, R.string.setup_state_ok)
    CheckState.WARN -> StateLook(Icons.Rounded.Warning, ZkWarn, R.string.setup_state_warn)
    CheckState.MISSING -> StateLook(Icons.Rounded.Close, ZkBad, R.string.setup_state_missing)
}

/** One readiness row; the state badge cross-fades as the probe answers. Shared with the Dashboard. */
@Composable
internal fun CheckRow(check: SetupCheck, modifier: Modifier = Modifier) {
    val l = look(check.state)
    val title = stringResource(check.id.title)
    val detail = check.detail.asString()
    val a11y = stringResource(R.string.setup_check_a11y, title, stringResource(l.label), detail)
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = a11y }) {
        val bg by animateColorAsState(l.color.copy(alpha = 0.14f), tween(250), label = "badge")
        Box(Modifier.size(36.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Crossfade(targetState = check.state, animationSpec = tween(250), label = "state") { st ->
                val sl = look(st)
                if (sl.icon == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(sl.icon, null, Modifier.size(20.dp), tint = sl.color)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).animateContentSize(tween(250)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            check.fix?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface) }
        }
    }
}

/* ---------------- 3. first profile ---------------- */

@Composable
private fun ProfileStep(ui: OnboardingUi, checks: List<SetupCheck>, ready: Boolean, onChoose: (ProfileId?) -> Unit) {
    Emblem(R.raw.setup_tune, loop = true, size = 96)
    StepTitle(stringResource(R.string.setup_profile_title), stringResource(R.string.setup_profile_body))
    if (!ready) {
        val blockers = checks.filterNot { it.allowsProfiles() }.map { stringResource(it.id.title) }
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(Modifier.fillMaxWidth().padding(16.dp)) {
                Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.setup_profile_blocked, blockers.joinToString(", ")),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Profiles.all.forEach { p ->
            ProfileOption(p, selected = ui.choice == p.id, enabled = ready && !ui.applying) {
                onChoose(if (ui.choice == p.id) null else p.id)
            }
        }
    }
    AnimatedVisibility(ui.applyFailed && ui.applyMessage != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Text(
            ui.applyMessage?.asString().orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

private fun profileIcon(p: Profile): ImageVector = when (p.id) {
    ProfileId.BATTERY -> Icons.Rounded.BatterySaver
    ProfileId.BALANCED -> Icons.Rounded.Balance
    ProfileId.PERFORMANCE -> Icons.Rounded.Speed
    ProfileId.GAME -> Icons.Rounded.SportsEsports
    ProfileId.AUTO -> Icons.Rounded.AutoMode
}

@Composable
private fun ProfileOption(profile: Profile, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val border by animateColorAsState(if (selected) cs.primary else cs.outlineVariant.copy(alpha = 0.6f), tween(200), label = "profileBorder")
    val container by animateColorAsState(
        if (selected) cs.primaryContainer else cs.surfaceContainerLow.copy(alpha = 0.92f), tween(200), label = "profileBg",
    )
    val shape = RoundedCornerShape(22.dp)
    Surface(
        shape = shape,
        color = container,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, border, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val tint = if (enabled) (if (selected) cs.onPrimaryContainer else cs.primary) else cs.outline
            Icon(profileIcon(profile), null, Modifier.size(24.dp), tint = tint)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(profile.nameRes), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = if (!enabled) cs.outline else if (selected) cs.onPrimaryContainer else cs.onSurface,
                )
                Text(
                    stringResource(profile.taglineRes), style = MaterialTheme.typography.bodySmall,
                    color = if (selected) cs.onPrimaryContainer.copy(alpha = 0.8f) else cs.onSurfaceVariant,
                )
            }
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
    }
}

/* ---------------- 4. done ---------------- */

@Composable
private fun DoneStep(ui: OnboardingUi, checks: List<SetupCheck>) {
    val allOk = checks.all { it.state == CheckState.OK }
    val accent = if (allOk) ZkGood else ZkWarn
    Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
        CompletionRing(accent, Modifier.size(132.dp))
        SetupAnimation(if (allOk) R.raw.setup_success else R.raw.setup_attention, loop = false, modifier = Modifier.size(104.dp))
    }
    StepTitle(
        stringResource(if (allOk) R.string.setup_done_ready_title else R.string.setup_done_partial_title),
        when {
            ui.applied != null -> ui.applyMessage?.asString() ?: stringResource(R.string.setup_done_applied, ui.applied.asString())
            allOk -> stringResource(R.string.setup_done_ready_body)
            else -> stringResource(R.string.setup_done_partial_body)
        },
        large = true,
    )
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            checks.forEach { c ->
                val l = look(c.state)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (l.icon == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(l.icon, null, Modifier.size(18.dp), tint = l.color)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.setup_check_summary_row, stringResource(c.id.title), stringResource(l.label)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
    val todo = checks.mapNotNull { it.fix }
    if (todo.isNotEmpty()) {
        Text(stringResource(R.string.setup_next_steps), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        todo.forEach {
            Text("• " + it.asString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/* ---------------- language sheet ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val current = remember { AppLocale.current(context) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.Language, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(stringResource(R.string.language_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.language_body), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(4.dp))
            AppLanguage.entries.forEach { lang ->
                val title = lang.endonym ?: stringResource(R.string.language_system)
                val subtitle = if (lang == AppLanguage.SYSTEM) {
                    val system = Resources.getSystem().configuration.locales[0]
                    stringResource(R.string.language_system_current, system.getDisplayName(system))
                } else null
                LanguageOption(title, subtitle, selected = lang == current) {
                    onDismiss()
                    if (lang != current) context.findActivity()?.let { AppLocale.apply(it, lang) }
                }
            }
        }
    }
}

@Composable
private fun LanguageOption(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(if (selected) 28.dp else 18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(if (selected) 28.dp else 18.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (selected) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/* ---------------- info sheet ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val info = remember { DeviceInfo.read(context) }
    fun size(bytes: Long) = Formatter.formatFileSize(context, bytes)
    val rows = listOf(
        Triple(Icons.Rounded.Info, stringResource(R.string.info_app_version),
            stringResource(R.string.info_app_version_value, info.appVersion, info.appBuild, info.buildType)),
        Triple(Icons.Rounded.PhoneAndroid, stringResource(R.string.info_device), info.deviceLine),
        Triple(Icons.Rounded.Android, stringResource(R.string.info_android),
            stringResource(R.string.info_android_value, info.androidRelease, info.sdk, info.securityPatch)),
        Triple(Icons.Rounded.DeveloperBoard, stringResource(R.string.info_soc),
            pluralStringResource(R.plurals.info_soc_value, info.cores, info.soc, info.cores, info.abis.firstOrNull().orEmpty())),
        Triple(Icons.Rounded.Memory, stringResource(R.string.info_ram),
            stringResource(R.string.info_ram_value, size(info.ramTotal), size(info.ramAvailable))),
        Triple(Icons.Rounded.Storage, stringResource(R.string.info_storage),
            stringResource(R.string.info_storage_value, size(info.storageTotal), size(info.storageFree))),
        Triple(Icons.Rounded.Terminal, stringResource(R.string.info_kernel), info.kernel),
    )
    var copied by remember { mutableStateOf(false) }
    val title = stringResource(R.string.info_title)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = {
                    val text = (listOf(title) + rows.map { "${it.second}: ${it.third}" }).joinToString("\n")
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(title, text))
                    copied = true
                }) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.info_copy))
                }
            }
            if (copied) {
                Text(stringResource(R.string.info_copied), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            rows.forEachIndexed { i, (icon, label, value) ->
                InfoRow(icon, label, value, accent = i % 3)
            }
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
        Row(
            Modifier.fillMaxWidth().padding(14.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(icon, container, content)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
