// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Safe Android property broker.
//!
//! Zairenkai never exposes a generic `setprop` command. Only properties in
//! the framework-owned namespace are mutable through this broker. Vendor and
//! framework properties are observable through getprop but remain immutable by
//! default unless a future signed provider explicitly adds a capability-gated
//! adapter.
//!
//! Copyright (C) 2026 FebriCahyaa

use std::process::{Command, Stdio};
use std::time::Duration;

const MAX_VALUE_BYTES: usize = 256;
const MAX_OUTPUT_BYTES: usize = 4096;
const TIMEOUT: Duration = Duration::from_millis(750);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Scope {
    FrameworkOwned,
    ExternalReadOnly,
}

#[derive(Debug, Clone, Copy)]
pub struct Spec {
    pub key: &'static str,
    pub scope: Scope,
    pub description: &'static str,
}

pub const SPECS: &[Spec] = &[
    Spec { key: "persist.zairenkai.profile", scope: Scope::FrameworkOwned, description: "selected profile" },
    Spec { key: "persist.zairenkai.mode", scope: Scope::FrameworkOwned, description: "requested runtime mode" },
    Spec { key: "persist.zairenkai.safety", scope: Scope::FrameworkOwned, description: "Sentinel safety state" },
    Spec { key: "persist.zairenkai.boost", scope: Scope::FrameworkOwned, description: "runtime boost budget" },
];

fn valid_read_key(key: &str) -> bool {
    !key.is_empty()
        && key.len() <= 92
        && key.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'_' || b == b'-')
        && key.as_bytes().first().is_some_and(|b| b.is_ascii_alphanumeric())
        && key.as_bytes().last().is_some_and(|b| b.is_ascii_alphanumeric() || *b == b'_' || *b == b'-')
}

const READ_ONLY_KEYS: &[&str] = &[
    "ro.board.platform",
    "ro.build.version.release",
    "ro.build.version.sdk",
    "ro.product.cpu.abi",
    "ro.product.cpu.abilist",
    "ro.product.device",
    "ro.product.model",
    "ro.product.name",
    "ro.boot.verifiedbootstate",
    "ro.boot.veritymode",
];

fn spec(key: &str) -> Option<&'static Spec> {
    SPECS.iter().find(|s| s.key == key)
}

fn valid_owned_key(key: &str) -> bool {
    valid_read_key(key)
        && key.starts_with("persist.zairenkai.")
        && spec(key).is_some_and(|s| matches!(s.scope, Scope::FrameworkOwned))
}

fn valid_value(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= MAX_VALUE_BYTES
        && !value.bytes().any(|b| b == 0 || b == b'\n' || b == b'\r')
}

fn valid_value_for(key: &str, value: &str) -> bool {
    if !valid_value(value) { return false; }
    match key {
        "persist.zairenkai.profile" | "persist.zairenkai.mode" | "persist.zairenkai.safety" =>
            value.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'_' || b == b'-'),
        "persist.zairenkai.boost" => value.parse::<u32>().is_ok_and(|v| v <= 1000),
        _ => false,
    }
}

pub fn get(key: &str) -> Result<String, String> {
    if !valid_read_key(key) { return Err("invalid Android property key".into()); }
    if !key.starts_with("persist.zairenkai.") && !READ_ONLY_KEYS.contains(&key) {
        return Err("property is outside the Zairenkai diagnostic allowlist".into());
    }
    run_bounded(Command::new("getprop").arg(key))
}

pub fn set(key: &str, value: &str) -> Result<(), String> {
    if !valid_owned_key(key) { return Err("property is not a registered Zairenkai-owned property".into()); }
    if !valid_value_for(key, value) { return Err("property value is invalid for this key".into()); }
    run_bounded(Command::new("setprop").arg(key).arg(value)).map(|_| ())
}

pub fn list() -> impl Iterator<Item = &'static Spec> {
    SPECS.iter()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_owned_namespace_is_mutable() {
        assert!(valid_owned_key("persist.zairenkai.mode"));
        assert!(!valid_owned_key("debug.hwui.renderer"));
        assert!(!valid_owned_key("persist.vendor.foo"));
        assert!(!valid_owned_key("persist.zairenkai.future_flag"));
    }

    #[test]
    fn control_characters_are_rejected() {
        assert!(!valid_value("hello\nworld"));
        assert!(valid_value_for("persist.zairenkai.mode", "performance"));
        assert!(valid_value_for("persist.zairenkai.boost", "750"));
        assert!(!valid_value_for("persist.zairenkai.boost", "5000"));
    }

    #[test]
    fn property_reads_fail_closed_outside_diagnostic_allowlist() {
        assert!(READ_ONLY_KEYS.contains(&"ro.product.model"));
        assert!(!READ_ONLY_KEYS.contains(&"ro.secret"));
        assert!("persist.zairenkai.mode".starts_with("persist.zairenkai."));
    }
}
