// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Durable, crash-safe state for zperfd.
//!
//! A transaction snapshots every node zperfd may mutate before the first
//! write. The lock is held across begin -> apply -> commit/rollback, so an app
//! command and the resident daemon cannot interleave mutations. Recovery is
//! two-phase: an uncommitted journal is rolled back; a committed marker makes
//! state-file publication idempotent after power loss.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::nodes::Sysroot;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::os::fd::AsRawFd;
use std::path::{Path, PathBuf};

const BASELINE: &str = "baseline.v1";
const TXN: &str = "transaction.pending";
const COMMITTED: &str = "transaction.committed";
const NEXT_MODE: &str = "mode.next";
const NEXT_EFFECTIVE: &str = "effective_mode.next";
const MODE: &str = "mode";
const EFFECTIVE: &str = "effective_mode";
const BOOT_ID: &str = "boot.id";
const LOCK_FILE: &str = ".lock";

#[derive(Clone, Debug)]
struct Entry {
    path: String,
    value: String,
}

fn hex(value: &str) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(value.len() * 2);
    for b in value.as_bytes() {
        out.push(HEX[(b >> 4) as usize] as char);
        out.push(HEX[(b & 0xf) as usize] as char);
    }
    out
}

fn unhex(value: &str) -> Option<String> {
    if value.len() % 2 != 0 {
        return None;
    }
    fn nibble(b: u8) -> Option<u8> {
        match b {
            b'0'..=b'9' => Some(b - b'0'),
            b'a'..=b'f' => Some(b - b'a' + 10),
            b'A'..=b'F' => Some(b - b'A' + 10),
            _ => None,
        }
    }
    let mut bytes = Vec::with_capacity(value.len() / 2);
    for pair in value.as_bytes().chunks_exact(2) {
        bytes.push(nibble(pair[0])? * 16 + nibble(pair[1])?);
    }
    String::from_utf8(bytes).ok()
}

fn atomic_write(path: &Path, data: &str) -> io::Result<()> {
    let tmp = path.with_extension("tmp");
    {
        let mut file = OpenOptions::new()
            .create(true)
            .truncate(true)
            .write(true)
            .open(&tmp)?;
        file.write_all(data.as_bytes())?;
        file.sync_all()?;
    }
    fs::rename(&tmp, path)?;
    if let Some(dir) = path.parent() {
        File::open(dir)?.sync_all()?;
    }
    Ok(())
}

fn remove_if_exists(path: &Path) -> io::Result<()> {
    match fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e),
    }
}

struct StateGuard {
    file: File,
}

impl StateGuard {
    fn acquire(root: &Path) -> io::Result<Self> {
        let path = root.join(LOCK_FILE);
        let file = OpenOptions::new().create(true).read(true).write(true).open(path)?;
        let rc = unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX) };
        if rc != 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(Self { file })
    }
}

impl Drop for StateGuard {
    fn drop(&mut self) {
        let _ = unsafe { libc::flock(self.file.as_raw_fd(), libc::LOCK_UN) };
    }
}

/// StateStore serializes mutations across daemon/CLI processes.
pub struct StateStore {
    root: PathBuf,
    guard: Option<StateGuard>,
}

impl StateStore {
    pub fn new<P: Into<PathBuf>>(root: P) -> Self {
        Self { root: root.into(), guard: None }
    }

    fn ensure(&self) -> io::Result<()> {
        fs::create_dir_all(&self.root)
    }

    fn file(&self, name: &str) -> PathBuf {
        self.root.join(name)
    }

    fn acquire(&mut self) -> io::Result<()> {
        if self.guard.is_none() {
            self.ensure()?;
            self.guard = Some(StateGuard::acquire(&self.root)?);
        }
        Ok(())
    }

    fn release(&mut self) {
        self.guard.take();
    }

    fn write_value(&self, name: &str, value: &str) -> io::Result<()> {
        self.ensure()?;
        atomic_write(&self.file(name), &format!("{value}\n"))
    }

    fn read_entries(&self, name: &str) -> io::Result<Vec<Entry>> {
        let text = fs::read_to_string(self.file(name))?;
        let mut entries = Vec::new();
        for line in text.lines() {
            let mut parts = line.splitn(2, '\t');
            let path = parts.next().and_then(unhex);
            let value = parts.next().and_then(unhex);
            if let (Some(path), Some(value)) = (path, value) {
                entries.push(Entry { path, value });
            }
        }
        Ok(entries)
    }

    fn write_entries(&self, name: &str, entries: &[Entry]) -> io::Result<()> {
        let mut data = String::new();
        for entry in entries {
            data.push_str(&hex(&entry.path));
            data.push('\t');
            data.push_str(&hex(&entry.value));
            data.push('\n');
        }
        self.ensure()?;
        atomic_write(&self.file(name), &data)
    }

    fn current_boot_id(&self, s: &Sysroot) -> String {
        s.read("/proc/sys/kernel/random/boot_id")
            .or_else(|| fs::read_to_string("/proc/sys/kernel/random/boot_id").ok().map(|v| v.trim().to_string()))
            .unwrap_or_else(|| "unknown".to_string())
    }

    /// The baseline is per boot. A previous boot's pseudo-file values must
    /// never be replayed after the kernel has reinitialized its tunables.
    fn ensure_boot_unlocked(&self, s: &Sysroot, nodes: &[String]) -> io::Result<()> {
        self.ensure()?;
        let current = self.current_boot_id(s);
        let stored = fs::read_to_string(self.file(BOOT_ID)).ok().map(|v| v.trim().to_string());
        if stored.as_deref() != Some(current.as_str()) {
            remove_if_exists(&self.file(BASELINE))?;
            remove_if_exists(&self.file(TXN))?;
            remove_if_exists(&self.file(COMMITTED))?;
            remove_if_exists(&self.file(NEXT_MODE))?;
            remove_if_exists(&self.file(NEXT_EFFECTIVE))?;
            self.write_value(BOOT_ID, &current)?;
        }

        if !self.file(BASELINE).exists() {
            let mut entries = Vec::new();
            for path in nodes {
                if s.exists(path) {
                    if let Some(value) = s.read(path) {
                        entries.push(Entry { path: path.clone(), value });
                    }
                }
            }
            self.write_entries(BASELINE, &entries)?;
        }
        Ok(())
    }

    pub fn ensure_boot(&mut self, s: &Sysroot, nodes: &[String]) -> io::Result<()> {
        self.acquire()?;
        let result = self.ensure_boot_unlocked(s, nodes);
        self.release();
        result
    }

    /// If a transaction exists without a commit marker, restore it. If the
    /// commit marker exists, complete the publication of the state files.
    fn recover_unlocked(&self, s: &Sysroot) -> io::Result<bool> {
        let pending = self.file(TXN);
        let committed = self.file(COMMITTED);
        if committed.exists() {
            if self.file(NEXT_MODE).exists() {
                fs::rename(self.file(NEXT_MODE), self.file(MODE))?;
            }
            if self.file(NEXT_EFFECTIVE).exists() {
                fs::rename(self.file(NEXT_EFFECTIVE), self.file(EFFECTIVE))?;
            }
            remove_if_exists(&pending)?;
            remove_if_exists(&committed)?;
            return Ok(false);
        }
        if !pending.exists() {
            return Ok(false);
        }

        let entries = self.read_entries(TXN)?;
        let mut first_error = None;
        for entry in entries {
            if s.exists(&entry.path) {
                if let Err(e) = s.write(&entry.path, &entry.value, false) {
                    first_error.get_or_insert(e);
                }
            }
        }
        if let Some(e) = first_error {
            return Err(e);
        }
        remove_if_exists(&pending)?;
        remove_if_exists(&self.file(NEXT_MODE))?;
        remove_if_exists(&self.file(NEXT_EFFECTIVE))?;
        Ok(true)
    }

    pub fn recover(&mut self, s: &Sysroot) -> io::Result<bool> {
        self.acquire()?;
        let result = self.recover_unlocked(s);
        self.release();
        result
    }

    /// Begin a transaction and retain the inter-process lock until commit or
    /// rollback. The caller must not call arbitrary mutation code outside this
    /// transaction boundary.
    pub fn begin(&mut self, s: &Sysroot, nodes: &[String]) -> io::Result<()> {
        self.acquire()?;
        if let Err(e) = self.ensure_boot_unlocked(s, nodes).and_then(|_| self.recover_unlocked(s)) {
            self.release();
            return Err(e);
        }

        let mut entries = Vec::new();
        for path in nodes {
            if s.exists(path) {
                if let Some(value) = s.read(path) {
                    entries.push(Entry { path: path.clone(), value });
                }
            }
        }
        if let Err(e) = self.write_entries(TXN, &entries).and_then(|_| {
            remove_if_exists(&self.file(COMMITTED))?;
            remove_if_exists(&self.file(NEXT_MODE))?;
            remove_if_exists(&self.file(NEXT_EFFECTIVE))?;
            Ok(())
        }) {
            self.release();
            return Err(e);
        }
        Ok(())
    }

    /// Commit state atomically. The marker is durable before the final mode
    /// files are published, making recovery idempotent across power loss.
    pub fn commit(
        &mut self,
        requested: Option<&str>,
        effective: Option<&str>,
    ) -> io::Result<()> {
        if self.guard.is_none() {
            return Err(io::Error::other("commit without begin"));
        }
        if let Some(value) = requested {
            self.write_value(NEXT_MODE, value)?;
        }
        if let Some(value) = effective {
            self.write_value(NEXT_EFFECTIVE, value)?;
        }
        self.write_value(COMMITTED, "1")?;
        if self.file(NEXT_MODE).exists() {
            fs::rename(self.file(NEXT_MODE), self.file(MODE))?;
        }
        if self.file(NEXT_EFFECTIVE).exists() {
            fs::rename(self.file(NEXT_EFFECTIVE), self.file(EFFECTIVE))?;
        }
        remove_if_exists(&self.file(TXN))?;
        remove_if_exists(&self.file(COMMITTED))?;
        self.release();
        Ok(())
    }

    /// Rollback keeps the pending journal when restoration itself fails. The
    /// next zperfd invocation can retry recovery instead of losing the only
    /// authoritative snapshot.
    pub fn rollback(&mut self, s: &Sysroot) -> io::Result<()> {
        let result = if self.file(TXN).exists() {
            let entries = self.read_entries(TXN)?;
            let mut first_error = None;
            for entry in entries {
                if s.exists(&entry.path) {
                    if let Err(e) = s.write(&entry.path, &entry.value, false) {
                        first_error.get_or_insert(e);
                    }
                }
            }
            if let Some(e) = first_error {
                Err(e)
            } else {
                remove_if_exists(&self.file(TXN))?;
                remove_if_exists(&self.file(COMMITTED))?;
                Ok(())
            }
        } else {
            Ok(())
        };
        self.release();
        result
    }

    /// Restore the first observed state of this boot. This is the recovery
    /// target for SAFE MODE and an explicit stock-reset operation.
    pub fn restore_baseline(&mut self, s: &Sysroot, nodes: &[String]) -> io::Result<usize> {
        self.acquire()?;
        let result = (|| {
            self.ensure_boot_unlocked(s, nodes)?;
            self.recover_unlocked(s)?;
            let entries = self.read_entries(BASELINE)?;
            let mut restored = 0usize;
            let mut first_error = None;
            for entry in entries {
                if s.exists(&entry.path) {
                    match s.write(&entry.path, &entry.value, false) {
                        Ok(()) => restored += 1,
                        Err(e) => first_error.get_or_insert(e),
                    }
                }
            }
            if let Some(e) = first_error {
                return Err(e);
            }
            self.write_value(MODE, "stock")?;
            self.write_value(EFFECTIVE, "stock")?;
            Ok(restored)
        })();
        self.release();
        result
    }

    pub fn mode(&self) -> Option<String> {
        fs::read_to_string(self.file(MODE))
            .ok()
            .map(|v| v.trim().to_string())
            .filter(|v| !v.is_empty())
    }

    pub fn effective_mode(&self) -> Option<String> {
        fs::read_to_string(self.file(EFFECTIVE))
            .ok()
            .map(|v| v.trim().to_string())
            .filter(|v| !v.is_empty())
    }

    pub fn has_pending_transaction(&self) -> bool {
        self.file(TXN).exists() || self.file(COMMITTED).exists()
    }
}

impl Drop for StateStore {
    fn drop(&mut self) {
        self.release();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::path::PathBuf;

    fn root(label: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("zperfd-state-{label}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        d
    }

    fn write(root: &Path, path: &str, value: &str) {
        let target = root.join(path.trim_start_matches('/'));
        fs::create_dir_all(target.parent().unwrap()).unwrap();
        fs::write(target, value).unwrap();
    }

    #[test]
    fn hex_roundtrip() {
        assert_eq!(unhex(&hex("hello/world")), Some("hello/world".into()));
    }

    #[test]
    fn uncommitted_transaction_recovers() {
        let root = root("recover");
        write(&root, "/proc/sys/x", "old");
        write(&root, "/proc/sys/kernel/random/boot_id", "boot-a");
        let s = Sysroot::new(&root);
        let mut state = StateStore::new(root.join("state"));
        state.ensure_boot(&s, &["/proc/sys/x".into()]).unwrap();
        state.begin(&s, &["/proc/sys/x".into()]).unwrap();
        s.write("/proc/sys/x", "new", false).unwrap();
        assert!(state.recover(&s).unwrap());
        assert_eq!(s.read("/proc/sys/x").as_deref(), Some("old"));
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn committed_transaction_publishes_once() {
        let root = root("commit");
        write(&root, "/proc/sys/x", "old");
        write(&root, "/proc/sys/kernel/random/boot_id", "boot-a");
        let s = Sysroot::new(&root);
        let mut state = StateStore::new(root.join("state"));
        state.ensure_boot(&s, &["/proc/sys/x".into()]).unwrap();
        state.begin(&s, &["/proc/sys/x".into()]).unwrap();
        s.write("/proc/sys/x", "new", false).unwrap();
        state.commit(Some("balance"), Some("balance")).unwrap();
        assert!(!state.recover(&s).unwrap());
        assert_eq!(state.mode().as_deref(), Some("balance"));
        assert_eq!(s.read("/proc/sys/x").as_deref(), Some("new"));
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn new_boot_discards_stale_transaction_and_refreshes_baseline() {
        let root = root("boot");
        write(&root, "/proc/sys/x", "old-a");
        write(&root, "/proc/sys/kernel/random/boot_id", "boot-a");
        let s = Sysroot::new(&root);
        let mut state = StateStore::new(root.join("state"));
        state.ensure_boot(&s, &["/proc/sys/x".into()]).unwrap();
        state.begin(&s, &["/proc/sys/x".into()]).unwrap();
        s.write("/proc/sys/x", "new-a", false).unwrap();

        write(&root, "/proc/sys/x", "stock-b");
        write(&root, "/proc/sys/kernel/random/boot_id", "boot-b");
        state.ensure_boot(&s, &["/proc/sys/x".into()]).unwrap();
        assert!(!state.recover(&s).unwrap());
        state.restore_baseline(&s, &["/proc/sys/x".into()]).unwrap();
        assert_eq!(s.read("/proc/sys/x").as_deref(), Some("stock-b"));
        let _ = fs::remove_dir_all(root);
    }
}
