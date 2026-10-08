// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Low-overhead Android input/touch observation.
//!
//! Reads evdev directly instead of spawning `getevent`/shell helpers. A small
//! dedicated reader thread drains interactive devices continuously, so short
//! touch bursts are not lost between the daemon's slower policy iterations.
//! The reader only reports interaction state; policy and actuation remain in
//! FAS and ZKFC. Device discovery is capability/name based and bounded.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::nodes::Sysroot;
use std::fs::{File, OpenOptions};
use std::io::{self, Read};
use std::os::fd::AsRawFd;
use std::os::unix::fs::OpenOptionsExt;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

const MAX_DEVICES: usize = 16;
const MAX_EVENTS_PER_POLL: usize = 96;
const RESCAN_EVERY: Duration = Duration::from_secs(5);
const BURST_WINDOW: Duration = Duration::from_millis(500);
const DISABLED_SLEEP: Duration = Duration::from_millis(250);
const WORKER_SLEEP_CAP: Duration = Duration::from_millis(50);
const EV_KEY: u16 = 1;
const EV_REL: u16 = 2;
const EV_ABS: u16 = 3;
const BTN_TOUCH: u16 = 330;
const BTN_GAMEPAD: u16 = 304;
const ABS_X: u16 = 0;
const ABS_Y: u16 = 1;
const ABS_MT_POSITION_X: u16 = 53;
const ABS_MT_POSITION_Y: u16 = 54;
const ABS_MT_TRACKING_ID: u16 = 57;

#[repr(C)]
#[derive(Clone, Copy, Default)]
struct RawInputEvent {
    tv_sec: libc::time_t,
    tv_usec: libc::suseconds_t,
    type_: u16,
    code: u16,
    value: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InteractionKind {
    TouchDown,
    TouchMove,
    TouchUp,
    Key,
    Scroll,
}

#[derive(Debug, Clone, Default)]
pub struct InteractionSnapshot {
    pub active: bool,
    pub recent: bool,
    pub touch_active: bool,
    pub age_ms: Option<u64>,
    pub burst_count: u8,
    pub source_count: u8,
    pub kind: Option<InteractionKind>,
    pub confidence: u8,
}

struct InputDevice {
    file: File,
}

#[derive(Default)]
struct SharedState {
    snapshot: InteractionSnapshot,
}

/// Public lifecycle wrapper around the continuous evdev reader.
///
/// `observe()` is intentionally cheap: the reader thread has already consumed
/// input events. No per-iteration `getevent` process or device rescan occurs on
/// the policy thread.
pub struct InteractionEngine {
    state: Arc<Mutex<SharedState>>,
    enabled: Arc<AtomicBool>,
    stop: Arc<AtomicBool>,
    reset_epoch: Arc<AtomicU64>,
    scan_ms: Arc<AtomicU32>,
    hold_ms: Arc<AtomicU32>,
    worker: Option<JoinHandle<()>>,
}

impl Default for InteractionEngine {
    fn default() -> Self { Self::new() }
}

impl InteractionEngine {
    pub fn new() -> Self {
        Self {
            state: Arc::new(Mutex::new(SharedState::default())),
            enabled: Arc::new(AtomicBool::new(false)),
            stop: Arc::new(AtomicBool::new(false)),
            reset_epoch: Arc::new(AtomicU64::new(0)),
            scan_ms: Arc::new(AtomicU32::new(20)),
            hold_ms: Arc::new(AtomicU32::new(180)),
            worker: None,
        }
    }

    fn ensure_worker(&mut self, s: &Sysroot) {
        if self.worker.is_some() {
            return;
        }
        let root = s.root().to_path_buf();
        let state = Arc::clone(&self.state);
        let enabled = Arc::clone(&self.enabled);
        let stop = Arc::clone(&self.stop);
        let reset_epoch = Arc::clone(&self.reset_epoch);
        let scan_ms = Arc::clone(&self.scan_ms);
        let hold_ms = Arc::clone(&self.hold_ms);
        self.worker = thread::Builder::new()
            .name("zperfd-input".into())
            .spawn(move || {
                let s = Sysroot::new(root);
                let mut scanner = InputScanner::new();
                let mut seen_epoch = reset_epoch.load(Ordering::Acquire);
                while !stop.load(Ordering::Acquire) {
                    let epoch = reset_epoch.load(Ordering::Acquire);
                    if epoch != seen_epoch {
                        scanner.reset();
                        seen_epoch = epoch;
                    }
                    if !enabled.load(Ordering::Acquire) {
                        scanner.reset();
                        thread::sleep(DISABLED_SLEEP);
                        continue;
                    }
                    let scan = scan_ms.load(Ordering::Relaxed).clamp(5, 250);
                    let hold = hold_ms.load(Ordering::Relaxed).clamp(20, 5000);
                    let snapshot = scanner.observe(&s, scan, hold);
                    if let Ok(mut shared) = state.lock() {
                        shared.snapshot = snapshot;
                    }
                    thread::sleep(Duration::from_millis(u64::from(scan)).min(WORKER_SLEEP_CAP));
                }
            })
            .ok();
    }

    pub fn reset(&mut self) {
        self.enabled.store(false, Ordering::Release);
        self.reset_epoch.fetch_add(1, Ordering::AcqRel);
        if let Ok(mut shared) = self.state.lock() {
            shared.snapshot = InteractionSnapshot::default();
        }
    }

    pub fn observe(&mut self, s: &Sysroot, scan_ms: u32, hold_ms: u32) -> InteractionSnapshot {
        self.scan_ms.store(scan_ms.clamp(5, 250), Ordering::Release);
        self.hold_ms.store(hold_ms.clamp(20, 5000), Ordering::Release);
        self.enabled.store(true, Ordering::Release);
        self.ensure_worker(s);
        self.state
            .lock()
            .map(|shared| shared.snapshot.clone())
            .unwrap_or_default()
    }

    /// Stop event observation without destroying the engine object. This is
    /// used when adaptive input telemetry is disabled or the scene becomes
    /// non-interactive, so the reader thread remains asleep rather than
    /// consuming input polling work in powersave/system scenes.
    pub fn disable(&mut self) {
        self.reset();
    }

    pub fn available(&self) -> bool {
        self.state
            .lock()
            .map(|s| s.snapshot.source_count > 0)
            .unwrap_or(false)
    }
}

impl Drop for InteractionEngine {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Release);
        self.enabled.store(false, Ordering::Release);
        self.reset_epoch.fetch_add(1, Ordering::AcqRel);
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}

struct InputScanner {
    devices: Vec<InputDevice>,
    last_rescan: Option<Instant>,
    last_poll: Option<Instant>,
    last_event: Option<Instant>,
    active_until: Option<Instant>,
    touch_active: bool,
    burst: std::collections::VecDeque<Instant>,
    source_count: u8,
    last_kind: Option<InteractionKind>,
}

impl InputScanner {
    fn new() -> Self {
        Self {
            devices: Vec::new(),
            last_rescan: None,
            last_poll: None,
            last_event: None,
            active_until: None,
            touch_active: false,
            burst: std::collections::VecDeque::with_capacity(16),
            source_count: 0,
            last_kind: None,
        }
    }

    fn reset(&mut self) {
        self.devices.clear();
        self.last_rescan = None;
        self.last_poll = None;
        self.last_event = None;
        self.active_until = None;
        self.touch_active = false;
        self.burst.clear();
        self.source_count = 0;
        self.last_kind = None;
    }

    fn observe(&mut self, s: &Sysroot, scan_ms: u32, hold_ms: u32) -> InteractionSnapshot {
        let now = Instant::now();
        if self
            .last_rescan
            .is_none_or(|t| now.saturating_duration_since(t) >= RESCAN_EVERY)
        {
            self.rescan(s, now);
        }
        let poll_every = Duration::from_millis(u64::from(scan_ms.clamp(5, 250)));
        if self
            .last_poll
            .is_none_or(|t| now.saturating_duration_since(t) >= poll_every)
        {
            self.last_poll = Some(now);
            self.poll_events(now, hold_ms.clamp(20, 5000));
        }
        while self
            .burst
            .front()
            .is_some_and(|t| now.saturating_duration_since(*t) > BURST_WINDOW)
        {
            self.burst.pop_front();
        }
        let age = self
            .last_event
            .map(|t| now.saturating_duration_since(t).as_millis() as u64);
        let recent = age.is_some_and(|ms| ms <= u64::from(hold_ms.max(20)).saturating_mul(2));
        let active = self.touch_active || self.active_until.is_some_and(|t| now < t);
        InteractionSnapshot {
            active,
            recent,
            touch_active: self.touch_active,
            age_ms: age,
            burst_count: self.burst.len().min(u8::MAX as usize) as u8,
            source_count: self.source_count,
            kind: self.last_kind,
            confidence: if self.devices.is_empty() { 0 } else { 90 },
        }
    }

    fn rescan(&mut self, s: &Sysroot, now: Instant) {
        self.devices.clear();
        for event in s
            .list_dir("/dev/input")
            .into_iter()
            .filter(|n| n.starts_with("event"))
            .take(MAX_DEVICES)
        {
            let dev_path = format!("/dev/input/{event}");
            let sys_name = format!("/sys/class/input/{event}/device/name");
            let name = s.read(&sys_name).unwrap_or_else(|| event.clone());
            let abs_path = format!("/sys/class/input/{event}/device/capabilities/abs");
            let key_path = format!("/sys/class/input/{event}/device/capabilities/key");
            if !looks_interactive(
                &name,
                s.read(&abs_path).as_deref(),
                s.read(&key_path).as_deref(),
            ) {
                continue;
            }
            let Ok(file) = OpenOptions::new()
                .read(true)
                .custom_flags(libc::O_CLOEXEC | libc::O_NONBLOCK)
                .open(s.path(&dev_path))
            else {
                continue;
            };
            self.devices.push(InputDevice { file });
        }
        self.last_rescan = Some(now);
        self.source_count = self.devices.len().min(u8::MAX as usize) as u8;
    }

    fn poll_events(&mut self, now: Instant, hold_ms: u32) {
        if self.devices.is_empty() {
            return;
        }
        let mut pfds = self
            .devices
            .iter()
            .map(|d| libc::pollfd {
                fd: d.file.as_raw_fd(),
                events: libc::POLLIN,
                revents: 0,
            })
            .collect::<Vec<_>>();
        let rc = unsafe { libc::poll(pfds.as_mut_ptr(), pfds.len() as libc::nfds_t, 0) };
        if rc <= 0 {
            return;
        }
        for i in 0..pfds.len() {
            if pfds[i].revents & libc::POLLIN == 0 {
                continue;
            }
            for _ in 0..MAX_EVENTS_PER_POLL {
                let mut raw = [0u8; std::mem::size_of::<RawInputEvent>()];
                let n = match self.devices[i].file.read(&mut raw) {
                    Ok(n) => n,
                    Err(e) if e.kind() == io::ErrorKind::WouldBlock => break,
                    Err(_) => break,
                };
                if n == 0 || n != raw.len() {
                    break;
                }
                let event = unsafe { std::ptr::read_unaligned(raw.as_ptr() as *const RawInputEvent) };
                if let Some(kind) = classify_event(event) {
                    self.record(kind, now, hold_ms);
                }
            }
        }
    }

    fn record(&mut self, kind: InteractionKind, now: Instant, hold_ms: u32) {
        self.last_event = Some(now);
        self.last_kind = Some(kind);
        self.burst.push_back(now);
        match kind {
            InteractionKind::TouchDown | InteractionKind::TouchMove => self.touch_active = true,
            InteractionKind::TouchUp => self.touch_active = false,
            InteractionKind::Key | InteractionKind::Scroll => {}
        }
        self.active_until = Some(now + Duration::from_millis(u64::from(hold_ms.clamp(20, 5000))));
    }
}

fn classify_event(e: RawInputEvent) -> Option<InteractionKind> {
    match e.type_ {
        EV_KEY if e.value != 0 && e.code == BTN_TOUCH => Some(InteractionKind::TouchDown),
        EV_KEY if e.code == BTN_TOUCH && e.value == 0 => Some(InteractionKind::TouchUp),
        EV_KEY if e.value != 0 => Some(InteractionKind::Key),
        EV_ABS if e.code == ABS_MT_TRACKING_ID => {
            if e.value >= 0 {
                Some(InteractionKind::TouchDown)
            } else {
                Some(InteractionKind::TouchUp)
            }
        }
        EV_ABS
            if matches!(
                e.code,
                ABS_X | ABS_Y | ABS_MT_POSITION_X | ABS_MT_POSITION_Y
            ) => Some(InteractionKind::TouchMove),
        EV_REL if e.value != 0 => Some(InteractionKind::Scroll),
        _ => None,
    }
}

fn looks_interactive(name: &str, abs: Option<&str>, key: Option<&str>) -> bool {
    let n = name.to_ascii_lowercase();
    if [
        "touchscreen",
        "touchpad",
        "touch",
        "digitizer",
        "synaptics",
        "goodix",
        "focaltech",
        "elan",
        "sec_touchscreen",
        "fts",
    ]
    .iter()
    .any(|v| n.contains(v))
    {
        return true;
    }
    if abs.is_some_and(|v| {
        bitmap_has(v, ABS_MT_POSITION_X) || bitmap_has(v, ABS_MT_POSITION_Y)
    }) {
        return true;
    }
    key.is_some_and(|v| bitmap_has(v, BTN_GAMEPAD))
}

fn bitmap_has(hex: &str, bit: u16) -> bool {
    let words = hex.split_whitespace().rev().collect::<Vec<_>>();
    let word = (bit / 32) as usize;
    let off = (bit % 32) as u32;
    let Some(raw) = words.get(word) else {
        return false;
    };
    u32::from_str_radix(raw, 16)
        .map(|v| v & (1u32 << off) != 0)
        .unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bitmap_detects_mt_capability() {
        assert!(bitmap_has("00000000 00000000 00200000", 53));
    }

    #[test]
    fn event_mapping_is_stable() {
        let e = RawInputEvent {
            type_: EV_ABS,
            code: ABS_MT_TRACKING_ID,
            value: 1,
            ..Default::default()
        };
        assert_eq!(classify_event(e), Some(InteractionKind::TouchDown));
    }

    #[test]
    fn gamepad_key_is_not_touch() {
        let e = RawInputEvent {
            type_: EV_KEY,
            code: BTN_GAMEPAD,
            value: 1,
            ..Default::default()
        };
        assert_eq!(classify_event(e), Some(InteractionKind::Key));
    }
}
