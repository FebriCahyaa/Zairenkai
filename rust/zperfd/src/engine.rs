// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! The apply engine. Turns a device-agnostic [Mode] into concrete node writes
//! for the detected device: per-policy cpufreq caps, governor, top-app boost
//! (uclamp on GKI, schedtune on non-GKI), input boost, GPU devfreq, VM and I/O
//! knobs. Every write is probe-gated and (for sysfs) locked.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::config::{self, Cpu, Gpu, Io, Mem, Mode};
use crate::nodes::Sysroot;
use crate::topo::Topology;

#[derive(Default, Debug)]
pub struct ApplyReport {
    pub applied: Vec<String>,
    pub skipped: Vec<String>,
}

impl ApplyReport {
    fn ok(&mut self, what: String) {
        self.applied.push(what);
    }
    fn skip(&mut self, what: String) {
        self.skipped.push(what);
    }
}

pub struct Engine<'a> {
    s: &'a Sysroot,
    t: &'a Topology,
    /// lock sysfs writes (chmod 0444) so the vendor HAL cannot revert them.
    pub lock: bool,
}

impl<'a> Engine<'a> {
    pub fn new(s: &'a Sysroot, t: &'a Topology) -> Self {
        Engine { s, t, lock: true }
    }

    pub fn apply_mode(&self, m: &Mode) -> ApplyReport {
        let mut r = ApplyReport::default();
        self.apply_cpu(&m.cpu, &mut r);
        self.apply_gpu(&m.gpu, &mut r);
        self.apply_mem(&m.mem, &mut r);
        self.apply_io(&m.io, &mut r);
        r
    }

    fn apply_cpu(&self, c: &Cpu, r: &mut ApplyReport) {
        for p in &self.t.policies {
            let maxf = c
                .max_freq
                .as_ref()
                .and_then(|f| config::resolve_freq(f, &p.avail, false))
                .or_else(|| c.max_perf_pct.and_then(|pct| config::resolve_pct(pct, &p.avail, false)));
            let minf = c
                .min_freq
                .as_ref()
                .and_then(|f| config::resolve_freq(f, &p.avail, true))
                .or_else(|| c.min_perf_pct.and_then(|pct| config::resolve_pct(pct, &p.avail, true)));

            // Clamp min <= max to avoid a rejected write.
            let (minf, maxf) = match (minf, maxf) {
                (Some(mi), Some(ma)) if mi > ma => (Some(ma), Some(ma)),
                other => other,
            };

            if let Some(ma) = maxf {
                let node = p.rel("scaling_max_freq");
                match self.s.write(&node, &ma.to_string(), self.lock) {
                    Ok(_) => r.ok(format!("{}=max {ma}", p.name)),
                    Err(_) => r.skip(node),
                }
            }
            if let Some(mi) = minf {
                let node = p.rel("scaling_min_freq");
                match self.s.write(&node, &mi.to_string(), self.lock) {
                    Ok(_) => r.ok(format!("{}=min {mi}", p.name)),
                    Err(_) => r.skip(node),
                }
            }
            if !c.governor.is_empty() {
                self.apply_governor(p, &c.governor, r);
            }
        }

        // msm_performance mirror (non-GKI Qualcomm) — "cpuIdx:freq ..." form.
        if self.t.has_msm_perf {
            if let Some(ma) = self.perf_param_str(c, false) {
                let _ = self.s.write("/sys/module/msm_performance/parameters/cpu_max_freq", &ma, self.lock);
            }
            if let Some(mi) = self.perf_param_str(c, true) {
                let _ = self.s.write("/sys/module/msm_performance/parameters/cpu_min_freq", &mi, self.lock);
            }
        }

        self.apply_boost(c, r);
        self.apply_input_boost(c, r);

        if let Some(b) = c.sched_boost {
            if self.s.write_first(
                &["/proc/sys/walt/sched_boost", "/proc/sys/kernel/sched_boost"],
                &b.to_string(),
                false,
            )
            .is_some()
            {
                r.ok(format!("sched_boost={b}"));
            }
        }
    }

    fn perf_param_str(&self, c: &Cpu, is_min: bool) -> Option<String> {
        // Build "idx:freq" for each policy's first CPU (policy index == cpu idx).
        let mut parts = Vec::new();
        for p in &self.t.policies {
            let idx: u32 = p.name.trim_start_matches("policy").parse().unwrap_or(0);
            let f = if is_min {
                c.min_freq
                    .as_ref()
                    .and_then(|f| config::resolve_freq(f, &p.avail, true))
                    .or_else(|| c.min_perf_pct.and_then(|pct| config::resolve_pct(pct, &p.avail, true)))
            } else {
                c.max_freq
                    .as_ref()
                    .and_then(|f| config::resolve_freq(f, &p.avail, false))
                    .or_else(|| c.max_perf_pct.and_then(|pct| config::resolve_pct(pct, &p.avail, false)))
            };
            if let Some(freq) = f {
                parts.push(format!("{idx}:{freq}"));
            }
        }
        if parts.is_empty() { None } else { Some(parts.join(" ")) }
    }

    fn apply_governor(&self, p: &crate::topo::Policy, pref: &[String], r: &mut ApplyReport) {
        let avail = self.s.read(&p.rel("scaling_available_governors")).unwrap_or_default();
        let have: Vec<&str> = avail.split_whitespace().collect();
        for g in pref {
            if have.is_empty() || have.contains(&g.as_str()) {
                if self.s.write(&p.rel("scaling_governor"), g, false).is_ok() {
                    r.ok(format!("{} gov={g}", p.name));
                    return;
                }
            }
        }
        r.skip(format!("{} governor (none of {:?})", p.name, pref));
    }

    fn apply_boost(&self, c: &Cpu, r: &mut ApplyReport) {
        // top-app perf hint: uclamp on GKI, schedtune on non-GKI.
        if let Some(min) = c.uclamp_min_pct {
            if let Some(dir) = &self.t.top_app_uclamp {
                let _ = self.s.write(&format!("{dir}/cpu.uclamp.min"), &min.to_string(), false);
                r.ok(format!("uclamp.min={min}"));
            } else if let Some(dir) = &self.t.stune_top {
                let _ = self.s.write(&format!("{dir}/schedtune.boost"), &min.to_string(), false);
                r.ok(format!("schedtune.boost={min}"));
            }
        }
        if let Some(max) = c.uclamp_max_pct {
            if let Some(dir) = &self.t.top_app_uclamp {
                let _ = self.s.write(&format!("{dir}/cpu.uclamp.max"), &max.to_string(), false);
                r.ok(format!("uclamp.max={max}"));
            }
        }
    }

    fn apply_input_boost(&self, c: &Cpu, r: &mut ApplyReport) {
        let dir = match &self.t.cpu_boost_dir {
            Some(d) => d,
            None => return,
        };
        if let Some(ms) = c.input_boost_ms {
            let _ = self.s.write(&format!("{dir}/input_boost_ms"), &ms.to_string(), false);
            r.ok(format!("input_boost_ms={ms}"));
        }
        if let Some(pct) = c.input_boost_pct {
            let mut parts = Vec::new();
            for p in &self.t.policies {
                let idx: u32 = p.name.trim_start_matches("policy").parse().unwrap_or(0);
                if let Some(f) = config::resolve_pct(pct, &p.avail, false) {
                    parts.push(format!("{idx}:{f}"));
                }
            }
            if !parts.is_empty() {
                let _ = self.s.write(&format!("{dir}/input_boost_freq"), &parts.join(" "), false);
                r.ok("input_boost_freq".into());
            }
        }
    }

    fn apply_gpu(&self, g: &Gpu, r: &mut ApplyReport) {
        let node = match &self.t.gpu {
            Some(n) => n,
            None => return,
        };
        if !node.avail.is_empty() {
            if let Some(ma) = g
                .max_freq
                .as_ref()
                .and_then(|f| config::resolve_freq(f, &node.avail, false))
                .or_else(|| g.max_perf_pct.and_then(|pct| config::resolve_pct(pct, &node.avail, false)))
            {
                if self.s.write(&node.rel("max_freq"), &ma.to_string(), self.lock).is_ok() {
                    r.ok(format!("gpu max={ma}"));
                }
            }
            if let Some(mi) = g
                .min_freq
                .as_ref()
                .and_then(|f| config::resolve_freq(f, &node.avail, true))
                .or_else(|| g.min_perf_pct.and_then(|pct| config::resolve_pct(pct, &node.avail, true)))
            {
                if self.s.write(&node.rel("min_freq"), &mi.to_string(), self.lock).is_ok() {
                    r.ok(format!("gpu min={mi}"));
                }
            }
        }
        for gov in &g.governor {
            let avail = self.s.read(&node.rel("available_governors")).unwrap_or_default();
            let have: Vec<&str> = avail.split_whitespace().collect();
            if have.is_empty() || have.contains(&gov.as_str()) {
                if self.s.write(&node.rel("governor"), gov, false).is_ok() {
                    r.ok(format!("gpu gov={gov}"));
                    break;
                }
            }
        }
    }

    fn set_proc_vm(&self, leaf: &str, val: Option<String>, r: &mut ApplyReport) {
        if let Some(v) = val {
            if self.s.write(&format!("/proc/sys/vm/{leaf}"), &v, false).is_ok() {
                r.ok(format!("vm.{leaf}"));
            }
        }
    }

    fn apply_mem(&self, m: &Mem, r: &mut ApplyReport) {
        self.set_proc_vm("swappiness", m.swappiness.map(|v| v.to_string()), r);
        self.set_proc_vm("vfs_cache_pressure", m.vfs_cache_pressure.map(|v| v.to_string()), r);
        self.set_proc_vm("watermark_scale_factor", m.watermark_scale_factor.map(|v| v.to_string()), r);
        self.set_proc_vm("page-cluster", m.page_cluster.map(|v| v.to_string()), r);
        self.set_proc_vm("extra_free_kbytes", m.extra_free_kbytes.map(|v| v.to_string()), r);
    }

    fn apply_io(&self, io: &Io, r: &mut ApplyReport) {
        if io.scheduler.is_empty() && io.read_ahead_kb.is_none() && io.nr_requests.is_none() {
            return;
        }
        for dev in self.s.list_dir("/sys/block") {
            let q = format!("/sys/block/{dev}/queue");
            if !self.s.exists(&format!("{q}/scheduler")) {
                continue;
            }
            if !io.scheduler.is_empty() {
                let avail = self.s.read(&format!("{q}/scheduler")).unwrap_or_default();
                for sched in &io.scheduler {
                    if avail.contains(sched.as_str()) && self.s.write(&format!("{q}/scheduler"), sched, false).is_ok() {
                        r.ok(format!("{dev} iosched={sched}"));
                        break;
                    }
                }
            }
            if let Some(ra) = io.read_ahead_kb {
                let _ = self.s.write(&format!("{q}/read_ahead_kb"), &ra.to_string(), false);
            }
            if let Some(nr) = io.nr_requests {
                let _ = self.s.write(&format!("{q}/nr_requests"), &nr.to_string(), false);
            }
        }
    }

    /// Best-effort restore toward stock: open freq range, schedutil, no boost.
    pub fn reset(&self) -> ApplyReport {
        let mut r = ApplyReport::default();
        for p in &self.t.policies {
            if p.max_hw > 0 {
                let _ = self.s.write(&p.rel("scaling_max_freq"), &p.max_hw.to_string(), false);
            }
            if p.min_hw > 0 {
                let _ = self.s.write(&p.rel("scaling_min_freq"), &p.min_hw.to_string(), false);
            }
            self.apply_governor(p, &["schedutil".to_string(), "walt".to_string()], &mut r);
        }
        if let Some(dir) = &self.t.top_app_uclamp {
            let _ = self.s.write(&format!("{dir}/cpu.uclamp.min"), "0", false);
        }
        if let Some(dir) = &self.t.stune_top {
            let _ = self.s.write(&format!("{dir}/schedtune.boost"), "0", false);
        }
        r
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Profile;
    use std::fs;
    use std::path::PathBuf;

    fn mkroot() -> PathBuf {
        use std::time::{SystemTime, UNIX_EPOCH};
        let tag = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let d = std::env::temp_dir().join(format!("zperf-eng-{tag}"));
        let _ = fs::remove_dir_all(&d);
        d
    }
    fn w(root: &PathBuf, rel: &str, val: &str) {
        let p = root.join(rel.trim_start_matches('/'));
        fs::create_dir_all(p.parent().unwrap()).unwrap();
        fs::write(p, val).unwrap();
    }

    #[test]
    fn apply_performance_nongki() {
        let root = mkroot();
        // one cluster, non-GKI
        w(&root, "/proc/sys/kernel/osrelease", "4.19.157-lavender");
        let base = "/sys/devices/system/cpu/cpufreq/policy0";
        w(&root, &format!("{base}/cpuinfo_min_freq"), "300000");
        w(&root, &format!("{base}/cpuinfo_max_freq"), "1843200");
        w(&root, &format!("{base}/scaling_available_frequencies"), "300000 1017600 1843200");
        w(&root, &format!("{base}/scaling_available_governors"), "schedutil performance powersave");
        w(&root, &format!("{base}/scaling_max_freq"), "1017600");
        w(&root, &format!("{base}/scaling_min_freq"), "300000");
        w(&root, &format!("{base}/scaling_governor"), "schedutil");
        w(&root, "/dev/stune/top-app/schedtune.boost", "0");
        w(&root, "/proc/sys/vm/swappiness", "100");

        let s = Sysroot::new(&root);
        let t = Topology::detect(&s);
        let prof = Profile::parse(
            r#"
            [mode.performance.cpu]
            max_freq = "max"
            min_freq = "50%"
            governor = ["performance", "schedutil"]
            uclamp_min_pct = 30
            [mode.performance.mem]
            swappiness = 60
            "#,
        )
        .unwrap();
        let eng = Engine::new(&s, &t);
        let rep = eng.apply_mode(prof.mode("performance").unwrap());

        assert_eq!(s.read(&format!("{base}/scaling_max_freq")).as_deref(), Some("1843200"));
        // 50% of 1843200 = 921600 -> floor-snap up to 1017600
        assert_eq!(s.read(&format!("{base}/scaling_min_freq")).as_deref(), Some("1017600"));
        assert_eq!(s.read(&format!("{base}/scaling_governor")).as_deref(), Some("performance"));
        assert_eq!(s.read("/dev/stune/top-app/schedtune.boost").as_deref(), Some("30"));
        assert_eq!(s.read("/proc/sys/vm/swappiness").as_deref(), Some("60"));
        assert!(rep.applied.iter().any(|x| x.contains("gov=performance")));
        let _ = fs::remove_dir_all(&root);
    }
}
