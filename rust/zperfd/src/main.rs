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
mod config;
mod engine;
mod family;
mod frame;
mod fas;
mod nodes;
mod scene_engine;
mod task_controller;
mod workload;
mod optimize;
mod platform;
mod scene;
mod state;
mod telemetry;
mod thermal;
mod memory;
mod properties;
mod storage;
mod topo;
mod tweak;

use backends::BackendPlan;
use config::Profile;
use engine::Engine;
use nodes::Sysroot;
use state::StateStore;
use std::fs::{self, File, OpenOptions};
use std::path::Path;
use std::hash::{Hash, Hasher};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};
use topo::Topology;
use zkfc_sys::Zkfc;
use zairenkai_core::authority::authorize;
use zairenkai_core::capability::CapabilitySet;
use zairenkai_core::manifest::CoreManifest;
use zairenkai_core::operation::Operation;

const GENERIC: &str = include_str!("../profiles/generic.toml");
const LAVENDER: &str = include_str!("../profiles/lavender.toml");
const CORE_MANIFEST: &str = include_str!("../../core/manifest.toml");
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
        _ => Err(format!("core authority denied {:?}: {}", operation, auth.reason)),
    }
}

fn core_manifest() -> Result<CoreManifest, String> {
    CoreManifest::parse(CORE_MANIFEST)
}

fn kernel_license_ok() -> Result<(), String> {
    let z = Zkfc::open().map_err(|e| format!("ZKFC unavailable: {e}"))?;
    let version = z
        .version()
        .map_err(|e| format!("ZKFC version query failed: {e}"))?;
    if !version.api_compatible() {
        return Err(format!(
            "incompatible ZKFC API {}.{}.{} (min supported {}.{}.{})",
            version.api_major(),
            version.api_minor(),
            version.api_patch(),
            (version.api_min_supported >> 16) & 0xff,
            (version.api_min_supported >> 8) & 0xff,
            version.api_min_supported & 0xff,
        ));
    }
    let status = z
        .license_state()
        .map_err(|e| format!("ZKFC license query failed: {e}"))?;
    if status != 1 {
        return Err(format!("ZKFC license state {status}; performance mutation locked"));
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
        "read.device", "read.kernel", "read.thermal", "read.performance", "read.logs", "read.inventory", "read.storage",
        "read.network", "read.memory", "read.security", "read.properties", "apply.profile", "tune.cpu",
        "tune.gpu", "tune.memory", "tune.io", "tune.power", "tune.thermal",
        "tune.storage", "tune.network", "tune.zram", "control.reset", "security.license",
        "security.policy", "control.hooks", "manage.device_registry", "manage.data_sources",
        "manage.evidence", "manage.recovery",
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
        println!("{{\"core_id\":\"{}\",\"permissions\":[{}]}}", zairenkai_core::CORE_ID, entries);
        return 0;
    }
    println!("{} permissions:", zairenkai_core::CORE_NAME);
    for (op, name) in OPS {
        println!("  {:<18} -> {:?} (risk={})", name, op.required_capability(), op.risk_weight());
    }
    println!("  runtime principal capabilities: {}", CapabilitySet::full_runtime().iter().count());
    0
}

fn cmd_core(json: bool) -> i32 {
    match core_manifest() {
        Ok(m) if json => {
            println!("{{\"id\":\"{}\",\"product\":\"{}\",\"core_api\":{},\"architectures":[{}],\"kernel_models":[{}],\"kernel_generations":[{}]}}",
                json_escape(&m.id), json_escape(&m.product), m.core_api,
                m.supported_architectures.iter().map(|v| format!("\"{}\"", json_escape(v))).collect::<Vec<_>>().join(","),
                m.supported_kernel_models.iter().map(|v| format!("\"{}\"", json_escape(v))).collect::<Vec<_>>().join(","),
                m.supported_kernel_generations.iter().map(|v| format!("\"{}\"", json_escape(v))).collect::<Vec<_>>().join(","));
            0
        }
        Ok(m) => {
            println!("{} {} api={} arch={} kernel-models={} generations={}",
                m.product, m.id, m.core_api, m.supported_architectures.len(),
                m.supported_kernel_models.len(), m.supported_kernel_generations.len());
            0
        }
        Err(e) => { eprintln!("core: {e}"); 1 }
    }
}

fn cmd_probe(s: &Sysroot, state: &Path, json: bool) -> i32 {
    if let Err(e) = core_authorize(Operation::Probe) { eprintln!("{e}"); return 1; }
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
        let thermal = topology.thermal_zones.iter().map(|z| format!(
            "{{\"name\":\"{}\",\"type\":\"{}\",\"provider\":\"{}\",\"temp_mdeg\":{}}}",
            json_escape(&z.name), json_escape(&z.ty), z.provider.as_str(),
            z.temp_mdeg.map(|v| v.to_string()).unwrap_or_else(|| "null".into())
        )).collect::<Vec<_>>().join(",");
        let boost = format!(
            "{{\"uclamp\":{},\"schedtune\":{},\"cpu_boost\":{}}}",
            topology.top_app_uclamp.is_some(),
            topology.stune_top.is_some(),
            topology.cpu_boost_dir.is_some(),
        );
        let gpu = topology.gpu.as_ref().map(|g| {
            format!(
                "{{\"provider\":\"{}\",\"opps\":{},\"min\":{},\"max\":{}}}",
                g.provider.as_str(),
                g.avail.len(),
                g.avail.first().copied().unwrap_or(0),
                g.avail.last().copied().unwrap_or(0),
            )
        }).unwrap_or_else(|| "null".into());
        let kernel_caps = Zkfc::open().ok().and_then(|z| z.capabilities().ok()).map(|c| format!(
            "{{\"kernel_caps\":{},\"runtime_caps\":{},\"kernel\":\"{}.{}.{}\",\"page_size\":{},\"cpu_count\":{}}}",
            c.kernel_caps(), c.runtime_caps(), c.kernel_major, c.kernel_minor, c.kernel_patch, c.page_size, c.cpu_count
        )).unwrap_or_else(|| "null".into());
        let telemetry = telemetry::json(s, &topology);
        let vendor_surfaces = plan.vendor_surfaces.iter().map(|x| format!("\"{}\"", x)).collect::<Vec<_>>().join(",");
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
    let caps = z.capabilities().map_err(|e| format!("ZKFC capability query failed: {e}"))?;
    if caps.kernel_caps() & zkfc_sys::ZKFC_CAP_TUNE_THERMAL == 0 {
        return Err("kernel does not advertise ZKFC thermal capability".into());
    }
    let interval_ms = args.interval_ms.clamp(250, 5000) as u32;
    let limit = thermal::kernel_guard_limit(snapshot).ok_or_else(|| "runtime thermal trip point is unavailable; kernel guard cannot be armed".to_string())?;
    let release = thermal::kernel_guard_release(snapshot).ok_or_else(|| "runtime thermal release point is unavailable; kernel guard cannot be armed".to_string())?;
    z.thermal_guard_config(interval_ms, limit, release, &zones)
        .map_err(|e| format!("ZKFC thermal guard configuration failed: {e}"))
}

fn apply_transaction(args: &Args, requested: &str) -> i32 {
    if let Err(e) = core_authorize(Operation::ApplyProfile) { eprintln!("{e}"); return 1; }
    if let Err(e) = kernel_license_ok() {
        eprintln!("apply blocked: {e}");
        return 4;
    }

    let s = Sysroot::new(&args.root);
    let topology = Topology::detect(&s);
    let thermal_snapshot = thermal::snapshot(&s, &topology);
    let family = family::load(&Path::new(&args.state).join("database"), topology.identity.vendor);
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
    eprintln!("thermal envelope: band={:?} boost={}‰ cap={:?} reason={}", therm.band, therm.boost_permille, therm.max_perf_cap_pct, therm.reason);
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
    let sub = args.positional.first().map(String::as_str).unwrap_or("list");
    let s = Sysroot::new(&args.root);
    let topology = Topology::detect(&s);

    match sub {
        "list" => {
            for spec in tweak::specs().filter(|spec| !(args.lite && spec.lite)) {
                let value = tweak::read(spec.id, &s, &topology).unwrap_or_else(|| "".into());
                println!(
                    "{}\t{}\t{}\t{}",
                    spec.id,
                    spec.category,
                    spec.title,
                    value
                );
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
                eprintln!("usage: tweak set <id> <value>");
                return 2;
            };
            if let Err(e) = core_authorize(tweak::operation_for(id)) { eprintln!("{e}"); return 1; }
            if let Err(e) = kernel_license_ok() {
                eprintln!("tweak blocked: {e}");
                return 4;
            }
            let Some(spec) = tweak::find(id) else {
                eprintln!("unknown tweak '{id}'");
                return 2;
            };
            if args.lite && spec.lite {
                eprintln!("tweak '{id}' is disabled in lite mode");
                return 2;
            }

            let family = family::load(&Path::new(&args.state).join("database"), topology.identity.vendor);
            let plan = BackendPlan::resolve(&topology.identity, &topology, family.as_ref());
            let engine = Engine::new(&s, &topology, &plan);
            let mut nodes = all_managed_nodes(&s, &topology, &engine);
            nodes.extend(tweak::managed_nodes(&s, &topology));
            nodes.sort();
            nodes.dedup();
            let mut state = StateStore::new(&args.state);
            if let Err(e) = state.ensure_boot(&s, &nodes).and_then(|_| state.recover(&s)) {
                eprintln!("state recovery failed: {e}");
                return 3;
            }
            let sentinel = sentinel_evaluate(args, tweak::operation_for(id));
            if !sentinel.allowed {
                eprintln!("tweak blocked by Sentinel: {:?}", sentinel.reasons);
                return 4;
            }
            if let Err(e) = state.begin(&s, &nodes) {
                eprintln!("transaction begin failed: {e}");
                return 3;
            }
            match tweak::apply(id, value, &s, &topology) {
                Ok(effective) => match state.commit(None, None) {
                    Ok(()) => {
                        println!("{{\"ok\":true,\"id\":\"{}\",\"value\":\"{}\"}}", json_escape(id), json_escape(&effective));
                        0
                    }
                    Err(e) => {
                        let _ = state.recover(&s);
                        eprintln!("state commit failed: {e}");
                        3
                    }
                },
                Err(e) => {
                    if let Err(rollback) = state.rollback(&s) {
                        eprintln!("rollback failed: {rollback}");
                    }
                    eprintln!("tweak failed: {e}");
                    3
                }
            }
        }
        _ => {
            eprintln!("usage: tweak list|get <id>|set <id> <value>");
            2
        }
    }
}

fn cmd_inventory(args: &Args) -> i32 {
    if let Err(e) = core_authorize(Operation::ReadInventory) { eprintln!("{e}"); return 1; }
    let s = Sysroot::new(&args.root);
    let identity = platform::PlatformIdentity::detect(&s);
    let inv = inventory::collect(&s, identity.clone());
    if args.json { println!("{}", inventory::json(&inv)); }
    else {
        println!("device      : {} {}", identity.vendor.as_str(), identity.platform);
        println!("memory      : {:?} KiB available={:?}", inv.memory.total_kb, inv.memory.available_kb);
        println!("zram        : {} device(s)", inv.memory.zram.len());
        println!("storage     : {} device(s)", inv.storage.len());
        println!("network     : {} interface(s)", inv.network.interface_count);
        println!("SELinux     : {:?}", inv.security.selinux_enforcing);
        println!("verifiedboot: {:?}", inv.security.verified_boot);
    }
    0
}


fn cmd_thermal(args: &Args) -> i32 {
    if let Err(e) = core_authorize(Operation::ReadThermal) { eprintln!("{e}"); return 1; }
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
    match args.positional.first().map(String::as_str).unwrap_or("list") {
        "list" => {
            if let Err(e) = core_authorize(Operation::ReadProperties) { eprintln!("{e}"); return 1; }
            for spec in properties::list() { println!("{}\t{:?}\t{}", spec.key, spec.scope, spec.description); }
            0
        }
        "get" => {
            if let Err(e) = core_authorize(Operation::ReadProperties) { eprintln!("{e}"); return 1; }
            let Some(key) = args.positional.get(1) else { eprintln!("usage: prop get <key>"); return 2; };
            match properties::get(key) {
                Ok(v) => { println!("{v}"); 0 }
                Err(e) => { eprintln!("property read failed: {e}"); 3 }
            }
        }
        "set" => {
            if let Err(e) = core_authorize(Operation::SetProperty) { eprintln!("{e}"); return 1; }
            let (Some(key), Some(value)) = (args.positional.get(1), args.positional.get(2)) else {
                eprintln!("usage: prop set <key> <value>"); return 2;
            };
            match properties::set(key, value) {
                Ok(()) => { println!("{{\"ok\":true,\"key\":\"{}\"}}", json_escape(key)); 0 }
                Err(e) => { eprintln!("property write failed: {e}"); 3 }
            }
        }
        _ => { eprintln!("usage: prop list|get <key>|set <key> <value>"); 2 }
    }
}

fn cmd_optimize(args: &Args) -> i32 {
    if let Err(e) = core_authorize(Operation::ReadPerformance) { eprintln!("{e}"); return 1; }
    let s = Sysroot::new(&args.root);
    let identity = platform::PlatformIdentity::detect(&s);
    let inv = inventory::collect(&s, identity.clone());
    let plan = optimize::plan(&s, &identity, &inv);
    if args.json { println!("{}", optimize::json(&plan)); }
    else {
        println!("RAM class            : {}", plan.ram_class);
        println!("storage class        : {}", plan.storage_class);
        println!("thermal safe         : {}", plan.thermal_safe);
        println!("network control      : {}", plan.network_control_available);
        println!("safe controls        : {}", plan.safe_controls.join(", "));
        println!("blocked controls     : {}", plan.blocked_controls.join(", "));
        for note in plan.notes { println!("note                 : {note}"); }
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
    match inv.security.verified_boot.as_deref().map(|v| v.trim().to_ascii_lowercase()) {
        Some(v) if v == "green" => zairenkai_core::sentinel::BootIntegrity::Green,
        Some(v) if v == "yellow" => zairenkai_core::sentinel::BootIntegrity::Yellow,
        Some(v) if v == "orange" => zairenkai_core::sentinel::BootIntegrity::Orange,
        Some(v) if v == "red" => zairenkai_core::sentinel::BootIntegrity::Red,
        _ => zairenkai_core::sentinel::BootIntegrity::Unknown,
    }
}

fn sentinel_evaluate(args: &Args, operation: Operation) -> zairenkai_core::sentinel::SentinelDecision {
    let s = Sysroot::new(&args.root);
    let identity = platform::PlatformIdentity::detect(&s);
    let inv = inventory::collect(&s, identity.clone());
    let compatible = Zkfc::open().ok().and_then(|z| z.version().ok()).is_some_and(|v| v.api_compatible());
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
    let battery_pct = s.read_u64("/sys/class/power_supply/battery/capacity").map(|v| v.min(100) as u8);
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
    let Some(name) = args.positional.first() else { eprintln!("usage: sentinel <operation>"); return 2; };
    let Some(operation) = parse_operation_name(name) else { eprintln!("unknown operation '{name}'"); return 2; };
    if let Err(e) = core_authorize(operation) { eprintln!("{e}"); return 1; }
    let decision = sentinel_evaluate(args, operation);
    if args.json {
        println!("{{\"allowed\":{},\"class\":\"{:?}\",\"reasons\":[{}]}}", decision.allowed, decision.class,
            decision.reasons.iter().map(|r| format!("\"{:?}\"", r)).collect::<Vec<_>>().join(","));
    } else {
        println!("allowed: {}\nclass  : {:?}", decision.allowed, decision.class);
        for reason in decision.reasons { println!("reason : {:?}", reason); }
    }
    if decision.allowed { 0 } else { 4 }
}

fn cmd_adaptive(args: &Args) -> i32 {
    let s = Sysroot::new(&args.root);
    let topology = Topology::detect(&s);
    let profile = match load_profile(args.profile.as_deref(), Path::new(&args.state), &topology.identity) {
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
        state.mode().unwrap_or_else(|| profile.meta.default_mode.clone())
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

    let observe = profile.adaptive.enabled
        && effective != "powersave"
        && scene.kind.performance_candidate();
    let mut workload_analyzer = workload::WorkloadAnalyzer::new();
    let workload = if observe {
        workload_analyzer.sample_with_gpu(&s, scene.package.as_deref(), topology.gpu.as_ref().map(|g| g.devfreq.as_str()))
    } else {
        None
    };
    let mut frame_analyzer = frame::FrameAnalyzer::new();
    let frame = if observe && workload.as_ref().is_some_and(|w| w.active) {
        let refresh_hz = frame_analyzer.current_refresh_hz();
        frame_analyzer.sample(
            scene.package.as_deref().unwrap_or_default(),
            refresh_hz,
            profile.adaptive.frame_budget_ms,
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
        base_uclamp,
        &profile.adaptive,
    );

    let zkfc_caps = Zkfc::open().ok().and_then(|z| z.capabilities().ok()).map(|c| c.kernel_caps());
    if args.json {
        println!(
            "{{\"scene\":\"{}\",\"kind\":\"{:?}\",\"event\":\"{:?}\",\"effective\":\"{}\",\"observe\":{},\"workload\":{},\"gpu_util_pct\":{},\"run_queue_delay_ms\":{},\"frame_available\":{},\"frame_fresh\":{},\"fps\":{:.3},\"p95_ms\":{:.3},\"jank_pct\":{:.3},\"thermal_headroom_permille\":{},\"decision_active\":{},\"extra_boost_pct\":{},\"uclamp_min_pct\":{},\"cpu_floor_pct\":{},\"confidence\":{},\"reason\":\"{}\",\"zkfc_perf_capable\":{}}}",
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
            zkfc_caps.is_some_and(|caps| caps & zkfc_sys::ZKFC_CAP_TUNE_PERF != 0),
        );
    } else {
        println!("scene       : {} ({:?}, {:?})", scene.package.as_deref().unwrap_or("none"), scene.kind, scene.event);
        println!("effective   : {effective}");
        println!("observe     : {observe}");
        if let Some(w) = workload {
            println!("workload    : pid={} cpu={:.1}% top={:.1}% render={:.1}% gpu={:?}% rq={:?}ms active={} confidence={}", w.pid, w.cpu_util_pct, w.top_thread_util_pct(), w.render_util_pct(), w.gpu_util_pct, w.run_queue_delay_ms, w.active, w.confidence);
        } else {
            println!("workload    : unavailable");
        }
        println!("frame       : available={} fresh={} new={} fps={:.1} p95={:.2}ms jank={:.1}% budget={:.2}ms", frame.available, frame.fresh, frame.new_frames, frame.fps, frame.p95_ms, frame.jank_ratio * 100.0, frame.budget_ms);
        println!("thermal     : headroom={:?} critical={} complete={}", thermal_snapshot.headroom_permille, thermal_snapshot.critical_reached, thermal_snapshot.telemetry_complete);
        println!("decision    : active={} boost=+{}% uclamp={}%% cpu_floor={}%% confidence={} reason={}", decision.active, decision.extra_boost_pct, decision.target_uclamp_min_pct, decision.cpu_floor_pct, decision.confidence, decision.reason);
        println!("zkfc perf   : {}", zkfc_caps.is_some_and(|caps| caps & zkfc_sys::ZKFC_CAP_TUNE_PERF != 0));
    }
    0
}

fn cmd_reset(args: &Args) -> i32 {
    if let Err(e) = core_authorize(Operation::ResetRuntime) { eprintln!("{e}"); return 1; }
    let s = Sysroot::new(&args.root);
    let topology = Topology::detect(&s);
    let family = family::load(&Path::new(&args.state).join("database"), topology.identity.vendor);
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

fn cmd_modes(args: &Args) -> i32 {
    let topology = Topology::detect(&Sysroot::new(&args.root));
    match load_profile(
        args.profile.as_deref(),
        Path::new(&args.state),
        &topology.identity,
    ) {
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
    let family = family::load(&Path::new(&args.state).join("database"), topology.identity.vendor);
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
    let desired = state.mode().unwrap_or_else(|| "balance".into());
    let effective = state.effective_mode().unwrap_or_else(|| {
        if desired == "auto" {
            scene::resolve_auto(&Sysroot::new(&args.root)).into()
        } else {
            desired.clone()
        }
    });
    let auto = desired == "auto";
    if args.json {
        println!(
            "{{\"ok\":true,\"desired\":\"{}\",\"effective\":\"{}\",\"auto\":{},\"pending_transaction\":{},\"safe_mode\":{}}}",
            json_escape(&desired),
            json_escape(&effective),
            auto,
            state.has_pending_transaction(),
            Path::new(SAFE_MODE).exists(),
        );
    } else {
        println!("desired   : {desired}\neffective : {effective}\nauto      : {auto}");
    }
    0
}


fn acquire_daemon_lock(state: &Path) -> std::io::Result<File> {
    fs::create_dir_all(state)?;
    let file = OpenOptions::new()
        .create(true)
        .read(true)
        .write(true)
        .open(state.join("daemon.lock"))?;
    let rc = unsafe { libc::flock(std::os::fd::AsRawFd::as_raw_fd(&file), libc::LOCK_EX | libc::LOCK_NB) };
    if rc != 0 {
        return Err(std::io::Error::last_os_error());
    }
    Ok(file)
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
        libc::signal(libc::SIGTERM, on_signal as libc::sighandler_t);
        libc::signal(libc::SIGINT, on_signal as libc::sighandler_t);
    }

    let s = Sysroot::new(&args.root);
    let topology = Topology::detect(&s);
    let family = family::load(&Path::new(&args.state).join("database"), topology.identity.vendor);
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
    eprintln!("zperfd: {} profile '{}' family={}", topology.flavor(), profile.meta.name, family.as_ref().map(|f| f.vendor.as_str()).unwrap_or("none"));

    // Adaptive control is a separate runtime layer above the durable profile.
    // Base-mode changes remain transactional; transient task boosts/affinity
    // are owned by ZKFC + ThreadTaskController and are always reset when the
    // scene changes, safe mode activates, or the daemon stops.
    let mut scene_engine = scene_engine::SceneEngine::new();
    let mut workload_analyzer = workload::WorkloadAnalyzer::new();
    let mut frame_analyzer = frame::FrameAnalyzer::new();
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
    let mut was_safe = false;
    while !STOP.load(Ordering::SeqCst) {
        let safe = Path::new(SAFE_MODE).exists();
        if safe {
            if !was_safe {
                if let Err(e) = state.restore_baseline(&s, &nodes) {
                    eprintln!("zperfd: SAFE MODE restore failed: {e}");
                } else {
                    eprintln!("zperfd: SAFE MODE active; baseline restored");
                }
            }
            was_safe = true;
            task_controller.reset();
            fas_controller.reset();
            last_adaptive_report = None;
            workload_analyzer.reset();
            frame_analyzer.reset();
            last_frame_probe = None;
            scene_engine.reset();
            last.clear();
            last_observed = None;
            next_reconcile = Instant::now() + reconcile_every;
            std::thread::sleep(Duration::from_millis(args.interval_ms.max(500)));
            continue;
        }
        if was_safe {
            // The user explicitly cleared safe mode. Re-apply the desired
            // policy even when its effective name has not changed.
            last.clear();
            was_safe = false;
        }

        let scene_snapshot = scene_engine.observe(&s, &profile);
        if matches!(scene_snapshot.event, scene_engine::SceneEvent::Switch | scene_engine::SceneEvent::Exit) {
            task_controller.reset();
            fas_controller.reset();
            last_adaptive_report = None;
            workload_analyzer.reset();
            frame_analyzer.reset();
            last_frame_probe = None;
        }
        let desired_raw = state.mode().unwrap_or_else(|| profile.meta.default_mode.clone());
        let desired = if desired_raw == "auto" || profile.mode.contains_key(&desired_raw) {
            desired_raw
        } else {
            eprintln!("zperfd: invalid persisted mode '{desired_raw}', falling back to '{}'", profile.meta.default_mode);
            profile.meta.default_mode.clone()
        };
        let mut effective = if desired == "auto" {
            scene::resolve_auto_with_previous(&s, (!last.is_empty()).then_some(last.as_str())).to_string()
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
            last_frame_probe = None;
            was_adaptive_observing = adaptive_observation;
        }
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
                    )
                        || w.cpu_util_pct >= 20.0
                        || w.top_thread_util_pct() >= 20.0
                        || w.render_util_pct() >= 15.0
                        || w.gpu_util_pct.is_some_and(|v| v >= 70.0))
            }) {
            if let Some(pkg) = scene_snapshot.package.as_deref() {
                let probe_due = last_frame_probe
                    .map(|t| Instant::now().saturating_duration_since(t) >= Duration::from_millis(profile.adaptive.frame_probe_ms as u64))
                    .unwrap_or(true);
                if probe_due {
                    last_frame_probe = Some(Instant::now());
                    let refresh_hz = frame_analyzer.current_refresh_hz();
                    frame_analyzer.sample(pkg, refresh_hz, profile.adaptive.frame_budget_ms)
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
                            eprintln!("apply failed: validated profile is missing mode '{effective}'");
                            return 3;
                        };
                        let thermal_snapshot = thermal::snapshot(&s, &topology);
                        if thermal_snapshot.telemetry_complete {
                            if let Err(e) = configure_kernel_thermal_guard(args, &thermal_snapshot) {
                                eprintln!("zperfd: thermal guard configuration failed: {e}");
                                let _ = state.rollback(&s);
                                std::thread::sleep(Duration::from_millis(args.interval_ms.max(500)));
                                continue;
                            }
                        } else {
                            eprintln!("zperfd: thermal trip topology incomplete; limited envelope active; no new kernel guard configured");
                        }
                        let therm = thermal::envelope(&thermal_snapshot);
                        let constrained = thermal::constrain_mode(mode, therm);
                        eprintln!("zperfd: thermal envelope band={:?} boost={}‰ cap={:?}", therm.band, therm.boost_permille, therm.max_perf_cap_pct);
                        let report = engine.apply_mode(&constrained);
                        if report.failed.is_empty() && !report.applied.is_empty() {
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
                        } else if let Err(e) = task_controller.apply(&decision, workload, &performance_cpus, performance_policy) {
                            eprintln!("zperfd: adaptive actuator failed: {e}");
                        } else {
                            let key = (workload.pid, decision.extra_boost_pct);
                            if last_adaptive_report != Some(key) {
                                eprintln!(
                                    "zperfd: adaptive scene={} age_ms={} reason={} pid={} cpu={:.1}% rss_kb={:?} io_r={}B/s io_w={}B/s gpu={:?}% rq={:?}ms frame_src={} fresh={} fps={:.1} avg={:.2}ms p95={:.2}ms jank={:.1}% boost=+{}% uclamp={} cpu_floor={} confidence={}",
                                    scene_snapshot.package.as_deref().unwrap_or("none"),
                                    scene_snapshot.age.as_millis(),
                                    scene_snapshot.reason,
                                    workload.pid,
                                    workload.cpu_util_pct,
                                    workload.rss_kb,
                                    workload.io_read_bps.unwrap_or(0),
                                    workload.io_write_bps.unwrap_or(0),
                                    frame_metrics.source,
                                    frame_metrics.fresh,
                                    frame_metrics.fps,
                                    frame_metrics.avg_ms,
                                    frame_metrics.p95_ms,
                                    frame_metrics.jank_ratio * 100.0,
                                    workload.gpu_util_pct,
                                    workload.run_queue_delay_ms,
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
                        eprintln!("zperfd: adaptive release scene={} reason={}", scene_snapshot.package.as_deref().unwrap_or("none"), decision.reason);
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
    last_adaptive_report = None;
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
        "core" => if args.positional.first().map(|x| x.as_str()) == Some("permissions") { cmd_core_permissions(args.json) } else { cmd_core(args.json) },
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
