// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Scene detection: the current foreground package and a few device props.
//! Phase-1 uses a light dumpsys/getprop poll; frame-aware control (FAS) is a
//! separate component.
//!
//! Copyright (C) 2026 FebriCahyaa

use std::process::Command;

/// Current foreground package via `dumpsys activity`, or None off-device.
pub fn foreground_pkg() -> Option<String> {
    let out = Command::new("sh")
        .arg("-c")
        .arg("dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity|topResumedActivity' | head -n1")
        .output()
        .ok()?;
    let text = String::from_utf8_lossy(&out.stdout);
    for tok in text.split_whitespace() {
        if let Some((pkg, _)) = tok.split_once('/') {
            if pkg.contains('.') && pkg.chars().next().map(|c| c.is_ascii_alphabetic()).unwrap_or(false) {
                return Some(pkg.to_string());
            }
        }
    }
    None
}

/// Read a system property via `getprop`, or None off-device.
pub fn getprop(key: &str) -> Option<String> {
    let out = Command::new("getprop").arg(key).output().ok()?;
    let v = String::from_utf8_lossy(&out.stdout).trim().to_string();
    if v.is_empty() {
        None
    } else {
        Some(v)
    }
}

/// SoC platform codename (ro.board.platform), lowercased.
pub fn soc_platform() -> Option<String> {
    getprop("ro.board.platform").map(|s| s.to_ascii_lowercase())
}
