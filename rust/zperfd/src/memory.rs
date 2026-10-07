// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Memory/ZRAM safety envelope.
//!
//! This module is deliberately advisory: it derives conservative limits from
//! runtime memory pressure and zram state, then lets Sentinel and transactions
//! decide whether mutation is permissible.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::inventory::Inventory;
use crate::nodes::Sysroot;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Pressure {
    Unknown,
    Normal,
    Elevated,
    Critical,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Envelope {
    pub pressure: Pressure,
    pub recommended_swappiness_floor: Option<u32>,
    pub zram_resize_allowed: bool,
}

fn ratio_bps(available: Option<u64>, total: Option<u64>) -> Option<u64> {
    let total = total?;
    if total == 0 { return None; }
    Some(available.unwrap_or(0).saturating_mul(10_000) / total)
}

pub fn pressure(inv: &Inventory) -> Pressure {
    let ratio = ratio_bps(inv.memory.available_kb, inv.memory.total_kb);
    match ratio {
        Some(v) if v < 500 => Pressure::Critical,
        Some(v) if v < 1200 => Pressure::Elevated,
        Some(_) => Pressure::Normal,
        None => Pressure::Unknown,
    }
}

pub fn envelope(inv: &Inventory) -> Envelope {
    let pressure = pressure(inv);
    let has_zram = !inv.memory.zram.is_empty();
    Envelope {
        pressure,
        recommended_swappiness_floor: match (pressure, has_zram) {
            (Pressure::Critical | Pressure::Elevated, true) => Some(80),
            _ => None,
        },
        zram_resize_allowed: inv.memory.zram.iter().any(|z| z.size_bytes == Some(0)),
    }
}

/// Return true only when zram is demonstrably uninitialized. Modern kernels
/// expose initstate; legacy trees may not, so disksize==0 is accepted as a
/// conservative fallback.
pub fn zram_uninitialized(s: &Sysroot, name: &str) -> bool {
    let base = format!("/sys/block/{name}");
    if !s.exists(&base) { return false; }
    if let Some(init) = s.read(&format!("{base}/initstate")) {
        return init.trim() == "0";
    }
    s.read_u64(&format!("{base}/disksize")) == Some(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::inventory::{MemoryInfo, NetworkInfo, SecurityInfo, StorageInfo, ZramInfo};
    use crate::platform::{PlatformIdentity, SocVendor};

    fn inv(avail: Option<u64>) -> Inventory {
        Inventory {
            identity: PlatformIdentity { vendor: SocVendor::Unknown, platform: "x".into(), model: "m".into(), compatible: "".into(), evidence: vec![] },
            memory: MemoryInfo { total_kb: Some(4_000_000), available_kb: avail, swap_total_kb: Some(1), swap_free_kb: Some(1), zram: vec![ZramInfo { name: "zram0".into(), size_bytes: Some(1), algorithm: Some("lz4".into()) }] },
            storage: Vec::<StorageInfo>::new(),
            network: NetworkInfo { interface_count: 0, interfaces: vec![], tcp_congestion: None, tcp_available: None },
            security: SecurityInfo { selinux_enforcing: None, verified_boot: None, verity_mode: None, device_mapper_devices: vec![], module_sig_enforce: None },
        }
    }

    #[test]
    fn critical_memory_prefers_zram_reclaim() {
        let e = envelope(&inv(Some(100_000)));
        assert_eq!(e.pressure, Pressure::Critical);
        assert_eq!(e.recommended_swappiness_floor, Some(80));
    }
}
