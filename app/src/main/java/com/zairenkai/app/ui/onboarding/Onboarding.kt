// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * First-run setup wizard: welcome, a live system check of every layer the
 * app depends on (root, ZKFC, zperfd, API token), an optional first profile
 * applied through zperfd, and a summary with what is left to do. Each step
 * has a Material 3 Expressive Lottie hero (res/raw/setup_*.json, coloured
 * from the active scheme); the header offers the app language and an
 * app/device info sheet on every step.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.ui.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Resources
import android.provider.Settings
import android.text.format.Formatter
import androidx.annotation.RawRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

@Composable
fun Onboarding(container: AppContainer, onDone: () -> Unit) {
    val factory = remember(container) { VmFactory(container) }
    val vm: OnboardingViewModel = viewModel(factory = factory)
    val ui by vm.ui.collectAsState()
    val checks = ui.checks
    val ready = checks.profilesReady()
    var showLanguage by rememberSaveable { mutableStateOf(false) }
    var showInfo by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            StepHeader(
                step = ui.step,
                onLanguage = { showLanguage = true },
                onInfo = { showInfo = true },
                onSkip = if (ui.step == SetupStep.WELCOME) ({ vm.finish(onDone) }) else null,
            )
        },
        bottomBar = {
            BottomActions(
                ui = ui,
                checksSettled = checks.settled(),
                profilesReady = ready,
                onBack = { vm.back() },
                onNext = { vm.next() },
                onApply = { ui.choice?.let { vm.applyChoice(Profiles.byId(it)) } },
                onFinish = { vm.finish(onDone) },
            )
        },
    ) { inner ->
        AnimatedContent(
            targetState = ui.step,
            transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { w -> dir * w / 3 } + fadeIn(tween(220)))
                    .togetherWith(slideOutHorizontally(tween(200)) { w -> -dir * w / 3 } + fadeOut(tween(160)))
            },
            modifier = Modifier.padding(inner).fillMaxSize(),
            label = "setupStep",
        ) { step ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (step) {
                    SetupStep.WELCOME -> WelcomeStep()
                    SetupStep.CHECK -> CheckStep(checks, onRecheck = vm::runChecks)
                    SetupStep.PROFILE -> ProfileStep(ui, checks, ready, onChoose = vm::choose)
                    SetupStep.DONE -> DoneStep(ui, checks)
                }
            }
        }
    }

    if (showLanguage) LanguageSheet(onDismiss = { showLanguage = false })
    if (showInfo) InfoSheet(onDismiss = { showInfo = false })
}

/* ---------------- frame ---------------- */

@Composable
private fun StepHeader(step: SetupStep, onLanguage: () -> Unit, onInfo: () -> Unit, onSkip: (() -> Unit)?) {
    val total = SetupStep.entries.size
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeaderButton(Icons.Rounded.Language, stringResource(R.string.setup_language), onLanguage)
            HeaderButton(Icons.Rounded.Info, stringResource(R.string.setup_info), onInfo)
            Spacer(Modifier.weight(1f))
            if (onSkip != null) TextButton(onClick = onSkip) { Text(stringResource(R.string.action_skip)) }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.setup_step_label, step.ordinal + 1, total, stringResource(step.title)),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SetupStep.entries.forEach { s ->
                // Expressive stepper: the current segment stretches, done ones stay filled.
                val weight by animateFloatAsState(
                    if (s == step) 2.4f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "segW",
                )
                val color by animateColorAsState(
                    if (s.ordinal <= step.ordinal) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                    label = "segC",
                )
                Box(Modifier.weight(weight).height(6.dp).clip(CircleShape).background(color))
            }
        }
    }
}

@Composable
private fun HeaderButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.size(48.dp),
    ) {
        Icon(icon, contentDescription = label)
    }
}

@Composable
private fun BottomActions(
    ui: OnboardingUi,
    checksSettled: Boolean,
    profilesReady: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onApply: () -> Unit,
    onFinish: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val showBack = ui.step != SetupStep.WELCOME && ui.step != SetupStep.DONE
        if (showBack) {
            OutlinedButton(onClick = onBack, enabled = !ui.applying, modifier = Modifier.weight(1f).height(56.dp)) {
                Text(stringResource(R.string.action_back))
            }
        }
        val primary = Modifier.weight(if (showBack) 1.6f else 1f).height(56.dp)
        when (ui.step) {
            SetupStep.WELCOME -> Button(onClick = onNext, modifier = primary) {
                Text(stringResource(R.string.setup_start), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null)
            }
            SetupStep.CHECK -> Button(onClick = onNext, enabled = checksSettled, modifier = primary) {
                Text(stringResource(if (checksSettled) R.string.action_next else R.string.setup_checking), fontWeight = FontWeight.SemiBold)
                if (checksSettled) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null)
                }
            }
            SetupStep.PROFILE -> {
                val choice = ui.choice
                if (profilesReady && choice != null) {
                    Button(onClick = onApply, enabled = !ui.applying, modifier = primary) {
                        if (ui.applying) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                stringResource(R.string.setup_apply_profile, stringResource(Profiles.byId(choice).nameRes)),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                } else {
                    Button(onClick = onNext, modifier = primary) {
                        Text(stringResource(R.string.setup_skip_for_now), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            SetupStep.DONE -> Button(onClick = onFinish, modifier = primary) {
                Text(stringResource(R.string.setup_enter_app), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Rounded.Check, null)
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/* ---------------- Lottie hero ---------------- */

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
private fun reduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

@Composable
private fun SetupAnimation(@RawRes res: Int, loop: Boolean, modifier: Modifier = Modifier) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(res))
    val still = reduceMotion()
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

@Composable
private fun Hero(@RawRes res: Int, loop: Boolean) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Crossfade(targetState = res to loop, animationSpec = tween(300), label = "hero") { (r, l) ->
            SetupAnimation(r, l, Modifier.size(200.dp))
        }
    }
}

/* ---------------- 1. welcome ---------------- */

@Composable
private fun WelcomeStep() {
    Hero(R.raw.setup_welcome, loop = true)
    StepTitle(stringResource(R.string.setup_welcome_title), stringResource(R.string.setup_welcome_body))
    Feature(Icons.Rounded.Timeline, R.string.setup_feature_monitor_title, R.string.setup_feature_monitor_body)
    Feature(Icons.Rounded.Tune, R.string.setup_feature_verified_title, R.string.setup_feature_verified_body)
    Feature(Icons.Rounded.Shield, R.string.setup_feature_safety_title, R.string.setup_feature_safety_body)
    Text(
        stringResource(R.string.setup_welcome_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Feature(icon: ImageVector, title: Int, body: Int) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
private fun CheckStep(checks: List<SetupCheck>, onRecheck: () -> Unit) {
    val settled = checks.settled()
    val ready = checks.profilesReady()
    Hero(
        when {
            !settled -> R.raw.setup_scan
            ready -> R.raw.setup_success
            else -> R.raw.setup_attention
        },
        loop = !settled,
    )
    StepTitle(stringResource(R.string.setup_check_title), stringResource(R.string.setup_check_body))
    checks.forEach { CheckRow(it) }
    if (settled) {
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
        )
        OutlinedButton(onClick = onRecheck) {
            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.setup_recheck))
        }
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

@Composable
private fun CheckRow(check: SetupCheck) {
    val l = look(check.state)
    val title = stringResource(check.id.title)
    val detail = check.detail.asString()
    val a11y = stringResource(R.string.setup_check_a11y, title, stringResource(l.label), detail)
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = a11y },
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(l.color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                if (l.icon == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(l.icon, null, Modifier.size(20.dp), tint = l.color)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                check.fix?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = l.color) }
            }
        }
    }
}

/* ---------------- 3. first profile ---------------- */

@Composable
private fun ProfileStep(ui: OnboardingUi, checks: List<SetupCheck>, ready: Boolean, onChoose: (ProfileId?) -> Unit) {
    Hero(R.raw.setup_tune, loop = true)
    StepTitle(stringResource(R.string.setup_profile_title), stringResource(R.string.setup_profile_body))
    if (!ready) {
        val blockers = checks.filterNot { it.allowsProfiles() }.map { stringResource(it.id.title) }
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
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
    Profiles.all.forEach { p ->
        ProfileOption(p, selected = ui.choice == p.id, enabled = ready && !ui.applying) {
            onChoose(if (ui.choice == p.id) null else p.id)
        }
    }
    if (ui.applyFailed) {
        ui.applyMessage?.let {
            Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
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
    val border by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, label = "profileBorder",
    )
    // Expressive: the selected option rounds further.
    val corner by animateFloatAsState(if (selected) 32f else 20f, spring(Spring.DampingRatioMediumBouncy), label = "corner")
    val shape = RoundedCornerShape(corner.dp)
    Surface(
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .border(2.dp, border, shape)
            .clip(shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            Icon(profileIcon(profile), null, Modifier.size(26.dp), tint = tint)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(profile.nameRes), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                Text(stringResource(profile.taglineRes), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
    }
}

/* ---------------- 4. done ---------------- */

@Composable
private fun DoneStep(ui: OnboardingUi, checks: List<SetupCheck>) {
    val allOk = checks.all { it.state == CheckState.OK }
    Hero(if (allOk) R.raw.setup_success else R.raw.setup_attention, loop = false)
    StepTitle(
        stringResource(if (allOk) R.string.setup_done_ready_title else R.string.setup_done_partial_title),
        when {
            ui.applied != null -> ui.applyMessage?.asString() ?: stringResource(R.string.setup_done_applied, ui.applied.asString())
            allOk -> stringResource(R.string.setup_done_ready_body)
            else -> stringResource(R.string.setup_done_partial_body)
        },
    )
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
