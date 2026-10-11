// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Shared helpers for unit tests.
//!
//! Copyright (C) 2026 FebriCahyaa

use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};

static NEXT: AtomicUsize = AtomicUsize::new(0);

/// A fresh, empty scratch path that no other test in this process shares.
///
/// The test harness runs tests on parallel threads of one process, so a
/// directory keyed only by PID lets one test delete or overwrite another
/// test's fixture tree. The directory itself is not created.
pub fn unique_temp_dir(tag: &str) -> PathBuf {
    let n = NEXT.fetch_add(1, Ordering::Relaxed);
    let p = std::env::temp_dir().join(format!("zairenkai-{tag}-{}-{n}", std::process::id()));
    let _ = std::fs::remove_dir_all(&p);
    p
}
