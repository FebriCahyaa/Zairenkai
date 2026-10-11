// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! zperfd — resident Zairenkai performance policy engine.
//!
//! Mutations are transactional, durable and kernel-license-gated. The daemon
//! owns desired/effective mode state; the Android app is a client.
//!
//! Copyright (C) 2026 FebriCahyaa

mod api;
mod backends;
mod catalog;
mod cgroup;
mod config;
mod engine;
mod family;
mod fas;
mod fingerprint;
mod frame;
mod interaction;
mod inventory;
mod memory;
mod nodes;
mod optimize;
mod platform;
mod properties;
mod scene;
mod scene_engine;
mod state;
mod storage;
mod task_controller;
mod telemetry;
#[cfg(test)]
mod test_support;
mod thermal;
mod topo;
mod tweak;
mod workload;

use backends::BackendPlan;
use config::Profile;
use engine::Engine;
use nodes::Sysroot;
use state::StateStore;
use std::fs::{self, File, OpenOptions};
use std::hash::{Hash, Hasher};
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};
use topo::Topology;
use zairenkai_core::authority::authorize;
use zairenkai_core::capability::CapabilitySet;
use zairenkai_core::manifest::CoreManifest;
use zairenkai_core::operation::Operation;
use zkfc_sys::Zkfc;

const GENERIC: &str = include_str!("../profiles/generic.toml");
const LAVENDER: &str = include_str!("../profiles/lavender.toml");
const CORE_MANIFEST: &str = include_str!("../../../core/manifest.toml");
const SAFE_MODE: &str = "/data/adb/zkfc/safe_mode";

static STOP: AtomicBool = AtomicBool::new(false);

extern "C" fn on_signal(_sig: libc::c_int) {
    STOP.store(true, Ordering::SeqCst);
}

struct Args {
    cmd: String,
    positional: Vec<String>,
    root: String,
    profile: Option<String>,
    state: String,
    interval_ms: u64,
    lite: bool,
    json: bool,
}

fn parse_args() -> Args {
    let mut args = Args {
        cmd: String::new(),
        positional: Vec::new(),
        root: "/".into(),
        profile: None,
        state: "/data/adb/zperf".into(),
        interval_ms: 500,
        lite: false,
        json: false,
    };
    let mut it = std::env::args().skip(1);
    while let Some(arg) = it.next() {
        match arg.as_str() {
            "--root" => args.root = it.next().unwrap_or_else(|| "/".into()),
            "--profile" => args.profile = it.next(),
            "--state" => args.state = it.next().unwrap_or_else(|| args.state.clone()),
            "--interval" => {
                args.interval_ms = it.next().and_then(|v| v.parse().ok()).unwrap_or(1500)
            }
            "--lite" => args.lite = true,
            "--json" => args.json = true,
            "-h" | "--help" => {
                print_help();
                std::process::exit(0);
            }
            _ if args.cmd.is_empty() => args.cmd = arg,
            _ => args.positional.push(arg),
        }
    }
    args
}

fn print_help() {
    eprintln!(
        "zperfd — Zairenkai performance engine\n\n\
  probe [--json]\n\
  modes\n\
  tweak list|get <id>|set <id> <value>\n\
  apply <mode>\n\
  set <mode|auto>\n\
  reset\n\
  status [--json]\n\
  core [--json]\n\
  adaptive [--json]\n\
  daemon"
    );
}

fn load_profile(
    explicit: Option<&str>,
    state: &Path,
    identity: &platform::PlatformIdentity,
) -> Result<Profile, String> {
    let text = if let Some(path) = explicit {
        fs::read_to_string(path).map_err(|e| format!("read {path}: {e}"))?
    } else if state.join("profile.toml").exists() {
        fs::read_to_string(state.join("profile.toml")).map_err(|e| e.to_string())?
    } else if let Some(resolved) = catalog::resolve(&state.join("database"), identity) {
        let path = resolved.catalog_path(&state.join("catalog"));
        if path.exists() {
            fs::read_to_string(path).map_err(|e| e.to_string())?
        } else {
            GENERIC.into()
        }
    } else {
        let soc = identity.soc_key();
        let catalog = state.join("catalog").join(format!("{soc}.toml"));
        if catalog.exists() {
            fs::read_to_string(catalog).map_err(|e| e.to_string())?
        } else if soc.contains("660") {
            LAVENDER.into()
        } else {
            GENERIC.into()
        }
    };
    let profile = Profile::parse(&text)?;
    profile.validate()?;
    Ok(profile)
}

fn json_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    for ch in s.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out
}

fn core_authorize(operation: Operation) -> Result<(), String> {
    let auth = authorize(&CapabilitySet::full_runtime(), operation);
    match auth.decision {
        zairenkai_core::authority::Decision::Allow => Ok(()),
        _ => Err(format!(
            "core authority denied {:?}: {}",
            operation, auth.reason
        )),
    }
}

fn core_manifest() -> Result<CoreManifest, String> {
    CoreManifest::parse(CORE_MANIFEST)
}

fn kernel_license_ok() -> Result<(), String> {
    kernel_gate().map_err(|(_, message)| message)
}

/// Kernel gate with a failure class, so clients can tell a missing or
/// incompatible framework apart from a framework that is present but not
/// authorized by a valid API token.
fn kernel_gate() -> Result<(), (&'static str, String)> {
    let z =
        Zkfc::open().map_err(|e| ("framework_unavailable", format!("ZKFC unavailable: {e}")))?;
    let version = z.version().map_err(|e| {
        (
            "framework_unavailable",
            format!("ZKFC version query failed: {e}"),
        )
    })?;
    if !version.api_compatible() {
        return Err((
            "framework_incompatible",
            format!(
                "incompatible ZKFC API {}.{}.{} (min supported {}.{}.{})",
                version.api_major(),
                version.api_minor(),
                version.api_patch(),
                (version.api_min_supported >> 16) & 0xff,
                (version.api_min_supported >> 8) & 0xff,
                version.api_min_supported & 0xff,
            ),
        ));
    }
    let status = z.license_state().map_err(|e| {
        (
            "framework_unavailable",
            format!("ZKFC license query failed: {e}"),
        )
    })?;
    if status != 1 {
        return Err((
            "license",
            format!("ZKFC license state {status}; performance mutation locked"),
        ));
    }
    Ok(())
}

fn all_managed_nodes(s: &Sysroot, t: &Topology, engine: &Engine<'_>) -> Vec<String> {
    let mut nodes = engine.managed_nodes();
    nodes.extend(tweak::managed_nodes(s, t));
    nodes.sort();
    nodes.dedup();
    nodes
}

/// Cheap non-cryptographic fingerprint of the live nodes controlled by zperfd.
/// It is a drift detector, not a security primitive: the transaction journal
/// remains authoritative for rollback and the kernel remains authoritative for
/// licensed operations.
fn runtime_fingerprint(s: &Sysroot, nodes: &[String]) -> u64 {
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    for node in nodes {
        node.hash(&mut hasher);
        match (s.exists(node), s.read(node)) {
            (false, _) => 0u8.hash(&mut hasher),
            (true, Some(value)) => {
                1u8.hash(&mut hasher);
                value.hash(&mut hasher);
            }
            (true, None) => 2u8.hash(&mut hasher),
        }
    }
    hasher.finish()
}

fn requested_mode(profile: &Profile, sysroot: &Sysroot, requested: &str) -> Result<String, String> {
    let mode = if requested == "auto" {
        scene::resolve_auto(sysroot)
    } else {
        requested
    };
    if !profile.mode.contains_key(mode) {
        return Err(format!("unknown mode '{mode}'"));
    }
    Ok(mode.to_string())
}

pub(crate) fn permission_names() -> String {
    const NAMES: &[&str] = &[
        "read.device",
        "read.kernel",
        "read.thermal",
        "read.performance",
        "read.logs",
        "read.inventory",
        "read.storage",
        "read.network",
        "read.memory",
        "read.security",
        "read.properties",
        "apply.profile",
        "tune.cpu",
        "tune.gpu",
        "tune.memory",
        "tune.io",
        "tune.power",
        "tune.thermal",
        "tune.storage",
        "tune.network",
        "tune.zram",
        "control.reset",
        "security.license",
        "security.policy",
        "control.hooks",
        "manage.device_registry",
        "manage.data_sources",
        "manage.evidence",
        "manage.recovery",
    ];
    NAMES.join(",")
}

fn cmd_core_permissions(json: bool) -> i32 {
    const OPS: &[(Operation, &str)] = &[
        (Operation::Probe, "probe"),
        (Operation::ReadStatus, "read.status"),
        (Operation::ReadPerformance, "read.performance"),
        (Operation::ReadThermal, "read.thermal"),
        (Operation::ReadLogs, "read.logs"),
        (Operation::ReadInventory, "read.inventory"),
        (Operation::ReadStorage, "read.storage"),
        (Operation::ReadNetwork, "read.network"),
        (Operation::ReadMemory, "read.memory"),
        (Operation::ReadSecurity, "read.security"),
        (Operation::ReadProperties, "read.properties"),
        (Operation::ApplyProfile, "apply.profile"),
        (Operation::SetCpuTweak, "tune.cpu"),
        (Operation::SetGpuTweak, "tune.gpu"),
        (Operation::SetMemoryTweak, "tune.memory"),
        (Operation::SetIoTweak, "tune.io"),
        (Operation::SetPowerTweak, "tune.power"),
        (Operation::SetThermalPolicy, "tune.thermal"),
        (Operation::SetProperty, "tune.properties"),
        (Operation::TuneStorage, "tune.storage"),
        (Operation::TuneNetwork, "tune.network"),
        (Operation::TuneZram, "tune.zram"),
        (Operation::ResetRuntime, "control.reset"),
        (Operation::InstallLicense, "security.license"),
        (Operation::ModifyPolicy, "security.policy"),
        (Operation::ManageHooks, "control.hooks"),
        (Operation::ManageDeviceRegistry, "manage.device_registry"),
        (Operation::ManageDataSources, "manage.data_sources"),
        (Operation::ManageEvidence, "manage.evidence"),
        (Operation::ManageRecovery, "manage.recovery"),
    ];
    if json {
        let entries = OPS.iter().map(|(op, name)| {
            let auth = authorize(&CapabilitySet::full_runtime(), *op);
            format!("{{\"operation\":\"{}\",\"capability\":\"{:?}\",\"risk\":{},\"decision\":\"{:?}\"}}",
                name, auth.required, auth.risk_weight, auth.decision)
        }).collect::<Vec<_>>().join(",");
        println!(
            "{{\"core_id\":\"{}\",\"permissions\":[{}]}}",
            zairenkai_core::CORE_ID,
            entries
        );
        return 0;
    }
    println!("{} permissions:", zairenkai_core::CORE_NAME);
    for (op, name) in OPS {
        println!(
            "  {:<18} -> {:?} (risk={})",
            name,
            op.required_capability(),
            op.risk_weight()
        );
    }
    println!(
        "  runtime principal capabilities: {}",
        CapabilitySet::full_runtime().iter().count()
    );
    0
}

fn cmd_core(json: bool) -> i32 {
    match core_manifest() {
        Ok(m) if json => {
            println!("{{\"id\":\"{}\",\"product\":\"{}\",\"core_api\":{},\"architectures\":[{}],\"kernel_models\":[{}],\"kernel_generations\":[{}]}}",
                json_escape(&m.id), json_escape(&m.product), m.core_api,
                m.supported_architectures.iter().map(|v| format!("\"{}\"", json_escape(v))).collect::<Vec<_>>().join(","),
                m.supported_kernel_models.iter().map(|v| format!("\"{}\"", json_escape(v))).collect::<Vec<_>>().join(","),
                m.supported_kernel_generations.iter().map(|v| format!("\"{}\"", json_escape(v))).collect::<Vec<_>>().join(","));
            0
        }
        Ok(m) => {
            println!(
                "{} {} api={} arch={} kernel-models={} generations={}",
                m.product,
                m.id,
                m.core_api,
                m.supported_architectures.len(),
                m.supported_kernel_models.len(),
                m.supported_kernel_generations.len()
            );
            0
        }
        Err(e) => {
            eprintln!("core: {e}");
            1
        }
    }
}

fn cmd_probe(s: &Sysroot, state: &Path, json: bool) -> i32 {
    if let Err(e) = core_authorize(Operation::Probe) {
        eprintln!("{e}");
        return 1;
    }
    let topology = Topology::detect(s);
    let family = family::load(&state.join("database"), topology.identity.vendor);
    let atlas = catalog::resolve(&state.join("database"), &topology.identity);
    let plan = BackendPlan::resolve(&topology.identity, &topology, family.as_ref());
    if json {
        let policies = topology
            .policies
            .iter()
            .map(|p| {
                format!(
                    "{{\"name\":\"{}\",\"min_hw\":{},\"max_hw\":{},\"opps\":{},\"min_opp\":{},\"max_opp\":{}}}",
                    json_escape(&p.name),
                    p.min_hw,
                    p.max_hw,
                    p.avail.len(),
                    p.avail.first().copied().unwrap_or(0),
                    p.avail.last().copied().unwrap_or(0)
                )
            })
            .collect::<Vec<_>>();
        let thermal = topology
            .thermal_zones
            .iter()
            .map(|z| {
                format!(
                    "{{\"name\":\"{}\",\"type\":\"{}\",\"provider\":\"{}\",\"temp_mdeg\":{}}}",
                    json_escape(&z.name),
                    json_escape(&z.ty),
                    z.provider.as_str(),
                    z.temp_mdeg
                        .map(|v| v.to_string())
                        .unwrap_or_else(|| "null".into())
                )
            })
            .collect::<Vec<_>>()
            .join(",");
        let boost = format!(
            "{{\"uclamp\":{},\"schedtune\":{},\"cpu_boost\":{}}}",
            topology.top_app_uclamp.is_some(),
            topology.stune_top.is_some(),
            topology.cpu_boost_dir.is_some(),
        );
        let gpu = topology
            .gpu
            .as_ref()
            .map(|g| {
                format!(
                    "{{\"provider\":\"{}\",\"opps\":{},\"min\":{},\"max\":{}}}",
                    g.provider.as_str(),
                    g.avail.len(),
                    g.avail.first().copied().unwrap_or(0),
                    g.avail.last().copied().unwrap_or(0),
                )
            })
            .unwrap_or_else(|| "null".into());
        let kernel_caps = Zkfc::open().ok().and_then(|z| z.capabilities().ok()).map(|c| format!(
            "{{\"kernel_caps\":{},\"runtime_caps\":{},\"kernel\":\"{}.{}.{}\",\"page_size\":{},\"cpu_count\":{}}}",
            c.kernel_caps(), c.runtime_caps(), c.kernel_major, c.kernel_minor, c.kernel_patch, c.page_size, c.cpu_count
        )).unwrap_or_else(|| "null".into());
        let telemetry = telemetry::json(s, &topology);
        let vendor_surfaces = plan
            .vendor_surfaces
            .iter()
            .map(|x| format!("\"{}\"", x))
            .collect::<Vec<_>>()
            .join(",");
        println!(
            "{{\"ok\":true,\"vendor\":\"{}\",\"soc\":\"{}\",\"flavor\":\"{}\",\"kernel_generation\":\"{}\",\"release\":\"{}\",\"cgroup_v2\":{},\"has_msm_perf\":{},\"boost\":{},\"atlas\":{{\"known\":{},\"device_id\":{}}},\"backend\":{{\"cpu\":{},\"boost\":\"{:?}\",\"gpu\":\"{:?}\",\"thermal_providers\":[{}],\"vendor_surfaces\":[{}],\"family_profile\":{}}},\"policies\":[{}],\"gpu\":{},\"thermal\":[{}],\"kernel_capabilities\":{},\"telemetry\":{}}}",
            topology.identity.vendor.as_str(),
            json_escape(&topology.identity.soc_key()),
            topology.flavor(),
            json_escape(&topology.kernel_generation),
            json_escape(&topology.release),
            topology.cgroup_v2,
            topology.has_msm_perf,
            boost,
            atlas.is_some(),
            atlas.as_ref().map(|r| format!("\"{}\"", json_escape(&r.entry.id))).unwrap_or_else(|| "null".into()),
            plan.cpu.is_some(),
            plan.boost,
            plan.gpu,
            plan.thermal.iter().map(|x| format!("\"{}\"", x.as_str())).collect::<Vec<_>>().join(","),
            vendor_surfaces,
            family.as_ref().map(|f| format!("\"{}\"", json_escape(&f.vendor))).unwrap_or_else(|| "null".into()),
            policies.join(","),
            gpu,
            thermal,
            kernel_caps,
            telemetry
        );
    } else {
        println!(
            "vendor      : {}\nsoc         : {}\nflavor      : {}\nkernel gen  : {}\nrelease     : {}\ncgroup v2   : {}\nmsm_perf    : {}\ngpu         : {}\nthermal     : {} zones\nAtlas       : {}\nfam policy  : {}\nbackend     : boost={:?}",
            topology.identity.vendor.as_str(),
            topology.identity.soc_key(),
            topology.flavor(),
            topology.kernel_generation,
            topology.release,
            topology.cgroup_v2,
            topology.has_msm_perf,
            topology.gpu.as_ref().map(|g| g.provider.as_str()).unwrap_or("none"),
            topology.thermal_zones.len(),
            atlas.as_ref().map(|r| r.entry.id.as_str()).unwrap_or("unknown"),
            plan.family_profile.as_deref().unwrap_or("none"),
            plan.boost
        );
        for p in &topology.policies {
            println!(
                "{:<9}: {} OPPs {}..{} kHz",
                p.name,
                p.avail.len(),
                p.avail.first().copied().unwrap_or(0),
                p.avail.last().copied().unwrap_or(0)
            );
        }
    }
    0
}

fn configure_kernel_thermal_guard(args: &Args, snapshot: &thermal::Snapshot) -> Result<(), String> {
    let zones = thermal::guard_zones(snapshot);
    if zones.is_empty() {
        return Err("no thermal control zones available; refusing performance mutation".into());
    }
    let z = Zkfc::open().map_err(|e| format!("ZKFC unavailable for thermal guard: {e}"))?;
    let caps = z
        .capabilities()
        .map_err(|e| format!("ZKFC capability query failed: {e}"))?;
    if caps.kernel_caps() & zkfc_sys::ZKFC_CAP_TUNE_THERMAL == 0 {
        return Err("kernel does not advertise ZKFC thermal capability".into());
    }
    let interval_ms = args.interval_ms.clamp(250, 5000) as u32;
    let limit = thermal::kernel_guard_limit(snapshot).ok_or_else(|| {
        "runtime thermal trip point is unavailable; kernel guard cannot be armed".to_string()
    })?;
    let release = thermal::kernel_guard_release(snapshot).ok_or_else(|| {
        "runtime thermal release point is unavailable; kernel guard cannot be armed".to_string()
    })?;
    z.thermal_guard_config(interval_ms, limit, release, &zones)
        .map_err(|e| format!("ZKFC thermal guard configuration failed: {e}"))
}

fn configure_kernel_input_boost(
    topology: &Topology,
    mode: Option<&config::Mode>,
) -> Result<(), String> {
    let Some(z) = Zkfc::open().ok() else {
        return Ok(());
    };
    // The legacy cpu_boost sysfs path already owns the vendor input boost. Do
    // not double-boost it, but explicitly disable any previous ZKFC fast path.
    if topology.cpu_boost_dir.is_some() {
        return z
            .input_boost(10, &[])
            .map_err(|e| format!("disable ZKFC input boost: {e}"));
    }
    let Some(mode) = mode else {
        return z
            .input_boost(10, &[])
            .map_err(|e| format!("disable ZKFC input boost: {e}"));
    };
    let Some(pct) = mode.cpu.input_boost_pct.filter(|v| *v > 0) else {
        return z
            .input_boost(10, &[])
            .map_err(|e| format!("disable ZKFC input boost: {e}"));
    };
    let duration = mode.cpu.input_boost_ms.unwrap_or(0);
    if duration == 0 {
        return z
            .input_boost(10, &[])
            .map_err(|e| format!("disable ZKFC input boost: {e}"));
    }
    let mut clusters = Vec::with_capacity(topology.policies.len().min(8));
    for policy in topology
        .policies
        .iter()
        .filter(|p| !p.cpus.is_empty())
        .take(8)
    {
        if policy.max_hw == 0 || policy.max_hw < policy.min_hw {
            continue;
        }
        let span = policy.max_hw.saturating_sub(policy.min_hw);
        let floor = policy
            .min_hw
            .saturating_add(span.saturating_mul(pct.min(100) as u64) / 100)
            .min(policy.max_hw)
            .min(u32::MAX as u64) as u32;
        clusters.push((policy.cpus[0] as u32, floor));
    }
    if clusters.is_empty() {
        return z
            .input_boost(10, &[])
            .map_err(|e| format!("disable ZKFC input boost: {e}"));
    }
    z.input_boost(duration.clamp(10, 5000), &clusters)
        .map_err(|e| format!("configure ZKFC input boost: {e}"))
}

fn apply_transaction(args: &Args, requested: &str) -> i32 {
    if let Err(e) = core_authorize(Operation::ApplyProfile) {
        eprintln!("{e}");
        return 1;
    }
    if let Err(e) = kernel_license_ok() {
        eprintln!("apply blocked: {e}");
        return 4;
    }

    let s = Sysroot::new(&args.root);
    let topology = Topology::detect(&s);
    let thermal_snapshot = thermal::snapshot(&s, &topology);
    let family = family::load(
        &Path::new(&args.state).join("database"),
        topology.identity.vendor,
    );
    let plan = BackendPlan::resolve(&topology.identity, &topology, family.as_ref());
    let engine = Engine::new(&s, &topology, &plan);
    let nodes = all_managed_nodes(&s, &topology, &engine);
    let mut state = StateStore::new(&args.state);

    if let Err(e) = state.ensure_boot(&s, &nodes) {
        eprintln!("state init failed: {e}");
        return 3;
    }
    if let Err(e) = state.recover(&s) {
        eprintln!("state recovery failed: {e}");
        return 3;
    }
    let sentinel = sentinel_evaluate(args, Operation::ApplyProfile);
    if !sentinel.allowed {
        eprintln!("apply blocked by Sentinel: {:?}", sentinel.reasons);
        return 4;
    }

    let profile = match load_profile(
        args.profile.as_deref(),
        Path::new(&args.state),
        &topology.identity,
    ) {
        Ok(p) => p,
        Err(e) => {
            eprintln!("profile error: {e}");
            return 2;
        }
    };
    let effective = match requested_mode(&profile, &s, requested) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("{e}");
            return 2;
        }
    };

    if let Err(e) = state.begin(&s, &nodes) {
        eprintln!("transaction begin failed: {e}");
        return 3;
    }
    if thermal_snapshot.telemetry_complete {
        if let Err(e) = configure_kernel_thermal_guard(args, &thermal_snapshot) {
            let _ = state.rollback(&s);
            eprintln!("apply blocked: {e}");
            return 4;
        }
    } else {
        eprintln!("thermal guard: runtime trip topology incomplete; keeping performance envelope limited and refusing new guard configuration");
    }
    let Some(mode) = profile.mode(&effective) else {
        let _ = state.rollback(&s);
        eprintln!("apply failed: validated profile is missing mode '{effective}'");
        return 3;
    };
    let therm = thermal::envelope(&thermal_snapshot);
    let constrained = thermal::constrain_mode(mode, therm);
    eprintln!(
        "thermal envelope: band={:?} boost={}‰ cap={:?} reason={}",
        therm.band, therm.boost_permille, therm.max_perf_cap_pct, therm.reason
    );
    let report = engine.apply_mode(&constrained);
    if !report.failed.is_empty() || report.applied.is_empty() {
        let rollback = state.rollback(&s);
        if let Err(e) = rollback {
            eprintln!("rollback failed: {e}");
        }
        eprintln!(
            "apply failed: {} failed, {} applied, {} skipped",
            report.failed.len(),
            report.applied.len(),
            report.skipped.len()
        );
        for failure in report.failed {
            eprintln!("  ! {failure}");
        }
        return 3;
    }

    if let Err(e) = configure_kernel_input_boost(&topology, Some(mode)) {
        eprintln!("warning: {e}");
    }

    if let Err(e) = state.commit(Some(requested), Some(&effective)) {
        // Commit can fail after the durable commit marker. Recovery completes
        // that publication when possible, or rolls back an uncommitted txn.
        let recovery = state.recover(&s);
        if let Err(recovery_error) = recovery {
            eprintln!("state commit failed: {e}; recovery failed: {recovery_error}");
        } else {
            eprintln!("state commit failed: {e}; recovery attempted");
        }
        return 3;
    }
    // Record where this choice was validated. If this write fails the next
    // boot simply holds the baseline until the user confirms again.
    if let Err(e) = state.set_mode_fingerprint(&fingerprint::compute(&s, &topology.identity)) {
        eprintln!(
            "warning: mode fingerprint not saved ({e}); confirmation will be required after reboot"
        );
    }

    println!(
        "mode={requested} effective={effective} applied={} skipped={}",
        report.applied.len(),
        report.skipped.len()
    );
    for item in report.applied {
        println!("  + {item}");
    }
    for item in report.skipped {
        println!("  - {item}");
    }
    0
}

fn cmd_tweak(args: &Args) -> i32 {
    let sub = args
        .positional
        .first()
        .map(String::as_str)
        .unwrap_or("list");
    let s = Sysroot::new(&args.root);
    let topology = Topology::detect(&s);

    match sub {
        "list" => {
            let specs = tweak::specs().filter(|spec| !(args.lite && spec.lite));
            if args.json {
                let entries = specs
                    .map(|spec| tweak_descriptor_json(&tweak::describe(spec, &s, &topology)))
                    .collect::<Vec<_>>()
                    .join(",");
                println!(
                    "{{\"ok\":true,\"source\":\"zperfd\",\"lite_mode\":{},\"tweaks\":[{}]}}",
                    args.lite, entries
                );
                return 0;
            }
            for spec in specs {
                let value = tweak::read(spec.id, &s, &topology).unwrap_or_else(|| "".into());
                println!("{}\t{}\t{}\t{}", spec.id, spec.category, spec.title, value);
            }
            0
        }
        "get" => {
            let Some(id) = args.positional.get(1) else {
                eprintln!("usage: tweak get <id>");
                return 2;
            };
            if tweak::find(id).is_none() {
                eprintln!("unknown tweak '{id}'");
                return 2;
            }
            match tweak::read(id, &s, &topology) {
                Some(value) => println!("{value}"),
                None => {
                    eprintln!("tweak '{id}' unavailable");
                    return 3;
                }
            }
            0
        }
        "set" => {
            let (Some(id), Some(value)) = (args.positional.get(1), args.positional.get(2)) else {
                return tweak_fail(2, "usage", "", "usage: tweak set <id> <value>");
            };
            if let Err(e) = core_authorize(tweak::operation_for(id)) {
                return tweak_fail(1, "unauthorized", id, &e.to_string());
            }
            if let Err((kind, e)) = kernel_gate() {
                return tweak_fail(4, kind, id, &format!("tweak blocked: {e}"));
            }
            let Some(spec) = tweak::find(id) else {
                return tweak_fail(2, "unknown", id, &format!("unknown tweak '{id}'"));
            };
            if args.lite && spec.lite {
                return tweak_fail(
                    2,
                    "lite",
                    id,
                    &format!("tweak '{id}' is disabled in lite mode"),
                );
            }

            let family = family::load(
                &Path::new(&args.state).join("database"),
                topology.identity.vendor,
            );
            let plan = BackendPlan::resolve(&topology.identity, &topology, family.as_ref());
            let engine = Engine::new(&s, &topology, &plan);
            let mut nodes = all_managed_nodes(&s, &topology, &engine);
            nodes.extend(tweak::managed_nodes(&s, &topology));
            nodes.sort();
            nodes.dedup();
            let mut state = StateStore::new(&args.state);
            if let Err(e) = state
                .ensure_boot(&s, &nodes)
                .and_then(|_| state.recover(&s))
            {
                return tweak_fail(3, "state", id, &format!("state recovery failed: {e}"));
            }
            let sentinel = sentinel_evaluate(args, tweak::operation_for(id));
            if !sentinel.allowed {
                return tweak_fail(
                    4,
                    "sentinel",
                    id,
                    &format!("tweak blocked by Sentinel: {:?}", sentinel.reasons),
                );
            }
            if let Err(e) = state.begin(&s, &nodes) {
                return tweak_fail(3, "state", id, &format!("transaction begin failed: {e}"));
            }
            // Success requires the write to be observable on every target
            // before the transaction commits (CORE_INVARIANTS: State).
            let outcome = tweak::apply(id, value, &s, &topology)
                .map_err(|e| ("rejected", format!("tweak failed: {e}")))
                .and_then(|applied| {
                    tweak::verify(id, &applied, &s, &topology)
                        .map(|()| applied)
                        .map_err(|e| ("not_observed", format!("write not observable: {e}")))
                });
            match outcome {
                Ok(applied) => match state.commit(None, None) {
                    Ok(()) => {
                        println!(
                            "{{\"ok\":true,\"id\":\"{}\",\"value\":\"{}\",\"targets\":{}}}",
                            json_escape(id),
                            json_escape(&applied.value),
                            applied.targets
                        );
                        0
                    }
                    Err(e) => {
                        let _ = state.recover(&s);
                        tweak_fail(3, "state", id, &format!("state commit failed: {e}"))
                    }
                },
                Err((kind, message)) => match state.rollback(&s) {
                    Ok(()) => tweak_fail(3, kind, id, &message),
                    // The journal stays in place; the next recovery pass (or
                    // SAFE MODE) restores the snapshot.
                    Err(rollback) => tweak_fail(
                        3,
                        "rollback_failed",
                        id,
                        &format!("{message}; rollback failed: {rollback}"),
                    ),
                },
            }
        }
        _ => {
            eprintln!("usage: tweak list|get <id>|set <id> <value>");
            2
        }
    }
}

/// Report a `tweak set` failure on stdout as JSON (the success line is JSON
/// too) and on stderr for logs. `kind` lets clients tell an unavailable
/// engine, a policy/authorization block and an invalid value apart.
fn tweak_fail(code: i32, kind: &str, id: &str, message: &str) -> i32 {
    println!(
        "{{\"ok\":false,\"id\":\"{}\",\"kind\":\"{}\",\"error\":\"{}\"}}",
        json_escape(id),
        json_escape(kind),
        json_escape(message)
    );
    eprintln!("{message}");
    code
}

fn tweak_descriptor_json(d: &tweak::Descriptor) -> String {
    let opt = |v: Option<&str>| {
        v.map(|v| format!("\"{}\"", json_escape(v)))
            .unwrap_or_else(|| "null".into())
    };
    let (kind, min, max, choices) = match &d.constraint {
        tweak::Constraint::Range { min, max } => {
            ("range", min.to_string(), max.to_string(), String::new())
        }
        tweak::Constraint::Choice(list) => (
            "choice",
            "null".into(),
            "null".into(),
            list.iter()
                .map(|c| format!("\"{}\"", json_escape(c)))
                .collect::<Vec<_>>()
                .join(","),
        ),
        tweak::Constraint::Text => ("text", "null".into(), "null".into(), String::new()),
    };
    format!(
        "{{\"id\":\"{}\",\"title\":\"{}\",\"category\":\"{}\",\"available\":{},\"lite_hidden\":{},\"kind\":\"{}\",\"min\":{},\"max\":{},\"choices\":[{}],\"value\":{},\"locked_reason\":{}}}",
        json_escape(d.spec.id),
        json_escape(d.spec.title),
        json_escape(d.spec.category),
        d.available,
        d.spec.lite,
        kind,
        min,
        max,
        choices,
        opt(d.value.as_deref()),
        opt(d.locked),
    )
}

fn cmd_inventory(args: &Args) -> i32 {
    if let Err(e) = core_authorize(Operation::ReadInventory) {
        eprintln!("{e}");
        return 1;
    }
    let s = Sysroot::new(&args.root);
    let identity = platform::PlatformIdentity::detect(&s);
    let inv = inventory::collect(&s, identity.clone());
    if args.json {
        println!("{}", inventory::json(&inv));
    } else {
        println!(
            "device      : {} {}",
            identity.vendor.as_str(),
            identity.platform
        );
        println!(
            "memory      : {:?} KiB available={:?}",
            inv.memory.total_kb, inv.memory.available_kb
        );
        println!("zram        : {} device(s)", inv.memory.zram.len());
        println!("storage     : {} device(s)", inv.storage.len());
        println!("network     : {} interface(s)", inv.network.interface_count);
        println!("SELinux     : {:?}", inv.security.selinux_enforcing);
        println!("verifiedboot: {:?}", inv.security.verified_boot);
    }
    0
}

fn cmd_thermal(args: &Args) -> i32 {
    if let Err(e) = core_authorize(Operation::ReadThermal) {
        eprintln!("{e}");
        return 1;
    }
    let s = Sysroot::new(&args.root);
    let topology = Topology::detect(&s);
    let snap = thermal::snapshot(&s, &topology);
    let env = thermal::envelope(&snap);
    if args.json {
        println!(
            "{{\"band\":\"{:?}\",\"boost_permille\":{},\"max_perf_cap_pct\":{},\"hottest_mdeg\":{},\"control_temp_mdeg\":{},\"performance_trip_mdeg\":{},\"critical_trip_mdeg\":{},\"control_zone\":{},\"release_mdeg\":{},\"headroom_mdeg\":{},\"headroom_permille\":{},\"critical_reached\":{},\"telemetry_complete\":{},\"battery_pct\":{},\"external_power\":{},\"reason\":\"{}\"}}",
            env.band,
            env.boost_permille,
            env.max_perf_cap_pct.map(|v| v.to_string()).unwrap_or_else(|| "null".into()),
            snap.hottest_mdeg.map(|v| v.to_string()).unwrap_or_else(|| "null".into()),
            snap.control_temp_mdeg.map(|v| v.to_string()).unwrap_or_else(|| "null".into()),
            snap.performance_trip_mdeg.map(|v| v.to_string()).unwrap_or_else(|| "null".into()),
            snap.critical_trip_mdeg.map(|v| v.to_string()).unwrap_or_else(|| "null".into()),
            snap.control_zone.as_deref().map(|v| format!("\"{}\"", json_escape(v))).unwrap_or_else(|| "null".into()),
            snap.release_mdeg.map(|v| v.to_string()).unwrap_or_else(|| "null".into()),
            snap.headroom_mdeg.map(|v| v.to_string()).unwrap_or_else(|| "null".into()),
            snap.headroom_permille.map(|v| v.to_string()).unwrap_or_else(|| "null".into()),
            snap.critical_reached,
            snap.telemetry_complete,
            snap.battery_pct.map(|v| v.to_string()).unwrap_or_else(|| "null".into()),
            snap.external_power,
            json_escape(env.reason),
        );
    } else {
        println!("band            : {:?}\nboost budget    : {}‰\nmax perf cap    : {}\nhottest         : {:?} mC\ncontrol temp    : {:?} mC\nperformance tp  : {:?} mC\ncritical tp     : {:?} mC\ncontrol zone    : {:?}\nrelease trip    : {:?} mC\nheadroom        : {:?} mC\nheadroom        : {:?}‰\ncritical reached: {}\ntelemetry complete: {}\nbattery         : {:?}%\nexternal power  : {}\nreason          : {}",
            env.band, env.boost_permille,
            env.max_perf_cap_pct.map(|v| format!("{v}%")).unwrap_or_else(|| "none".into()),
            snap.hottest_mdeg, snap.control_temp_mdeg, snap.performance_trip_mdeg,
            snap.critical_trip_mdeg, snap.control_zone, snap.release_mdeg, snap.headroom_mdeg, snap.headroom_permille,
            snap.critical_reached, snap.telemetry_complete, snap.battery_pct, snap.external_power, env.reason);
    }
    0
}

fn cmd_prop(args: &Args) -> i32 {
    match args
        .positional
        .first()
        .map(String::as_str)
        .unwrap_or("list")
    {
        "list" => {
            if let Err(e) = core_authorize(Operation::ReadProperties) {
                eprintln!("{e}");
                return 1;
            }
            for spec in properties::list() {
                println!("{}\t{:?}\t{}", spec.key, spec.scope, spec.description);
            }
            0
        }
        "get" => {
            if let Err(e) = core_authorize(Operation::ReadProperties) {
                eprintln!("{e}");
                return 1;
            }
            let Some(key) = args.positional.get(1) else {
                eprintln!("usage: prop get <key>");
                return 2;
            };
            match properties::get(key) {
                Ok(v) => {
                    println!("{v}");
                    0
                }
                Err(e) => {
                    eprintln!("property read failed: {e}");
                    3
                }
            }
        }
        "set" => {
            if let Err(e) = core_authorize(Operation::SetProperty) {
                eprintln!("{e}");
                return 1;
            }
            let (Some(key), Some(value)) = (args.positional.get(1), args.positional.get(2)) else {
                eprintln!("usage: prop set <key> <value>");
                return 2;
            };
            match properties::set(key, value) {
                Ok(()) => {
                    println!("{{\"ok\":true,\"key\":\"{}\"}}", json_escape(key));
                    0
                }
                Err(e) => {
                    eprintln!("property write failed: {e}");
                    3
                }
            }
        }
        _ => {
            eprintln!("usage: prop list|get <key>|set <key> <value>");
            2
        }
    }
}

fn cmd_optimize(args: &Args) -> i32 {
    if let Err(e) = core_authorize(Operation::ReadPerformance) {
        eprintln!("{e}");
        return 1;
    }
    let s = Sysroot::new(&args.root);
    let identity = platform::PlatformIdentity::detect(&s);
    let inv = inventory::collect(&s, identity.clone());
    let plan = optimize::plan(&s, &inv);
    if args.json {
        println!("{}", optimize::json(&plan));
    } else {
        println!("RAM class            : {}", plan.ram_class);
        println!("storage class        : {}", plan.storage_class);
        println!("thermal safe         : {}", plan.thermal_safe);
        println!("network control      : {}", plan.network_control_available);
        println!("safe controls        : {}", plan.safe_controls.join(", "));
        println!(
            "blocked controls     : {}",
            plan.blocked_controls.join(", ")
        );
        for note in plan.notes {
            println!("note                 : {note}");
        }
    }
    0
}

fn parse_operation_name(name: &str) -> Option<Operation> {
    Some(match name {
        "apply.profile" => Operation::ApplyProfile,
        "tune.cpu" => Operation::SetCpuTweak,
        "tune.gpu" => Operation::SetGpuTweak,
        "tune.memory" => Operation::SetMemoryTweak,
        "tune.io" => Operation::SetIoTweak,
        "tune.power" => Operation::SetPowerTweak,
        "tune.thermal" => Operation::SetThermalPolicy,
        "tune.properties" => Operation::SetProperty,
        "tune.storage" => Operation::TuneStorage,
        "tune.network" => Operation::TuneNetwork,
        "tune.zram" => Operation::TuneZram,
        "control.reset" => Operation::ResetRuntime,
        "security.license" => Operation::InstallLicense,
        "security.policy" => Operation::ModifyPolicy,
        "control.hooks" => Operation::ManageHooks,
        _ => return None,
    })
}

fn boot_integrity(inv: &inventory::Inventory) -> zairenkai_core::sentinel::BootIntegrity {
    match inv
        .security
        .verified_boot
        .as_deref()
        .map(|v| v.trim().to_ascii_lowercase())
    {
        Some(v) if v == "green" => zairenkai_core::sentinel::BootIntegrity::Green,
        Some(v) if v == "yellow" => zairenkai_core::sentinel::BootIntegrity::Yellow,
        Some(v) if v == "orange" => zairenkai_core::sentinel::BootIntegrity::Orange,
        Some(v) if v == "red" => zairenkai_core::sentinel::BootIntegrity::Red,
        _ => zairenkai_core::sentinel::BootIntegrity::Unknown,
    }
}

fn sentinel_evaluate(
    args: &Args,
    operation: Operation,
) -> zairenkai_core::sentinel::SentinelDecision {
    let s = Sysroot::new(&args.root);
    let identity = platform::PlatformIdentity::detect(&s);
    let inv = inventory::collect(&s, identity.clone());
    let compatible = Zkfc::open()
        .ok()
        .and_then(|z| z.version().ok())
        .is_some_and(|v| v.api_compatible());
    let state_dir = Path::new(&args.state);
    let safe_mode_active = Path::new(SAFE_MODE).exists() || state_dir.join("safe_mode").exists();
    let persistent_valid = !state_dir.join("transaction.pending").exists();
    let device_known = identity.vendor != platform::SocVendor::Unknown
        && (!identity.platform.is_empty() || !identity.compatible.is_empty());
    let evidence_level = match identity.evidence.len() {
        0 => zairenkai_core::intelligence::EvidenceLevel::Unknown,
        1 => zairenkai_core::intelligence::EvidenceLevel::Heuristic,
        _ => zairenkai_core::intelligence::EvidenceLevel::Observed,
    };
    let battery_pct = s
        .read_u64("/sys/class/power_supply/battery/capacity")
        .map(|v| v.min(100) as u8);
    let topology = Topology::detect(&s);
    let thermal_snapshot = thermal::snapshot(&s, &topology);
    zairenkai_core::sentinel::evaluate(&zairenkai_core::sentinel::SentinelInput {
        operation,
        kernel_api_compatible: compatible,
        persistent_state_valid: persistent_valid,
        safe_mode_active,
        runtime_reconciled: persistent_valid,
        thermal_telemetry_complete: thermal_snapshot.telemetry_complete,
        thermal_headroom_permille: thermal_snapshot.headroom_permille,
        thermal_critical_reached: thermal_snapshot.critical_reached,
        battery_pct,
        external_power: thermal_snapshot.external_power,
        storage_health: inventory::storage_health(&s),
        boot_integrity: boot_integrity(&inv),
        device_known,
        evidence_level,
        lite_mode: args.lite,
    })
}

fn cmd_sentinel(args: &Args) -> i32 {
    let Some(name) = args.positional.first() else {
        eprintln!("usage: sentinel <operation>");
        return 2;
    };
    let Some(operation) = parse_operation_name(name) else {
        eprintln!("unknown operation '{name}'");
        return 2;
    };
    if let Err(e) = core_authorize(operation) {
        eprintln!("{e}");
        return 1;
    }
    let decision = sentinel_evaluate(args, operation);
    if args.json {
        println!(
            "{{\"allowed\":{},\"class\":\"{:?}\",\"reasons\":[{}]}}",
            decision.allowed,
            decision.class,
            decision
                .reasons
                .iter()
                .map(|r| format!("\"{:?}\"", r))
                .collect::<Vec<_>>()
                .join(",")
        );
    } else {
        println!(
            "allowed: {}\nclass  : {:?}",
            decision.allowed, decision.class
        );
        for reason in decision.reasons {
            println!("reason : {:?}", reason);
        }
    }
    if decision.allowed {
        0
    } else {
        4
    }
}

fn cmd_adaptive(args: &Args) -> i32 {
    let s = Sysroot::new(&args.root);
    let topology = Topology::detect(&s);
    let profile = match load_profile(
        args.profile.as_deref(),
        Path::new(&args.state),
        &topology.identity,
    ) {
        Ok(profile) => profile,
        Err(e) => {
            eprintln!("adaptive profile error: {e}");
            return 2;
        }
    };
    let mut scene_engine = scene_engine::SceneEngine::new();
    let scene = scene_engine.observe(&s, &profile);
    let desired = {
        let state = StateStore::new(&args.state);
        state
            .mode()
            .unwrap_or_else(|| profile.meta.default_mode.clone())
    };
    let mut effective = if desired == "auto" {
        scene::resolve_auto_with_previous(&s, None).to_string()
    } else {
        desired.clone()
    };
    if let Some(package) = scene.package.as_ref() {
        if let Some(mode) = profile.perapp.get(package) {
            effective = mode.clone();
        }
    }

    let observe =
        profile.adaptive.enabled && effective != "powersave" && scene.kind.performance_candidate();
    let mut workload_analyzer = workload::WorkloadAnalyzer::new();
    let workload = if observe {
        workload_analyzer.sample_with_gpu(
            &s,
            scene.package.as_deref(),
            topology.gpu.as_ref().map(|g| g.devfreq.as_str()),
        )
    } else {
        None
    };
    let mut interaction_engine = interaction::InteractionEngine::new();
    let interaction = if observe && profile.adaptive.input_enabled && scene.interactive {
        let _ = interaction_engine.observe(
            &s,
            profile.adaptive.input_scan_ms,
            profile.adaptive.interaction_hold_ms,
        );
        std::thread::sleep(Duration::from_millis(u64::from(
            profile.adaptive.input_scan_ms.clamp(5, 50),
        )));
        interaction_engine.observe(
            &s,
            profile.adaptive.input_scan_ms,
            profile.adaptive.interaction_hold_ms,
        )
    } else {
        interaction::InteractionSnapshot::default()
    };
    let mut frame_analyzer = frame::FrameAnalyzer::new();
    let frame = if observe && workload.as_ref().is_some_and(|w| w.active) {
        let refresh_hz = frame_analyzer.current_refresh_hz();
        frame_analyzer.sample(
            scene.package.as_deref().unwrap_or_default(),
            workload.as_ref().map(|w| w.pid).unwrap_or_default(),
            refresh_hz,
            profile.adaptive.frame_budget_ms,
            &profile.adaptive.frame_source,
        )
    } else {
        frame::FrameMetrics::default()
    };
    let thermal_snapshot = thermal::snapshot(&s, &topology);
    let base_uclamp = profile
        .mode(&effective)
        .and_then(|m| m.cpu.uclamp_min_pct)
        .unwrap_or(0);
    let decision = fas::FasController::new().update(
        &scene,
        workload.as_ref(),
        &frame,
        &thermal_snapshot,
        &interaction,
        base_uclamp,
        &profile.adaptive,
    );

    let zkfc_caps = Zkfc::open()
        .ok()
        .and_then(|z| z.capabilities().ok())
        .map(|c| c.kernel_caps());
    if args.json {
        println!(
            "{{\"scene\":\"{}\",\"kind\":\"{:?}\",\"event\":\"{:?}\",\"effective\":\"{}\",\"observe\":{},\"workload\":{},\"gpu_util_pct\":{},\"run_queue_delay_ms\":{},\"frame_available\":{},\"frame_fresh\":{},\"fps\":{:.3},\"p95_ms\":{:.3},\"jank_pct\":{:.3},\"thermal_headroom_permille\":{},\"decision_active\":{},\"extra_boost_pct\":{},\"uclamp_min_pct\":{},\"cpu_floor_pct\":{},\"confidence\":{},\"reason\":\"{}\",\"input_recent\":{},\"input_age_ms\":{},\"input_sources\":{},\"frame_source\":\"{}\",\"zkfc_perf_capable\":{}}}",
            json_escape(scene.package.as_deref().unwrap_or("none")),
            scene.kind,
            scene.event,
            json_escape(&effective),
            observe,
            workload.is_some(),
            workload.as_ref().and_then(|w| w.gpu_util_pct.map(|v| format!("{v:.3}"))).unwrap_or_else(|| "null".into()),
            workload.as_ref().and_then(|w| w.run_queue_delay_ms.map(|v| format!("{v:.3}"))).unwrap_or_else(|| "null".into()),
            frame.available,
            frame.fresh,
            frame.fps,
            frame.p95_ms,
            frame.jank_ratio * 100.0,
            thermal_snapshot.headroom_permille.map(|v| v.to_string()).unwrap_or_else(|| "null".into()),
            decision.active,
            decision.extra_boost_pct,
            decision.target_uclamp_min_pct,
            decision.cpu_floor_pct,
            decision.confidence,
            decision.reason,
            interaction.recent,
            interaction.age_ms.map(|v| v.to_string()).unwrap_or_else(|| "null".into()),
            interaction.source_count,
            json_escape(&frame.source),
            zkfc_caps.is_some_and(|caps| caps & zkfc_sys::ZKFC_CAP_TUNE_PERF != 0),
        );
    } else {
        println!(
            "scene       : {} ({:?}, {:?})",
            scene.package.as_deref().unwrap_or("none"),
            scene.kind,
            scene.event
        );
        println!("effective   : {effective}");
        println!("observe     : {observe}");
        if let Some(w) = workload {
            println!("workload    : package={} pid={} cpu={:.1}% top={:.1}% render={:.1}% gpu={:?}% rq={:?}ms active={} confidence={}", w.package, w.pid, w.cpu_util_pct, w.top_thread_util_pct(), w.render_util_pct(), w.gpu_util_pct, w.run_queue_delay_ms, w.active, w.confidence);
        } else {
            println!("workload    : unavailable");
        }
        println!("frame       : source={} available={} fresh={} window={} new={} fps={:.1} p95={:.2}ms jank={:.1}% budget={:.2}ms", frame.source, frame.available, frame.fresh, frame.frames, frame.new_frames, frame.fps, frame.p95_ms, frame.jank_ratio * 100.0, frame.budget_ms);
        println!("interaction : recent={} active={} touch={} last={:?} age={:?}ms burst={} sources={} confidence={}", interaction.recent, interaction.active, interaction.touch_active, interaction.kind, interaction.age_ms, interaction.burst_count, interaction.source_count, interaction.confidence);
        println!(
            "thermal     : headroom={:?} critical={} complete={}",
            thermal_snapshot.headroom_permille,
            thermal_snapshot.critical_reached,
            thermal_snapshot.telemetry_complete
        );
        println!(
            "decision    : active={} boost=+{}% uclamp={}% cpu_floor={}% confidence={} reason={}",
            decision.active,
            decision.extra_boost_pct,
            decision.target_uclamp_min_pct,
            decision.cpu_floor_pct,
            decision.confidence,
            decision.reason
        );
        println!(
            "zkfc perf   : {}",
            zkfc_caps.is_some_and(|caps| caps & zkfc_sys::ZKFC_CAP_TUNE_PERF != 0)
        );
    }
    0
}

fn cmd_reset(args: &Args) -> i32 {
    if let Err(e) = core_authorize(Operation::ResetRuntime) {
        eprintln!("{e}");
        return 1;
    }
    let s = Sysroot::new(&args.root);
    let topology = Topology::detect(&s);
    let family = family::load(
        &Path::new(&args.state).join("database"),
        topology.identity.vendor,
    );
    let plan = BackendPlan::resolve(&topology.identity, &topology, family.as_ref());
    let engine = Engine::new(&s, &topology, &plan);
    let nodes = all_managed_nodes(&s, &topology, &engine);
    let mut state = StateStore::new(&args.state);
    if let Err(e) = state.ensure_boot(&s, &nodes) {
        eprintln!("state init failed: {e}");
        return 3;
    }
    let sentinel = sentinel_evaluate(args, Operation::ResetRuntime);
    if !sentinel.allowed {
        eprintln!("reset blocked by Sentinel: {:?}", sentinel.reasons);
        return 4;
    }
    match state.restore_baseline(&s, &nodes) {
        Ok(count) => {
            println!("restored baseline: {count} node(s)");
            0
        }
        Err(e) => {
            eprintln!("baseline reset failed: {e}");
            3
        }
    }
}

/// What a mode requests, straight from the resolved device profile. Values
/// are requests: frequency specs are resolved against the live OPP table and
/// the thermal envelope can still reduce them at apply time.
fn mode_summary_json(id: &str, mode: &config::Mode) -> String {
    fn freq(spec: &Option<config::FreqSpec>) -> String {
        match spec {
            Some(config::FreqSpec::Khz(khz)) => format!("\"{khz}kHz\""),
            Some(config::FreqSpec::Spec(text)) => format!("\"{}\"", json_escape(text)),
            None => "null".into(),
        }
    }
    fn num<T: std::fmt::Display>(v: Option<T>) -> String {
        v.map(|v| v.to_string()).unwrap_or_else(|| "null".into())
    }
    fn list(items: &[String]) -> String {
        items
            .iter()
            .map(|v| format!("\"{}\"", json_escape(v)))
            .collect::<Vec<_>>()
            .join(",")
    }
    format!(
        "{{\"id\":\"{}\",\"cpu\":{{\"min_freq\":{},\"max_freq\":{},\"max_perf_pct\":{},\"governors\":[{}],\"uclamp_min_pct\":{},\"input_boost_pct\":{},\"input_boost_ms\":{}}},\"gpu\":{{\"min_freq\":{},\"max_freq\":{},\"max_perf_pct\":{},\"governors\":[{}]}},\"mem\":{{\"swappiness\":{},\"vfs_cache_pressure\":{}}},\"io\":{{\"schedulers\":[{}],\"read_ahead_kb\":{}}}}}",
        json_escape(id),
        freq(&mode.cpu.min_freq),
        freq(&mode.cpu.max_freq),
        num(mode.cpu.max_perf_pct),
        list(&mode.cpu.governor),
        num(mode.cpu.uclamp_min_pct),
        num(mode.cpu.input_boost_pct),
        num(mode.cpu.input_boost_ms),
        freq(&mode.gpu.min_freq),
        freq(&mode.gpu.max_freq),
        num(mode.gpu.max_perf_pct),
        list(&mode.gpu.governor),
        num(mode.mem.swappiness),
        num(mode.mem.vfs_cache_pressure),
        list(&mode.io.scheduler),
        num(mode.io.read_ahead_kb),
    )
}

fn cmd_modes(args: &Args) -> i32 {
    let topology = Topology::detect(&Sysroot::new(&args.root));
    match load_profile(
        args.profile.as_deref(),
        Path::new(&args.state),
        &topology.identity,
    ) {
        Ok(profile) if args.json => {
            let modes = profile
                .mode
                .iter()
                .map(|(id, mode)| mode_summary_json(id, mode))
                .collect::<Vec<_>>()
                .join(",");
            println!(
                "{{\"ok\":true,\"profile\":\"{}\",\"default\":\"{}\",\"modes\":[{}]}}",
                json_escape(&profile.meta.name),
                json_escape(&profile.meta.default_mode),
                modes
            );
            0
        }
        Ok(profile) => {
            println!(
                "profile: {} (soc={})\ndefault: {}\nmodes: {}",
                profile.meta.name,
                profile.meta.soc,
                profile.meta.default_mode,
                profile.mode.keys().cloned().collect::<Vec<_>>().join(", ")
            );
            0
        }
        Err(e) => {
            eprintln!("profile error: {e}");
            2
        }
    }
}

fn cmd_status(args: &Args) -> i32 {
    let s = Sysroot::new(&args.root);
    let topology = Topology::detect(&s);
    let family = family::load(
        &Path::new(&args.state).join("database"),
        topology.identity.vendor,
    );
    let plan = BackendPlan::resolve(&topology.identity, &topology, family.as_ref());
    let engine = Engine::new(&s, &topology, &plan);
    let nodes = all_managed_nodes(&s, &topology, &engine);
    let mut state = StateStore::new(&args.state);
    if let Err(e) = state.ensure_boot(&s, &nodes) {
        eprintln!("state init failed: {e}");
        return 3;
    }
    if let Err(e) = state.recover(&s) {
        eprintln!("state recovery failed: {e}");
        return 3;
    }
    let saved = state.mode();
    let safe_mode = Path::new(SAFE_MODE).exists();
    let hold = hold_reason(
        safe_mode,
        saved.as_deref(),
        state.mode_fingerprint().as_deref(),
        &fingerprint::compute(&s, &topology.identity),
    );
    let desired = saved.clone().unwrap_or_else(|| "balance".into());
    let effective = if hold.is_some() {
        "stock".to_string()
    } else {
        state.effective_mode().unwrap_or_else(|| {
            if desired == "auto" {
                scene::resolve_auto(&Sysroot::new(&args.root)).into()
            } else {
                desired.clone()
            }
        })
    };
    let auto = desired == "auto";
    if args.json {
        println!(
            "{{\"ok\":true,\"desired\":\"{}\",\"effective\":\"{}\",\"auto\":{},\"saved\":{},\"hold\":{},\"revalidation_required\":{},\"pending_transaction\":{},\"safe_mode\":{}}}",
            json_escape(&desired),
            json_escape(&effective),
            auto,
            saved.is_some(),
            hold.map(|h| format!("\"{h}\"")).unwrap_or_else(|| "null".into()),
            hold == Some("revalidation_required"),
            state.has_pending_transaction(),
            safe_mode,
        );
    } else {
        println!("desired   : {desired}\neffective : {effective}\nauto      : {auto}");
        if let Some(reason) = hold {
            println!("hold      : {reason}");
        }
    }
    0
}

fn acquire_daemon_lock(state: &Path) -> std::io::Result<File> {
    fs::create_dir_all(state)?;
    let file = OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(state.join("daemon.lock"))?;
    let rc = unsafe {
        libc::flock(
            std::os::fd::AsRawFd::as_raw_fd(&file),
            libc::LOCK_EX | libc::LOCK_NB,
        )
    };
    if rc != 0 {
        return Err(std::io::Error::last_os_error());
    }
    Ok(file)
}

/// Why the daemon must hold the boot baseline instead of applying a mode.
///
/// - `safe_mode`: the boot fail-safe is active.
/// - `stock`: the user explicitly reset to stock (`zperfd reset`).
/// - `revalidation_required`: a mode was saved on a different kernel build or
///   device (or before fingerprints existed) and has not been confirmed here.
///
/// No saved mode means no user choice to replay; the profile default applies.
fn hold_reason(
    safe_mode: bool,
    saved_mode: Option<&str>,
    saved_fingerprint: Option<&str>,
    current_fingerprint: &str,
) -> Option<&'static str> {
    if safe_mode {
        return Some("safe_mode");
    }
    match saved_mode {
        None => None,
        Some("stock") => Some("stock"),
        Some(_) if saved_fingerprint != Some(current_fingerprint) => Some("revalidation_required"),
        Some(_) => None,
    }
}

fn cmd_daemon(args: &Args) -> i32 {
    let _daemon_lock = match acquire_daemon_lock(Path::new(&args.state)) {
        Ok(lock) => lock,
        Err(e) => {
            eprintln!("zperfd: another daemon instance is already active or lock failed: {e}");
            return 5;
        }
    };

    unsafe {
        let handler = on_signal as extern "C" fn(libc::c_int) as libc::sighandler_t;
        libc::signal(libc::SIGTERM, handler);
        libc::signal(libc::SIGINT, handler);
    }

    let s = Sysroot::new(&args.root);
    let topology = Topology::detect(&s);
    let family = family::load(
        &Path::new(&args.state).join("database"),
        topology.identity.vendor,
    );
    let plan = BackendPlan::resolve(&topology.identity, &topology, family.as_ref());
    let engine = Engine::new(&s, &topology, &plan);
    let mut nodes = all_managed_nodes(&s, &topology, &engine);
    let mut state = StateStore::new(&args.state);
    if let Err(e) = state.ensure_boot(&s, &nodes) {
        eprintln!("zperfd: state init failed: {e}");
        return 3;
    }
    if let Err(e) = state.recover(&s) {
        eprintln!("zperfd: state recovery failed: {e}");
        return 3;
    }

    let profile = match load_profile(
        args.profile.as_deref(),
        Path::new(&args.state),
        &topology.identity,
    ) {
        Ok(p) => p,
        Err(e) => {
            eprintln!("zperfd: profile error: {e}");
            return 2;
        }
    };
    eprintln!(
        "zperfd: {} backend={} oem={} profile '{}' family={}",
        topology.flavor(),
        plan.backend.as_str(),
        plan.oem.as_str(),
        profile.meta.name,
        family.as_ref().map(|f| f.vendor.as_str()).unwrap_or("none")
    );

    // Adaptive control is a separate runtime layer above the durable profile.
    // Base-mode changes remain transactional; transient task boosts/affinity
    // are owned by ZKFC + ThreadTaskController and are always reset when the
    // scene changes, safe mode activates, or the daemon stops.
    let mut scene_engine = scene_engine::SceneEngine::new();
    let mut workload_analyzer = workload::WorkloadAnalyzer::new();
    let mut frame_analyzer = frame::FrameAnalyzer::new();
    let mut interaction_engine = interaction::InteractionEngine::new();
    let mut last_frame_probe: Option<Instant> = None;
    let mut fas_controller = fas::FasController::new();
    let mut last_adaptive_report: Option<(i32, u32)> = None;
    let mut was_adaptive_observing = false;
    let mut task_controller = task_controller::ThreadTaskController::new();
    let performance_cpus = topology.performance_cpus();
    let performance_policy = topology.performance_policy();

    let mut last = String::new();
    let mut last_observed = None;
    let mut next_reconcile = Instant::now();
    let reconcile_every = Duration::from_millis(args.interval_ms.saturating_mul(4).max(2_000));
    // The identity cannot change within one boot; compute it once.
    let current_fingerprint = fingerprint::compute(&s, &topology.identity);
    let mut held: Option<&'static str> = None;
    while !STOP.load(Ordering::SeqCst) {
        let hold = hold_reason(
            Path::new(SAFE_MODE).exists(),
            state.mode().as_deref(),
            state.mode_fingerprint().as_deref(),
            &current_fingerprint,
        );
        if let Some(reason) = hold {
            if held != Some(reason) {
                // hold_baseline keeps the requested mode, so it can be
                // re-applied once the user clears SAFE MODE or confirms it.
                // A failed hold is retried on the next iteration rather than
                // assumed done.
                match state.hold_baseline(&s, &nodes) {
                    Ok(_) => {
                        eprintln!("zperfd: holding stock baseline ({reason})");
                        held = Some(reason);
                    }
                    Err(e) => eprintln!("zperfd: baseline hold ({reason}) failed: {e}"),
                }
            }
            task_controller.reset();
            let _ = configure_kernel_input_boost(&topology, None);
            fas_controller.reset();
            last_adaptive_report = None;
            workload_analyzer.reset();
            frame_analyzer.reset();
            interaction_engine.reset();
            last_frame_probe = None;
            scene_engine.reset();
            last.clear();
            last_observed = None;
            next_reconcile = Instant::now() + reconcile_every;
            std::thread::sleep(Duration::from_millis(args.interval_ms.max(500)));
            continue;
        }
        if held.take().is_some() {
            // The user cleared SAFE MODE or confirmed the mode on this
            // device. Re-apply the desired policy even when its effective
            // name has not changed.
            last.clear();
        }

        let scene_snapshot = scene_engine.observe(&s, &profile);
        if matches!(
            scene_snapshot.event,
            scene_engine::SceneEvent::Switch | scene_engine::SceneEvent::Exit
        ) {
            task_controller.reset();
            fas_controller.reset();
            last_adaptive_report = None;
            workload_analyzer.reset();
            frame_analyzer.reset();
            interaction_engine.reset();
            last_frame_probe = None;
        }
        let desired_raw = state
            .mode()
            .unwrap_or_else(|| profile.meta.default_mode.clone());
        let desired = if desired_raw == "auto" || profile.mode.contains_key(&desired_raw) {
            desired_raw
        } else {
            eprintln!(
                "zperfd: invalid persisted mode '{desired_raw}', falling back to '{}'",
                profile.meta.default_mode
            );
            profile.meta.default_mode.clone()
        };
        let mut effective = if desired == "auto" {
            scene::resolve_auto_with_previous(&s, (!last.is_empty()).then_some(last.as_str()))
                .to_string()
        } else {
            desired.clone()
        };
        if let Some(package) = scene_snapshot.package.as_ref() {
            if let Some(mode) = profile.perapp.get(package) {
                effective = mode.clone();
            }
        }

        let adaptive_observation = profile.adaptive.enabled
            && effective != "powersave"
            && scene_snapshot.kind.performance_candidate();
        if adaptive_observation != was_adaptive_observing {
            workload_analyzer.reset();
            frame_analyzer.reset();
            interaction_engine.reset();
            last_frame_probe = None;
            was_adaptive_observing = adaptive_observation;
        }
        let interaction =
            if adaptive_observation && profile.adaptive.input_enabled && scene_snapshot.interactive
            {
                interaction_engine.observe(
                    &s,
                    profile.adaptive.input_scan_ms,
                    profile.adaptive.interaction_hold_ms,
                )
            } else {
                interaction_engine.disable();
                interaction::InteractionSnapshot::default()
            };
        let workload = if adaptive_observation {
            workload_analyzer.sample_with_gpu(
                &s,
                scene_snapshot.package.as_deref(),
                topology.gpu.as_ref().map(|gpu| gpu.devfreq.as_str()),
            )
        } else {
            None
        };
        let frame_metrics = if adaptive_observation
            && workload.as_ref().is_some_and(|w| {
                w.active
                    && (matches!(
                        scene_snapshot.kind,
                        scene_engine::SceneKind::Game
                            | scene_engine::SceneKind::Benchmark
                            | scene_engine::SceneKind::Camera
                            | scene_engine::SceneKind::Video
                    ) || w.cpu_util_pct >= 20.0
                        || w.top_thread_util_pct() >= 20.0
                        || w.render_util_pct() >= 15.0
                        || w.gpu_util_pct.is_some_and(|v| v >= 70.0))
            }) {
            if let Some(pkg) = scene_snapshot.package.as_deref() {
                let probe_due = last_frame_probe
                    .map(|t| {
                        Instant::now().saturating_duration_since(t)
                            >= Duration::from_millis(profile.adaptive.frame_probe_ms as u64)
                    })
                    .unwrap_or(true);
                if probe_due {
                    last_frame_probe = Some(Instant::now());
                    let refresh_hz = frame_analyzer.current_refresh_hz();
                    frame_analyzer.sample(
                        pkg,
                        workload.as_ref().map(|w| w.pid).unwrap_or_default(),
                        refresh_hz,
                        profile.adaptive.frame_budget_ms,
                        &profile.adaptive.frame_source,
                    )
                } else {
                    frame_analyzer.cached()
                }
            } else {
                frame::FrameMetrics::default()
            }
        } else {
            frame::FrameMetrics::default()
        };

        let now = Instant::now();
        let check_drift = now >= next_reconcile;
        if check_drift {
            let refreshed_nodes = all_managed_nodes(&s, &topology, &engine);
            if refreshed_nodes != nodes {
                if let Err(e) = state.ensure_boot(&s, &refreshed_nodes) {
                    eprintln!("zperfd: managed-node refresh failed: {e}");
                    next_reconcile = now + reconcile_every;
                    std::thread::sleep(Duration::from_millis(args.interval_ms.max(250)));
                    continue;
                }
                nodes = refreshed_nodes;
                last_observed = None;
            }
        }
        let observed = check_drift.then(|| runtime_fingerprint(&s, &nodes));
        let drifted = check_drift && observed != last_observed;
        let needs_apply = effective != last || drifted;
        if profile.mode.contains_key(&effective) && needs_apply {
            next_reconcile = now + reconcile_every;
            match kernel_license_ok() {
                Ok(()) => {
                    let sentinel = sentinel_evaluate(args, Operation::ApplyProfile);
                    if !sentinel.allowed {
                        eprintln!("zperfd: Sentinel blocked mutation: {:?}", sentinel.reasons);
                        std::thread::sleep(Duration::from_millis(args.interval_ms.max(500)));
                        continue;
                    }
                    if let Err(e) = state.begin(&s, &nodes) {
                        eprintln!("zperfd: transaction begin failed: {e}");
                    } else {
                        let Some(mode) = profile.mode(&effective) else {
                            let _ = state.rollback(&s);
                            eprintln!(
                                "apply failed: validated profile is missing mode '{effective}'"
                            );
                            return 3;
                        };
                        let thermal_snapshot = thermal::snapshot(&s, &topology);
                        if thermal_snapshot.telemetry_complete {
                            if let Err(e) = configure_kernel_thermal_guard(args, &thermal_snapshot)
                            {
                                eprintln!("zperfd: thermal guard configuration failed: {e}");
                                let _ = state.rollback(&s);
                                std::thread::sleep(Duration::from_millis(
                                    args.interval_ms.max(500),
                                ));
                                continue;
                            }
                        } else {
                            eprintln!("zperfd: thermal trip topology incomplete; limited envelope active; no new kernel guard configured");
                        }
                        let therm = thermal::envelope(&thermal_snapshot);
                        let constrained = thermal::constrain_mode(mode, therm);
                        eprintln!(
                            "zperfd: thermal envelope band={:?} boost={}‰ cap={:?}",
                            therm.band, therm.boost_permille, therm.max_perf_cap_pct
                        );
                        let report = engine.apply_mode(&constrained);
                        if report.failed.is_empty() && !report.applied.is_empty() {
                            if let Err(e) = configure_kernel_input_boost(&topology, Some(mode)) {
                                eprintln!("zperfd: input boost fast path unavailable: {e}");
                            }
                            match state.commit(Some(&desired), Some(&effective)) {
                                Ok(()) => {
                                    last = effective.clone();
                                    last_observed = Some(runtime_fingerprint(&s, &nodes));
                                    eprintln!(
                                        "zperfd: {} -> {} applied={} skipped={}",
                                        desired,
                                        effective,
                                        report.applied.len(),
                                        report.skipped.len()
                                    );
                                }
                                Err(e) => {
                                    let _ = configure_kernel_input_boost(&topology, None);
                                    let _ = state.recover(&s);
                                    eprintln!("zperfd: commit failed: {e}");
                                }
                            }
                        } else {
                            if let Err(e) = state.rollback(&s) {
                                eprintln!("zperfd: rollback failed: {e}");
                            }
                            eprintln!("zperfd: apply {effective} failed");
                            for failure in report.failed {
                                eprintln!("zperfd:   ! {failure}");
                            }
                        }
                    }
                }
                Err(e) => eprintln!("zperfd: mutation locked: {e}"),
            }
        } else if let Some(observed) = observed {
            last_observed = Some(observed);
            next_reconcile = now + reconcile_every;
        }

        // FAS runs inside the already-selected scene. It layers a bounded
        // transient boost over the durable profile instead of rewriting the
        // complete profile every sampling tick.
        if adaptive_observation {
            if let Some(ref workload) = workload {
                let thermal_snapshot = thermal::snapshot(&s, &topology);
                let base_uclamp = profile
                    .mode(&effective)
                    .and_then(|m| m.cpu.uclamp_min_pct)
                    .unwrap_or(0);
                let decision = fas_controller.update(
                    &scene_snapshot,
                    Some(workload),
                    &frame_metrics,
                    &thermal_snapshot,
                    &interaction,
                    base_uclamp,
                    &profile.adaptive,
                );
                if decision.active {
                    if let Err(e) = kernel_license_ok() {
                        eprintln!("zperfd: adaptive mutation locked: {e}");
                    } else {
                        let sentinel = sentinel_evaluate(args, Operation::SetCpuTweak);
                        if !sentinel.allowed {
                            eprintln!("zperfd: adaptive blocked: {:?}", sentinel.reasons);
                        } else if let Err(e) = task_controller.apply(
                            &s,
                            &plan,
                            &interaction,
                            &decision,
                            workload,
                            &performance_cpus,
                            performance_policy,
                        ) {
                            eprintln!("zperfd: adaptive actuator failed: {e}");
                        } else {
                            let key = (workload.pid, decision.extra_boost_pct);
                            if last_adaptive_report != Some(key) {
                                eprintln!(
                                    "zperfd: adaptive scene={} age_ms={} reason={} pid={} cpu={:.1}% rss_kb={:?} io_r={}B/s io_w={}B/s gpu={:?}% rq={:?}ms input_age={}ms backend={} frame_src={} fresh={} fps={:.1} avg={:.2}ms p95={:.2}ms jank={:.1}% boost=+{}% uclamp={} cpu_floor={} confidence={}",
                                    scene_snapshot.package.as_deref().unwrap_or("none"),
                                    scene_snapshot.age.as_millis(),
                                    scene_snapshot.reason,
                                    workload.pid,
                                    workload.cpu_util_pct,
                                    workload.rss_kb,
                                    workload.io_read_bps.unwrap_or(0),
                                    workload.io_write_bps.unwrap_or(0),
                                    workload.gpu_util_pct,
                                    workload.run_queue_delay_ms,
                                    interaction.age_ms.unwrap_or(0),
                                    plan.backend.as_str(),
                                    frame_metrics.source,
                                    frame_metrics.fresh,
                                    frame_metrics.fps,
                                    frame_metrics.avg_ms,
                                    frame_metrics.p95_ms,
                                    frame_metrics.jank_ratio * 100.0,
                                    decision.extra_boost_pct,
                                    decision.target_uclamp_min_pct,
                                    decision.cpu_floor_pct,
                                    decision.confidence,
                                );
                                last_adaptive_report = Some(key);
                            }
                        }
                    }
                } else {
                    let was_active = last_adaptive_report.take().is_some();
                    task_controller.reset();
                    if was_active {
                        eprintln!(
                            "zperfd: adaptive release scene={} reason={}",
                            scene_snapshot.package.as_deref().unwrap_or("none"),
                            decision.reason
                        );
                    }
                }
            }
        } else {
            task_controller.reset();
            fas_controller.reset();
        }

        std::thread::sleep(Duration::from_millis(args.interval_ms.max(250)));
    }
    task_controller.reset();
    fas_controller.reset();
    interaction_engine.reset();
    let _ = configure_kernel_input_boost(&topology, None);
    eprintln!("zperfd: stopping");
    0
}

fn main() {
    let args = parse_args();
    let code = match args.cmd.as_str() {
        "probe" => cmd_probe(&Sysroot::new(&args.root), Path::new(&args.state), args.json),
        "modes" => cmd_modes(&args),
        "tweak" => cmd_tweak(&args),
        "apply" | "set" => args
            .positional
            .first()
            .map(|mode| apply_transaction(&args, mode))
            .unwrap_or(2),
        "reset" => cmd_reset(&args),
        "status" => cmd_status(&args),
        "inventory" => cmd_inventory(&args),
        "optimize" => cmd_optimize(&args),
        "thermal" => cmd_thermal(&args),
        "adaptive" => cmd_adaptive(&args),
        "prop" => cmd_prop(&args),
        "sentinel" => cmd_sentinel(&args),
        "core" => {
            if args.positional.first().map(|x| x.as_str()) == Some("permissions") {
                cmd_core_permissions(args.json)
            } else {
                cmd_core(args.json)
            }
        }
        "api" => api::run(&args),
        "daemon" => cmd_daemon(&args),
        "" => {
            print_help();
            1
        }
        command => {
            eprintln!("unknown command '{command}'");
            print_help();
            1
        }
    };
    std::process::exit(code);
}

#[cfg(test)]
mod tests {
    use super::hold_reason;

    const FP: &str = "zk-fp-v1:00000000000000aa";

    #[test]
    fn saved_mode_replays_only_on_the_device_it_was_confirmed_on() {
        assert_eq!(hold_reason(false, Some("fast"), Some(FP), FP), None);
        assert_eq!(
            hold_reason(false, Some("fast"), Some("zk-fp-v1:00000000000000bb"), FP),
            Some("revalidation_required")
        );
        // Saved before fingerprints existed: never replayed blindly.
        assert_eq!(
            hold_reason(false, Some("auto"), None, FP),
            Some("revalidation_required")
        );
    }

    #[test]
    fn stock_and_safe_mode_hold_while_no_choice_uses_the_profile_default() {
        assert_eq!(hold_reason(false, Some("stock"), None, FP), Some("stock"));
        assert_eq!(
            hold_reason(true, Some("fast"), Some(FP), FP),
            Some("safe_mode")
        );
        assert_eq!(hold_reason(true, None, None, FP), Some("safe_mode"));
        assert_eq!(hold_reason(false, None, None, FP), None);
    }
}
