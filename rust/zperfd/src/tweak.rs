// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Safe individual-tweak engine.
//!
//! The legacy C catalog remains the UI/read compatibility layer, but every
//! mutation now passes through zperfd so profile and single-tweak writes share
//! the same license gate, state transaction and device-aware validation.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::nodes::Sysroot;
use crate::topo::Topology;

#[derive(Clone, Copy)]
pub struct Spec {
    pub id: &'static str,
    pub category: &'static str,
    pub title: &'static str,
    pub lite: bool,
}

const SPECS: &[Spec] = &[
    Spec { id: "cpu_governor", category: "cpu", title: "CPU governor", lite: false },
    Spec { id: "sched_latency_ns", category: "cpu", title: "Scheduler latency (ns)", lite: false },
    Spec { id: "sched_min_granularity_ns", category: "cpu", title: "Scheduler min granularity (ns)", lite: false },
    Spec { id: "sched_migration_cost_ns", category: "cpu", title: "Task migration cost (ns)", lite: false },
    Spec { id: "sched_boost", category: "cpu", title: "WALT scheduler boost", lite: true },
    Spec { id: "gpu_governor", category: "gpu", title: "GPU governor", lite: false },
    Spec { id: "gpu_min_freq", category: "gpu", title: "GPU minimum frequency", lite: false },
    Spec { id: "gpu_max_freq", category: "gpu", title: "GPU maximum frequency", lite: false },
    Spec { id: "gpu_force_clk", category: "gpu", title: "GPU force clock on", lite: true },
    Spec { id: "swappiness", category: "memory", title: "Swappiness", lite: false },
    Spec { id: "dirty_ratio", category: "memory", title: "Dirty ratio", lite: false },
    Spec { id: "dirty_background_ratio", category: "memory", title: "Dirty background ratio", lite: false },
    Spec { id: "vfs_cache_pressure", category: "memory", title: "VFS cache pressure", lite: false },
    Spec { id: "min_free_kbytes", category: "memory", title: "Min free memory (KiB)", lite: false },
    Spec { id: "vm_stat_interval", category: "memory", title: "VM stat interval (s)", lite: true },
    Spec { id: "lmk_minfree", category: "memory", title: "LMK minfree", lite: true },
    Spec { id: "zram_comp_algorithm", category: "zram", title: "zram compression", lite: false },
    Spec { id: "zram_disksize", category: "zram", title: "zram size (bytes)", lite: true },
    Spec { id: "zram_max_comp_streams", category: "zram", title: "zram comp streams", lite: true },
    Spec { id: "io_scheduler", category: "io", title: "I/O scheduler", lite: false },
    Spec { id: "fsync", category: "io", title: "fsync enabled", lite: true },
    Spec { id: "tcp_congestion_control", category: "network", title: "TCP congestion control", lite: false },
    Spec { id: "tcp_fastopen", category: "network", title: "TCP fast open", lite: false },
    Spec { id: "tcp_low_latency", category: "network", title: "TCP low latency", lite: true },
    Spec { id: "kcal", category: "display", title: "Display color (R G B)", lite: true },
    Spec { id: "kcal_sat", category: "display", title: "Display saturation", lite: true },
    Spec { id: "charge_limit", category: "battery", title: "Charge control limit", lite: false },
];

pub fn find(id: &str) -> Option<&'static Spec> {
    SPECS.iter().find(|spec| spec.id == id)
}

pub fn specs() -> impl Iterator<Item = &'static Spec> {
    SPECS.iter()
}

pub fn is_lite(id: &str) -> Option<bool> {
    find(id).map(|spec| spec.lite)
}

pub fn read(id: &str, s: &Sysroot, t: &Topology) -> Option<String> {
    if find(id).is_none() { return None; }
    match id {
        "cpu_governor" => t.policies.first().and_then(|p| s.read(&p.rel("scaling_governor"))),
        "sched_latency_ns" => s.read("/proc/sys/kernel/sched_latency_ns"),
        "sched_min_granularity_ns" => s.read("/proc/sys/kernel/sched_min_granularity_ns"),
        "sched_migration_cost_ns" => s.read("/proc/sys/kernel/sched_migration_cost_ns"),
        "sched_boost" => first_existing(s, &["/proc/sys/walt/sched_boost", "/proc/sys/kernel/sched_boost"]).and_then(|p| s.read(&p)),
        "gpu_governor" => t.gpu.as_ref().and_then(|g| s.read(&g.rel("governor"))),
        "gpu_min_freq" => t.gpu.as_ref().and_then(|g| s.read(&g.rel("min_freq"))),
        "gpu_max_freq" => t.gpu.as_ref().and_then(|g| s.read(&g.rel("max_freq"))),
        "gpu_force_clk" => s.read("/sys/class/kgsl/kgsl-3d0/force_clk_on"),
        "swappiness" => s.read("/proc/sys/vm/swappiness"),
        "dirty_ratio" => s.read("/proc/sys/vm/dirty_ratio"),
        "dirty_background_ratio" => s.read("/proc/sys/vm/dirty_background_ratio"),
        "vfs_cache_pressure" => s.read("/proc/sys/vm/vfs_cache_pressure"),
        "min_free_kbytes" => s.read("/proc/sys/vm/min_free_kbytes"),
        "vm_stat_interval" => s.read("/proc/sys/vm/stat_interval"),
        "lmk_minfree" => s.read("/sys/module/lowmemorykiller/parameters/minfree"),
        "zram_comp_algorithm" => s.read("/sys/block/zram0/comp_algorithm"),
        "zram_disksize" => s.read("/sys/block/zram0/disksize"),
        "zram_max_comp_streams" => s.read("/sys/block/zram0/max_comp_streams"),
        "io_scheduler" => s.list_dir("/sys/block").into_iter().find_map(|dev| s.read(&format!("/sys/block/{dev}/queue/scheduler"))),
        "fsync" => s.read("/sys/module/sync/parameters/fsync_enabled"),
        "tcp_congestion_control" => s.read("/proc/sys/net/ipv4/tcp_congestion_control"),
        "tcp_fastopen" => s.read("/proc/sys/net/ipv4/tcp_fastopen"),
        "tcp_low_latency" => s.read("/proc/sys/net/ipv4/tcp_low_latency"),
        "kcal" => s.read("/sys/devices/platform/kcal_ctrl.0/kcal"),
        "kcal_sat" => s.read("/sys/devices/platform/kcal_ctrl.0/kcal_sat"),
        "charge_limit" => first_existing(s, &[
            "/sys/class/power_supply/battery/charge_control_limit",
            "/sys/class/power_supply/battery/batt_slate_mode",
        ]).and_then(|p| s.read(&p)),
        _ => None,
    }
}


fn write_checked(s: &Sysroot, path: &str, value: &str) -> Result<(), String> {
    s.write(path, value, false).map_err(|e| format!("{path}: {e}"))
}

fn parse_range(value: &str, min: i64, max: i64) -> Result<String, String> {
    let n: i64 = value.trim().parse().map_err(|_| format!("expected integer {min}..{max}"))?;
    if !(min..=max).contains(&n) {
        return Err(format!("value {n} outside safe range {min}..{max}"));
    }
    Ok(n.to_string())
}

fn parse_choice(value: &str, available: &str) -> Result<String, String> {
    let value = value.trim();
    if value.is_empty() || value.bytes().any(|b| b.is_ascii_whitespace() || b == b'/' || b == b'\\') {
        return Err("invalid choice".into());
    }
    // sysfs choice nodes commonly mark the active value as `[choice]`; the
    // brackets are presentation syntax, not part of the accepted value.
    let choices: Vec<&str> = available
        .split_whitespace()
        .map(|token| token.trim_matches(['[', ']']))
        .collect();
    if !choices.is_empty() && !choices.contains(&value) {
        return Err(format!("'{value}' is not supported by this device"));
    }
    Ok(value.to_string())
}

fn first_existing(s: &Sysroot, paths: &[&str]) -> Option<String> {
    paths.iter().find(|p| s.exists(p)).map(|p| (*p).to_string())
}

fn validate_lmk(value: &str) -> Result<String, String> {
    let v = value.trim();
    if v.is_empty() || v.len() > 512 || !v.bytes().all(|b| b.is_ascii_digit() || b == b',' || b.is_ascii_whitespace()) {
        return Err("LMK minfree must be a bounded comma/space-separated integer list".into());
    }
    if v.split(|c: char| c == ',' || c.is_ascii_whitespace()).filter(|x| !x.is_empty()).any(|x| x.parse::<u64>().is_err()) {
        return Err("LMK minfree contains a non-integer".into());
    }
    Ok(v.to_string())
}

fn validate_kcal(value: &str) -> Result<String, String> {
    let nums: Vec<u16> = value
        .split_whitespace()
        .map(|part| part.parse::<u16>().map_err(|_| ()))
        .collect::<Result<_, _>>()
        .map_err(|_| "KCAL expects three 0..255 channel values".to_string())?;
    if nums.len() != 3 || nums.iter().any(|v| *v > 255) {
        return Err("KCAL expects three 0..255 channel values".into());
    }
    Ok(nums.iter().map(|v| v.to_string()).collect::<Vec<_>>().join(" "))
}

fn zram_size_limit(s: &Sysroot) -> u64 {
    let mem_kb = s
        .read("/proc/meminfo")
        .and_then(|text| text.lines().find(|line| line.starts_with("MemTotal:")))
        .and_then(|line| line.split_whitespace().nth(1))
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(2 * 1024 * 1024);
    // Permit up to 2x physical RAM, but never more than 16 GiB.
    (mem_kb.saturating_mul(1024).saturating_mul(2)).min(16 * 1024 * 1024 * 1024)
}

pub fn apply(id: &str, value: &str, s: &Sysroot, t: &Topology) -> Result<String, String> {
    if find(id).is_none() {
        return Err(format!("unknown tweak '{id}'"));
    }
    match id {
        "cpu_governor" => {
            if t.policies.is_empty() { return Err("no cpufreq policies detected".into()); }
            let mut applied = 0usize;
            for p in &t.policies {
                let avail = s.read(&p.rel("scaling_available_governors")).unwrap_or_default();
                let gov = parse_choice(value, &avail)?;
                write_checked(s, &p.rel("scaling_governor"), &gov)?;
                applied += 1;
            }
            Ok(format!("{applied} policies -> {value}"))
        }
        "sched_latency_ns" => write_proc(s, "/proc/sys/kernel/sched_latency_ns", value, 100_000, 100_000_000),
        "sched_min_granularity_ns" => write_proc(s, "/proc/sys/kernel/sched_min_granularity_ns", value, 100_000, 50_000_000),
        "sched_migration_cost_ns" => write_proc(s, "/proc/sys/kernel/sched_migration_cost_ns", value, 0, 50_000_000),
        "sched_boost" => {
            let v = parse_range(value, 0, 3)?;
            let path = first_existing(s, &["/proc/sys/walt/sched_boost", "/proc/sys/kernel/sched_boost"])
                .ok_or_else(|| "scheduler boost node unavailable".to_string())?;
            write_checked(s, &path, &v)?;
            Ok(v)
        }
        "gpu_governor" => {
            let g = t.gpu.as_ref().ok_or_else(|| "GPU devfreq not detected".to_string())?;
            let avail = s.read(&g.rel("available_governors")).unwrap_or_default();
            let gov = parse_choice(value, &avail)?;
            write_checked(s, &g.rel("governor"), &gov)?;
            Ok(gov)
        }
        "gpu_min_freq" | "gpu_max_freq" => {
            let g = t.gpu.as_ref().ok_or_else(|| "GPU devfreq not detected".to_string())?;
            if g.avail.is_empty() { return Err("GPU OPP table unavailable".into()); }
            let n: u64 = value.trim().parse().map_err(|_| "GPU frequency must be kHz".to_string())?;
            let lo = *g.avail.first().unwrap();
            let hi = *g.avail.last().unwrap();
            if n < lo || n > hi { return Err(format!("GPU frequency outside {lo}..{hi} kHz")); }
            let snapped = if id == "gpu_min_freq" {
                g.avail.iter().copied().find(|f| *f >= n).unwrap_or(hi)
            } else {
                g.avail.iter().copied().rev().find(|f| *f <= n).unwrap_or(lo)
            };
            write_checked(s, &g.rel(if id == "gpu_min_freq" { "min_freq" } else { "max_freq" }), &snapped.to_string())?;
            Ok(snapped.to_string())
        }
        "gpu_force_clk" => write_sys_first(s, &["/sys/class/kgsl/kgsl-3d0/force_clk_on"], value, 0, 1),
        "swappiness" => write_proc(s, "/proc/sys/vm/swappiness", value, 0, 200),
        "dirty_ratio" => write_proc(s, "/proc/sys/vm/dirty_ratio", value, 0, 100),
        "dirty_background_ratio" => write_proc(s, "/proc/sys/vm/dirty_background_ratio", value, 0, 100),
        "vfs_cache_pressure" => write_proc(s, "/proc/sys/vm/vfs_cache_pressure", value, 0, 500),
        "min_free_kbytes" => write_proc(s, "/proc/sys/vm/min_free_kbytes", value, 1024, 262_144),
        "vm_stat_interval" => write_proc(s, "/proc/sys/vm/stat_interval", value, 1, 120),
        "lmk_minfree" => {
            let node = "/sys/module/lowmemorykiller/parameters/minfree";
            let v = validate_lmk(value)?;
            write_checked(s, node, &v)?;
            Ok(v)
        }
        "zram_comp_algorithm" => {
            let node = "/sys/block/zram0/comp_algorithm";
            let available = s.read(node).ok_or_else(|| "zram0 compression node unavailable".to_string())?;
            let choice = parse_choice(value, &available)?;
            write_checked(s, node, &choice)?;
            Ok(choice)
        }
        "zram_disksize" => {
            let n: u64 = value.trim().parse().map_err(|_| "zram size must be bytes".to_string())?;
            let max = zram_size_limit(s);
            if n > max { return Err(format!("zram size exceeds safe cap {max} bytes")); }
            let node = "/sys/block/zram0/disksize";
            write_checked(s, node, &n.to_string())?;
            Ok(n.to_string())
        }
        "zram_max_comp_streams" => write_sys_first(s, &["/sys/block/zram0/max_comp_streams"], value, 1, 16),
        "io_scheduler" => {
            let mut count = 0usize;
            for dev in s.list_dir("/sys/block") {
                let node = format!("/sys/block/{dev}/queue/scheduler");
                if !s.exists(&node) { continue; }
                let avail = s.read(&node).unwrap_or_default();
                let choice = parse_choice(value, &avail)?;
                write_checked(s, &node, &choice)?;
                count += 1;
            }
            if count == 0 { return Err("no block I/O scheduler nodes found".into()); }
            Ok(format!("{count} block devices -> {value}"))
        }
        "fsync" => write_sys_first(s, &["/sys/module/sync/parameters/fsync_enabled"], value, 0, 1),
        "tcp_congestion_control" => {
            let node = "/proc/sys/net/ipv4/tcp_congestion_control";
            let avail = s.read("/proc/sys/net/ipv4/tcp_allowed_congestion_control").unwrap_or_default();
            let choice = parse_choice(value, &avail)?;
            write_checked(s, node, &choice)?;
            Ok(choice)
        }
        "tcp_fastopen" => write_proc(s, "/proc/sys/net/ipv4/tcp_fastopen", value, 0, 3),
        "tcp_low_latency" => write_proc(s, "/proc/sys/net/ipv4/tcp_low_latency", value, 0, 1),
        "kcal" => {
            let v = validate_kcal(value)?;
            write_checked(s, "/sys/devices/platform/kcal_ctrl.0/kcal", &v)?;
            Ok(v)
        }
        "kcal_sat" => write_sys_first(s, &["/sys/devices/platform/kcal_ctrl.0/kcal_sat"], value, 128, 383),
        "charge_limit" => {
            let node = first_existing(s, &[
                "/sys/class/power_supply/battery/charge_control_limit",
                "/sys/class/power_supply/battery/batt_slate_mode",
            ]).ok_or_else(|| "battery charge-limit node unavailable".to_string())?;
            let v = parse_range(value, 0, 100)?;
            write_checked(s, &node, &v)?;
            Ok(v)
        }
        _ => unreachable!(),
    }
}

fn write_proc(s: &Sysroot, node: &str, value: &str, min: i64, max: i64) -> Result<String, String> {
    let v = parse_range(value, min, max)?;
    if !s.exists(node) { return Err(format!("{node} unavailable")); }
    write_checked(s, node, &v)?;
    Ok(v)
}

fn write_sys_first(s: &Sysroot, paths: &[&str], value: &str, min: i64, max: i64) -> Result<String, String> {
    let v = parse_range(value, min, max)?;
    let node = first_existing(s, paths).ok_or_else(|| "tunable unavailable".to_string())?;
    write_checked(s, &node, &v)?;
    Ok(v)
}

/// Paths potentially modified by the individual tweak interface.
#[cfg(test)]
mod tests {
    use super::parse_choice;

    #[test]
    fn sysfs_active_choice_brackets_are_ignored() {
        assert_eq!(parse_choice("mq-deadline", "[mq-deadline] bfq" ).unwrap(), "mq-deadline");
        assert!(parse_choice("kyber", "[mq-deadline] bfq").is_err());
    }

    #[test]
    fn choice_validation_accepts_plain_lists() {
        assert_eq!(parse_choice("schedutil", "schedutil performance").unwrap(), "schedutil");
    }
}

pub fn managed_nodes(s: &Sysroot, t: &Topology) -> Vec<String> {
    let mut out = vec![
        "/proc/sys/kernel/sched_latency_ns",
        "/proc/sys/kernel/sched_min_granularity_ns",
        "/proc/sys/kernel/sched_migration_cost_ns",
        "/proc/sys/walt/sched_boost",
        "/proc/sys/kernel/sched_boost",
        "/proc/sys/vm/swappiness",
        "/proc/sys/vm/dirty_ratio",
        "/proc/sys/vm/dirty_background_ratio",
        "/proc/sys/vm/vfs_cache_pressure",
        "/proc/sys/vm/min_free_kbytes",
        "/proc/sys/vm/stat_interval",
        "/sys/module/lowmemorykiller/parameters/minfree",
        "/sys/block/zram0/comp_algorithm",
        "/sys/block/zram0/disksize",
        "/sys/block/zram0/max_comp_streams",
        "/sys/module/sync/parameters/fsync_enabled",
        "/proc/sys/net/ipv4/tcp_congestion_control",
        "/proc/sys/net/ipv4/tcp_fastopen",
        "/proc/sys/net/ipv4/tcp_low_latency",
        "/sys/devices/platform/kcal_ctrl.0/kcal",
        "/sys/devices/platform/kcal_ctrl.0/kcal_sat",
        "/sys/class/kgsl/kgsl-3d0/force_clk_on",
    ].into_iter().map(String::from).collect::<Vec<_>>();
    if let Some(g) = &t.gpu {
        for leaf in ["min_freq", "max_freq", "governor"] { out.push(g.rel(leaf)); }
    }
    for p in &t.policies {
        out.push(p.rel("scaling_governor"));
        out.push(p.rel("scaling_min_freq"));
        out.push(p.rel("scaling_max_freq"));
    }
    for dev in s.list_dir("/sys/block") {
        for leaf in ["scheduler", "read_ahead_kb", "nr_requests"] {
            out.push(format!("/sys/block/{dev}/queue/{leaf}"));
        }
    }
    if t.has_msm_perf {
        out.push("/sys/module/msm_performance/parameters/cpu_min_freq".into());
        out.push("/sys/module/msm_performance/parameters/cpu_max_freq".into());
    }
    for p in [
        "/sys/class/power_supply/battery/charge_control_limit",
        "/sys/class/power_supply/battery/batt_slate_mode",
    ] { out.push(p.into()); }
    out.sort();
    out.dedup();
    out
}
