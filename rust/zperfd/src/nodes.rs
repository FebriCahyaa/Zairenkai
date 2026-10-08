// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Probe-driven sysfs/procfs access with path containment and race-free writes.
//!
//! Zairenkai only writes a small set of kernel tuning trees. The rooted
//! Sysroot abstraction keeps unit tests off the real /sys and /proc trees.
//!
//! Copyright (C) 2026 FebriCahyaa

use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::os::fd::{AsRawFd, FromRawFd};
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::OpenOptionsExt;
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

    pub fn root(&self) -> &Path { &self.root }

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

    fn open_write_beneath(&self, rel: &str) -> io::Result<File> {
        if !Self::safe_rel(rel) {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "unsafe path"));
        }

        // Resolve every path component without following symlinks. O_NOFOLLOW
        // on only the final file is insufficient because an attacker could
        // replace a parent directory with a symlink between checks.
        let mut components = Path::new(rel.trim_start_matches('/')).components();
        let mut dir = OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_DIRECTORY | libc::O_CLOEXEC | libc::O_NOFOLLOW)
            .open(&self.root)?;

        let final_name = components
            .next_back()
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "empty path"))?;

        for component in components {
            let Component::Normal(name) = component else {
                return Err(io::Error::new(io::ErrorKind::InvalidInput, "unsafe path"));
            };
            let c_name = std::ffi::CString::new(name.as_bytes())
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "NUL in path"))?;
            let fd = unsafe {
                libc::openat(
                    dir.as_raw_fd(),
                    c_name.as_ptr(),
                    libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
                )
            };
            if fd < 0 {
                return Err(io::Error::last_os_error());
            }
            dir = unsafe { File::from_raw_fd(fd) };
        }

        let Component::Normal(name) = final_name else {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "unsafe path"));
        };
        let c_name = std::ffi::CString::new(name.as_bytes())
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "NUL in path"))?;
        let fd = unsafe {
            libc::openat(
                dir.as_raw_fd(),
                c_name.as_ptr(),
                libc::O_WRONLY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
            )
        };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(unsafe { File::from_raw_fd(fd) })
    }

    /// Write a kernel pseudo-file without changing its permissions.
    ///
    /// Cross-process serialization belongs to StateStore. Sysroot deliberately
    /// has no permission-toggling or pseudo-locking facility because changing
    /// sysfs/procfs modes around a write is race-prone and cannot prevent a
    /// separate privileged writer from changing the same node. Path resolution
    /// is descriptor-based so intermediate and final symlinks are rejected.
    pub fn write(&self, rel: &str, val: &str) -> io::Result<()> {
        let mut file = self.open_write_beneath(rel)?;
        file.write_all(format!("{val}\n").as_bytes())
    }

    pub fn write_first(&self, candidates: &[&str], val: &str) -> Option<String> {
        for rel in candidates {
            if self.exists(rel) && self.write(rel, val).is_ok() { return Some((*rel).to_string()); }
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
    use std::os::unix::fs::PermissionsExt;

    fn tmp() -> PathBuf {
        let d = std::env::temp_dir().join(format!("zperfd-nodes-{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        d
    }

    #[test]
    fn write_roundtrip_preserves_mode_without_permission_toggle() {
        let root=tmp();
        let p=root.join("sys/x/knob");
        fs::create_dir_all(p.parent().unwrap()).unwrap();
        fs::write(&p,"0").unwrap();
        fs::set_permissions(&p,fs::Permissions::from_mode(0o640)).unwrap();
        let s=Sysroot::new(&root);
        s.write("/sys/x/knob","42").unwrap();
        assert_eq!(s.read("/sys/x/knob").as_deref(),Some("42"));
        assert_eq!(fs::metadata(&p).unwrap().permissions().mode() & 0o777,0o640);
        let _=fs::remove_dir_all(&root);
    }

    #[test]
    fn rejects_parent_and_symlink_paths() {
        let root=tmp();
        let real=root.join("sys/x/knob");
        fs::create_dir_all(real.parent().unwrap()).unwrap();
        fs::write(&real,"1").unwrap();
        std::os::unix::fs::symlink(&real,root.join("sys/x/link")).unwrap();
        let parent_link=root.join("sys/parent-link");
        std::os::unix::fs::symlink(root.join("sys/x"), &parent_link).unwrap();
        let s=Sysroot::new(&root);
        assert!(!s.exists("/sys/x/../x/knob"));
        assert!(s.write("/sys/x/link","2").is_err());
        assert!(s.write("/sys/parent-link/knob","2").is_err());
        let _=fs::remove_dir_all(&root);
    }
}
