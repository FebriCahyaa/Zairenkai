// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Profile model (TOML). A profile is a per-device/SoC catalog entry holding a
//! set of named power modes (powersave/balance/performance/fast). Values are
//! device-agnostic: frequencies are expressed as keywords or percentages and
//! resolved against the device's real OPP table at apply time.
//!
//! Copyright (C) 2026 FebriCahyaa

use serde::Deserialize;
use std::collections::BTreeMap;

#[derive(Debug, Clone, Deserialize, Default)]
pub struct Profile {
    #[serde(default)]
    pub meta: Meta,
    #[serde(default)]
    pub mode: BTreeMap<String, Mode>,
    /// Per-package mode override: package name -> mode key.
    #[serde(default)]
    pub perapp: BTreeMap<String, String>,
    /// Runtime feedback-loop parameters. Defaults are conservative and can be
    /// overridden per device profile without changing the controller binary.
    #[serde(default)]
    pub adaptive: AdaptiveConfig,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Meta {
    #[serde(default = "default_name")]
    pub name: String,
    #[serde(default)]
    pub soc: String,
    #[serde(default = "default_mode")]
    pub default_mode: String,
}

impl Default for Meta {
    fn default() -> Self {
        Meta { name: default_name(), soc: String::new(), default_mode: default_mode() }
    }
}

fn default_name() -> String { "generic".into() }
fn default_mode() -> String { "balance".into() }

#[derive(Debug, Clone, Deserialize)]
pub struct AdaptiveConfig {
    #[serde(default = "default_true")]
    pub enabled: bool,
    #[serde(default = "default_frame_budget_ms")]
    pub frame_budget_ms: f64,
    #[serde(default = "default_max_extra_boost")]
    pub max_extra_boost_pct: u32,
    #[serde(default = "default_jank_threshold")]
    pub jank_threshold_pct: u32,
    #[serde(default = "default_engage_windows")]
    pub engage_windows: u8,
    #[serde(default = "default_release_windows")]
    pub release_windows: u8,
    #[serde(default = "default_frame_probe_ms")]
    pub frame_probe_ms: u32,
    /// Preferred frame source. SurfaceFlinger FrameTimeline is the default;
    /// gfxinfo remains an explicit compatibility fallback.
    #[serde(default = "default_frame_source")]
    pub frame_source: String,
    #[serde(default = "default_true")]
    pub input_enabled: bool,
    #[serde(default = "default_interaction_boost_pct")]
    pub interaction_boost_pct: u32,
    #[serde(default = "default_interaction_boost_ms")]
    pub interaction_boost_ms: u32,
    #[serde(default = "default_interaction_hold_ms")]
    pub interaction_hold_ms: u32,
    #[serde(default = "default_input_scan_ms")]
    pub input_scan_ms: u32,
    #[serde(default = "default_true")]
    pub affinity_hint: bool,
}

impl Default for AdaptiveConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            frame_budget_ms: 16.666_667,
            max_extra_boost_pct: 35,
            jank_threshold_pct: 8,
            engage_windows: 2,
            release_windows: 3,
            frame_probe_ms: 900,
            frame_source: "surfaceflinger".into(),
            input_enabled: true,
            interaction_boost_pct: 15,
            interaction_boost_ms: 120,
            interaction_hold_ms: 180,
            input_scan_ms: 20,
            affinity_hint: true,
        }
    }
}

fn default_true() -> bool { true }
fn default_frame_budget_ms() -> f64 { 16.666_667 }
fn default_max_extra_boost() -> u32 { 35 }
fn default_jank_threshold() -> u32 { 8 }
fn default_engage_windows() -> u8 { 2 }
fn default_release_windows() -> u8 { 3 }
fn default_frame_probe_ms() -> u32 { 900 }
fn default_frame_source() -> String { "surfaceflinger".into() }
fn default_interaction_boost_pct() -> u32 { 15 }
fn default_interaction_boost_ms() -> u32 { 120 }
fn default_interaction_hold_ms() -> u32 { 180 }
fn default_input_scan_ms() -> u32 { 20 }

#[derive(Debug, Clone, Deserialize, Default)]
pub struct Mode {
    #[serde(default)]
    pub cpu: Cpu,
    #[serde(default)]
    pub gpu: Gpu,
    #[serde(default)]
    pub mem: Mem,
    #[serde(default)]
    pub io: Io,
}

#[derive(Debug, Clone, Deserialize, Default)]
pub struct Cpu {
    pub min_freq: Option<FreqSpec>,
    pub max_freq: Option<FreqSpec>,
    pub min_perf_pct: Option<u32>,
    pub max_perf_pct: Option<u32>,
    #[serde(default)]
    pub governor: Vec<String>,
    /// top-app uclamp (GKI cgroup) or schedtune boost (non-GKI), in percent.
    pub uclamp_min_pct: Option<u32>,
    pub uclamp_max_pct: Option<u32>,
    /// input boost freq as a percent of each policy max, and its duration.
    pub input_boost_pct: Option<u32>,
    pub input_boost_ms: Option<u32>,
    pub sched_boost: Option<i32>,
}

#[derive(Debug, Clone, Deserialize, Default)]
pub struct Gpu {
    pub min_freq: Option<FreqSpec>,
    pub max_freq: Option<FreqSpec>,
    pub min_perf_pct: Option<u32>,
    pub max_perf_pct: Option<u32>,
    #[serde(default)]
    pub governor: Vec<String>,
}

#[derive(Debug, Clone, Deserialize, Default)]
pub struct Mem {
    pub swappiness: Option<u32>,
    pub vfs_cache_pressure: Option<u32>,
    pub watermark_scale_factor: Option<u32>,
    pub page_cluster: Option<i32>,
    pub extra_free_kbytes: Option<u32>,
}

#[derive(Debug, Clone, Deserialize, Default)]
pub struct Io {
    #[serde(default)]
    pub scheduler: Vec<String>,
    pub read_ahead_kb: Option<u32>,
    pub nr_requests: Option<u32>,
}

/// A frequency value: a raw kHz integer, or a keyword/percentage string
/// (`"min"`, `"max"`, `"80%"`, `"1.8GHz"`, `"1800MHz"`, `"-300MHz"`).
#[derive(Debug, Clone, Deserialize)]
#[serde(untagged)]
pub enum FreqSpec {
    Khz(u64),
    Spec(String),
}

impl Profile {
    pub fn parse(text: &str) -> Result<Profile, String> {
        toml::from_str::<Profile>(text).map_err(|e| e.to_string())
    }

    pub fn mode(&self, key: &str) -> Option<&Mode> {
        self.mode.get(key)
    }
}

impl Profile {
    pub fn validate(&self) -> Result<(), String> {
        if self.meta.name.trim().is_empty() || self.meta.name.len() > 64 { return Err("invalid profile name".into()); }
        if self.meta.default_mode.is_empty() || !self.mode.contains_key(&self.meta.default_mode) {
            return Err(format!("default_mode '{}' has no matching mode", self.meta.default_mode));
        }
        if self.mode.is_empty() { return Err("profile has no modes".into()); }
        for (name, mode) in &self.mode {
            if name.is_empty() || name.len() > 32 || !name.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-')) {
                return Err(format!("invalid mode name '{name}'"));
            }
            mode.validate()?;
        }
        if !self.adaptive.frame_budget_ms.is_finite() || !(4.0..=100.0).contains(&self.adaptive.frame_budget_ms) {
            return Err("adaptive.frame_budget_ms must be finite and 4..100ms".into());
        }
        if self.adaptive.max_extra_boost_pct > 60 {
            return Err("adaptive.max_extra_boost_pct must be 0..60".into());
        }
        if self.adaptive.jank_threshold_pct > 100 {
            return Err("adaptive.jank_threshold_pct must be 0..100".into());
        }
        if self.adaptive.engage_windows == 0 || self.adaptive.engage_windows > 8 || self.adaptive.release_windows == 0 || self.adaptive.release_windows > 16 {
            return Err("adaptive window counts are out of range".into());
        }
        if !(250..=5_000).contains(&self.adaptive.frame_probe_ms) {
            return Err("adaptive.frame_probe_ms must be 250..5000ms".into());
        }
        if !matches!(self.adaptive.frame_source.as_str(), "auto" | "surfaceflinger" | "gfxinfo") {
            return Err("adaptive.frame_source must be auto, surfaceflinger, or gfxinfo".into());
        }
        if self.adaptive.interaction_boost_pct > 35 {
            return Err("adaptive.interaction_boost_pct must be 0..35".into());
        }
        if !(10..=5000).contains(&self.adaptive.interaction_boost_ms) {
            return Err("adaptive.interaction_boost_ms must be 10..5000ms".into());
        }
        if !(20..=5000).contains(&self.adaptive.interaction_hold_ms) {
            return Err("adaptive.interaction_hold_ms must be 20..5000ms".into());
        }
        if !(5..=250).contains(&self.adaptive.input_scan_ms) {
            return Err("adaptive.input_scan_ms must be 5..250ms".into());
        }
        for (pkg, mode) in &self.perapp {
            let valid = pkg.split('.').count() >= 2 && pkg.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'_');
            if !valid || pkg.len() > 255 { return Err(format!("invalid package '{pkg}'")); }
            if !self.mode.contains_key(mode) { return Err(format!("perapp '{pkg}' references unknown mode '{mode}'")); }
        }
        Ok(())
    }
}

impl Mode {
    pub fn validate(&self) -> Result<(), String> {
        let pct = |v: Option<u32>, n: &str| -> Result<(), String> { if v.unwrap_or(0) > 100 { Err(format!("{n} must be 0..100")) } else { Ok(()) } };
        pct(self.cpu.min_perf_pct, "cpu.min_perf_pct")?; pct(self.cpu.max_perf_pct, "cpu.max_perf_pct")?;
        pct(self.cpu.uclamp_min_pct, "cpu.uclamp_min_pct")?; pct(self.cpu.uclamp_max_pct, "cpu.uclamp_max_pct")?;
        pct(self.cpu.input_boost_pct, "cpu.input_boost_pct")?; pct(self.gpu.min_perf_pct, "gpu.min_perf_pct")?; pct(self.gpu.max_perf_pct, "gpu.max_perf_pct")?;
        if let (Some(a), Some(b)) = (self.cpu.uclamp_min_pct, self.cpu.uclamp_max_pct) { if a > b { return Err("uclamp_min_pct > uclamp_max_pct".into()); } }
        if self.cpu.input_boost_ms.unwrap_or(0) > 10_000 { return Err("input_boost_ms too large".into()); }
        if !(-100..=100).contains(&self.cpu.sched_boost.unwrap_or(0)) { return Err("sched_boost must be -100..100".into()); }
        if self.mem.swappiness.unwrap_or(0) > 200 || self.mem.vfs_cache_pressure.unwrap_or(0) > 1000 { return Err("memory value out of safe range".into()); }
        if self.io.read_ahead_kb.unwrap_or(0) > 4096 || self.io.nr_requests.map(|v| v == 0 || v > 4096).unwrap_or(false) { return Err("io value out of safe range".into()); }
        for list in [&self.cpu.governor, &self.gpu.governor, &self.io.scheduler] {
            if list.len() > 16 || list.iter().any(|x| x.is_empty() || x.len() > 64 || x.chars().any(|c| c.is_whitespace() || c == '/' || c == '\\')) {
                return Err("invalid candidate list".into());
            }
        }
        Ok(())
    }
}

pub fn uclamp_from_pct(pct: u32) -> u32 { ((pct.min(100) as u64 * 1024 + 50) / 100) as u32 }

/// Resolve a [FreqSpec] against a device's available-frequency table (kHz,
/// sorted ascending). The result is snapped to a real OPP: for a ceiling we
/// pick the highest OPP `<=` the request, for a floor the lowest OPP `>=` it.
pub fn resolve_freq(spec: &FreqSpec, avail_asc: &[u64], is_floor: bool) -> Option<u64> {
    if avail_asc.is_empty() {
        return None;
    }
    let lo = *avail_asc.first().unwrap();
    let hi = *avail_asc.last().unwrap();
    let raw: u64 = match spec {
        FreqSpec::Khz(k) => *k,
        FreqSpec::Spec(s) => parse_freq_str(s, lo, hi)?,
    };
    Some(snap(raw, avail_asc, is_floor))
}

/// Resolve a percent-of-max into a kHz value then snap.
pub fn resolve_pct(pct: u32, avail_asc: &[u64], is_floor: bool) -> Option<u64> {
    if avail_asc.is_empty() {
        return None;
    }
    let hi = *avail_asc.last().unwrap();
    let raw = (hi as u128 * pct.min(100) as u128 / 100) as u64;
    Some(snap(raw, avail_asc, is_floor))
}

fn parse_freq_str(s: &str, lo: u64, hi: u64) -> Option<u64> {
    let t = s.trim();
    match t.to_ascii_lowercase().as_str() {
        "min" => return Some(lo),
        "max" => return Some(hi),
        _ => {}
    }
    if let Some(p) = t.strip_suffix('%') {
        let pct: f64 = p.trim().parse().ok()?;
        if !pct.is_finite() || !(0.0..=100.0).contains(&pct) { return None; }
        return Some(((hi as f64) * pct / 100.0) as u64);
    }
    // max-relative, e.g. "-300MHz"
    let (neg, body) = if let Some(rest) = t.strip_prefix('-') {
        (true, rest.trim())
    } else {
        (false, t)
    };
    let khz = parse_unit_khz(body)?;
    if neg {
        Some(hi.saturating_sub(khz))
    } else {
        Some(khz)
    }
}

fn parse_unit_khz(body: &str) -> Option<u64> {
    let b = body.trim().to_ascii_lowercase();
    if let Some(v) = b.strip_suffix("ghz") {
        let f: f64 = v.trim().parse().ok()?;
        if !f.is_finite() || f < 0.0 { return None; }
        return Some((f * 1_000_000.0) as u64);
    }
    if let Some(v) = b.strip_suffix("mhz") {
        let f: f64 = v.trim().parse().ok()?;
        if !f.is_finite() || f < 0.0 { return None; }
        return Some((f * 1_000.0) as u64);
    }
    if let Some(v) = b.strip_suffix("khz") {
        let f: f64 = v.trim().parse().ok()?;
        if !f.is_finite() || f < 0.0 { return None; }
        return Some(f as u64);
    }
    b.parse().ok()
}

fn snap(target: u64, avail_asc: &[u64], is_floor: bool) -> u64 {
    if is_floor {
        // lowest OPP >= target, else the highest OPP
        avail_asc.iter().copied().find(|&f| f >= target).unwrap_or(*avail_asc.last().unwrap())
    } else {
        // highest OPP <= target, else the lowest OPP
        avail_asc.iter().copied().rev().find(|&f| f <= target).unwrap_or(*avail_asc.first().unwrap())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const AVAIL: &[u64] = &[300000, 600000, 1200000, 1800000, 2400000];

    #[test]
    fn keywords() {
        assert_eq!(resolve_freq(&FreqSpec::Spec("min".into()), AVAIL, false), Some(300000));
        assert_eq!(resolve_freq(&FreqSpec::Spec("max".into()), AVAIL, false), Some(2400000));
    }

    #[test]
    fn percent_and_snap_ceiling() {
        // 80% of 2.4GHz = 1.92GHz -> snap down to 1.8GHz
        assert_eq!(resolve_freq(&FreqSpec::Spec("80%".into()), AVAIL, false), Some(1800000));
        assert_eq!(resolve_pct(50, AVAIL, false), Some(1200000));
    }

    #[test]
    fn units_and_relative() {
        assert_eq!(resolve_freq(&FreqSpec::Spec("1.8GHz".into()), AVAIL, false), Some(1800000));
        assert_eq!(resolve_freq(&FreqSpec::Spec("1200MHz".into()), AVAIL, false), Some(1200000));
        // -600MHz off 2.4GHz = 1.8GHz
        assert_eq!(resolve_freq(&FreqSpec::Spec("-600MHz".into()), AVAIL, false), Some(1800000));
    }

    #[test]
    fn raw_khz_floor() {
        // floor snap: lowest >= 1000000 is 1200000
        assert_eq!(resolve_freq(&FreqSpec::Khz(1_000_000), AVAIL, true), Some(1200000));
    }

    #[test]
    fn adaptive_defaults_are_conservative() {
        let p = Profile::parse(r#"
            [meta]
            name = "test"
            default_mode = "balance"
            [mode.balance]
        "#).unwrap();
        assert!(p.adaptive.enabled);
        assert_eq!(p.adaptive.engage_windows, 2);
        assert_eq!(p.adaptive.release_windows, 3);
        assert_eq!(p.adaptive.frame_probe_ms, 900);
        assert_eq!(p.adaptive.frame_source, "surfaceflinger");
        assert!(p.adaptive.input_enabled);
        assert_eq!(p.adaptive.interaction_boost_ms, 120);
        assert_eq!(p.adaptive.interaction_hold_ms, 180);
        assert!((p.adaptive.frame_budget_ms - 16.666_667).abs() < 0.0001);
    }

    #[test]
    fn parse_profile() {
        let p = Profile::parse(
            r#"
            [meta]
            name = "test"
            default_mode = "balance"
            [mode.balance.cpu]
            max_perf_pct = 100
            min_freq = "min"
            governor = ["schedutil", "walt"]
            uclamp_min_pct = 0
            [mode.performance.cpu]
            max_freq = "max"
            min_freq = "25%"
            uclamp_min_pct = 20
            input_boost_ms = 2000
            [perapp]
            "com.tencent.tmgp.sgame" = "performance"
            "#,
        )
        .unwrap();
        assert_eq!(p.meta.name, "test");
        assert_eq!(p.meta.default_mode, "balance");
        assert!(p.mode.contains_key("balance"));
        assert_eq!(p.perapp.get("com.tencent.tmgp.sgame").map(|s| s.as_str()), Some("performance"));
        assert_eq!(p.mode("performance").unwrap().cpu.input_boost_ms, Some(2000));
    }
}
