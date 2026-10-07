// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Read-only performance/thermal telemetry model.
//!
//! Telemetry is evidence, not a tuning directive. Values that cannot be read
//! remain unknown instead of being synthesized from generic assumptions.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::nodes::Sysroot;
use crate::topo::Topology;

#[derive(Debug, Clone, Default)]
pub struct PsiSnapshot {
    pub some_avg10: Option<f32>,
    pub full_avg10: Option<f32>,
}

#[derive(Debug, Clone)]
pub struct CpuRuntime {
    pub policy: String,
    pub current_khz: Option<u64>,
    pub min_khz: Option<u64>,
    pub max_khz: Option<u64>,
}

#[derive(Debug, Clone)]
pub struct Snapshot {
    pub battery_pct: Option<u32>,
    pub charging: bool,
    pub load1: Option<f32>,
    pub mem_available_kb: Option<u64>,
    pub cpu: Vec<CpuRuntime>,
    pub gpu_current: Option<u64>,
    pub hottest_mdeg: Option<i64>,
    pub thermal_zone_count: usize,
    pub cpu_psi: PsiSnapshot,
    pub memory_psi: PsiSnapshot,
    pub io_psi: PsiSnapshot,
}

pub fn collect(s: &Sysroot, t: &Topology) -> Snapshot {
    let load1 = s.read("/proc/loadavg").and_then(|v| v.split_whitespace().next()?.parse().ok());
    let mem_available_kb = s
        .read("/proc/meminfo")
        .and_then(|text| text.lines().find(|l| l.starts_with("MemAvailable:")))
        .and_then(|l| l.split_whitespace().nth(1)?.parse().ok());
    let mut cpu = Vec::new();
    for p in &t.policies {
        cpu.push(CpuRuntime {
            policy: p.name.clone(),
            current_khz: s.read_u64(&p.rel("scaling_cur_freq")),
            min_khz: s.read_u64(&p.rel("scaling_min_freq")),
            max_khz: s.read_u64(&p.rel("scaling_max_freq")),
        });
    }
    let hottest_mdeg = t.thermal_zones.iter().filter_map(|z| z.temp_mdeg).max();
    Snapshot {
        battery_pct: s.read_u64("/sys/class/power_supply/battery/capacity").map(|v| v.min(100) as u32),
        charging: crate::scene::charging(s),
        load1,
        mem_available_kb,
        cpu,
        gpu_current: t.gpu.as_ref().and_then(|g| s.read_u64(&g.rel("cur_freq"))),
        hottest_mdeg,
        thermal_zone_count: t.thermal_zones.len(),
        cpu_psi: read_psi(s, "/proc/pressure/cpu"),
        memory_psi: read_psi(s, "/proc/pressure/memory"),
        io_psi: read_psi(s, "/proc/pressure/io"),
    }
}

fn read_psi(s: &Sysroot, path: &str) -> PsiSnapshot {
    let Some(text) = s.read(path) else { return PsiSnapshot::default(); };
    let mut out = PsiSnapshot::default();
    for line in text.lines() {
        let Some(rest) = line.strip_prefix("some ") else {
            if let Some(rest) = line.strip_prefix("full ") {
                out.full_avg10 = parse_avg10(rest);
            }
            continue;
        };
        out.some_avg10 = parse_avg10(rest);
    }
    out
}

fn parse_avg10(s: &str) -> Option<f32> {
    s.split_whitespace()
        .find_map(|part| part.strip_prefix("avg10=")?.parse().ok())
}

pub fn json(s: &Sysroot, t: &Topology) -> String {
    let x = collect(s, t);
    let cpu = x.cpu.iter().map(|c| format!(
        "{{\"policy\":\"{}\",\"cur_khz\":{},\"min_khz\":{},\"max_khz\":{}}}",
        escape(&c.policy), opt_u64(c.current_khz), opt_u64(c.min_khz), opt_u64(c.max_khz)
    )).collect::<Vec<_>>().join(",");
    format!(
        "{{\"battery_pct\":{},\"charging\":{},\"load1\":{},\"mem_available_kb\":{},\"hottest_mdeg\":{},\"thermal_zone_count\":{},\"gpu_current_khz\":{},\"cpu\":[{}],\"psi\":{{\"cpu_some_avg10\":{},\"cpu_full_avg10\":{},\"memory_some_avg10\":{},\"memory_full_avg10\":{},\"io_some_avg10\":{},\"io_full_avg10\":{}}}}}",
        opt_u32(x.battery_pct), x.charging, opt_f32(x.load1), opt_u64(x.mem_available_kb), opt_i64(x.hottest_mdeg),
        x.thermal_zone_count, opt_u64(x.gpu_current), cpu,
        opt_f32(x.cpu_psi.some_avg10), opt_f32(x.cpu_psi.full_avg10),
        opt_f32(x.memory_psi.some_avg10), opt_f32(x.memory_psi.full_avg10),
        opt_f32(x.io_psi.some_avg10), opt_f32(x.io_psi.full_avg10),
    )
}

fn opt_u32(v: Option<u32>) -> String { v.map(|v| v.to_string()).unwrap_or_else(|| "null".into()) }
fn opt_u64(v: Option<u64>) -> String { v.map(|v| v.to_string()).unwrap_or_else(|| "null".into()) }
fn opt_i64(v: Option<i64>) -> String { v.map(|v| v.to_string()).unwrap_or_else(|| "null".into()) }
fn opt_f32(v: Option<f32>) -> String { v.filter(|x| x.is_finite()).map(|v| format!("{v:.3}")).unwrap_or_else(|| "null".into()) }
fn escape(s: &str) -> String { s.replace('\\', "\\\\").replace('"', "\\\"") }

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::path::PathBuf;

    #[test]
    fn parses_psi() {
        let root = std::env::temp_dir().join(format!("zairenkai-psi-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(root.join("proc/pressure")).unwrap();
        fs::write(root.join("proc/pressure/cpu"), "some avg10=1.23 avg60=0.4 avg300=0.1 total=1\nfull avg10=0.00 avg60=0.0 avg300=0.0 total=0\n").unwrap();
        let x = read_psi(&Sysroot::new(&root), "/proc/pressure/cpu");
        assert_eq!(x.some_avg10, Some(1.23));
        assert_eq!(x.full_avg10, Some(0.0));
        let _ = fs::remove_dir_all(root);
    }
}
