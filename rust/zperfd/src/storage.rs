// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Storage capability and health classifier.
//!
//! Storage tuning never infers UFS/eMMC health from a model string. Runtime
//! queue attributes and health descriptors are the authority.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::inventory::{Inventory, StorageInfo};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind { Ufs, Emmc, Sd, Generic }

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Health { Good, Warning, Critical, Unknown }

pub fn kind(s: &StorageInfo) -> Kind {
    match s.kind.as_str() {
        "ufs" => Kind::Ufs,
        "emmc" => Kind::Emmc,
        "sd" => Kind::Sd,
        _ => Kind::Generic,
    }
}

pub fn health(s: &StorageInfo) -> Health {
    match s.health.as_str() {
        "critical" => Health::Critical,
        "warning" => Health::Warning,
        "reported" => Health::Unknown,
        _ => Health::Unknown,
    }
}

pub fn queue_tuning_allowed(s: &StorageInfo) -> bool {
    matches!(kind(s), Kind::Ufs | Kind::Emmc | Kind::Generic)
        && !matches!(health(s), Health::Critical)
}

pub fn safe_block_devices(s: &crate::nodes::Sysroot) -> Vec<String> {
    let mut out = Vec::new();
    for name in s.list_dir("/sys/block") {
        if name.starts_with("loop") || name.starts_with("ram") || name.starts_with("zram") || name.starts_with("dm-") || name.starts_with("sr") {
            continue;
        }
        let base = format!("/sys/block/{name}");
        if s.read_u64(&format!("{base}/queue/rotational")) == Some(1) {
            continue;
        }
        if name.starts_with("mmcblk") {
            let device_type = s.read(&format!("{base}/device/type")).unwrap_or_default();
            if device_type.trim().eq_ignore_ascii_case("sd") {
                continue;
            }
        }
        out.push(name);
    }
    out.sort();
    out
}

pub fn safe_devices(inv: &Inventory) -> usize {
    inv.storage.iter().filter(|s| queue_tuning_allowed(s)).count()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn storage(kind: &str, health: &str) -> StorageInfo {
        StorageInfo { name: "sda".into(), kind: kind.into(), model: None, size_bytes: None, scheduler: None, read_ahead_kb: None, health: health.into() }
    }

    #[test]
    fn critical_storage_is_never_tuned() {
        assert!(!queue_tuning_allowed(&storage("ufs", "critical")));
    }

    #[test]
    fn reported_storage_is_not_assumed_healthy() {
        assert_eq!(health(&storage("emmc", "reported")), Health::Unknown);
        assert!(queue_tuning_allowed(&storage("emmc", "reported")));
    }

    #[test]
    fn critical_health_blocks_queue_tuning_but_unknown_does_not_fake_health() {
        assert!(!queue_tuning_allowed(&storage("ufs", "critical")));
        assert_eq!(health(&storage("ufs", "unknown")), Health::Unknown);
    }
}
