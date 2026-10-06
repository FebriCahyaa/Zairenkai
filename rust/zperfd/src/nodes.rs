// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Probe-driven sysfs/procfs access. Every write is "probe then write": a node
//! is touched only if it exists, and locked writes use the uperf/Scene idiom
//! (chmod 0666 -> echo -> chmod 0444) so a vendor perf-HAL cannot silently
//! revert it. A configurable root makes the whole engine unit-testable against
//! a fake /sys tree.
//!
//! Copyright (C) 2026 FebriCahyaa

use std::fs;
use std::io;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};

#[derive(Clone)]
pub struct Sysroot {
    root: PathBuf,
}

impl Sysroot {
    pub fn new<P: Into<PathBuf>>(root: P) -> Self {
        Sysroot { root: root.into() }
    }

    #[allow(dead_code)]
    pub fn host() -> Self {
        Sysroot::new("/")
    }

    pub fn path(&self, rel: &str) -> PathBuf {
        let trimmed = rel.trim_start_matches('/');
        self.root.join(trimmed)
    }

    pub fn exists(&self, rel: &str) -> bool {
        self.path(rel).exists()
    }

    pub fn read(&self, rel: &str) -> Option<String> {
        fs::read_to_string(self.path(rel)).ok().map(|s| s.trim().to_string())
    }

    pub fn read_u64(&self, rel: &str) -> Option<u64> {
        self.read(rel).and_then(|s| s.trim().parse().ok())
    }

    /// Parse a whitespace-separated list of u64 (e.g. scaling_available_frequencies).
    pub fn read_u64_list(&self, rel: &str) -> Vec<u64> {
        let mut v: Vec<u64> = self
            .read(rel)
            .map(|s| s.split_whitespace().filter_map(|t| t.parse().ok()).collect())
            .unwrap_or_default();
        v.sort_unstable();
        v.dedup();
        v
    }

    /// List immediate sub-directory names of a directory node.
    pub fn list_dir(&self, rel: &str) -> Vec<String> {
        let mut out = Vec::new();
        if let Ok(rd) = fs::read_dir(self.path(rel)) {
            for e in rd.flatten() {
                if let Some(name) = e.file_name().to_str() {
                    out.push(name.to_string());
                }
            }
        }
        out.sort();
        out
    }

    fn chmod(p: &Path, mode: u32) {
        let _ = fs::set_permissions(p, fs::Permissions::from_mode(mode));
    }

    /// Write [val] to [rel]. With [lock], unlock (0666), write, then relock
    /// (0444). Returns Err if the node does not exist or the write fails.
    pub fn write(&self, rel: &str, val: &str, lock: bool) -> io::Result<()> {
        let p = self.path(rel);
        if !p.exists() {
            return Err(io::Error::new(io::ErrorKind::NotFound, rel.to_string()));
        }
        if lock {
            Self::chmod(&p, 0o666);
        }
        let res = fs::write(&p, format!("{val}\n"));
        if lock {
            Self::chmod(&p, 0o444);
        }
        res
    }

    /// Write to the first candidate path that exists. Returns the path written,
    /// or None if none existed / all writes failed.
    pub fn write_first(&self, candidates: &[&str], val: &str, lock: bool) -> Option<String> {
        for rel in candidates {
            if self.exists(rel) && self.write(rel, val, lock).is_ok() {
                return Some((*rel).to_string());
            }
        }
        None
    }

    /// First candidate path that exists (no write).
    pub fn first_existing(&self, candidates: &[&str]) -> Option<String> {
        candidates.iter().find(|c| self.exists(c)).map(|s| (*s).to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn tmp() -> PathBuf {
        let d = std::env::temp_dir().join(format!("zperf-nodes-{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        d
    }

    #[test]
    fn write_locked_roundtrip() {
        let root = tmp();
        let rel = "/sys/x/knob";
        fs::create_dir_all(root.join("sys/x")).unwrap();
        fs::write(root.join("sys/x/knob"), "0").unwrap();
        // make it read-only first to prove lock path unlocks it
        fs::set_permissions(root.join("sys/x/knob"), fs::Permissions::from_mode(0o444)).unwrap();
        let s = Sysroot::new(&root);
        assert!(s.exists(rel));
        s.write(rel, "42", true).unwrap();
        assert_eq!(s.read(rel).as_deref(), Some("42"));
        // missing node errors
        assert!(s.write("/sys/x/nope", "1", true).is_err());
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn freq_list_and_first() {
        let root = tmp();
        fs::create_dir_all(root.join("sys/a")).unwrap();
        fs::write(root.join("sys/a/freqs"), "1800000 300000 1200000 300000").unwrap();
        let s = Sysroot::new(&root);
        assert_eq!(s.read_u64_list("/sys/a/freqs"), vec![300000, 1200000, 1800000]);
        assert_eq!(s.first_existing(&["/sys/a/nope", "/sys/a/freqs"]).as_deref(), Some("/sys/a/freqs"));
        let _ = fs::remove_dir_all(&root);
    }
}
