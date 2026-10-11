// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Safe individual-tweak engine.
//!
//! zperfd is the authority for single tweaks: `tweak list --json` publishes
//! the runtime descriptor (current value, accepted choices or range, lock
//! state) and `tweak set` validates against the same tables, writes inside a
//! state transaction and commits only after the value is observable. The
//! legacy zkfctl C catalog is a read-only fallback for when zperfd is absent.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::nodes::Sysroot;
use crate::topo::Topology;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Spec {
    pub id: &'static str,
    pub category: &'static str,
    pub title: &'static str,
    pub lite: bool,
}

const SPECS: &[Spec] = &[
    Spec {
        id: "cpu_governor",
        category: "cpu",
        title: "CPU governor",
        lite: false,
    },
    Spec {
        id: "sched_latency_ns",
        category: "cpu",
        title: "Scheduler latency (ns)",
        lite: false,
    },
    Spec {
        id: "sched_min_granularity_ns",
        category: "cpu",
        title: "Scheduler min granularity (ns)",
        lite: false,
    },
    Spec {
        id: "sched_migration_cost_ns",
        category: "cpu",
        title: "Task migration cost (ns)",
        lite: false,
    },
    Spec {
        id: "sched_boost",
        category: "cpu",
        title: "WALT scheduler boost",
        lite: true,
    },
    Spec {
        id: "gpu_governor",
        category: "gpu",
        title: "GPU governor",
        lite: false,
    },
    Spec {
        id: "gpu_min_freq",
        category: "gpu",
        title: "GPU minimum frequency",
        lite: false,
    },
    Spec {
        id: "gpu_max_freq",
        category: "gpu",
        title: "GPU maximum frequency",
        lite: false,
    },
    Spec {
        id: "gpu_force_clk",
        category: "gpu",
        title: "GPU force clock on",
        lite: true,
    },
    Spec {
        id: "swappiness",
        category: "memory",
        title: "Swappiness",
        lite: false,
    },
    Spec {
        id: "dirty_ratio",
        category: "memory",
        title: "Dirty ratio",
        lite: false,
    },
    Spec {
        id: "dirty_background_ratio",
        category: "memory",
        title: "Dirty background ratio",
        lite: false,
    },
    Spec {
        id: "vfs_cache_pressure",
        category: "memory",
        title: "VFS cache pressure",
        lite: false,
    },
    Spec {
        id: "min_free_kbytes",
        category: "memory",
        title: "Min free memory (KiB)",
        lite: false,
    },
    Spec {
        id: "vm_stat_interval",
        category: "memory",
        title: "VM stat interval (s)",
        lite: true,
    },
    Spec {
        id: "vm_page_cluster",
        category: "memory",
        title: "VM page cluster",
        lite: true,
    },
    Spec {
        id: "dirty_expire_centisecs",
        category: "memory",
        title: "Dirty expiry (centisecs)",
        lite: false,
    },
    Spec {
        id: "dirty_writeback_centisecs",
        category: "memory",
        title: "Dirty writeback (centisecs)",
        lite: false,
    },
    Spec {
        id: "lmk_minfree",
        category: "memory",
        title: "LMK minfree",
        lite: true,
    },
    Spec {
        id: "zram_comp_algorithm",
        category: "zram",
        title: "zram compression",
        lite: false,
    },
    Spec {
        id: "zram_disksize",
        category: "zram",
        title: "zram size (bytes)",
        lite: true,
    },
    Spec {
        id: "zram_max_comp_streams",
        category: "zram",
        title: "zram comp streams",
        lite: true,
    },
    Spec {
        id: "zram_mem_limit",
        category: "zram",
        title: "zram memory limit (bytes)",
        lite: true,
    },
    Spec {
        id: "io_scheduler",
        category: "io",
        title: "I/O scheduler",
        lite: false,
    },
    Spec {
        id: "io_read_ahead_kb",
        category: "io",
        title: "I/O read-ahead (KiB)",
        lite: false,
    },
    Spec {
        id: "io_nr_requests",
        category: "io",
        title: "I/O queue depth",
        lite: false,
    },
    Spec {
        id: "io_rq_affinity",
        category: "io",
        title: "I/O rq affinity",
        lite: true,
    },
    Spec {
        id: "io_nomerges",
        category: "io",
        title: "I/O request merging",
        lite: true,
    },
    Spec {
        id: "fsync",
        category: "io",
        title: "fsync enabled",
        lite: true,
    },
    Spec {
        id: "tcp_congestion_control",
        category: "network",
        title: "TCP congestion control",
        lite: false,
    },
    Spec {
        id: "tcp_fastopen",
        category: "network",
        title: "TCP fast open",
        lite: false,
    },
    Spec {
        id: "tcp_low_latency",
        category: "network",
        title: "TCP low latency",
        lite: true,
    },
    Spec {
        id: "kcal",
        category: "display",
        title: "Display color (R G B)",
        lite: true,
    },
    Spec {
        id: "kcal_sat",
        category: "display",
        title: "Display saturation",
        lite: true,
    },
    Spec {
        id: "charge_limit",
        category: "battery",
        title: "Charge control limit",
        lite: false,
    },
];

pub fn find(id: &str) -> Option<&'static Spec> {
    SPECS.iter().find(|spec| spec.id == id)
}

pub fn specs() -> impl Iterator<Item = &'static Spec> {
    SPECS.iter()
}

pub fn operation_for(id: &str) -> zairenkai_core::operation::Operation {
    match find(id).map(|s| s.category) {
        Some("cpu") => zairenkai_core::operation::Operation::SetCpuTweak,
        Some("gpu") => zairenkai_core::operation::Operation::SetGpuTweak,
        Some("memory") => zairenkai_core::operation::Operation::SetMemoryTweak,
        Some("zram") => zairenkai_core::operation::Operation::TuneZram,
        Some("io") => zairenkai_core::operation::Operation::SetIoTweak,
        Some("network") => zairenkai_core::operation::Operation::TuneNetwork,
        Some("battery") => zairenkai_core::operation::Operation::SetPowerTweak,
        Some("display") => zairenkai_core::operation::Operation::SetPowerTweak,
        _ => zairenkai_core::operation::Operation::SetPowerTweak,
    }
}

/// Static safe bounds for integer tweaks. This single table feeds both the
/// write validator and the descriptor published to clients, so the range a UI
/// offers can never drift from the range the engine accepts.
const RANGES: &[(&str, i64, i64)] = &[
    ("sched_latency_ns", 100_000, 100_000_000),
    ("sched_min_granularity_ns", 100_000, 50_000_000),
    ("sched_migration_cost_ns", 0, 50_000_000),
    ("sched_boost", 0, 3),
    ("gpu_force_clk", 0, 1),
    ("swappiness", 0, 200),
    ("dirty_ratio", 0, 100),
    ("dirty_background_ratio", 0, 100),
    ("vfs_cache_pressure", 0, 500),
    ("min_free_kbytes", 1024, 262_144),
    ("vm_stat_interval", 1, 120),
    ("vm_page_cluster", 0, 7),
    ("dirty_expire_centisecs", 100, 60_000),
    ("dirty_writeback_centisecs", 100, 60_000),
    ("zram_max_comp_streams", 1, 16),
    ("io_read_ahead_kb", 0, 2048),
    ("io_nr_requests", 4, 1024),
    ("io_rq_affinity", 0, 2),
    ("io_nomerges", 0, 2),
    ("fsync", 0, 1),
    ("tcp_fastopen", 0, 3),
    ("tcp_low_latency", 0, 1),
    ("kcal_sat", 128, 383),
    ("charge_limit", 0, 100),
];

fn bounds(id: &str) -> Result<(i64, i64), String> {
    RANGES
        .iter()
        .find(|(key, _, _)| *key == id)
        .map(|(_, min, max)| (*min, *max))
        .ok_or_else(|| format!("internal: no safe range registered for '{id}'"))
}

fn parse_bounded(id: &str, value: &str) -> Result<String, String> {
    let (min, max) = bounds(id)?;
    parse_range(value, min, max)
}

/// Result of a successful write: the effective value that must now be
/// observable, and how many kernel nodes received it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Applied {
    pub value: String,
    pub targets: usize,
}

impl Applied {
    fn one(value: String) -> Self {
        Self { value, targets: 1 }
    }
}

/// What a client may offer for a tweak on this device.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Constraint {
    Range { min: i64, max: i64 },
    Choice(Vec<String>),
    Text,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Descriptor {
    pub spec: &'static Spec,
    pub available: bool,
    pub value: Option<String>,
    pub constraint: Constraint,
    /// Set when the node exists but the engine will refuse to change it now.
    pub locked: Option<&'static str>,
}

pub fn read(id: &str, s: &Sysroot, t: &Topology) -> Option<String> {
    find(id)?;
    match id {
        "cpu_governor" => t
            .policies
            .first()
            .and_then(|p| s.read(&p.rel("scaling_governor"))),
        "sched_latency_ns" => s.read("/proc/sys/kernel/sched_latency_ns"),
        "sched_min_granularity_ns" => s.read("/proc/sys/kernel/sched_min_granularity_ns"),
        "sched_migration_cost_ns" => s.read("/proc/sys/kernel/sched_migration_cost_ns"),
        "sched_boost" => first_existing(
            s,
            &["/proc/sys/walt/sched_boost", "/proc/sys/kernel/sched_boost"],
        )
        .and_then(|p| s.read(&p)),
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
        "vm_page_cluster" => s.read("/proc/sys/vm/page-cluster"),
        "dirty_expire_centisecs" => s.read("/proc/sys/vm/dirty_expire_centisecs"),
        "dirty_writeback_centisecs" => s.read("/proc/sys/vm/dirty_writeback_centisecs"),
        "lmk_minfree" => s.read("/sys/module/lowmemorykiller/parameters/minfree"),
        "zram_comp_algorithm" => s.read("/sys/block/zram0/comp_algorithm"),
        "zram_disksize" => s.read("/sys/block/zram0/disksize"),
        "zram_max_comp_streams" => s.read("/sys/block/zram0/max_comp_streams"),
        "zram_mem_limit" => s.read("/sys/block/zram0/mem_limit"),
        "io_scheduler" => crate::storage::safe_block_devices(s)
            .into_iter()
            .find_map(|dev| s.read(&format!("/sys/block/{dev}/queue/scheduler"))),
        "io_read_ahead_kb" => crate::storage::safe_block_devices(s)
            .into_iter()
            .find_map(|dev| s.read(&format!("/sys/block/{dev}/queue/read_ahead_kb"))),
        "io_nr_requests" => crate::storage::safe_block_devices(s)
            .into_iter()
            .find_map(|dev| s.read(&format!("/sys/block/{dev}/queue/nr_requests"))),
        "io_rq_affinity" => crate::storage::safe_block_devices(s)
            .into_iter()
            .find_map(|dev| s.read(&format!("/sys/block/{dev}/queue/rq_affinity"))),
        "io_nomerges" => crate::storage::safe_block_devices(s)
            .into_iter()
            .find_map(|dev| s.read(&format!("/sys/block/{dev}/queue/nomerges"))),
        "fsync" => s.read("/sys/module/sync/parameters/fsync_enabled"),
        "tcp_congestion_control" => s.read("/proc/sys/net/ipv4/tcp_congestion_control"),
        "tcp_fastopen" => s.read("/proc/sys/net/ipv4/tcp_fastopen"),
        "tcp_low_latency" => s.read("/proc/sys/net/ipv4/tcp_low_latency"),
        "kcal" => s.read("/sys/devices/platform/kcal_ctrl.0/kcal"),
        "kcal_sat" => s.read("/sys/devices/platform/kcal_ctrl.0/kcal_sat"),
        "charge_limit" => s.read("/sys/class/power_supply/battery/charge_control_limit"),
        _ => None,
    }
}

fn write_checked(s: &Sysroot, path: &str, value: &str) -> Result<(), String> {
    s.write(path, value).map_err(|e| format!("{path}: {e}"))
}

fn parse_range(value: &str, min: i64, max: i64) -> Result<String, String> {
    let n: i64 = value
        .trim()
        .parse()
        .map_err(|_| format!("expected integer {min}..{max}"))?;
    if !(min..=max).contains(&n) {
        return Err(format!("value {n} outside safe range {min}..{max}"));
    }
    Ok(n.to_string())
}

fn effective_congestion_allowlist(allowed: &str, available: &str) -> String {
    let available_tokens: Vec<&str> = available
        .split_whitespace()
        .filter(|v| !v.is_empty())
        .collect();
    if available_tokens.is_empty() {
        return String::new();
    }
    let allowed_tokens: std::collections::BTreeSet<&str> = allowed
        .split_whitespace()
        .filter(|v| !v.is_empty())
        .collect();
    if allowed_tokens.is_empty() {
        return available_tokens.join(" ");
    }
    let intersection = available_tokens
        .iter()
        .copied()
        .filter(|v| allowed_tokens.contains(v))
        .collect::<Vec<_>>();
    if intersection.is_empty() {
        available_tokens.join(" ")
    } else {
        intersection.join(" ")
    }
}

fn parse_choice(value: &str, available: &str) -> Result<String, String> {
    let value = value.trim();
    if value.is_empty()
        || value
            .bytes()
            .any(|b| b.is_ascii_whitespace() || b == b'/' || b == b'\\')
    {
        return Err("invalid choice".into());
    }
    // sysfs choice nodes commonly mark the active value as `[choice]`; the
    // brackets are presentation syntax, not part of the accepted value.
    let choices: Vec<&str> = available
        .split_whitespace()
        .map(|token| token.trim_matches(['[', ']']))
        .filter(|token| !token.is_empty())
        .collect();
    if choices.is_empty() {
        return Err("device did not expose a runtime allowlist for this choice".into());
    }
    if !choices.contains(&value) {
        return Err(format!("'{value}' is not supported by this device"));
    }
    Ok(value.to_string())
}

fn first_existing(s: &Sysroot, paths: &[&str]) -> Option<String> {
    paths.iter().find(|p| s.exists(p)).map(|p| (*p).to_string())
}

fn validate_lmk(value: &str) -> Result<String, String> {
    let v = value.trim();
    if v.is_empty()
        || v.len() > 512
        || !v
            .bytes()
            .all(|b| b.is_ascii_digit() || b == b',' || b.is_ascii_whitespace())
    {
        return Err("LMK minfree must be a bounded comma/space-separated integer list".into());
    }
    if v.split(|c: char| c == ',' || c.is_ascii_whitespace())
        .filter(|x| !x.is_empty())
        .any(|x| x.parse::<u64>().is_err())
    {
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
    Ok(nums
        .iter()
        .map(|v| v.to_string())
        .collect::<Vec<_>>()
        .join(" "))
}

fn zram_size_limit(s: &Sysroot) -> Option<u64> {
    let mem_kb = s.read("/proc/meminfo").and_then(|text| {
        let line = text.lines().find(|line| line.starts_with("MemTotal:"))?;
        line.split_whitespace().nth(1)?.parse::<u64>().ok()
    })?;
    // Permit up to 2x physical RAM, but never more than 16 GiB.
    Some((mem_kb.saturating_mul(1024).saturating_mul(2)).min(16 * 1024 * 1024 * 1024))
}

pub fn apply(id: &str, value: &str, s: &Sysroot, t: &Topology) -> Result<Applied, String> {
    if find(id).is_none() {
        return Err(format!("unknown tweak '{id}'"));
    }
    match id {
        "cpu_governor" => {
            if t.policies.is_empty() {
                return Err("no cpufreq policies detected".into());
            }
            let mut applied = 0usize;
            for p in &t.policies {
                let avail = s
                    .read(&p.rel("scaling_available_governors"))
                    .unwrap_or_default();
                let gov = parse_choice(value, &avail)?;
                write_checked(s, &p.rel("scaling_governor"), &gov)?;
                applied += 1;
            }
            Ok(Applied {
                value: value.trim().to_string(),
                targets: applied,
            })
        }
        "sched_latency_ns" => write_proc(s, "/proc/sys/kernel/sched_latency_ns", value, id),
        "sched_min_granularity_ns" => {
            write_proc(s, "/proc/sys/kernel/sched_min_granularity_ns", value, id)
        }
        "sched_migration_cost_ns" => {
            write_proc(s, "/proc/sys/kernel/sched_migration_cost_ns", value, id)
        }
        "sched_boost" => {
            let v = parse_bounded(id, value)?;
            let path = first_existing(
                s,
                &["/proc/sys/walt/sched_boost", "/proc/sys/kernel/sched_boost"],
            )
            .ok_or_else(|| "scheduler boost node unavailable".to_string())?;
            write_checked(s, &path, &v)?;
            Ok(Applied::one(v))
        }
        "gpu_governor" => {
            let g = t
                .gpu
                .as_ref()
                .ok_or_else(|| "GPU devfreq not detected".to_string())?;
            let avail = s.read(&g.rel("available_governors")).unwrap_or_default();
            let gov = parse_choice(value, &avail)?;
            write_checked(s, &g.rel("governor"), &gov)?;
            Ok(Applied::one(gov))
        }
        "gpu_min_freq" | "gpu_max_freq" => {
            let g = t
                .gpu
                .as_ref()
                .ok_or_else(|| "GPU devfreq not detected".to_string())?;
            if g.avail.is_empty() {
                return Err("GPU OPP table unavailable".into());
            }
            let n: u64 = value
                .trim()
                .parse()
                .map_err(|_| "GPU frequency must be kHz".to_string())?;
            let lo = *g.avail.first().unwrap();
            let hi = *g.avail.last().unwrap();
            if n < lo || n > hi {
                return Err(format!("GPU frequency outside {lo}..{hi} kHz"));
            }
            let current_min = s.read_u64(&g.rel("min_freq"));
            let current_max = s.read_u64(&g.rel("max_freq"));
            if id == "gpu_min_freq" && current_max.is_none() {
                return Err("GPU current maximum frequency unavailable".into());
            }
            if id == "gpu_max_freq" && current_min.is_none() {
                return Err("GPU current minimum frequency unavailable".into());
            }
            if id == "gpu_min_freq" && current_max.is_some_and(|max| n > max) {
                return Err(format!("GPU minimum {n} kHz exceeds current maximum"));
            }
            if id == "gpu_max_freq" && current_min.is_some_and(|min| n < min) {
                return Err(format!("GPU maximum {n} kHz is below current minimum"));
            }
            let snapped = if id == "gpu_min_freq" {
                g.avail.iter().copied().find(|f| *f >= n).unwrap_or(hi)
            } else {
                g.avail
                    .iter()
                    .copied()
                    .rev()
                    .find(|f| *f <= n)
                    .unwrap_or(lo)
            };
            write_checked(
                s,
                &g.rel(if id == "gpu_min_freq" {
                    "min_freq"
                } else {
                    "max_freq"
                }),
                &snapped.to_string(),
            )?;
            Ok(Applied::one(snapped.to_string()))
        }
        "gpu_force_clk" => {
            write_sys_first(s, &["/sys/class/kgsl/kgsl-3d0/force_clk_on"], value, id)
        }
        "swappiness" => write_proc(s, "/proc/sys/vm/swappiness", value, id),
        "dirty_ratio" => write_proc(s, "/proc/sys/vm/dirty_ratio", value, id),
        "dirty_background_ratio" => write_proc(s, "/proc/sys/vm/dirty_background_ratio", value, id),
        "vfs_cache_pressure" => write_proc(s, "/proc/sys/vm/vfs_cache_pressure", value, id),
        "min_free_kbytes" => write_proc(s, "/proc/sys/vm/min_free_kbytes", value, id),
        "vm_stat_interval" => write_proc(s, "/proc/sys/vm/stat_interval", value, id),
        "vm_page_cluster" => write_proc(s, "/proc/sys/vm/page-cluster", value, id),
        "dirty_expire_centisecs" => write_proc(s, "/proc/sys/vm/dirty_expire_centisecs", value, id),
        "dirty_writeback_centisecs" => {
            write_proc(s, "/proc/sys/vm/dirty_writeback_centisecs", value, id)
        }
        "lmk_minfree" => {
            let node = "/sys/module/lowmemorykiller/parameters/minfree";
            let v = validate_lmk(value)?;
            write_checked(s, node, &v)?;
            Ok(Applied::one(v))
        }
        "zram_comp_algorithm" => {
            let node = "/sys/block/zram0/comp_algorithm";
            if !crate::memory::zram_uninitialized(s, "zram0") {
                return Err(
                    "zram0 is initialized; compression algorithm is no longer mutable".into(),
                );
            }
            let available = s
                .read(node)
                .ok_or_else(|| "zram0 compression node unavailable".to_string())?;
            let choice = parse_choice(value, &available)?;
            write_checked(s, node, &choice)?;
            Ok(Applied::one(choice))
        }
        "zram_disksize" => {
            let n: u64 = value
                .trim()
                .parse()
                .map_err(|_| "zram size must be bytes".to_string())?;
            let max =
                zram_size_limit(s).ok_or_else(|| "physical memory size unavailable".to_string())?;
            if n == 0 {
                return Err("zram size must be greater than zero".into());
            }
            if n > max {
                return Err(format!("zram size exceeds safe cap {max} bytes"));
            }
            if !crate::memory::zram_uninitialized(s, "zram0") {
                return Err(
                    "zram0 is initialized; disk size cannot be changed without an explicit reset"
                        .into(),
                );
            }
            let node = "/sys/block/zram0/disksize";
            write_checked(s, node, &n.to_string())?;
            Ok(Applied::one(n.to_string()))
        }
        "zram_max_comp_streams" => {
            write_sys_first(s, &["/sys/block/zram0/max_comp_streams"], value, id)
        }
        "zram_mem_limit" => {
            let n: u64 = value
                .trim()
                .parse()
                .map_err(|_| "zram memory limit must be bytes".to_string())?;
            let max =
                zram_size_limit(s).ok_or_else(|| "physical memory size unavailable".to_string())?;
            if n > max {
                return Err(format!("zram memory limit exceeds safe cap {max} bytes"));
            }
            let node = "/sys/block/zram0/mem_limit";
            write_checked(s, node, &n.to_string())?;
            Ok(Applied::one(n.to_string()))
        }
        "io_scheduler" => {
            let mut count = 0usize;
            for dev in crate::storage::safe_block_devices(s) {
                let node = format!("/sys/block/{dev}/queue/scheduler");
                if !s.exists(&node) {
                    continue;
                }
                let avail = s.read(&node).unwrap_or_default();
                let choice = parse_choice(value, &avail)?;
                write_checked(s, &node, &choice)?;
                count += 1;
            }
            if count == 0 {
                return Err("no block I/O scheduler nodes found".into());
            }
            Ok(Applied {
                value: value.trim().to_string(),
                targets: count,
            })
        }
        "io_read_ahead_kb" => {
            let v = parse_bounded(id, value)?;
            let mut count = 0usize;
            for dev in crate::storage::safe_block_devices(s) {
                let node = format!("/sys/block/{dev}/queue/read_ahead_kb");
                if !s.exists(&node) {
                    continue;
                }
                write_checked(s, &node, &v)?;
                count += 1;
            }
            if count == 0 {
                return Err("no block read-ahead nodes found".into());
            }
            Ok(Applied {
                value: v,
                targets: count,
            })
        }
        "io_nr_requests" => {
            let v = parse_bounded(id, value)?;
            let mut count = 0usize;
            for dev in crate::storage::safe_block_devices(s) {
                let node = format!("/sys/block/{dev}/queue/nr_requests");
                if !s.exists(&node) {
                    continue;
                }
                write_checked(s, &node, &v)?;
                count += 1;
            }
            if count == 0 {
                return Err("no block queue-depth nodes found".into());
            }
            Ok(Applied {
                value: v,
                targets: count,
            })
        }
        "io_rq_affinity" => {
            let v = parse_bounded(id, value)?;
            let mut count = 0usize;
            for dev in crate::storage::safe_block_devices(s) {
                let node = format!("/sys/block/{dev}/queue/rq_affinity");
                if !s.exists(&node) {
                    continue;
                }
                write_checked(s, &node, &v)?;
                count += 1;
            }
            if count == 0 {
                return Err("no safe rq_affinity nodes found".into());
            }
            Ok(Applied {
                value: v,
                targets: count,
            })
        }
        "io_nomerges" => {
            let v = parse_bounded(id, value)?;
            let mut count = 0usize;
            for dev in crate::storage::safe_block_devices(s) {
                let node = format!("/sys/block/{dev}/queue/nomerges");
                if !s.exists(&node) {
                    continue;
                }
                write_checked(s, &node, &v)?;
                count += 1;
            }
            if count == 0 {
                return Err("no safe nomerges nodes found".into());
            }
            Ok(Applied {
                value: v,
                targets: count,
            })
        }
        "fsync" => write_sys_first(s, &["/sys/module/sync/parameters/fsync_enabled"], value, id),
        "tcp_congestion_control" => {
            let node = "/proc/sys/net/ipv4/tcp_congestion_control";
            let allowed = s
                .read("/proc/sys/net/ipv4/tcp_allowed_congestion_control")
                .unwrap_or_default();
            let available = s
                .read("/proc/sys/net/ipv4/tcp_available_congestion_control")
                .unwrap_or_default();
            let effective = effective_congestion_allowlist(&allowed, &available);
            let choice = parse_choice(value, &effective)?;
            write_checked(s, node, &choice)?;
            Ok(Applied::one(choice))
        }
        "tcp_fastopen" => write_proc(s, "/proc/sys/net/ipv4/tcp_fastopen", value, id),
        "tcp_low_latency" => write_proc(s, "/proc/sys/net/ipv4/tcp_low_latency", value, id),
        "kcal" => {
            let v = validate_kcal(value)?;
            write_checked(s, "/sys/devices/platform/kcal_ctrl.0/kcal", &v)?;
            Ok(Applied::one(v))
        }
        "kcal_sat" => write_sys_first(
            s,
            &["/sys/devices/platform/kcal_ctrl.0/kcal_sat"],
            value,
            id,
        ),
        "charge_limit" => {
            let node = "/sys/class/power_supply/battery/charge_control_limit";
            if !s.exists(node) {
                return Err("battery charge_control_limit node unavailable".into());
            }
            let v = parse_bounded(id, value)?;
            write_checked(s, node, &v)?;
            Ok(Applied::one(v))
        }
        _ => unreachable!(),
    }
}

fn write_proc(s: &Sysroot, node: &str, value: &str, id: &str) -> Result<Applied, String> {
    let v = parse_bounded(id, value)?;
    if !s.exists(node) {
        return Err(format!("{node} unavailable"));
    }
    write_checked(s, node, &v)?;
    Ok(Applied::one(v))
}

fn write_sys_first(s: &Sysroot, paths: &[&str], value: &str, id: &str) -> Result<Applied, String> {
    let v = parse_bounded(id, value)?;
    let node = first_existing(s, paths).ok_or_else(|| "tunable unavailable".to_string())?;
    write_checked(s, &node, &v)?;
    Ok(Applied::one(v))
}

/// Tokens of a sysfs choice node, without the `[active]` brackets.
fn choice_tokens(raw: &str) -> Vec<String> {
    raw.split_whitespace()
        .map(|token| token.trim_matches(['[', ']']).to_string())
        .filter(|token| !token.is_empty())
        .collect()
}

/// The active entry of a choice node. Bracketed lists such as
/// `[mq-deadline] kyber` report the bracketed token; plain nodes such as
/// `scaling_governor` report their whole (single-token) content.
fn active_choice(raw: &str) -> Option<String> {
    let raw = raw.trim();
    if let Some(token) = raw
        .split_whitespace()
        .find(|token| token.starts_with('[') && token.ends_with(']'))
    {
        return Some(token.trim_matches(['[', ']']).to_string());
    }
    (!raw.is_empty() && !raw.contains(char::is_whitespace)).then(|| raw.to_string())
}

/// Choices accepted by every node in `lists`; a write must succeed on all of
/// them, so only the intersection is offered.
fn common_choices<I: IntoIterator<Item = String>>(lists: I) -> Vec<String> {
    let mut common: Option<Vec<String>> = None;
    for raw in lists {
        let tokens = choice_tokens(&raw);
        common = Some(match common {
            None => tokens,
            Some(prev) => prev.into_iter().filter(|c| tokens.contains(c)).collect(),
        });
    }
    common.unwrap_or_default()
}

fn block_nodes(s: &Sysroot, leaf: &str) -> Vec<String> {
    crate::storage::safe_block_devices(s)
        .into_iter()
        .map(|dev| format!("/sys/block/{dev}/queue/{leaf}"))
        .filter(|node| s.exists(node))
        .collect()
}

/// Runtime description of one tweak: whether it exists here, its current
/// value and exactly what the engine would accept. Nothing here is static
/// device knowledge; every choice list and dynamic bound comes from the
/// live kernel surface.
pub fn describe(spec: &'static Spec, s: &Sysroot, t: &Topology) -> Descriptor {
    let raw = read(spec.id, s, t).filter(|v| !v.trim().is_empty());
    let mut locked = None;
    let (value, constraint) = match spec.id {
        "cpu_governor" => (
            raw.as_deref().and_then(active_choice),
            Constraint::Choice(common_choices(
                t.policies
                    .iter()
                    .filter_map(|p| s.read(&p.rel("scaling_available_governors"))),
            )),
        ),
        "gpu_governor" => (
            raw.as_deref().and_then(active_choice),
            Constraint::Choice(
                t.gpu
                    .as_ref()
                    .and_then(|g| s.read(&g.rel("available_governors")))
                    .map(|v| choice_tokens(&v))
                    .unwrap_or_default(),
            ),
        ),
        "io_scheduler" => (
            raw.as_deref().and_then(active_choice),
            Constraint::Choice(common_choices(
                block_nodes(s, "scheduler")
                    .into_iter()
                    .filter_map(|node| s.read(&node)),
            )),
        ),
        "zram_comp_algorithm" => {
            if raw.is_some() && !crate::memory::zram_uninitialized(s, "zram0") {
                locked = Some("zram0 is initialized; compression algorithm is fixed until reset");
            }
            (
                raw.as_deref().and_then(active_choice),
                Constraint::Choice(raw.as_deref().map(choice_tokens).unwrap_or_default()),
            )
        }
        "tcp_congestion_control" => {
            let allowed = s
                .read("/proc/sys/net/ipv4/tcp_allowed_congestion_control")
                .unwrap_or_default();
            let available = s
                .read("/proc/sys/net/ipv4/tcp_available_congestion_control")
                .unwrap_or_default();
            (
                raw.clone(),
                Constraint::Choice(choice_tokens(&effective_congestion_allowlist(
                    &allowed, &available,
                ))),
            )
        }
        "gpu_min_freq" | "gpu_max_freq" => {
            let range = t
                .gpu
                .as_ref()
                .and_then(|g| Some((*g.avail.first()?, *g.avail.last()?)));
            let constraint = match range {
                Some((lo, hi)) => Constraint::Range {
                    min: lo as i64,
                    max: hi as i64,
                },
                None => {
                    if raw.is_some() {
                        locked = Some("GPU OPP table unavailable");
                    }
                    Constraint::Text
                }
            };
            (raw.clone(), constraint)
        }
        "zram_disksize" | "zram_mem_limit" => {
            let constraint = match zram_size_limit(s) {
                Some(max) => Constraint::Range {
                    min: if spec.id == "zram_disksize" { 1 } else { 0 },
                    max: max.min(i64::MAX as u64) as i64,
                },
                None => {
                    if raw.is_some() {
                        locked = Some("physical memory size unavailable");
                    }
                    Constraint::Text
                }
            };
            if spec.id == "zram_disksize"
                && raw.is_some()
                && !crate::memory::zram_uninitialized(s, "zram0")
            {
                locked = Some("zram0 is initialized; size is fixed until reset");
            }
            (raw.clone(), constraint)
        }
        "lmk_minfree" | "kcal" => (raw.clone(), Constraint::Text),
        _ => match bounds(spec.id) {
            Ok((min, max)) => (raw.clone(), Constraint::Range { min, max }),
            Err(_) => (raw.clone(), Constraint::Text),
        },
    };
    Descriptor {
        spec,
        available: raw.is_some(),
        value,
        constraint,
        locked,
    }
}

fn normalized_list(raw: &str) -> Vec<String> {
    raw.split(|c: char| c == ',' || c.is_ascii_whitespace())
        .filter(|x| !x.is_empty())
        .map(str::to_string)
        .collect()
}

/// Largest page size the kernel may round zram sizes up to (arm64 64 KiB).
const MAX_PAGE_BYTES: u64 = 64 * 1024;

fn observed_matches(id: &str, expected: &str, observed: &str) -> bool {
    let observed = observed.trim();
    match id {
        "cpu_governor" | "gpu_governor" | "io_scheduler" | "zram_comp_algorithm" => {
            active_choice(observed).as_deref() == Some(expected)
        }
        // zram rounds byte sizes up to a page boundary.
        "zram_disksize" | "zram_mem_limit" => {
            match (expected.parse::<u64>(), observed.parse::<u64>()) {
                (Ok(want), Ok(got)) => got >= want && got - want < MAX_PAGE_BYTES,
                _ => false,
            }
        }
        "lmk_minfree" | "kcal" => normalized_list(observed) == normalized_list(expected),
        _ => observed == expected,
    }
}

/// Confirm that a write is observable on every node it targeted. A kernel
/// that accepts a write but clamps, ignores or immediately overrides it must
/// not be reported as a successful mutation.
pub fn verify(id: &str, applied: &Applied, s: &Sysroot, t: &Topology) -> Result<(), String> {
    let nodes: Vec<String> = match id {
        "cpu_governor" => t
            .policies
            .iter()
            .map(|p| p.rel("scaling_governor"))
            .collect(),
        "io_scheduler" => block_nodes(s, "scheduler"),
        "io_read_ahead_kb" => block_nodes(s, "read_ahead_kb"),
        "io_nr_requests" => block_nodes(s, "nr_requests"),
        "io_rq_affinity" => block_nodes(s, "rq_affinity"),
        "io_nomerges" => block_nodes(s, "nomerges"),
        _ => {
            let observed =
                read(id, s, t).ok_or_else(|| format!("{id}: value unreadable after write"))?;
            return if observed_matches(id, &applied.value, &observed) {
                Ok(())
            } else {
                Err(format!(
                    "{id}: kernel reports '{}' after writing '{}'",
                    observed.trim(),
                    applied.value
                ))
            };
        }
    };
    if nodes.len() != applied.targets {
        return Err(format!(
            "{id}: {} node(s) written but {} present after write",
            applied.targets,
            nodes.len()
        ));
    }
    for node in nodes {
        let observed = s
            .read(&node)
            .ok_or_else(|| format!("{node}: unreadable after write"))?;
        if !observed_matches(id, &applied.value, &observed) {
            return Err(format!(
                "{node}: kernel reports '{}' after writing '{}'",
                observed.trim(),
                applied.value
            ));
        }
    }
    Ok(())
}

/// Paths potentially modified by the individual tweak interface.
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
        "/proc/sys/vm/page-cluster",
        "/proc/sys/vm/dirty_expire_centisecs",
        "/proc/sys/vm/dirty_writeback_centisecs",
        "/sys/module/lowmemorykiller/parameters/minfree",
        "/sys/block/zram0/comp_algorithm",
        "/sys/block/zram0/disksize",
        "/sys/block/zram0/max_comp_streams",
        "/sys/block/zram0/mem_limit",
        "/sys/module/sync/parameters/fsync_enabled",
        "/proc/sys/net/ipv4/tcp_congestion_control",
        "/proc/sys/net/ipv4/tcp_fastopen",
        "/proc/sys/net/ipv4/tcp_low_latency",
        "/sys/devices/platform/kcal_ctrl.0/kcal",
        "/sys/devices/platform/kcal_ctrl.0/kcal_sat",
        "/sys/class/kgsl/kgsl-3d0/force_clk_on",
    ]
    .into_iter()
    .map(String::from)
    .collect::<Vec<_>>();
    if let Some(g) = &t.gpu {
        for leaf in ["min_freq", "max_freq", "governor"] {
            out.push(g.rel(leaf));
        }
    }
    for p in &t.policies {
        out.push(p.rel("scaling_governor"));
        out.push(p.rel("scaling_min_freq"));
        out.push(p.rel("scaling_max_freq"));
    }
    for dev in s.list_dir("/sys/block") {
        for leaf in [
            "scheduler",
            "read_ahead_kb",
            "nr_requests",
            "rq_affinity",
            "nomerges",
        ] {
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
    ] {
        out.push(p.into());
    }
    out.sort();
    out.dedup();
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sysfs_active_choice_brackets_are_ignored() {
        assert_eq!(
            parse_choice("mq-deadline", "[mq-deadline] bfq").unwrap(),
            "mq-deadline"
        );
        assert!(parse_choice("kyber", "[mq-deadline] bfq").is_err());
    }

    #[test]
    fn choice_validation_accepts_plain_lists() {
        assert_eq!(
            parse_choice("schedutil", "schedutil performance").unwrap(),
            "schedutil"
        );
    }

    #[test]
    fn choice_validation_fails_closed_without_runtime_allowlist() {
        assert!(parse_choice("cubic", "").is_err());
    }

    fn fixture() -> (std::path::PathBuf, Sysroot, Topology) {
        let r = crate::test_support::unique_temp_dir("tweak");
        let w = |path: &str, value: &str| {
            let p = r.join(path.trim_start_matches('/'));
            std::fs::create_dir_all(p.parent().unwrap()).unwrap();
            std::fs::write(p, value).unwrap();
        };
        for (policy, govs) in [
            ("policy0", "schedutil performance powersave"),
            ("policy4", "schedutil performance"),
        ] {
            let base = format!("/sys/devices/system/cpu/cpufreq/{policy}");
            w(&format!("{base}/cpuinfo_min_freq"), "300000");
            w(&format!("{base}/cpuinfo_max_freq"), "2000000");
            w(
                &format!("{base}/scaling_available_frequencies"),
                "300000 2000000",
            );
            w(&format!("{base}/scaling_available_governors"), govs);
            w(&format!("{base}/scaling_governor"), "schedutil");
            w(
                &format!("{base}/related_cpus"),
                if policy == "policy0" { "0-3" } else { "4-7" },
            );
        }
        w("/sys/block/sda/queue/scheduler", "[mq-deadline] kyber none");
        w("/sys/block/sda/queue/rotational", "0");
        w("/sys/block/sda/queue/read_ahead_kb", "128");
        w("/sys/block/zram0/comp_algorithm", "lzo [lz4] zstd");
        w("/sys/block/zram0/disksize", "2147483648");
        w("/sys/block/zram0/initstate", "1");
        w("/proc/meminfo", "MemTotal:        8000000 kB\n");
        w("/proc/sys/vm/swappiness", "60");
        let sysroot = Sysroot::new(&r);
        let topology = Topology::detect(&sysroot);
        (r, sysroot, topology)
    }

    #[test]
    fn every_integer_tweak_has_one_registered_range() {
        let dynamic_or_textual = [
            "cpu_governor",
            "gpu_governor",
            "gpu_min_freq",
            "gpu_max_freq",
            "lmk_minfree",
            "zram_comp_algorithm",
            "zram_disksize",
            "zram_mem_limit",
            "io_scheduler",
            "tcp_congestion_control",
            "kcal",
        ];
        for spec in specs() {
            let registered = bounds(spec.id).is_ok();
            assert_eq!(
                registered,
                !dynamic_or_textual.contains(&spec.id),
                "range registration mismatch for {}",
                spec.id
            );
        }
        for (id, min, max) in RANGES {
            assert!(find(id).is_some(), "range for unknown tweak {id}");
            assert!(min <= max, "inverted range for {id}");
        }
    }

    #[test]
    fn descriptor_offers_only_choices_every_target_accepts() {
        let (r, s, t) = fixture();
        let gov = describe(find("cpu_governor").unwrap(), &s, &t);
        assert_eq!(gov.value.as_deref(), Some("schedutil"));
        assert_eq!(
            gov.constraint,
            Constraint::Choice(vec!["schedutil".into(), "performance".into()])
        );
        let io = describe(find("io_scheduler").unwrap(), &s, &t);
        assert_eq!(io.value.as_deref(), Some("mq-deadline"));
        assert_eq!(
            io.constraint,
            Constraint::Choice(vec!["mq-deadline".into(), "kyber".into(), "none".into()])
        );
        let _ = std::fs::remove_dir_all(r);
    }

    #[test]
    fn descriptor_reports_ranges_locks_and_absence_honestly() {
        let (r, s, t) = fixture();
        let swap = describe(find("swappiness").unwrap(), &s, &t);
        assert!(swap.available);
        assert_eq!(swap.constraint, Constraint::Range { min: 0, max: 200 });
        let zram = describe(find("zram_disksize").unwrap(), &s, &t);
        assert!(
            zram.locked.is_some(),
            "initialized zram must be reported as locked"
        );
        assert_eq!(
            zram.constraint,
            Constraint::Range {
                min: 1,
                max: 16_384_000_000
            }
        );
        let algo = describe(find("zram_comp_algorithm").unwrap(), &s, &t);
        assert_eq!(algo.value.as_deref(), Some("lz4"));
        assert!(algo.locked.is_some());
        let gpu = describe(find("gpu_governor").unwrap(), &s, &t);
        assert!(!gpu.available);
        assert_eq!(gpu.value, None);
        let _ = std::fs::remove_dir_all(r);
    }

    #[test]
    fn apply_reports_the_value_and_every_target() {
        let (r, s, t) = fixture();
        let applied = apply("cpu_governor", "performance", &s, &t).unwrap();
        assert_eq!(
            applied,
            Applied {
                value: "performance".into(),
                targets: 2
            }
        );
        verify("cpu_governor", &applied, &s, &t).unwrap();
        assert!(apply("cpu_governor", "powersave", &s, &t).is_err());
        let _ = std::fs::remove_dir_all(r);
    }

    #[test]
    fn verification_rejects_values_the_kernel_did_not_keep() {
        let (r, s, t) = fixture();
        let applied = apply("swappiness", "100", &s, &t).unwrap();
        verify("swappiness", &applied, &s, &t).unwrap();
        // Simulate a kernel or vendor service clamping the node after write.
        std::fs::write(r.join("proc/sys/vm/swappiness"), "60\n").unwrap();
        assert!(verify("swappiness", &applied, &s, &t).is_err());
        let io = Applied {
            value: "kyber".into(),
            targets: 1,
        };
        assert!(verify("io_scheduler", &io, &s, &t).is_err());
        std::fs::write(
            r.join("sys/block/sda/queue/scheduler"),
            "mq-deadline [kyber] none",
        )
        .unwrap();
        verify("io_scheduler", &io, &s, &t).unwrap();
        let _ = std::fs::remove_dir_all(r);
    }

    #[test]
    fn zram_sizes_tolerate_page_rounding_only() {
        assert!(observed_matches("zram_disksize", "1000000", "1003520"));
        assert!(!observed_matches("zram_disksize", "1000000", "999424"));
        assert!(!observed_matches("zram_disksize", "1000000", "2000000"));
        assert!(observed_matches(
            "lmk_minfree",
            "18432, 23040",
            "18432,23040"
        ));
    }

    #[test]
    fn congestion_allowlist_is_bounded_by_available_algorithms() {
        assert_eq!(
            effective_congestion_allowlist("cubic bbr", "reno cubic bbr"),
            "cubic bbr"
        );
        assert_eq!(
            effective_congestion_allowlist("", "reno cubic"),
            "reno cubic"
        );
        assert_eq!(
            effective_congestion_allowlist("bbr2", "reno cubic"),
            "reno cubic"
        );
        // No runtime-available algorithms: nothing is selectable.
        assert_eq!(effective_congestion_allowlist("bbr", ""), "");
        assert!(parse_choice("bbr", &effective_congestion_allowlist("bbr", "")).is_err());
    }
}
