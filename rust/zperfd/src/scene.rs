// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Small Android scene/telemetry helpers used by zperfd Auto mode.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::nodes::Sysroot;
use std::io::Read;
use std::collections::BTreeMap;
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

pub fn getprops() -> Option<BTreeMap<String, String>> {
    let out = bounded_output(Command::new("getprop"), DUMPSYS_TIMEOUT)?;
    let text = String::from_utf8_lossy(&out);
    let mut props = BTreeMap::new();
    for line in text.lines() {
        let line = line.trim();
        let Some(rest) = line.strip_prefix('[') else { continue; };
        let Some((key, value)) = rest.split_once("]:") else { continue; };
        let key = key.trim();
        let value = value.trim().trim_start_matches('[').trim_end_matches(']').trim();
        if key.is_empty() || !key.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'_' || b == b'-') {
            continue;
        }
        props.insert(key.to_string(), value.to_string());
    }
    Some(props)
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
    if matches!(s.read("/sys/class/power_supply/battery/status").map(|v| v.to_ascii_lowercase()).as_deref(), Some("charging")) {
        return true;
    }

    // "Full" means the battery is full, not necessarily that external power
    // is currently available. Inspect every non-battery input supply instead
    // of assuming vendor-specific directory names such as USB or AC.
    for supply in s.list_dir("/sys/class/power_supply") {
        if supply.eq_ignore_ascii_case("battery") {
            continue;
        }
        let base = format!("/sys/class/power_supply/{supply}");
        let kind = s.read(&format!("{base}/type")).map(|v| v.to_ascii_lowercase());
        let online = s.read_u64(&format!("{base}/online"));
        let recognized = matches!(kind.as_deref(),
            Some("mains") | Some("usb") | Some("usb_c") | Some("usb_pd") | Some("wireless"));
        if recognized && online.is_some_and(|v| v > 0) {
            return true;
        }
    }
    false
}


/// Decide Auto mode from current telemetry. Missing telemetry is treated as
/// unsafe for performance selection instead of optimistic defaults.
pub fn decide_auto(
    battery: Option<u32>,
    thermal: zairenkai_core::policy::ThermalSignal,
    charge: bool,
    previous: Option<&str>,
) -> &'static str {
    let decision = zairenkai_core::policy::evaluate(zairenkai_core::policy::PolicyInput {
        battery_pct: battery.map(|v| v.min(100) as u8),
        thermal,
        external_power: charge,
        previous_mode: previous,
    });
    decision.intent.mode()
}

pub fn resolve_auto(s: &Sysroot) -> &'static str {
    let topology = crate::topo::Topology::detect(s);
    let snapshot = crate::thermal::snapshot(s, &topology);
    let thermal = zairenkai_core::policy::ThermalSignal {
        telemetry_complete: snapshot.telemetry_complete,
        headroom_permille: snapshot.headroom_permille,
        critical_reached: snapshot.critical_reached,
    };
    decide_auto(snapshot.battery_pct, thermal, snapshot.external_power, None)
}

pub fn resolve_auto_with_previous(s: &Sysroot, previous: Option<&str>) -> &'static str {
    let topology = crate::topo::Topology::detect(s);
    let snapshot = crate::thermal::snapshot(s, &topology);
    let thermal = zairenkai_core::policy::ThermalSignal {
        telemetry_complete: snapshot.telemetry_complete,
        headroom_permille: snapshot.headroom_permille,
        critical_reached: snapshot.critical_reached,
    };
    decide_auto(snapshot.battery_pct, thermal, snapshot.external_power, previous)
}

#[cfg(test)]
mod tests {
    use super::{charging, decide_auto};
    use crate::nodes::Sysroot;
    use std::fs;
    use std::path::PathBuf;

    fn root() -> PathBuf {
        let p = std::env::temp_dir().join(format!("zairenkai-scene-{}", std::process::id()));
        let _ = fs::remove_dir_all(&p);
        fs::create_dir_all(&p).unwrap();
        p
    }

    fn write(r: &PathBuf, path: &str, value: &str) {
        let p = r.join(path.trim_start_matches('/'));
        fs::create_dir_all(p.parent().unwrap()).unwrap();
        fs::write(p, value).unwrap();
    }


    #[test]
    fn full_battery_without_external_power_is_not_charging() {
        let r = root();
        write(&r, "/sys/class/power_supply/battery/status", "Full");
        assert!(!charging(&Sysroot::new(&r)));
        let _ = fs::remove_dir_all(r);
    }

    #[test]
    fn full_battery_with_lowercase_usb_input_is_external_power() {
        let r = root();
        write(&r, "/sys/class/power_supply/battery/status", "Full");
        write(&r, "/sys/class/power_supply/usb/type", "USB");
        write(&r, "/sys/class/power_supply/usb/online", "1");
        assert!(charging(&Sysroot::new(&r)));
        let _ = fs::remove_dir_all(r);
    }

    #[test]
    fn full_battery_with_online_usb_is_external_power() {
        let r = root();
        write(&r, "/sys/class/power_supply/battery/status", "Full");
        write(&r, "/sys/class/power_supply/USB/online", "1");
        assert!(charging(&Sysroot::new(&r)));
        let _ = fs::remove_dir_all(r);
    }

    #[test]
    fn unknown_thermal_data_never_selects_performance() {
        assert_eq!(decide_auto(Some(80), zairenkai_core::policy::ThermalSignal { telemetry_complete: false, headroom_permille: None, critical_reached: false }, true, None), "balance");
    }

    #[test]
    fn performance_has_thermal_hysteresis() {
        let release = zairenkai_core::policy::ThermalSignal {
            telemetry_complete: true, headroom_permille: Some(650), critical_reached: false,
        };
        let hold = zairenkai_core::policy::ThermalSignal {
            telemetry_complete: true, headroom_permille: Some(400), critical_reached: false,
        };
        assert_eq!(decide_auto(Some(80), release, true, Some("performance")), "performance");
        assert_eq!(decide_auto(Some(80), hold, true, Some("performance")), "performance");
        let insufficient = zairenkai_core::policy::ThermalSignal {
            telemetry_complete: true, headroom_permille: Some(200), critical_reached: false,
        };
        assert_eq!(decide_auto(Some(80), insufficient, true, Some("performance")), "balance");
    }

    #[test]
    fn powersave_has_recovery_hysteresis() {
        let thermal = zairenkai_core::policy::ThermalSignal {
            telemetry_complete: true, headroom_permille: Some(1000), critical_reached: false,
        };
        assert_eq!(decide_auto(Some(23), thermal, false, Some("powersave")), "powersave");
        assert_eq!(decide_auto(Some(26), thermal, false, Some("powersave")), "balance");
    }
}
