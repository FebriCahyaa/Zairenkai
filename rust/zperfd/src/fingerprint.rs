// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Kernel + device identity fingerprint for persisted mode choices.
//!
//! A mode the user selected was validated on one kernel build and one device.
//! At boot the daemon replays it only when the fingerprint still matches; a
//! kernel update, a different ROM build or a restored backup on other
//! hardware holds the stock baseline until the user confirms the mode again
//! (CORE_INVARIANTS: never replay settings on an unverified surface).
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::nodes::Sysroot;
use crate::platform::PlatformIdentity;

/// Bumped whenever the set of hashed components changes, so an old stored
/// fingerprint can never accidentally match a new computation.
const SCHEME: &str = "zk-fp-v1";

/// FNV-1a 64. Deterministic across Rust versions and platforms, unlike
/// `std::collections::hash_map::DefaultHasher`, so stored values stay valid.
fn fnv1a64(bytes: &[u8]) -> u64 {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for byte in bytes {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    hash
}

/// Fingerprint of the running kernel build and the device identity.
///
/// `/proc/sys/kernel/version` carries the build number and timestamp, so two
/// builds with the same release string still differ.
pub fn compute(s: &Sysroot, identity: &PlatformIdentity) -> String {
    let parts = [
        SCHEME.to_string(),
        s.read("/proc/sys/kernel/osrelease").unwrap_or_default(),
        s.read("/proc/sys/kernel/version").unwrap_or_default(),
        identity.vendor.as_str().to_string(),
        identity.platform.trim().to_ascii_lowercase(),
        identity.model.trim().to_string(),
    ];
    format!("{SCHEME}:{:016x}", fnv1a64(parts.join("\n").as_bytes()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::platform::{OemVendor, SocVendor};

    fn identity(model: &str) -> PlatformIdentity {
        PlatformIdentity {
            oem: OemVendor::Unknown,
            vendor: SocVendor::Qualcomm,
            platform: "sm8550".into(),
            model: model.into(),
            compatible: String::new(),
            evidence: vec![],
        }
    }

    fn root(release: &str, version: &str) -> (std::path::PathBuf, Sysroot) {
        let r = crate::test_support::unique_temp_dir("fingerprint");
        std::fs::create_dir_all(r.join("proc/sys/kernel")).unwrap();
        std::fs::write(r.join("proc/sys/kernel/osrelease"), release).unwrap();
        std::fs::write(r.join("proc/sys/kernel/version"), version).unwrap();
        let s = Sysroot::new(&r);
        (r, s)
    }

    #[test]
    fn hash_is_stable_across_builds_of_this_binary() {
        // Pinned value: a change here invalidates every stored fingerprint.
        assert_eq!(fnv1a64(b"zairenkai"), 0x9923_fd1c_c892_defb);
    }

    #[test]
    fn same_kernel_and_device_match_and_any_change_differs() {
        let (r1, a) = root("6.1.75-android14", "#1 SMP PREEMPT Mon Jan 1 2026");
        let base = compute(&a, &identity("Phone"));
        assert_eq!(base, compute(&a, &identity("Phone")));
        assert!(base.starts_with("zk-fp-v1:"));
        assert_ne!(base, compute(&a, &identity("Other")));

        let (r2, rebuilt) = root("6.1.75-android14", "#2 SMP PREEMPT Tue Feb 2 2026");
        assert_ne!(base, compute(&rebuilt, &identity("Phone")));
        let (r3, updated) = root("6.1.99-android14", "#1 SMP PREEMPT Mon Jan 1 2026");
        assert_ne!(base, compute(&updated, &identity("Phone")));
        for r in [r1, r2, r3] {
            let _ = std::fs::remove_dir_all(r);
        }
    }
}
