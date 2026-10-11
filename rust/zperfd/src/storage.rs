// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Storage queue-tuning target selection.
//!
//! Storage tuning never infers UFS/eMMC health from a model string. Runtime
//! queue attributes and health descriptors are the authority: device health
//! is aggregated by `inventory::storage_health`, and Sentinel blocks every
//! storage/IO mutation while any device reports critical health.
//!
//! Copyright (C) 2026 FebriCahyaa

pub fn safe_block_devices(s: &crate::nodes::Sysroot) -> Vec<String> {
    let mut out = Vec::new();
    for name in s.list_dir("/sys/block") {
        if name.starts_with("loop")
            || name.starts_with("ram")
            || name.starts_with("zram")
            || name.starts_with("dm-")
            || name.starts_with("sr")
        {
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
