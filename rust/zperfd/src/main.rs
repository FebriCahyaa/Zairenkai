// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! zperfd — Zairenkai universal performance engine.
//!
//! A probe-driven userspace daemon that applies 4-mode performance profiles
//! (powersave/balance/performance/fast) on both GKI and non-GKI kernels. It
//! resolves device-agnostic profiles against the real OPP tables, picks uclamp
//! vs schedtune automatically, and reacts to the foreground app.
//!
//! Subcommands:
//!   zperfd probe   [--root R] [--json]
//!   zperfd modes   [--profile F]
//!   zperfd apply   <mode> [--profile F] [--root R] [--no-lock]
//!   zperfd reset   [--root R]
//!   zperfd daemon  [--profile F] [--state DIR] [--interval MS] [--root R]
//!
//! Copyright (C) 2026 FebriCahyaa

mod config;
mod engine;
mod nodes;
mod scene;
mod topo;

use config::Profile;
use engine::Engine;
use nodes::Sysroot;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;
use topo::Topology;

const GENERIC: &str = include_str!("../profiles/generic.toml");
const LAVENDER: &str = include_str!("../profiles/lavender.toml");

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
    no_lock: bool,
    json: bool,
}

fn parse_args() -> Args {
    let mut a = Args {
        cmd: String::new(),
        positional: Vec::new(),
        root: "/".into(),
        profile: None,
        state: "/data/adb/zperf".into(),
        interval_ms: 1500,
        no_lock: false,
        json: false,
    };
    let mut it = std::env::args().skip(1);
    while let Some(arg) = it.next() {
        match arg.as_str() {
            "--root" => a.root = it.next().unwrap_or_else(|| "/".into()),
            "--profile" => a.profile = it.next(),
            "--state" => a.state = it.next().unwrap_or_else(|| a.state.clone()),
            "--interval" => a.interval_ms = it.next().and_then(|v| v.parse().ok()).unwrap_or(1500),
            "--no-lock" => a.no_lock = true,
            "--json" => a.json = true,
            "-h" | "--help" => {
                print_help();
                std::process::exit(0);
            }
            _ => {
                if a.cmd.is_empty() {
                    a.cmd = arg;
                } else {
                    a.positional.push(arg);
                }
            }
        }
    }
    a
}

fn print_help() {
    eprintln!(
        "zperfd — Zairenkai performance engine\n\
         usage:\n  \
         zperfd probe  [--root R] [--json]\n  \
         zperfd modes  [--profile F]\n  \
         zperfd apply  <mode> [--profile F] [--root R] [--no-lock]\n  \
         zperfd reset  [--root R]\n  \
         zperfd daemon [--profile F] [--state DIR] [--interval MS] [--root R]"
    );
}

fn load_profile(explicit: Option<&str>, state: &Path, soc: Option<&str>) -> Result<Profile, String> {
    if let Some(p) = explicit {
        let text = fs::read_to_string(p).map_err(|e| format!("read {p}: {e}"))?;
        return Profile::parse(&text);
    }
    let f = state.join("profile.toml");
    if f.exists() {
        let text = fs::read_to_string(&f).map_err(|e| e.to_string())?;
        return Profile::parse(&text);
    }
    if let Some(soc) = soc {
        let cat = state.join("catalog").join(format!("{soc}.toml"));
        if cat.exists() {
            let text = fs::read_to_string(&cat).map_err(|e| e.to_string())?;
            return Profile::parse(&text);
        }
        if soc.contains("660") {
            return Profile::parse(LAVENDER);
        }
    }
    Profile::parse(GENERIC)
}

fn json_escape(s: &str) -> String {
    let mut o = String::with_capacity(s.len() + 2);
    for c in s.chars() {
        match c {
            '"' => o.push_str("\\\""),
            '\\' => o.push_str("\\\\"),
            '\n' => o.push_str("\\n"),
            '\r' => o.push_str("\\r"),
            '\t' => o.push_str("\\t"),
            c if (c as u32) < 0x20 => o.push_str(&format!("\\u{:04x}", c as u32)),
            c => o.push(c),
        }
    }
    o
}

fn cmd_probe(s: &Sysroot, json: bool) -> i32 {
    let t = Topology::detect(s);
    if json {
        let mut pol = Vec::new();
        for p in &t.policies {
            pol.push(format!(
                "{{\"name\":\"{}\",\"min_hw\":{},\"max_hw\":{},\"opps\":{},\"min_opp\":{},\"max_opp\":{}}}",
                json_escape(&p.name),
                p.min_hw,
                p.max_hw,
                p.avail.len(),
                p.avail.first().copied().unwrap_or(0),
                p.avail.last().copied().unwrap_or(0),
            ));
        }
        let gpu = match &t.gpu {
            Some(g) => format!(
                "{{\"kind\":\"{:?}\",\"opps\":{},\"min\":{},\"max\":{}}}",
                g.kind,
                g.avail.len(),
                g.avail.first().copied().unwrap_or(0),
                g.avail.last().copied().unwrap_or(0),
            ),
            None => "null".into(),
        };
        println!(
            "{{\"flavor\":\"{}\",\"gki\":{},\"release\":\"{}\",\"cgroup_v2\":{},\"has_msm_perf\":{},\
             \"boost\":{{\"uclamp\":{},\"schedtune\":{},\"cpu_boost\":{}}},\"policies\":[{}],\"gpu\":{}}}",
            t.flavor(),
            t.gki,
            json_escape(&t.release),
            t.cgroup_v2,
            t.has_msm_perf,
            t.top_app_uclamp.is_some(),
            t.stune_top.is_some(),
            t.cpu_boost_dir.is_some(),
            pol.join(","),
            gpu,
        );
    } else {
        println!("flavor      : {}", t.flavor());
        println!("release     : {}", t.release);
        println!("cgroup v2   : {}", t.cgroup_v2);
        println!("msm_perf    : {}", t.has_msm_perf);
        println!(
            "boost       : uclamp={} schedtune={} cpu_boost={}",
            t.top_app_uclamp.is_some(),
            t.stune_top.is_some(),
            t.cpu_boost_dir.is_some()
        );
        for p in &t.policies {
            println!(
                "{:<9}  : {} OPPs  {}..{} kHz",
                p.name,
                p.avail.len(),
                p.avail.first().copied().unwrap_or(0),
                p.avail.last().copied().unwrap_or(0)
            );
        }
        match &t.gpu {
            Some(g) => println!("gpu         : {:?}  {} OPPs  {}..{}", g.kind, g.avail.len(),
                g.avail.first().copied().unwrap_or(0), g.avail.last().copied().unwrap_or(0)),
            None => println!("gpu         : none detected"),
        }
    }
    0
}

fn cmd_apply(a: &Args, mode: &str) -> i32 {
    let s = Sysroot::new(&a.root);
    let t = Topology::detect(&s);
    let prof = match load_profile(a.profile.as_deref(), Path::new(&a.state), scene::soc_platform().as_deref()) {
        Ok(p) => p,
        Err(e) => {
            eprintln!("profile error: {e}");
            return 2;
        }
    };
    let m = match prof.mode(mode) {
        Some(m) => m,
        None => {
            eprintln!("unknown mode '{mode}' (have: {})", prof.mode.keys().cloned().collect::<Vec<_>>().join(", "));
            return 2;
        }
    };
    let mut eng = Engine::new(&s, &t);
    eng.lock = !a.no_lock;
    let rep = eng.apply_mode(m);
    println!("[{}] applied {} node(s), skipped {}", mode, rep.applied.len(), rep.skipped.len());
    for x in &rep.applied {
        println!("  + {x}");
    }
    0
}

fn cmd_reset(a: &Args) -> i32 {
    let s = Sysroot::new(&a.root);
    let t = Topology::detect(&s);
    let eng = Engine::new(&s, &t);
    let rep = eng.reset();
    println!("reset: {} node(s)", rep.applied.len());
    0
}

fn cmd_modes(a: &Args) -> i32 {
    match load_profile(a.profile.as_deref(), Path::new(&a.state), scene::soc_platform().as_deref()) {
        Ok(p) => {
            println!("profile: {} (soc={})", p.meta.name, p.meta.soc);
            println!("default: {}", p.meta.default_mode);
            println!("modes  : {}", p.mode.keys().cloned().collect::<Vec<_>>().join(", "));
            0
        }
        Err(e) => {
            eprintln!("profile error: {e}");
            2
        }
    }
}

fn cmd_daemon(a: &Args) -> i32 {
    unsafe {
        libc::signal(libc::SIGTERM, on_signal as *const () as libc::sighandler_t);
        libc::signal(libc::SIGINT, on_signal as *const () as libc::sighandler_t);
    }
    let s = Sysroot::new(&a.root);
    let t = Topology::detect(&s);
    let state = PathBuf::from(&a.state);
    let _ = fs::create_dir_all(&state);
    let prof = match load_profile(a.profile.as_deref(), &state, scene::soc_platform().as_deref()) {
        Ok(p) => p,
        Err(e) => {
            eprintln!("profile error: {e}");
            return 2;
        }
    };
    let mut eng = Engine::new(&s, &t);
    eng.lock = !a.no_lock;

    eprintln!(
        "zperfd: {} profile '{}', {} policies, default '{}'",
        t.flavor(),
        prof.meta.name,
        t.policies.len(),
        prof.meta.default_mode
    );

    let mode_file = state.join("mode");
    let mut last_applied = String::new();
    let mut last_pkg = String::new();

    while !STOP.load(Ordering::SeqCst) {
        let base = fs::read_to_string(&mode_file).ok().map(|s| s.trim().to_string()).unwrap_or_else(|| prof.meta.default_mode.clone());
        let pkg = scene::foreground_pkg();
        let eff = pkg
            .as_ref()
            .and_then(|p| prof.perapp.get(p).cloned())
            .unwrap_or_else(|| base.clone());

        if eff != last_applied || pkg != Some(last_pkg.clone()) {
            if let Some(m) = prof.mode(&eff) {
                let rep = eng.apply_mode(m);
                eprintln!(
                    "zperfd: mode '{}' ({}), applied {}",
                    eff,
                    pkg.as_deref().unwrap_or("-"),
                    rep.applied.len()
                );
                last_applied = eff;
                last_pkg = pkg.unwrap_or_default();
            }
        }
        // sleep in short slices so a signal stops us promptly.
        let mut slept = 0u64;
        while slept < a.interval_ms && !STOP.load(Ordering::SeqCst) {
            std::thread::sleep(Duration::from_millis(100));
            slept += 100;
        }
    }
    eprintln!("zperfd: stopping, restoring stock limits");
    let _ = eng.reset();
    0
}

fn main() {
    let a = parse_args();
    let code = match a.cmd.as_str() {
        "probe" => cmd_probe(&Sysroot::new(&a.root), a.json),
        "apply" => match a.positional.first() {
            Some(m) => cmd_apply(&a, &m.clone()),
            None => {
                eprintln!("apply: missing <mode>");
                2
            }
        },
        "reset" => cmd_reset(&a),
        "modes" => cmd_modes(&a),
        "daemon" => cmd_daemon(&a),
        "" => {
            print_help();
            1
        }
        other => {
            eprintln!("unknown command '{other}'");
            print_help();
            1
        }
    };
    std::process::exit(code);
}
