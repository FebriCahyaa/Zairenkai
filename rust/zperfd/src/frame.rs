// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Android frame telemetry with a SurfaceFlinger FrameTimeline primary source and gfxinfo compatibility fallback.
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
    /// True only when the metric came from a fresh compositor/frame probe.
    /// Cached observations must not advance the feedback controller's windows.
    pub fresh: bool,
}

#[derive(Clone, Copy, Debug)]
struct FrameTiming {
    token: i64,
    intended_ns: i64,
    completed_ns: i64,
    duration_ns: u64,
    jank: bool,
}

pub struct FrameAnalyzer {
    history: VecDeque<FrameTiming>,
    last_completed: i64,
    last_token: i64,
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
            last_token: 0,
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
        self.last_token = 0;
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

    /// Sample Android's SurfaceFlinger FrameTimeline first. This path asks the
    /// compositor for its already-classified frame timeline instead of asking
    /// ActivityManager/GraphicsStats to reconstruct a full `gfxinfo` report.
    /// `gfxinfo` remains an explicit compatibility fallback.
    pub fn sample(
        &mut self,
        package: &str,
        pid: i32,
        refresh_hz: Option<f64>,
        configured_budget_ms: f64,
        source_preference: &str,
    ) -> FrameMetrics {
        let configured_budget_ns = if configured_budget_ms.is_finite() && (4.0..=100.0).contains(&configured_budget_ms) {
            (configured_budget_ms * 1_000_000.0) as u64
        } else { DEFAULT_BUDGET_NS };
        let budget_ns = refresh_hz.filter(|v| v.is_finite() && *v >= 30.0 && *v <= 360.0)
            .map(|v| (1_000_000_000.0 / v) as u64).unwrap_or(configured_budget_ns);
        let budget_ms = budget_ns as f64 / 1_000_000.0;
        if self.last_budget_ns > 0 {
            let delta = self.last_budget_ns.abs_diff(budget_ns);
            if delta.saturating_mul(20) > self.last_budget_ns { self.history.clear(); self.last_completed = 0; self.last_token = 0; }
        }
        self.last_budget_ns = budget_ns;

        let mut parsed = Vec::new();
        let prefer_sf = matches!(source_preference, "auto" | "surfaceflinger");
        if prefer_sf {
            if let Some(output) = bounded_command(Command::new("dumpsys").args(["SurfaceFlinger", "--frametimeline"]), COMMAND_TIMEOUT) {
                parsed = parse_surfaceflinger_frametimeline(&String::from_utf8_lossy(&output), package, pid);
                if !parsed.is_empty() { return self.ingest(parsed, "surfaceflinger-frametimeline", budget_ms, budget_ns); }
            }
        }
        if matches!(source_preference, "auto" | "gfxinfo") {
            if let Some(output) = bounded_command(Command::new("dumpsys").args(["gfxinfo", package, "framestats"]), COMMAND_TIMEOUT) {
                parsed = parse_framestats(&String::from_utf8_lossy(&output));
                if !parsed.is_empty() { return self.ingest(parsed, "gfxinfo", budget_ms, budget_ns); }
            }
        }
        let fallback_source = if prefer_sf { "surfaceflinger-frametimeline" } else { "gfxinfo" };
        let metrics = FrameMetrics { budget_ms, source: fallback_source.into(), confidence: 0, fresh: false, ..FrameMetrics::default() };
        self.last_metrics = metrics.clone();
        metrics
    }

    fn ingest(&mut self, parsed: Vec<FrameTiming>, source: &str, budget_ms: f64, budget_ns: u64) -> FrameMetrics {
        let mut new_frames = 0u32;
        for frame in parsed {
            if frame.token != 0 {
                if frame.token <= self.last_token { continue; }
                self.last_token = frame.token;
            } else {
                if frame.completed_ns <= self.last_completed { continue; }
                self.last_completed = frame.completed_ns;
            }
            self.history.push_back(frame);
            if self.history.len() > HISTORY { self.history.pop_front(); }
            new_frames = new_frames.saturating_add(1);
        }
        let now = Instant::now();
        let elapsed = self.last_sample.replace(now).map(|old| now.saturating_duration_since(old));
        let recent = self.history.iter().copied().collect::<Vec<_>>();
        let count = recent.len() as u32;
        if count == 0 {
            let m=FrameMetrics { budget_ms, source:source.into(), confidence:0, ..FrameMetrics::default() }; self.last_metrics=m.clone(); return m;
        }
        let mut durations=recent.iter().map(|f| f.duration_ns as f64).collect::<Vec<_>>();
        durations.sort_by(|a,b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
        let avg_ms=durations.iter().sum::<f64>()/durations.len() as f64/1_000_000.0;
        let p95_idx=((durations.len() as f64*0.95).ceil() as usize).saturating_sub(1).min(durations.len()-1);
        let p95_ms=durations[p95_idx]/1_000_000.0;
        let jank=recent.iter().filter(|f| f.jank || f.duration_ns > budget_ns).count() as f64/durations.len() as f64;
        let fps=if let Some(elapsed)=elapsed.filter(|d| d.as_secs_f64()>=0.05) {(new_frames as f64/elapsed.as_secs_f64()).clamp(0.0,360.0)}
            else if let (Some(first),Some(last))=(recent.first(),recent.last()) { let span=last.completed_ns.saturating_sub(first.intended_ns) as f64/1e9; if span>0.05 {(count as f64/span).clamp(0.0,360.0)} else {0.0} } else {0.0};
        let metrics=FrameMetrics { available:true, source:source.into(), frames:count,new_frames,fps,avg_ms,p95_ms,jank_ratio:jank,budget_ms,confidence:if new_frames>0 {92} else {55},fresh:new_frames>0 };
        self.last_metrics=metrics.clone(); metrics
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
        rows.push(FrameTiming { token: 0, intended_ns, completed_ns, duration_ns, jank: false });
    }
    rows
}

fn parse_surfaceflinger_frametimeline(text: &str, package: &str, pid: i32) -> Vec<FrameTiming> {
    let mut rows = Vec::new();
    let mut token = 0i64;
    let mut owner_pid = 0i32;
    let mut layer_match = false;
    let mut jank = false;
    let mut expected_start = None::<i64>;
    let mut actual_start = None::<i64>;
    let mut actual_end = None::<i64>;
    let mut actual_present = None::<i64>;
    let mut flush = |rows: &mut Vec<FrameTiming>, token: &mut i64, owner_pid: &mut i32, layer_match: &mut bool, jank: &mut bool,
                     expected_start: &mut Option<i64>, actual_start: &mut Option<i64>, actual_end: &mut Option<i64>, actual_present: &mut Option<i64>| {
        let match_frame = *owner_pid == pid || (*layer_match && !package.is_empty());
        if match_frame {
            if let (Some(start), Some(end)) = (*actual_start, *actual_end) {
                if end > start && (end - start) < 2_500_000_000 {
                    let intended = expected_start.unwrap_or(start);
                    let complete = actual_present.unwrap_or(end);
                    rows.push(FrameTiming { token:*token, intended_ns:intended, completed_ns:complete, duration_ns:(end-start) as u64, jank:*jank });
                }
            }
        }
        *token=0; *owner_pid=0; *layer_match=false; *jank=false;
        *expected_start=None; *actual_start=None; *actual_end=None; *actual_present=None;
    };
    for line in text.lines() {
        let trim=line.trim();
        if trim.starts_with("Layer - ") {
            if token != 0 || actual_start.is_some() { flush(&mut rows,&mut token,&mut owner_pid,&mut layer_match,&mut jank,&mut expected_start,&mut actual_start,&mut actual_end,&mut actual_present); }
            let layer=trim.trim_start_matches("Layer - ");
            layer_match=layer.contains(package);
        } else if let Some(v)=trim.strip_prefix("Token:") { token=v.trim().parse().unwrap_or(0); }
        else if let Some(v)=trim.strip_prefix("Owner Pid :") { owner_pid=v.trim().parse().unwrap_or(0); }
        else if let Some(v)=trim.strip_prefix("Jank Type :") { jank=!v.trim().eq_ignore_ascii_case("None"); }
        else if trim.starts_with("Expected") { expected_start=parse_timeline_row(trim); }
        else if trim.starts_with("Actual") { if let Some((s,e,p))=parse_timeline_row3(trim) { actual_start=Some(s); actual_end=Some(e); actual_present=Some(p); } }
    }
    if token != 0 || actual_start.is_some() { flush(&mut rows,&mut token,&mut owner_pid,&mut layer_match,&mut jank,&mut expected_start,&mut actual_start,&mut actual_end,&mut actual_present); }
    rows
}


fn parse_timeline_values(line: &str) -> Vec<f64> {
    line.split('|')
        .flat_map(|part| part.trim().split_whitespace())
        .filter_map(|x| x.parse::<f64>().ok())
        .collect()
}

fn timeline_ns(value: f64) -> Option<i64> {
    if !value.is_finite() { return None; }
    // AOSP SurfaceFlinger dumps have historically represented timestamps in
    // different textual units. Large monotonic timestamps are already ns;
    // compact timeline dumps are interpreted as milliseconds. Supporting both
    // keeps the parser useful across Android vendor releases without another
    // probe or framework-side conversion.
    let ns = if value.abs() >= 1.0e12 { value } else { value * 1.0e6 };
    if ns.abs() > (i64::MAX as f64) { return None; }
    Some(ns.round() as i64)
}

fn parse_timeline_row(line: &str) -> Option<i64> {
    let v=parse_timeline_values(line);
    v.first().copied().and_then(timeline_ns)
}

fn parse_timeline_row3(line: &str) -> Option<(i64,i64,i64)> {
    let v=parse_timeline_values(line);
    (v.len()>=3).then(|| (timeline_ns(v[0])?, timeline_ns(v[1])?, timeline_ns(v[2])?))
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
    use super::*;

    #[test]
    fn parses_surfaceflinger_frametimeline_rows() {
        let sample = "Layer - com.example.game/com.example.game.Main\nToken: 42\nOwner Pid : 3210\nJank Type : None\nExpected | 1000 | 1008.333 | 1016.666\nActual | 1000.200 | 1008.500 | 1016.700\nLayer - other.layer\nToken: 77\nOwner Pid : 9999\nJank Type : None\nExpected | 1000 | 1008.333 | 1016.666\nActual | 1000.200 | 1008.500 | 1016.700\n";
        let frames = parse_surfaceflinger_frametimeline(sample, "com.example.game", 3210);
        assert_eq!(frames.len(), 1);
        assert_eq!(frames[0].token, 42);
        assert!(!frames[0].jank);
        assert_eq!(frames[0].duration_ns, 8_300_000);
    }

    #[test]
    fn parses_jank_label_as_janky() {
        let sample = "Layer - com.example.game\nToken: 9\nOwner Pid : 3210\nJank Type : MissedFrame\nExpected | 1000 | 1008.333 | 1016.666\nActual | 1000.200 | 1010.000 | 1020.000\n";
        let frames = parse_surfaceflinger_frametimeline(sample, "com.example.game", 3210);
        assert_eq!(frames.len(), 1);
        assert!(frames[0].jank);
    }

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

    #[test]
    fn timeline_units_support_compact_ms_and_monotonic_ns() {
        assert_eq!(timeline_ns(16.5), Some(16_500_000));
        assert_eq!(timeline_ns(1_000_000_000_000.0), Some(1_000_000_000_000));
    }
}
