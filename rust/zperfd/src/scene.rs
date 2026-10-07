// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Small Android scene/telemetry helpers used by zperfd Auto mode.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::nodes::Sysroot;
use std::io::Read;
use std::process::{Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

const DUMPSYS_TIMEOUT: Duration = Duration::from_millis(750);
const COMMAND_CAPTURE_BYTES: usize = 256 * 1024;

fn bounded_output(command: &mut Command, timeout: Duration) -> Option<Vec<u8>> {
    let mut child = command.stdout(Stdio::piped()).stderr(Stdio::null()).spawn().ok()?;
    let Some(mut stdout) = child.stdout.take() else {
        let _ = child.kill();
        let _ = child.wait();
        return None;
    };
    let reader = thread::spawn(move || {
        let mut buf = Vec::with_capacity(COMMAND_CAPTURE_BYTES.min(64 * 1024));
        let mut chunk = [0u8; 16 * 1024];
        loop {
            let n = stdout.read(&mut chunk).ok()?;
            if n == 0 { break; }
            let remaining = COMMAND_CAPTURE_BYTES.saturating_sub(buf.len());
            if remaining > 0 {
                buf.extend_from_slice(&chunk[..n.min(remaining)]);
            }
        }
        Some(buf)
    });

    let deadline = Instant::now() + timeout;
    let status = loop {
        if let Ok(Some(status)) = child.try_wait() {
            break Some(status);
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            let _ = reader.join();
            return None;
        }
        thread::sleep(Duration::from_millis(10));
    }?;

    let output = reader.join().ok()??;
    status.success().then_some(output)
}

pub fn foreground_pkg() -> Option<String> {
    let out = bounded_output(
        Command::new("dumpsys").args(["activity", "activities"]),
        DUMPSYS_TIMEOUT,
    )?;
    let text = String::from_utf8_lossy(&out);
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
    let out = bounded_output(Command::new("getprop").arg(key), DUMPSYS_TIMEOUT)?;
    let v = String::from_utf8_lossy(&out).trim().to_string();
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

/// Decide Auto mode from current telemetry. Missing telemetry is treated as
/// unsafe for performance selection instead of optimistic defaults.
pub fn decide_auto(
    battery: Option<u32>,
    hot: Option<f32>,
    charge: bool,
    previous: Option<&str>,
) -> &'static str {
    let low_battery = battery.map(|v| v <= 20).unwrap_or(false);
    let hot_limit = hot.map(|v| v >= 46.0).unwrap_or(false);
    if low_battery || hot_limit {
        return "powersave";
    }

    // Hysteresis prevents rapid performance <-> balance and powersave <- based
    // oscillation when telemetry sits on a boundary.
    match previous {
        Some("powersave")
            if battery.map(|v| v <= 25).unwrap_or(true)
                || hot.map(|v| v >= 43.0).unwrap_or(true) =>
        {
            return "powersave";
        }
        Some("performance")
            if charge && hot.map(|v| v < 44.0).unwrap_or(false) =>
        {
            return "performance";
        }
        _ => {}
    }

    if charge && hot.map(|v| v < 42.0).unwrap_or(false) {
        "performance"
    } else {
        "balance"
    }
}

pub fn resolve_auto(s: &Sysroot) -> &'static str {
    decide_auto(battery_percent(s), hottest_c(s), charging(s), None)
}

pub fn resolve_auto_with_previous(s: &Sysroot, previous: Option<&str>) -> &'static str {
    decide_auto(battery_percent(s), hottest_c(s), charging(s), previous)
}

#[cfg(test)]
mod tests {
    use super::decide_auto;

    #[test]
    fn unknown_thermal_data_never_selects_performance() {
        assert_eq!(decide_auto(Some(80), None, true, None), "balance");
    }

    #[test]
    fn performance_has_thermal_hysteresis() {
        assert_eq!(decide_auto(Some(80), Some(43.0), true, Some("performance")), "performance");
        assert_eq!(decide_auto(Some(80), Some(44.0), true, Some("performance")), "balance");
    }

    #[test]
    fn powersave_has_recovery_hysteresis() {
        assert_eq!(decide_auto(Some(23), Some(40.0), false, Some("powersave")), "powersave");
        assert_eq!(decide_auto(Some(26), Some(40.0), false, Some("powersave")), "balance");
    }
}
