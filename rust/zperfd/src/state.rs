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
use std::collections::HashSet;
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
const MAX_JOURNAL_BYTES: u64 = 1 << 20;
const MAX_JOURNAL_ENTRIES: usize = 4096;
const MAX_ENTRY_PATH_BYTES: usize = 4096;
const MAX_ENTRY_VALUE_BYTES: usize = 64 * 1024;

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

    fn snapshot_value(&self, s: &Sysroot, path: &str) -> io::Result<String> {
        let value = s.read(path).ok_or_else(|| {
            io::Error::new(io::ErrorKind::PermissionDenied, format!("cannot snapshot {path}"))
        })?;
        if value.len() > MAX_ENTRY_VALUE_BYTES {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("managed node {path} exceeds {MAX_ENTRY_VALUE_BYTES} bytes"),
            ));
        }
        Ok(value)
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
        let path = self.file(name);
        let size = fs::metadata(&path)?.len();
        if size > MAX_JOURNAL_BYTES {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("{name} journal exceeds {MAX_JOURNAL_BYTES} bytes"),
            ));
        }
        let text = fs::read_to_string(path)?;
        let mut entries = Vec::new();
        for (line_no, line) in text.lines().enumerate() {
            if line.is_empty() {
                continue;
            }
            if entries.len() >= MAX_JOURNAL_ENTRIES {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!("{name} journal has too many entries"),
                ));
            }
            let mut parts = line.splitn(2, '\t');
            let path_hex = parts.next();
            let value_hex = parts.next();
            let path = path_hex.and_then(unhex);
            let value = value_hex.and_then(unhex);
            match (path, value) {
                (Some(path), Some(value))
                    if !path.is_empty()
                        && path.len() <= MAX_ENTRY_PATH_BYTES
                        && value.len() <= MAX_ENTRY_VALUE_BYTES
                        && path.starts_with('/')
                        && !path.bytes().any(|b| b == 0) =>
                {
                    entries.push(Entry { path, value });
                }
                _ => {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        format!("invalid {name} journal entry at line {}", line_no + 1),
                    ));
                }
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

    fn current_boot_id(&self, s: &Sysroot) -> io::Result<String> {
        let value = s
            .read("/proc/sys/kernel/random/boot_id")
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "kernel boot_id unavailable"))?;
        if value.is_empty() {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "kernel boot_id is empty"));
        }
        Ok(value)
    }

    fn ensure_baseline_nodes_unlocked(&self, s: &Sysroot, nodes: &[String]) -> io::Result<()> {
        let mut entries = if self.file(BASELINE).exists() {
            self.read_entries(BASELINE)?
        } else {
            Vec::new()
        };
        let mut known = entries
            .iter()
            .map(|entry| entry.path.clone())
            .collect::<HashSet<_>>();
        let mut changed = false;

        // A per-boot baseline is immutable for an already-seen node, but newly
        // appearing kernel nodes are captured at their first observation. This
        // matters for dynamic block devices and late-created sysfs surfaces.
        for path in nodes {
            if known.contains(path) || !s.exists(path) {
                continue;
            }
            let value = self.snapshot_value(s, path)?;
            entries.push(Entry { path: path.clone(), value });
            known.insert(path.clone());
            changed = true;
        }

        if changed || !self.file(BASELINE).exists() {
            self.write_entries(BASELINE, &entries)?;
        }
        Ok(())
    }

    /// The baseline is per boot. A previous boot's pseudo-file values must
    /// never be replayed after the kernel has reinitialized its tunables.
    fn ensure_boot_unlocked(&self, s: &Sysroot, nodes: &[String]) -> io::Result<()> {
        self.ensure()?;
        let current = self.current_boot_id(s)?;
        let stored = fs::read_to_string(self.file(BOOT_ID)).ok().map(|v| v.trim().to_string());
        if stored.as_deref() != Some(current.as_str()) {
            remove_if_exists(&self.file(BASELINE))?;
            remove_if_exists(&self.file(TXN))?;
            remove_if_exists(&self.file(COMMITTED))?;
            remove_if_exists(&self.file(NEXT_MODE))?;
            remove_if_exists(&self.file(NEXT_EFFECTIVE))?;
            self.write_value(BOOT_ID, &current)?;
        }

        self.ensure_baseline_nodes_unlocked(s, nodes)
    }

    pub fn ensure_boot(&mut self, s: &Sysroot, nodes: &[String]) -> io::Result<()> {
        self.acquire()?;
        let result = self.ensure_boot_unlocked(s, nodes);
        self.release();
        result
    }

    fn write_commit_marker(&self, mode: bool, effective: bool) -> io::Result<()> {
        let data = format!(
            "version=1\nmode={}\neffective={}\n",
            if mode { 1 } else { 0 },
            if effective { 1 } else { 0 },
        );
        atomic_write(&self.file(COMMITTED), &data)
    }

    fn read_commit_marker(&self) -> io::Result<(bool, bool)> {
        let text = fs::read_to_string(self.file(COMMITTED))?;
        let mut version = None;
        let mut mode = None;
        let mut effective = None;
        for line in text.lines() {
            let Some((key, value)) = line.split_once('=') else {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "malformed commit marker"));
            };
            match key {
                "version" => version = value.parse::<u32>().ok(),
                "mode" => mode = value.parse::<u8>().ok().filter(|v| *v <= 1),
                "effective" => effective = value.parse::<u8>().ok().filter(|v| *v <= 1),
                _ => return Err(io::Error::new(io::ErrorKind::InvalidData, "unknown commit marker field")),
            }
        }
        if version != Some(1) || mode.is_none() || effective.is_none() {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid commit marker"));
        }
        Ok((mode == Some(1), effective == Some(1)))
    }

    /// If a transaction exists without a commit marker, restore it. If the
    /// commit marker exists, complete the publication of the explicitly
    /// recorded state files; missing required publication files are fatal.
    fn recover_unlocked(&self, s: &Sysroot) -> io::Result<bool> {
        let pending = self.file(TXN);
        let committed = self.file(COMMITTED);
        if committed.exists() {
            let (publish_mode, publish_effective) = self.read_commit_marker()?;
            if publish_mode {
                if self.file(NEXT_MODE).exists() {
                    fs::rename(self.file(NEXT_MODE), self.file(MODE))?;
                } else if !self.file(MODE).exists() {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "commit marker requires unpublished mode state",
                    ));
                }
            }
            if publish_effective {
                if self.file(NEXT_EFFECTIVE).exists() {
                    fs::rename(self.file(NEXT_EFFECTIVE), self.file(EFFECTIVE))?;
                } else if !self.file(EFFECTIVE).exists() {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "commit marker requires unpublished effective mode state",
                    ));
                }
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
                if let Err(e) = s.write(&entry.path, &entry.value) {
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
                let value = self.snapshot_value(s, path)?;
                entries.push(Entry { path: path.clone(), value });
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
        let publish_mode = requested.is_some();
        let publish_effective = effective.is_some();
        if publish_mode {
            self.write_value(NEXT_MODE, requested.unwrap_or_default())?;
        }
        if publish_effective {
            self.write_value(NEXT_EFFECTIVE, effective.unwrap_or_default())?;
        }
        self.write_commit_marker(publish_mode, publish_effective)?;
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
                    if let Err(e) = s.write(&entry.path, &entry.value) {
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
    fn rollback_unlocked(&self, s: &Sysroot) -> io::Result<()> {
        if !self.file(TXN).exists() {
            return Ok(());
        }
        let entries = self.read_entries(TXN)?;
        let mut first_error = None;
        for entry in entries {
            if s.exists(&entry.path) {
                if let Err(e) = s.write(&entry.path, &entry.value) {
                    first_error.get_or_insert(e);
                }
            }
        }
        if let Some(e) = first_error {
            return Err(e);
        }
        remove_if_exists(&self.file(TXN))?;
        remove_if_exists(&self.file(COMMITTED))?;
        remove_if_exists(&self.file(NEXT_MODE))?;
        remove_if_exists(&self.file(NEXT_EFFECTIVE))?;
        Ok(())
    }

    pub fn restore_baseline(&mut self, s: &Sysroot, nodes: &[String]) -> io::Result<usize> {
        self.acquire()?;
        let result = (|| {
            self.ensure_boot_unlocked(s, nodes)?;
            self.recover_unlocked(s)?;

            // Snapshot the current runtime first. If baseline restoration or
            // state publication fails half-way, the current state remains a
            // durable rollback target for this process and the next recovery
            // pass instead of leaving a mixed runtime.
            let mut current = Vec::new();
            for path in nodes {
                if s.exists(path) {
                    let value = self.snapshot_value(s, path)?;
                    current.push(Entry { path: path.clone(), value });
                }
            }
            self.write_entries(TXN, &current)?;
            remove_if_exists(&self.file(COMMITTED))?;
            remove_if_exists(&self.file(NEXT_MODE))?;
            remove_if_exists(&self.file(NEXT_EFFECTIVE))?;

            let apply = (|| {
                let entries = self.read_entries(BASELINE)?;
                let mut restored = 0usize;
                for entry in entries {
                    if s.exists(&entry.path) {
                        s.write(&entry.path, &entry.value)?;
                        restored += 1;
                    }
                }

                // Publish the semantic state only after every runtime node
                // has been restored successfully. The committed marker makes
                // publication idempotent across power loss.
                self.write_value(NEXT_MODE, "stock")?;
                self.write_value(NEXT_EFFECTIVE, "stock")?;
                self.write_commit_marker(true, true)?;
                fs::rename(self.file(NEXT_MODE), self.file(MODE))?;
                fs::rename(self.file(NEXT_EFFECTIVE), self.file(EFFECTIVE))?;
                remove_if_exists(&self.file(TXN))?;
                remove_if_exists(&self.file(COMMITTED))?;
                Ok(restored)
            })();

            match apply {
                Ok(restored) => Ok(restored),
                Err(error) => match self.rollback_unlocked(s) {
                    Ok(()) => Err(error),
                    Err(rollback_error) => Err(io::Error::other(format!(
                        "baseline restore failed: {error}; rollback failed: {rollback_error}"
                    ))),
                },
            }
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
        s.write("/proc/sys/x", "new").unwrap();
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
        s.write("/proc/sys/x", "new").unwrap();
        state.commit(Some("balance"), Some("balance")).unwrap();
        assert!(!state.recover(&s).unwrap());
        assert_eq!(state.mode().as_deref(), Some("balance"));
        assert_eq!(s.read("/proc/sys/x").as_deref(), Some("new"));
        let _ = fs::remove_dir_all(root);
    }


    #[test]
    fn committed_marker_is_idempotent_after_first_rename() {
        let root = root("commit-marker-idempotent");
        write(&root, "/proc/sys/x", "old");
        write(&root, "/proc/sys/kernel/random/boot_id", "boot-a");
        let state_root = root.join("state");
        fs::create_dir_all(&state_root).unwrap();
        fs::write(state_root.join(COMMITTED), "version=1\nmode=1\neffective=1\n").unwrap();
        fs::write(state_root.join(MODE), "balance\n").unwrap();
        fs::write(state_root.join(NEXT_EFFECTIVE), "balance\n").unwrap();
        let s = Sysroot::new(&root);
        let mut state = StateStore::new(&state_root);
        assert!(!state.recover(&s).unwrap());
        assert_eq!(fs::read_to_string(state_root.join(MODE)).unwrap().trim(), "balance");
        assert_eq!(fs::read_to_string(state_root.join(EFFECTIVE)).unwrap().trim(), "balance");
        assert!(!state_root.join(COMMITTED).exists());
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn committed_marker_refuses_missing_required_publication() {
        let root = root("commit-marker-missing");
        write(&root, "/proc/sys/x", "old");
        write(&root, "/proc/sys/kernel/random/boot_id", "boot-a");
        let state_root = root.join("state");
        fs::create_dir_all(&state_root).unwrap();
        fs::write(state_root.join(COMMITTED), "version=1\nmode=1\neffective=1\n").unwrap();
        fs::write(state_root.join(NEXT_MODE), "balance\n").unwrap();
        let s = Sysroot::new(&root);
        let mut state = StateStore::new(&state_root);
        let err = state.recover(&s).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
        assert!(state_root.join(COMMITTED).exists());
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn commit_marker_records_partial_publication_contract() {
        let root = root("commit-marker-contract");
        write(&root, "/proc/sys/x", "old");
        write(&root, "/proc/sys/kernel/random/boot_id", "boot-a");
        let state_root = root.join("state");
        fs::create_dir_all(&state_root).unwrap();
        fs::write(state_root.join(COMMITTED), "version=1\nmode=1\neffective=0\n").unwrap();
        fs::write(state_root.join(NEXT_MODE), "balance\n").unwrap();
        let s = Sysroot::new(&root);
        let mut state = StateStore::new(&state_root);
        assert!(!state.recover(&s).unwrap());
        assert_eq!(fs::read_to_string(state_root.join(MODE)).unwrap().trim(), "balance");
        assert!(!state_root.join(COMMITTED).exists());
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn malformed_transaction_is_rejected_and_preserved() {
        let root = root("malformed");
        write(&root, "/proc/sys/x", "old");
        write(&root, "/proc/sys/kernel/random/boot_id", "boot-a");
        let state_root = root.join("state");
        fs::create_dir_all(&state_root).unwrap();
        fs::write(state_root.join(TXN), "not-a-valid-journal-entry\n").unwrap();
        let s = Sysroot::new(&root);
        let mut state = StateStore::new(&state_root);
        let err = state.recover(&s).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
        assert!(state_root.join(TXN).exists(), "corrupt journal must be retained");
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn missing_boot_id_fails_closed_without_refreshing_state() {
        let root = root("missing-boot-id");
        write(&root, "/proc/sys/x", "old");
        let state_root = root.join("state");
        fs::create_dir_all(&state_root).unwrap();
        fs::write(state_root.join(BOOT_ID), "old-boot").unwrap();
        fs::write(state_root.join(BASELINE), "").unwrap();
        let s = Sysroot::new(&root);
        let mut state = StateStore::new(&state_root);
        let err = state.ensure_boot(&s, &["/proc/sys/x".into()]).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::NotFound);
        assert_eq!(fs::read_to_string(state_root.join(BOOT_ID)).unwrap(), "old-boot");
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn baseline_adopts_nodes_that_appear_later_in_the_same_boot() {
        let root = root("dynamic-node");
        write(&root, "/proc/sys/x", "old");
        write(&root, "/proc/sys/kernel/random/boot_id", "boot-a");
        let s = Sysroot::new(&root);
        let mut state = StateStore::new(root.join("state"));
        state.ensure_boot(&s, &["/proc/sys/x".into()]).unwrap();

        write(&root, "/proc/sys/y", "stock-y");
        state.ensure_boot(&s, &["/proc/sys/x".into(), "/proc/sys/y".into()]).unwrap();
        s.write("/proc/sys/y", "changed-y").unwrap();
        state.restore_baseline(&s, &["/proc/sys/x".into(), "/proc/sys/y".into()]).unwrap();

        assert_eq!(s.read("/proc/sys/y").as_deref(), Some("stock-y"));
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
        s.write("/proc/sys/x", "new-a").unwrap();

        write(&root, "/proc/sys/x", "stock-b");
        write(&root, "/proc/sys/kernel/random/boot_id", "boot-b");
        state.ensure_boot(&s, &["/proc/sys/x".into()]).unwrap();
        assert!(!state.recover(&s).unwrap());
        state.restore_baseline(&s, &["/proc/sys/x".into()]).unwrap();
        assert_eq!(s.read("/proc/sys/x").as_deref(), Some("stock-b"));
        let _ = fs::remove_dir_all(root);
    }
}
