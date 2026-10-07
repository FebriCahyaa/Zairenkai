// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Probe-driven sysfs/procfs access with path containment and reversible locking.
//!
//! Zairenkai only writes a small set of kernel tuning trees. The rooted
//! Sysroot abstraction keeps unit tests off the real /sys and /proc trees.
//!
//! Copyright (C) 2026 FebriCahyaa

use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Component, Path, PathBuf};

#[derive(Clone)]
pub struct Sysroot {
    root: PathBuf,
}

impl Sysroot {
    pub fn new<P: Into<PathBuf>>(root: P) -> Self { Self { root: root.into() } }

    #[allow(dead_code)]
    pub fn host() -> Self { Self::new("/") }

    fn safe_rel(rel: &str) -> bool {
        let p = Path::new(rel.trim_start_matches('/'));
        !p.as_os_str().is_empty()
            && p.components().all(|c| matches!(c, Component::Normal(_)))
    }

    pub fn path(&self, rel: &str) -> PathBuf {
        self.root.join(rel.trim_start_matches('/'))
    }

    pub fn exists(&self, rel: &str) -> bool {
        Self::safe_rel(rel) && self.path(rel).exists()
    }

    pub fn read(&self, rel: &str) -> Option<String> {
        if !Self::safe_rel(rel) { return None; }
        fs::read_to_string(self.path(rel)).ok().map(|s| s.trim().to_string())
    }

    pub fn read_u64(&self, rel: &str) -> Option<u64> { self.read(rel).and_then(|s| s.parse().ok()) }

    pub fn read_u64_list(&self, rel: &str) -> Vec<u64> {
        let mut v = self.read(rel)
            .map(|s| s.split_whitespace().filter_map(|t| t.parse().ok()).collect())
            .unwrap_or_default();
        v.sort_unstable();
        v.dedup();
        v
    }

    pub fn list_dir(&self, rel: &str) -> Vec<String> {
        if !Self::safe_rel(rel) { return Vec::new(); }
        let mut out = Vec::new();
        if let Ok(rd) = fs::read_dir(self.path(rel)) {
            for e in rd.flatten() {
                if let Some(name) = e.file_name().to_str() { out.push(name.to_string()); }
            }
        }
        out.sort();
        out
    }

    /// Write a kernel pseudo-file. When `lock` is true, temporarily make the
    /// node owner/group/world writable and restore its original mode exactly.
    pub fn write(&self, rel: &str, val: &str, lock: bool) -> io::Result<()> {
        if !Self::safe_rel(rel) {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "unsafe path"));
        }
        let p = self.path(rel);
        let meta = fs::symlink_metadata(&p)?;
        if meta.file_type().is_symlink() {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "symlink not allowed"));
        }
        let original_mode = meta.permissions().mode();
        if lock {
            fs::set_permissions(&p, fs::Permissions::from_mode(original_mode | 0o222))?;
        }
        let result = OpenOptions::new()
            .write(true)
            .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC)
            .open(&p)
            .and_then(|mut file| file.write_all(format!("{val}\n").as_bytes()));
        if lock {
            let _ = fs::set_permissions(&p, fs::Permissions::from_mode(original_mode));
        }
        result
    }

    pub fn write_first(&self, candidates: &[&str], val: &str, lock: bool) -> Option<String> {
        for rel in candidates {
            if self.exists(rel) && self.write(rel, val, lock).is_ok() { return Some((*rel).to_string()); }
        }
        None
    }

    pub fn first_existing(&self, candidates: &[&str]) -> Option<String> {
        candidates.iter().find(|c| self.exists(c)).map(|s| (*s).to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn tmp() -> PathBuf {
        let d = std::env::temp_dir().join(format!("zperfd-nodes-{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        d
    }

    #[test]
    fn write_locked_roundtrip_restores_mode() {
        let root=tmp();
        let p=root.join("sys/x/knob");
        fs::create_dir_all(p.parent().unwrap()).unwrap();
        fs::write(&p,"0").unwrap();
        fs::set_permissions(&p,fs::Permissions::from_mode(0o440)).unwrap();
        let s=Sysroot::new(&root);
        s.write("/sys/x/knob","42",true).unwrap();
        assert_eq!(s.read("/sys/x/knob").as_deref(),Some("42"));
        assert_eq!(fs::metadata(&p).unwrap().permissions().mode() & 0o777,0o440);
        let _=fs::remove_dir_all(&root);
    }

    #[test]
    fn rejects_parent_and_symlink_paths() {
        let root=tmp();
        let real=root.join("sys/x/knob");
        fs::create_dir_all(real.parent().unwrap()).unwrap();
        fs::write(&real,"1").unwrap();
        std::os::unix::fs::symlink(&real,root.join("sys/x/link")).unwrap();
        let s=Sysroot::new(&root);
        assert!(!s.exists("/sys/x/../x/knob"));
        assert!(s.write("/sys/x/link","2",false).is_err());
        let _=fs::remove_dir_all(&root);
    }
}
