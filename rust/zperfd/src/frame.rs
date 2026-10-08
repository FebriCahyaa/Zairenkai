// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Android frame telemetry with a vendor-neutral gfxinfo parser.
//!
//! Frame telemetry is opportunistic: when Android exposes framestats we build a
//! rolling window; when it does not, the adaptive controller can fall back to
//! workload telemetry with reduced confidence.
//!
//! Copyright (C) 2026 FebriCahyaa

use std::collections::VecDeque;
use std::process::{Command, Stdio};
use std::thread;
use std::io::Read;
use std::time::{Duration, Instant};

const MAX_BYTES: usize = 512 * 1024;
const COMMAND_TIMEOUT: Duration = Duration::from_millis(1200);
const DEFAULT_BUDGET_NS: u64 = 16_666_667;
const HISTORY: usize = 240;

#[derive(Clone, Debug, Default)]
pub struct FrameMetrics {
    pub available: bool,
    pub source: String,
    pub frames: u32,
    pub new_frames: u32,
    pub fps: f64,
    pub avg_ms: f64,
    pub p95_ms: f64,
    pub jank_ratio: f64,
    pub budget_ms: f64,
    pub confidence: u8,
    /// True only when the metric came from a fresh gfxinfo probe. Cached
    /// observations must not advance the feedback controller's windows.
    pub fresh: bool,
}

#[derive(Clone, Copy, Debug)]
struct FrameTiming {
    intended_ns: i64,
    completed_ns: i64,
    duration_ns: u64,
}

pub struct FrameAnalyzer {
    history: VecDeque<FrameTiming>,
    last_completed: i64,
    last_sample: Option<Instant>,
    last_metrics: FrameMetrics,
    last_budget_ns: u64,
    refresh_hz: Option<f64>,
    last_refresh_probe: Option<Instant>,
}

impl Default for FrameAnalyzer {
    fn default() -> Self { Self::new() }
}

impl FrameAnalyzer {
    pub fn new() -> Self {
        Self {
            history: VecDeque::with_capacity(HISTORY),
            last_completed: 0,
            last_sample: None,
            last_metrics: FrameMetrics::default(),
            last_budget_ns: 0,
            refresh_hz: None,
            last_refresh_probe: None,
        }
    }

    pub fn reset(&mut self) {
        self.history.clear();
        self.last_completed = 0;
        self.last_sample = None;
        self.last_metrics = FrameMetrics::default();
        self.last_budget_ns = 0;
        self.refresh_hz = None;
        self.last_refresh_probe = None;
    }

    /// Best-effort current display refresh rate. The probe is cached because
    /// `dumpsys display` is considerably more expensive than reading procfs.
    pub fn current_refresh_hz(&mut self) -> Option<f64> {
        let now = Instant::now();
        if self
            .last_refresh_probe
            .is_some_and(|t| now.saturating_duration_since(t) < Duration::from_secs(5))
        {
            return self.refresh_hz;
        }
        self.last_refresh_probe = Some(now);
        self.refresh_hz = bounded_command(
            Command::new("dumpsys").arg("display"),
            Duration::from_millis(800),
        )
        .and_then(|bytes| parse_refresh_rate(&String::from_utf8_lossy(&bytes)));
        self.refresh_hz
    }

    pub fn sample(&mut self, package: &str, refresh_hz: Option<f64>, configured_budget_ms: f64) -> FrameMetrics {
        let configured_budget_ns = if configured_budget_ms.is_finite() && (4.0..=100.0).contains(&configured_budget_ms) {
            (configured_budget_ms * 1_000_000.0) as u64
        } else {
            DEFAULT_BUDGET_NS
        };
        let budget_ns = refresh_hz
            .filter(|v| v.is_finite() && *v >= 30.0 && *v <= 360.0)
            .map(|v| (1_000_000_000.0 / v) as u64)
            .unwrap_or(configured_budget_ns);
        let budget_ms = budget_ns as f64 / 1_000_000.0;
        if self.last_budget_ns > 0 {
            let delta = self.last_budget_ns.abs_diff(budget_ns);
            if delta.saturating_mul(20) > self.last_budget_ns {
                self.history.clear();
                self.last_completed = 0;
            }
        }
        self.last_budget_ns = budget_ns;
        let Some(output) = bounded_command(Command::new("dumpsys").args(["gfxinfo", package, "framestats"]), COMMAND_TIMEOUT) else {
            let metrics = FrameMetrics { source: "gfxinfo".into(), budget_ms, confidence: 0, fresh: false, ..FrameMetrics::default() };
            self.last_metrics = metrics.clone();
            return metrics;
        };
        let text = String::from_utf8_lossy(&output);
        let parsed = parse_framestats(&text);
        if parsed.is_empty() {
            let metrics = FrameMetrics { budget_ms, source: "gfxinfo".into(), confidence: 0, fresh: false, ..FrameMetrics::default() };
            self.last_metrics = metrics.clone();
            return metrics;
        }
        let mut new_frames = 0u32;
        for frame in parsed {
            if frame.completed_ns <= self.last_completed { continue; }
            self.last_completed = frame.completed_ns;
            self.history.push_back(frame);
            if self.history.len() > HISTORY { self.history.pop_front(); }
            new_frames = new_frames.saturating_add(1);
        }

        let now = Instant::now();
        let elapsed = self.last_sample.replace(now).map(|old| now.saturating_duration_since(old));
        let recent = self.history.iter().copied().collect::<Vec<_>>();
        let count = recent.len() as u32;
        if count == 0 { return FrameMetrics { budget_ms, source: "gfxinfo".into(), confidence: 0, ..FrameMetrics::default() }; }
        let mut durations = recent.iter().map(|f| f.duration_ns as f64).collect::<Vec<_>>();
        durations.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
        let avg_ms = durations.iter().sum::<f64>() / durations.len() as f64 / 1_000_000.0;
        let p95_idx = ((durations.len() as f64 * 0.95).ceil() as usize).saturating_sub(1).min(durations.len() - 1);
        let p95_ms = durations[p95_idx] / 1_000_000.0;
        let jank = durations.iter().filter(|d| **d > budget_ns as f64).count() as f64 / durations.len() as f64;
        let fps = if let Some(elapsed) = elapsed.filter(|d| d.as_secs_f64() >= 0.05) {
            (new_frames as f64 / elapsed.as_secs_f64()).clamp(0.0, 360.0)
        } else if let (Some(first), Some(last)) = (recent.first(), recent.last()) {
            let span = last.completed_ns.saturating_sub(first.intended_ns) as f64 / 1_000_000_000.0;
            if span > 0.05 { (count as f64 / span).clamp(0.0, 360.0) } else { 0.0 }
        } else { 0.0 };

        let metrics = FrameMetrics {
            available: true,
            source: "gfxinfo".into(),
            frames: count,
            new_frames,
            fps,
            avg_ms,
            p95_ms,
            jank_ratio: jank,
            budget_ms,
            confidence: if new_frames > 0 { 80 } else { 40 },
            fresh: new_frames > 0,
        };
        self.last_metrics = metrics.clone();
        metrics
    }

    pub fn cached(&self) -> FrameMetrics {
        let mut metrics = self.last_metrics.clone();
        metrics.fresh = false;
        metrics
    }
}

fn parse_framestats(text: &str) -> Vec<FrameTiming> {
    let mut intended_idx = None;
    let mut completed_idx = None;
    let mut rows = Vec::new();
    for line in text.lines() {
        let upper = line.to_ascii_uppercase();
        let normalized = upper.replace('_', "").replace(' ', "");
        if normalized.contains("INTENDEDVSYNC") && normalized.contains("FRAMECOMPLETED") {
            let cols = line.split(',').map(|s| s.trim().to_ascii_uppercase().replace('_', "").replace(' ', "")).collect::<Vec<_>>();
            intended_idx = cols.iter().position(|v| v == "INTENDEDVSYNC");
            completed_idx = cols.iter().position(|v| v == "FRAMECOMPLETED");
            continue;
        }
        let (Some(ii), Some(ci)) = (intended_idx, completed_idx) else { continue; };
        let cols = line.split(',').map(str::trim).collect::<Vec<_>>();
        if cols.len() <= ii.max(ci) { continue; }
        let Ok(intended_ns) = cols[ii].parse::<i64>() else { continue; };
        let Ok(completed_ns) = cols[ci].parse::<i64>() else { continue; };
        if intended_ns <= 0 || completed_ns <= intended_ns { continue; }
        let duration_ns = completed_ns.saturating_sub(intended_ns) as u64;
        if duration_ns > 2_000_000_000 { continue; }
        rows.push(FrameTiming { intended_ns, completed_ns, duration_ns });
    }
    rows
}

fn parse_refresh_rate(text: &str) -> Option<f64> {
    let lower = text.to_ascii_lowercase();
    for needle in ["mrefreshrate=", "refreshrate=", "refresh rate="] {
        let mut start = 0usize;
        while let Some(pos) = lower[start..].find(needle) {
            let idx = start + pos + needle.len();
            let token = lower[idx..]
                .chars()
                .skip_while(|c| c.is_ascii_whitespace())
                .take_while(|c| c.is_ascii_digit() || *c == '.')
                .collect::<String>();
            if let Ok(rate) = token.parse::<f64>() {
                if rate.is_finite() && (30.0..=360.0).contains(&rate) {
                    return Some(rate);
                }
            }
            start = idx;
        }
    }
    None
}

fn bounded_command(command: &mut Command, timeout: Duration) -> Option<Vec<u8>> {
    let mut child = command.stdout(Stdio::piped()).stderr(Stdio::null()).spawn().ok()?;
    let mut stdout = child.stdout.take()?;
    let reader = thread::spawn(move || {
        let mut out = Vec::with_capacity(64 * 1024);
        let mut chunk = [0u8; 16 * 1024];
        loop {
            let n = stdout.read(&mut chunk).ok()?;
            if n == 0 { break; }
            let keep = n.min(MAX_BYTES.saturating_sub(out.len()));
            if keep > 0 { out.extend_from_slice(&chunk[..keep]); }
        }
        Some(out)
    });
    let deadline = Instant::now() + timeout;
    loop {
        if let Ok(Some(status)) = child.try_wait() {
            if !status.success() { return None; }
            return reader.join().ok().flatten();
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            let _ = reader.join();
            return None;
        }
        thread::sleep(Duration::from_millis(10));
    }
}

#[cfg(test)]
mod tests {
    use super::{parse_framestats, parse_refresh_rate};

    #[test]
    fn parses_display_refresh_rate() {
        assert_eq!(parse_refresh_rate("mRefreshRate=120.000000"), Some(120.0));
        assert_eq!(parse_refresh_rate("refreshRate=60"), Some(60.0));
    }

    #[test]
    fn parses_gfxinfo_csv() {
        let text = "INTENDED_VSYNC,VSYNC,FRAME_COMPLETED\n1000000000,1000000005,1010000000\n2000000000,2000000005,2020000000\n";
        let rows = parse_framestats(text);
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].duration_ns, 10_000_000);
    }
}
