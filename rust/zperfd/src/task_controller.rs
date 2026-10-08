// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Ephemeral per-task actuation for the adaptive engine.
//!
//! ZKFC owns uclamp/inheritance because it can preserve task identity across
//! PID reuse and suspend boosts during thermal trips. This layer only adds a
//! narrow affinity hint for high-value render/game threads and restores every
//! kernel scheduler attribute that it changes.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::fas::FasDecision;
use crate::workload::{ThreadClass, WorkloadSnapshot};
use std::collections::BTreeMap;
use std::io;
use zkfc_sys::{Zkfc, ZKFC_CAP_TUNE_PERF, ZKFC_CQ_CLEAR, ZKFC_TB_INHERIT, ZKFC_TB_RESET, ZKFC_TB_THREADS};

#[derive(Clone, Debug)]
struct SavedAffinity {
    tid: i32,
    original: Vec<u8>,
    applied: Vec<u8>,
}

pub struct ThreadTaskController {
    zkfc: Option<Zkfc>,
    boosted_pid: Option<i32>,
    last_uclamp_min: Option<u32>,
    task_boost_supported: Option<bool>,
    qos_cpu: Option<u32>,
    last_cpu_floor_pct: Option<u32>,
    saved_affinity: BTreeMap<i32, SavedAffinity>,
}

impl Default for ThreadTaskController {
    fn default() -> Self {
        Self::new()
    }
}

impl Drop for ThreadTaskController {
    fn drop(&mut self) {
        self.reset();
    }
}

impl ThreadTaskController {
    pub fn new() -> Self {
        Self {
            zkfc: None,
            boosted_pid: None,
            last_uclamp_min: None,
            task_boost_supported: None,
            qos_cpu: None,
            last_cpu_floor_pct: None,
            saved_affinity: BTreeMap::new(),
        }
    }

    pub fn reset(&mut self) {
        if let (Some(z), Some(pid)) = (self.zkfc.as_ref(), self.boosted_pid) {
            let _ = z.task_boost(pid, 0, 1024, ZKFC_TB_THREADS | ZKFC_TB_RESET);
        }
        for saved in self.saved_affinity.values() {
            if get_affinity(saved.tid).as_deref() == Some(saved.applied.as_slice()) {
                let _ = set_affinity(saved.tid, &saved.original);
            }
        }
        if let (Some(z), Some(cpu)) = (self.zkfc.as_ref(), self.qos_cpu) {
            let _ = z.cpufreq_qos(cpu, 0, 0, ZKFC_CQ_CLEAR);
        }
        self.qos_cpu = None;
        self.last_cpu_floor_pct = None;
        self.saved_affinity.clear();
        self.boosted_pid = None;
        self.last_uclamp_min = None;
        self.task_boost_supported = None;
    }

    pub fn apply(
        &mut self,
        decision: &FasDecision,
        workload: &WorkloadSnapshot,
        performance_cpus: &[usize],
        performance_policy: Option<(usize, u64, u64)>,
    ) -> Result<(), String> {
        if !decision.active {
            if self.boosted_pid == Some(workload.pid) {
                self.reset();
            }
            return Ok(());
        }

        if self.zkfc.is_none() {
            self.zkfc = Zkfc::open().ok();
        }
        if read_start_time(workload.pid) != Some(workload.start_time_ticks) {
            return Err("workload process identity changed before actuation".into());
        }
        let Some(z) = self.zkfc.as_ref() else { return Err("ZKFC unavailable for task boost".into()); };
        let caps = z.capabilities().map_err(|e| format!("ZKFC capabilities: {e}"))?;
        if caps.kernel_caps() & ZKFC_CAP_TUNE_PERF == 0 {
            return Err("ZKFC performance capability unavailable".into());
        }
        let uclamp_min = ((decision.target_uclamp_min_pct as u64 * 1024 + 50) / 100) as u32;
        if self.task_boost_supported != Some(false)
            && (self.boosted_pid != Some(workload.pid) || self.last_uclamp_min != Some(uclamp_min))
        {
            match z.task_boost(
                workload.pid,
                uclamp_min,
                ((decision.target_uclamp_max_pct.min(100) as u64 * 1024 + 50) / 100) as u32,
                ZKFC_TB_THREADS | ZKFC_TB_INHERIT,
            ) {
                Ok(_) => {
                    self.last_uclamp_min = Some(uclamp_min);
                    self.task_boost_supported = Some(true);
                }
                Err(e) if matches!(e.raw_os_error(), Some(libc::EOPNOTSUPP) | Some(libc::ENODEV)) => {
                    self.task_boost_supported = Some(false);
                }
                Err(e) => return Err(format!("ZKFC task boost: {e}")),
            }
        }
        self.boosted_pid = Some(workload.pid);

        if let Some((cpu, min_hw, max_hw)) = performance_policy {
            if decision.cpu_floor_pct > 0 && max_hw > min_hw {
                let span = max_hw.saturating_sub(min_hw);
                let floor = min_hw
                    .saturating_add(span.saturating_mul(decision.cpu_floor_pct as u64) / 100)
                    .min(u32::MAX as u64);
                if self.qos_cpu != Some(cpu) || self.last_cpu_floor_pct != Some(decision.cpu_floor_pct) {
                    if let Err(e) = z.cpufreq_qos(cpu as u32, floor.min(max_hw) as u32, 0, 0) {
                        if !matches!(e.raw_os_error(), Some(libc::EOPNOTSUPP) | Some(libc::ENODEV)) {
                            return Err(format!("ZKFC cpufreq QoS: {e}"));
                        }
                    } else {
                        self.qos_cpu = Some(cpu as u32);
                        self.last_cpu_floor_pct = Some(decision.cpu_floor_pct);
                    }
                }
            } else if let Some(qos_cpu) = self.qos_cpu {
                let _ = z.cpufreq_qos(qos_cpu, 0, 0, ZKFC_CQ_CLEAR);
                self.qos_cpu = None;
                self.last_cpu_floor_pct = None;
            }
        }

        if decision.affinity_hint && !performance_cpus.is_empty() {
            let mut changed = 0usize;
            for tid in workload.top_threads.iter().copied().take(8) {
                let Some(thread) = workload.threads.iter().find(|thread| thread.tid == tid) else { continue; };
                if !matches!(thread.class, ThreadClass::Render | ThreadClass::Game) { continue; }
                if thread.cpu_util_pct < 2.0 { continue; }
                if self.saved_affinity.contains_key(&thread.tid) { continue; }
                if read_start_time(thread.tid) != Some(thread.start_time_ticks) { continue; }
                let Some(previous) = get_affinity(thread.tid) else { continue; };
                if previous.is_empty() { continue; }
                let target = cpu_mask(performance_cpus);
                if target == previous { continue; }
                if set_affinity(thread.tid, &target).is_ok() {
                    self.saved_affinity.insert(
                        thread.tid,
                        SavedAffinity {
                            tid: thread.tid,
                            original: previous,
                            applied: target,
                        },
                    );
                    changed += 1;
                }
                if changed >= 4 { break; }
            }
        }
        Ok(())
    }
}

fn cpu_mask(cpus: &[usize]) -> Vec<u8> {
    let size = std::mem::size_of::<libc::cpu_set_t>();
    let mut mask = vec![0u8; size];
    for &cpu in cpus {
        let byte = cpu / 8;
        if byte < mask.len() {
            mask[byte] |= 1u8 << (cpu % 8);
        }
    }
    mask
}

fn read_start_time(tid: i32) -> Option<u64> {
    if tid <= 0 { return None; }
    let text = std::fs::read_to_string(format!("/proc/{tid}/stat")).ok()?;
    let close = text.rfind(") ")?;
    text.get(close + 2..)?.split_whitespace().nth(19)?.parse().ok()
}

fn get_affinity(tid: i32) -> Option<Vec<u8>> {
    if tid <= 0 { return None; }
    let size = std::mem::size_of::<libc::cpu_set_t>();
    let mut set = unsafe { std::mem::zeroed::<libc::cpu_set_t>() };
    let rc = unsafe { libc::sched_getaffinity(tid, size, &mut set as *mut libc::cpu_set_t) };
    if rc != 0 { return None; }
    let ptr = &set as *const libc::cpu_set_t as *const u8;
    Some(unsafe { std::slice::from_raw_parts(ptr, size) }.to_vec())
}

fn set_affinity(tid: i32, mask: &[u8]) -> io::Result<()> {
    if tid <= 0 || mask.is_empty() { return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid affinity")); }
    let size = std::mem::size_of::<libc::cpu_set_t>();
    if mask.len() > size { return Err(io::Error::new(io::ErrorKind::InvalidInput, "affinity mask too large")); }
    let mut set = unsafe { std::mem::zeroed::<libc::cpu_set_t>() };
    let dst = &mut set as *mut libc::cpu_set_t as *mut u8;
    unsafe { std::ptr::copy_nonoverlapping(mask.as_ptr(), dst, mask.len()); }
    let rc = unsafe { libc::sched_setaffinity(tid, size, &set as *const libc::cpu_set_t) };
    if rc != 0 { Err(io::Error::last_os_error()) } else { Ok(()) }
}

#[cfg(test)]
mod tests {
    use super::cpu_mask;
    #[test]
    fn affinity_mask_sets_expected_bits() {
        let mask = cpu_mask(&[0, 1, 8]);
        assert_eq!(&mask[..2], &[3, 1]);
        assert_eq!(mask.len(), std::mem::size_of::<libc::cpu_set_t>());
    }
}
