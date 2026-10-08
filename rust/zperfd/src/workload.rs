// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Foreground workload and thread telemetry for the adaptive controller.
//!
//! The analyzer is deliberately procfs-based so it works across vendor kernels
//! without binding the control plane to one Android framework daemon. It tracks
//! deltas rather than absolute counters and therefore remains cheap after the
//! first sample.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::nodes::Sysroot;
use std::cmp::Ordering;
use std::collections::HashMap;
use std::time::{Duration, Instant};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ThreadClass {
    Main,
    Render,
    Game,
    Audio,
    Binder,
    Worker,
    Other,
}


impl ThreadClass {
    pub fn performance_candidate(self) -> bool {
        matches!(self, Self::Main | Self::Render | Self::Game | Self::Audio | Self::Worker)
    }
}

#[derive(Clone, Debug)]
pub struct ThreadSample {
    pub tid: i32,
    pub name: String,
    pub class: ThreadClass,
    pub cpu_util_pct: f64,
    pub run_queue_delay_ms: Option<f64>,
    /// `/proc/<tid>/stat` starttime tick. Used to prevent TID reuse from
    /// receiving an affinity change intended for a previous thread.
    pub start_time_ticks: u64,
}

#[derive(Clone, Debug)]
pub struct WorkloadSnapshot {
    pub package: String,
    pub pid: i32,
    /// `/proc/<pid>/stat` starttime tick. Used as a userspace identity guard
    /// around direct affinity operations.
    pub start_time_ticks: u64,
    pub cpu_util_pct: f64,
    pub io_read_bps: Option<u64>,
    pub io_write_bps: Option<u64>,
    pub rss_kb: Option<u64>,
    /// Scheduler run-queue delay observed for the whole process over the
    /// sampling window, in milliseconds.
    pub run_queue_delay_ms: Option<f64>,
    pub threads: Vec<ThreadSample>,
    pub top_threads: Vec<i32>,
    pub active: bool,
    /// Best-effort instantaneous GPU utilization in percent.
    pub gpu_util_pct: Option<f64>,
    /// 0..100 confidence of the procfs workload observation.
    pub confidence: u8,
}

impl WorkloadSnapshot {
    pub fn top_thread_util_pct(&self) -> f64 {
        self.threads.first().map_or(0.0, |thread| thread.cpu_util_pct)
    }

    pub fn render_util_pct(&self) -> f64 {
        self.threads
            .iter()
            .filter(|thread| matches!(thread.class, ThreadClass::Render))
            .map(|thread| thread.cpu_util_pct)
            .fold(0.0, f64::max)
    }

    pub fn top_thread_run_queue_delay_ms(&self) -> f64 {
        self.threads
            .first()
            .and_then(|thread| thread.run_queue_delay_ms)
            .unwrap_or(0.0)
    }
}

#[derive(Clone, Copy, Debug)]
struct Counter {
    ticks: u64,
    io_read: u64,
    io_write: u64,
    run_delay_ns: u64,
    start_time_ticks: u64,
}

pub struct WorkloadAnalyzer {
    process_last: HashMap<i32, Counter>,
    thread_last: HashMap<i32, Counter>,
    last_sample: Option<Instant>,
    cached_pid: Option<(String, i32)>,
    last_pid: Option<i32>,
    clk_tck: f64,
}

impl Default for WorkloadAnalyzer {
    fn default() -> Self { Self::new() }
}

impl WorkloadAnalyzer {
    pub fn new() -> Self {
        let clk_tck = unsafe { libc::sysconf(libc::_SC_CLK_TCK) };
        Self {
            process_last: HashMap::new(),
            thread_last: HashMap::new(),
            last_sample: None,
            cached_pid: None,
            last_pid: None,
            clk_tck: if clk_tck > 0 { clk_tck as f64 } else { 100.0 },
        }
    }

    pub fn reset(&mut self) {
        self.process_last.clear();
        self.thread_last.clear();
        self.last_sample = None;
        self.cached_pid = None;
        self.last_pid = None;
    }

    pub fn sample(&mut self, s: &Sysroot, package: Option<&str>) -> Option<WorkloadSnapshot> {
        self.sample_with_gpu(s, package, None)
    }

    pub fn sample_with_gpu(
        &mut self,
        s: &Sysroot,
        package: Option<&str>,
        gpu_path: Option<&str>,
    ) -> Option<WorkloadSnapshot> {
        let package = package?.trim();
        if package.is_empty() {
            return None;
        }
        let pid = if self.cached_pid.as_ref().is_some_and(|(cached, pid)| cached == package && process_matches(s, *pid, package)) {
            self.cached_pid.as_ref().map(|(_, pid)| *pid)?
        } else {
            let pid = find_package_pid(s, package)?;
            self.cached_pid = Some((package.to_string(), pid));
            pid
        };
        if self.last_pid != Some(pid) {
            self.thread_last.clear();
            self.process_last.retain(|old_pid, _| *old_pid == pid);
            self.last_pid = Some(pid);
        }
        let now = Instant::now();
        let elapsed = self
            .last_sample
            .replace(now)
            .map(|old| now.saturating_duration_since(old))
            .unwrap_or(Duration::ZERO);

        let process_stat = read_stat(s, &format!("/proc/{pid}/stat"))?;
        let process_io = read_io(s, &format!("/proc/{pid}/io")).unwrap_or((0, 0));
        let process_run_delay = read_schedstat(s, pid).unwrap_or(0);
        let process_delta = self.process_last.insert(
            pid,
            Counter {
                ticks: process_stat.ticks,
                io_read: process_io.0,
                io_write: process_io.1,
                run_delay_ns: process_run_delay,
                start_time_ticks: process_stat.start_time_ticks,
            },
        );
        let process_delta = process_delta.filter(|counter| counter.start_time_ticks == process_stat.start_time_ticks);
        let process_util = delta_util(
            process_delta,
            process_stat.ticks,
            elapsed,
            self.clk_tck,
        );

        let mut threads = Vec::new();
        let task_dir = format!("/proc/{pid}/task");
        for tid_s in s.list_dir(&task_dir) {
            let Ok(tid) = tid_s.parse::<i32>() else { continue };
            if tid <= 0 { continue; }
            let stat_path = format!("{task_dir}/{tid}/stat");
            let Some(stat) = read_stat(s, &stat_path) else { continue };
            let name = s
                .read(&format!("{task_dir}/{tid}/comm"))
                .unwrap_or_else(|| stat.name.clone());
            let name = name.trim().to_string();
            let run_delay = read_schedstat(s, tid).unwrap_or(0);
            let prev = self.thread_last.insert(
                tid,
                Counter {
                    ticks: stat.ticks,
                    io_read: 0,
                    io_write: 0,
                    run_delay_ns: run_delay,
                    start_time_ticks: stat.start_time_ticks,
                },
            );
            let prev = prev.filter(|counter| counter.start_time_ticks == stat.start_time_ticks);
            let util = delta_util(prev, stat.ticks, elapsed, self.clk_tck);
            let run_queue_delay_ms = elapsed_delta_ms(prev.map(|c| c.run_delay_ns), run_delay);
            threads.push(ThreadSample {
                tid,
                class: classify_thread(&name),
                name,
                cpu_util_pct: util,
                run_queue_delay_ms,
                start_time_ticks: stat.start_time_ticks,
            });
        }

        threads.sort_by(|a, b| {
            b.cpu_util_pct.partial_cmp(&a.cpu_util_pct).unwrap_or(Ordering::Equal)
        });
        let top_threads = threads.iter().take(8).map(|t| t.tid).collect::<Vec<_>>();
        let confidence = if !threads.is_empty() { 90 } else { 55 };
        let active = process_util >= 2.0 || threads.iter().take(4).any(|t| t.cpu_util_pct >= 3.0);
        let gpu_util_pct = gpu_path.and_then(|path| read_gpu_util(s, path));
        let rss_kb = read_rss_kb(s, pid);
        let io_read_bps = elapsed_bps(process_delta.map(|c| c.io_read), process_io.0, elapsed);
        let io_write_bps = elapsed_bps(process_delta.map(|c| c.io_write), process_io.1, elapsed);
        let run_queue_delay_ms = elapsed_delta_ms(
            process_delta.map(|c| c.run_delay_ns),
            process_run_delay,
        );

        // Remove stale thread entries from the previous process incarnation.
        // Keeping this bounded is important on long-running daemons.
        if self.thread_last.len() > 4096 {
            self.thread_last.retain(|tid, _| s.exists(&format!("/proc/{tid}")));
        }
        if self.process_last.len() > 128 {
            self.process_last.retain(|pid, _| s.exists(&format!("/proc/{pid}")));
        }

        Some(WorkloadSnapshot {
            package: package.to_string(),
            pid,
            start_time_ticks: process_stat.start_time_ticks,
            cpu_util_pct: process_util,
            io_read_bps,
            io_write_bps,
            rss_kb,
            run_queue_delay_ms,
            threads,
            top_threads,
            active,
            gpu_util_pct,
            confidence,
        })
    }
}

#[derive(Clone, Debug)]
struct ProcStat {
    name: String,
    ticks: u64,
    start_time_ticks: u64,
}

fn find_package_pid(s: &Sysroot, package: &str) -> Option<i32> {
    let mut best = None;
    for entry in s.list_dir("/proc") {
        let Ok(pid) = entry.parse::<i32>() else { continue };
        if pid <= 0 { continue; }
        let Some(cmd) = s.read(&format!("/proc/{pid}/cmdline")) else { continue; };
        let first = cmd.split('\0').next().unwrap_or_default();
        let name = first.rsplit('/').next().unwrap_or(first);
        if first == package || name == package || first.starts_with(&format!("{package}:")) {
            // Prefer the smallest PID only as a deterministic tie-breaker;
            // Android normally has exactly one package process here.
            best = Some(best.map_or(pid, |old: i32| old.min(pid)));
        }
    }
    best
}

fn process_matches(s: &Sysroot, pid: i32, package: &str) -> bool {
    let Some(cmd) = s.read(&format!("/proc/{pid}/cmdline")) else { return false; };
    let first = cmd.split('\0').next().unwrap_or_default();
    let name = first.rsplit('/').next().unwrap_or(first);
    first == package || name == package || first.starts_with(&format!("{package}:"))
}

fn read_stat(s: &Sysroot, path: &str) -> Option<ProcStat> {
    let text = s.read(path)?;
    let close = text.rfind(") ")?;
    let name = text.get(1..close)?.to_string();
    let fields = text.get(close + 2..)?.split_whitespace().collect::<Vec<_>>();
    // fields[0] = state (field #3), fields[11]/[12] = utime/stime (#14/#15).
    let utime = fields.get(11)?.parse::<u64>().ok()?;
    let stime = fields.get(12)?.parse::<u64>().ok()?;
    let start_time_ticks = fields.get(19)?.parse::<u64>().ok()?;
    Some(ProcStat {
        name,
        ticks: utime.saturating_add(stime),
        start_time_ticks,
    })
}

fn read_io(s: &Sysroot, path: &str) -> Option<(u64, u64)> {
    let text = s.read(path)?;
    let mut read_bytes = None;
    let mut write_bytes = None;
    for line in text.lines() {
        if let Some(v) = line.strip_prefix("read_bytes:") {
            read_bytes = v.trim().parse::<u64>().ok();
        } else if let Some(v) = line.strip_prefix("write_bytes:") {
            write_bytes = v.trim().parse::<u64>().ok();
        }
    }
    Some((read_bytes.unwrap_or(0), write_bytes.unwrap_or(0)))
}

fn read_rss_kb(s: &Sysroot, pid: i32) -> Option<u64> {
    let statm = s.read(&format!("/proc/{pid}/statm"))?;
    let rss_pages = statm.split_whitespace().nth(1)?.parse::<u64>().ok()?;
    let page_size = unsafe { libc::sysconf(libc::_SC_PAGESIZE) };
    if page_size <= 0 { return None; }
    Some(rss_pages.saturating_mul(page_size as u64) / 1024)
}

fn delta_util(prev: Option<Counter>, current_ticks: u64, elapsed: Duration, clk_tck: f64) -> f64 {
    let Some(prev) = prev else { return 0.0; };
    let delta = current_ticks.saturating_sub(prev.ticks) as f64;
    let secs = elapsed.as_secs_f64();
    if secs <= 0.000_001 { return 0.0; }
    (delta / clk_tck / secs * 100.0).clamp(0.0, 800.0)
}

fn elapsed_delta_ms(prev: Option<u64>, current: u64) -> Option<f64> {
    Some(current.saturating_sub(prev?) as f64 / 1_000_000.0)
}

fn read_schedstat(s: &Sysroot, pid: i32) -> Option<u64> {
    let text = s.read(&format!("/proc/{pid}/schedstat"))?;
    text.split_whitespace().nth(1)?.parse::<u64>().ok()
}

fn elapsed_bps(prev: Option<u64>, current: u64, elapsed: Duration) -> Option<u64> {
    let prev = prev?;
    let secs = elapsed.as_secs_f64();
    if secs <= 0.000_001 { return None; }
    Some((current.saturating_sub(prev) as f64 / secs) as u64)
}

pub fn parse_cpu_list(input: &str) -> Vec<usize> {
    let mut out = Vec::new();
    for token in input.trim().split(',') {
        let token = token.trim();
        if token.is_empty() { continue; }
        if let Some((a, b)) = token.split_once('-') {
            let Ok(a) = a.trim().parse::<usize>() else { continue };
            let Ok(b) = b.trim().parse::<usize>() else { continue };
            for cpu in a..=b.min(a.saturating_add(255)) { out.push(cpu); }
        } else if let Ok(cpu) = token.parse::<usize>() {
            out.push(cpu);
        }
    }
    out.sort_unstable();
    out.dedup();
    out
}

fn read_gpu_util(s: &Sysroot, devfreq_path: &str) -> Option<f64> {
    let direct = [
        format!("{devfreq_path}/gpu_busy_percentage"),
        format!("{devfreq_path}/busy_percent"),
        format!("{devfreq_path}/utilization"),
        format!("{devfreq_path}/load"),
    ];
    for path in direct {
        let Some(raw) = s.read(&path) else { continue };
        let fields = raw.split_whitespace().collect::<Vec<_>>();
        if let Some(first) = fields.first().and_then(|v| v.parse::<f64>().ok()) {
            if fields.len() >= 2 {
                if let Some(second) = fields.get(1).and_then(|v| v.parse::<f64>().ok()) {
                    if second > 0.0 && first <= second {
                        let pct = first / second * 100.0;
                        if pct.is_finite() && (0.0..=100.0).contains(&pct) { return Some(pct); }
                    }
                }
            }
            if first.is_finite() && (0.0..=100.0).contains(&first) {
                return Some(first);
            }
        }
    }
    None
}

fn classify_thread(name: &str) -> ThreadClass {
    let n = name.to_ascii_lowercase();
    if n == "main" || n.ends_with("main") || n.contains("choreographer") {
        return ThreadClass::Main;
    }
    if n.contains("renderthread") || n.contains("hwui") || n.contains("glthread")
        || n.contains("surface") || n.contains("rhi") || n.contains("renderer") {
        return ThreadClass::Render;
    }
    if n.contains("gamethread") || n.contains("unity") || n.contains("ue4")
        || n.contains("unreal") || n.contains("game") {
        return ThreadClass::Game;
    }
    if n.contains("audio") || n.contains("sound") || n.contains("opensl") {
        return ThreadClass::Audio;
    }
    if n.contains("binder") || n.contains("hwservicemanager") {
        return ThreadClass::Binder;
    }
    if n.contains("worker") || n.contains("pool") || n.contains("executor") {
        return ThreadClass::Worker;
    }
    ThreadClass::Other
}

#[cfg(test)]
mod tests {
    use super::{classify_thread, parse_cpu_list, ThreadClass};

    #[test]
    fn parses_linux_cpu_ranges() {
        assert_eq!(parse_cpu_list("0-3,6,8-9"), vec![0, 1, 2, 3, 6, 8, 9]);
    }

    #[test]
    fn classifies_render_and_game_threads() {
        assert_eq!(classify_thread("RenderThread"), ThreadClass::Render);
        assert_eq!(classify_thread("UnityMain"), ThreadClass::Game);
        assert!(classify_thread("RenderThread").performance_candidate());
    }

    #[test]
    fn gpu_util_percent_prefers_ratio_when_available() {
        let r = std::env::temp_dir().join(format!("zairenkai-gpu-util-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&r);
        std::fs::create_dir_all(r.join("sys/gpu")).unwrap();
        std::fs::write(r.join("sys/gpu/load"), "70 100").unwrap();
        assert_eq!(super::read_gpu_util(&Sysroot::new(&r), "/sys/gpu"), Some(70.0));
        let _ = std::fs::remove_dir_all(r);
    }
}
