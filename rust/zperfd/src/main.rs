// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! zperfd — resident Zairenkai performance policy engine.
//!
//! Mutations are transactional, durable and kernel-license-gated. The daemon
//! owns desired/effective mode state; the Android app is a client.
//!
//! Copyright (C) 2026 FebriCahyaa

mod backends;
mod catalog;
mod config;
mod engine;
mod family;
mod nodes;
mod platform;
mod scene;
mod state;
mod telemetry;
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
use zkfc_sys::{Zkfc, ZKFC_API_VERSION};

const GENERIC: &str = include_str!("../profiles/generic.toml");
const LAVENDER: &str = include_str!("../profiles/lavender.toml");
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
        interval_ms: 1500,
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

fn cmd_probe(s: &Sysroot, state: &Path, json: bool) -> i32 {
    let topology = Topology::detect(s);
    let family = family::load(&state.join("database"), topology.identity.vendor);
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
            "{{\"ok\":true,\"vendor\":\"{}\",\"soc\":\"{}\",\"flavor\":\"{}\",\"kernel_generation\":\"{}\",\"release\":\"{}\",\"cgroup_v2\":{},\"has_msm_perf\":{},\"boost\":{},\"backend\":{{\"cpu\":{},\"boost\":\"{:?}\",\"gpu\":\"{:?}\",\"thermal_providers\":[{}],\"vendor_surfaces\":[{}],\"family_profile\":{}}},\"policies\":[{}],\"gpu\":{},\"thermal\":[{}],\"kernel_capabilities\":{},\"telemetry\":{}}}",
            topology.identity.vendor.as_str(),
            json_escape(&topology.identity.soc_key()),
            topology.flavor(),
            json_escape(&topology.kernel_generation),
            json_escape(&topology.release),
            topology.cgroup_v2,
            topology.has_msm_perf,
            boost,
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
            "vendor      : {}\nsoc         : {}\nflavor      : {}\nkernel gen  : {}\nrelease     : {}\ncgroup v2   : {}\nmsm_perf    : {}\ngpu         : {}\nthermal     : {} zones\nfam policy  : {}\nbackend     : boost={:?}",
            topology.identity.vendor.as_str(),
            topology.identity.soc_key(),
            topology.flavor(),
            topology.kernel_generation,
            topology.release,
            topology.cgroup_v2,
            topology.has_msm_perf,
            topology.gpu.as_ref().map(|g| g.provider.as_str()).unwrap_or("none"),
            topology.thermal_zones.len(),
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

fn apply_transaction(args: &Args, requested: &str) -> i32 {
    if let Err(e) = kernel_license_ok() {
        eprintln!("apply blocked: {e}");
        return 4;
    }

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
    let Some(mode) = profile.mode(&effective) else {
        let _ = state.rollback(&s);
        eprintln!("apply failed: validated profile is missing mode '{effective}'");
        return 3;
    };
    let report = engine.apply_mode(mode);
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

fn cmd_reset(args: &Args) -> i32 {
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
        if let Some(package) = scene::foreground_pkg() {
            if let Some(mode) = profile.perapp.get(&package) {
                effective = mode.clone();
            }
        }

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
                    if let Err(e) = state.begin(&s, &nodes) {
                        eprintln!("zperfd: transaction begin failed: {e}");
                    } else {
                        let Some(mode) = profile.mode(&effective) else {
                            let _ = state.rollback(&s);
                            eprintln!("apply failed: validated profile is missing mode '{effective}'");
                            return 3;
                        };
                        let report = engine.apply_mode(mode);
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

        std::thread::sleep(Duration::from_millis(args.interval_ms.max(250)));
    }
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
