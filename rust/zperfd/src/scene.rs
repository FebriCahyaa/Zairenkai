// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Small Android scene/telemetry helpers used by zperfd Auto mode.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::nodes::Sysroot;
use std::process::Command;

pub fn foreground_pkg() -> Option<String> {
    let out = Command::new("dumpsys").args(["activity", "activities"]).output().ok()?;
    if !out.status.success() { return None; }
    let text = String::from_utf8_lossy(&out.stdout);
    for line in text.lines() {
        if !line.contains("mResumedActivity") && !line.contains("topResumedActivity") { continue; }
        for tok in line.split_whitespace() {
            let pkg = tok.split_once('/')?.0;
            if valid_package(pkg) { return Some(pkg.to_string()); }
        }
    }
    None
}

fn valid_package(s: &str) -> bool {
    let parts: Vec<&str> = s.split('.').collect();
    parts.len() >= 2 && parts.iter().all(|p| !p.is_empty() && p.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_'))
}

pub fn getprop(key: &str) -> Option<String> {
    if key.is_empty() || !key.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'_' || b == b'-') { return None; }
    let out = Command::new("getprop").arg(key).output().ok()?;
    if !out.status.success() { return None; }
    let v = String::from_utf8_lossy(&out.stdout).trim().to_string();
    (!v.is_empty()).then_some(v)
}

pub fn soc_platform() -> Option<String> { getprop("ro.board.platform").map(|s| s.to_ascii_lowercase()) }

pub fn battery_percent(s: &Sysroot) -> Option<u32> {
    s.read_u64("/sys/class/power_supply/battery/capacity").map(|v| v.min(100) as u32)
}

pub fn charging(s: &Sysroot) -> bool {
    match s.read("/sys/class/power_supply/battery/status").map(|v| v.to_ascii_lowercase()) {
        Some(v) => matches!(v.as_str(), "charging" | "full"),
        None => false,
    }
}

pub fn hottest_c(s: &Sysroot) -> Option<f32> {
    let mut hottest = None;
    for zone in s.list_dir("/sys/class/thermal") {
        if !zone.starts_with("thermal_zone") { continue; }
        if let Some(mdeg) = s.read_u64(&format!("/sys/class/thermal/{zone}/temp")) {
            if (20000..150000).contains(&mdeg) {
                let c = mdeg as f32 / 1000.0;
                hottest = Some(hottest.map_or(c, |h: f32| h.max(c)));
            }
        }
    }
    hottest
}

pub fn resolve_auto(s: &Sysroot) -> &'static str {
    let battery = battery_percent(s).unwrap_or(50);
    let hot = hottest_c(s).unwrap_or(35.0);
    let charge = charging(s);
    if battery <= 20 || hot >= 46.0 { "powersave" }
    else if charge && hot < 42.0 { "performance" }
    else { "balance" }
}
