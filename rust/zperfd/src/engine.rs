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
    pub failed: Vec<String>,
}

impl ApplyReport {
    fn ok(&mut self, what: String) {
        self.applied.push(what);
    }
    fn skip(&mut self, what: String) {
        self.skipped.push(what);
    }
    fn fail(&mut self, what: String) {
        self.failed.push(what);
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

    fn record_write(&self, r: &mut ApplyReport, node: String, label: String, lock: bool) {
        match self.s.write(&node, &label, lock) {
            Ok(()) => r.ok(format!("{node}={label}")),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => r.skip(node),
            Err(e) => r.fail(format!("{node}={label}: {e}")),
        }
    }

    /// Enumerate nodes this engine may mutate. This is used by the persistent
    /// state layer to capture an exact pre-change snapshot before mutation.
    pub fn managed_nodes(&self) -> Vec<String> {
        let mut out = vec![
            "/proc/sys/vm/swappiness".into(),
            "/proc/sys/vm/vfs_cache_pressure".into(),
            "/proc/sys/vm/watermark_scale_factor".into(),
            "/proc/sys/vm/page-cluster".into(),
            "/proc/sys/vm/extra_free_kbytes".into(),
            "/proc/sys/walt/sched_boost".into(),
            "/proc/sys/kernel/sched_boost".into(),
        ];
        for p in &self.t.policies {
            for leaf in ["scaling_min_freq","scaling_max_freq","scaling_governor"] {
                out.push(p.rel(leaf));
            }
        }
        if self.t.has_msm_perf {
            out.push("/sys/module/msm_performance/parameters/cpu_min_freq".into());
            out.push("/sys/module/msm_performance/parameters/cpu_max_freq".into());
        }
        if let Some(dir)=&self.t.top_app_uclamp {
            out.push(format!("{dir}/cpu.uclamp.min"));
            out.push(format!("{dir}/cpu.uclamp.max"));
        }
        if let Some(dir)=&self.t.stune_top { out.push(format!("{dir}/schedtune.boost")); }
        if let Some(dir)=&self.t.cpu_boost_dir {
            out.push(format!("{dir}/input_boost_ms"));
            out.push(format!("{dir}/input_boost_freq"));
        }
        if let Some(g)=&self.t.gpu {
            for leaf in ["min_freq","max_freq","governor"] { out.push(g.rel(leaf)); }
        }
        for dev in self.s.list_dir("/sys/block") {
            let q=format!("/sys/block/{dev}/queue");
            for leaf in ["scheduler","read_ahead_kb","nr_requests"] { out.push(format!("{q}/{leaf}")); }
        }
        out.sort(); out.dedup(); out
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

            let current_min = self.s.read_u64(&p.rel("scaling_min_freq"));
            let current_max = self.s.read_u64(&p.rel("scaling_max_freq"));

            // Changing a cpufreq range can fail when the intermediate state has
            // min > max. Move the constraining side first.
            let write_min_first = matches!((minf, maxf, current_min),
                (Some(mi), Some(ma), Some(cur_min)) if ma < cur_min && mi <= ma);

            let mut write_limit = |leaf: &str, value: u64, label: String| {
                let node = p.rel(leaf);
                match self.s.write(&node, &value.to_string(), self.lock) {
                    Ok(_) => r.ok(label),
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => r.skip(node),
                    Err(e) => r.fail(format!("{label}: {e}")),
                }
            };

            if write_min_first {
                if let Some(mi) = minf {
                    write_limit("scaling_min_freq", mi, format!("{}=min {mi}", p.name));
                }
                if let Some(ma) = maxf {
                    write_limit("scaling_max_freq", ma, format!("{}=max {ma}", p.name));
                }
            } else {
                if let Some(ma) = maxf {
                    // If max is being raised above the current min, raising max
                    // first avoids the symmetric intermediate min > max case.
                    let _ = current_max;
                    write_limit("scaling_max_freq", ma, format!("{}=max {ma}", p.name));
                }
                if let Some(mi) = minf {
                    write_limit("scaling_min_freq", mi, format!("{}=min {mi}", p.name));
                }
            }
            if !c.governor.is_empty() {
                self.apply_governor(p, &c.governor, r);
            }
        }

        // msm_performance mirror (non-GKI Qualcomm) — "cpuIdx:freq ..." form.
        if self.t.has_msm_perf {
            if let Some(ma) = self.perf_param_str(c, false) {
                let node = "/sys/module/msm_performance/parameters/cpu_max_freq".to_string();
                match self.s.write(&node, &ma, self.lock) {
                    Ok(()) => r.ok(format!("msm_performance.max={ma}")),
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => r.skip(node),
                    Err(e) => r.fail(format!("msm_performance.max={ma}: {e}")),
                }
            }
            if let Some(mi) = self.perf_param_str(c, true) {
                let node = "/sys/module/msm_performance/parameters/cpu_min_freq".to_string();
                match self.s.write(&node, &mi, self.lock) {
                    Ok(()) => r.ok(format!("msm_performance.min={mi}")),
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => r.skip(node),
                    Err(e) => r.fail(format!("msm_performance.min={mi}: {e}")),
                }
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
                let node = p.rel("scaling_governor");
                match self.s.write(&node, g, false) {
                    Ok(()) => {
                        r.ok(format!("{} gov={g}", p.name));
                        return;
                    }
                    Err(e) if e.kind() != std::io::ErrorKind::NotFound => {
                        r.fail(format!("{node}={g}: {e}"));
                    }
                    Err(_) => {}
                }
            }
        }
        r.skip(format!("{} governor (none of {:?})", p.name, pref));
    }

    fn apply_boost(&self, c: &Cpu, r: &mut ApplyReport) {
        // top-app perf hint: uclamp on GKI, schedtune on non-GKI.
        if let Some(min) = c.uclamp_min_pct {
            if let Some(dir) = &self.t.top_app_uclamp {
                let node = format!("{dir}/cpu.uclamp.min");
                let scaled = config::uclamp_from_pct(min);
                match self.s.write(&node, &scaled.to_string(), false) {
                    Ok(()) => r.ok(format!("uclamp.min={scaled} ({min}%)")),
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => r.skip(node),
                    Err(e) => r.fail(format!("uclamp.min={min}: {e}")),
                }
            } else if let Some(dir) = &self.t.stune_top {
                let node = format!("{dir}/schedtune.boost");
                match self.s.write(&node, &min.to_string(), false) {
                    Ok(()) => r.ok(format!("schedtune.boost={min}")),
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => r.skip(node),
                    Err(e) => r.fail(format!("schedtune.boost={min}: {e}")),
                }
            }
        }
        if let Some(max) = c.uclamp_max_pct {
            if let Some(dir) = &self.t.top_app_uclamp {
                let node = format!("{dir}/cpu.uclamp.max");
                let scaled = config::uclamp_from_pct(max);
                match self.s.write(&node, &scaled.to_string(), false) {
                    Ok(()) => r.ok(format!("uclamp.max={scaled} ({max}%)")),
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => r.skip(node),
                    Err(e) => r.fail(format!("uclamp.max={max}: {e}")),
                }
            }
        }
    }

    fn apply_input_boost(&self, c: &Cpu, r: &mut ApplyReport) {
        let dir = match &self.t.cpu_boost_dir {
            Some(d) => d,
            None => return,
        };
        if let Some(ms) = c.input_boost_ms {
            let node = format!("{dir}/input_boost_ms");
            match self.s.write(&node, &ms.to_string(), false) {
                Ok(()) => r.ok(format!("input_boost_ms={ms}")),
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => r.skip(node),
                Err(e) => r.fail(format!("input_boost_ms={ms}: {e}")),
            }
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
                let node = format!("{dir}/input_boost_freq");
                match self.s.write(&node, &parts.join(" "), false) {
                    Ok(()) => r.ok("input_boost_freq".into()),
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => r.skip(node),
                    Err(e) => r.fail(format!("input_boost_freq: {e}")),
                }
            }
        }
    }

    fn apply_gpu(&self, g: &Gpu, r: &mut ApplyReport) {
        let node = match &self.t.gpu {
            Some(n) => n,
            None => return,
        };
        if !node.avail.is_empty() {
            let max_target = g
                .max_freq
                .as_ref()
                .and_then(|f| config::resolve_freq(f, &node.avail, false))
                .or_else(|| g.max_perf_pct.and_then(|pct| config::resolve_pct(pct, &node.avail, false)));
            let min_target = g
                .min_freq
                .as_ref()
                .and_then(|f| config::resolve_freq(f, &node.avail, true))
                .or_else(|| g.min_perf_pct.and_then(|pct| config::resolve_pct(pct, &node.avail, true)));
            let current_min = self.s.read_u64(&node.rel("min_freq"));
            let current_max = self.s.read_u64(&node.rel("max_freq"));
            let min_first = matches!((min_target, max_target, current_min),
                (Some(mi), Some(ma), Some(cur)) if ma < cur && mi <= ma);
            let write_min = |value: u64, r: &mut ApplyReport| {
                let path = node.rel("min_freq");
                match self.s.write(&path, &value.to_string(), self.lock) {
                    Ok(()) => r.ok(format!("gpu min={value}")),
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => r.skip(path),
                    Err(e) => r.fail(format!("gpu min={value}: {e}")),
                }
            };
            let write_max = |value: u64, r: &mut ApplyReport| {
                let path = node.rel("max_freq");
                match self.s.write(&path, &value.to_string(), self.lock) {
                    Ok(()) => r.ok(format!("gpu max={value}")),
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => r.skip(path),
                    Err(e) => r.fail(format!("gpu max={value}: {e}")),
                }
            };
            if min_first {
                if let Some(mi) = min_target { write_min(mi, r); }
                if let Some(ma) = max_target { write_max(ma, r); }
            } else {
                if let Some(ma) = max_target { write_max(ma, r); }
                if let Some(mi) = min_target { write_min(mi, r); }
            }
            let _ = current_max;
        }
        for gov in &g.governor {
            let avail = self.s.read(&node.rel("available_governors")).unwrap_or_default();
            let have: Vec<&str> = avail.split_whitespace().collect();
            if have.is_empty() || have.contains(&gov.as_str()) {
                let path = node.rel("governor");
                match self.s.write(&path, gov, false) {
                    Ok(()) => {
                        r.ok(format!("gpu gov={gov}"));
                        break;
                    }
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                    Err(e) => r.fail(format!("gpu gov={gov}: {e}")),
                }
            }
        }
    }

    fn set_proc_vm(&self, leaf: &str, val: Option<String>, r: &mut ApplyReport) {
        if let Some(v) = val {
            let node = format!("/proc/sys/vm/{leaf}");
            match self.s.write(&node, &v, false) {
                Ok(()) => r.ok(format!("vm.{leaf}")),
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => r.skip(node),
                Err(e) => r.fail(format!("vm.{leaf}={v}: {e}")),
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
                    if avail.contains(sched.as_str()) {
                        let path = format!("{q}/scheduler");
                        match self.s.write(&path, sched, false) {
                            Ok(()) => {
                                r.ok(format!("{dev} iosched={sched}"));
                                break;
                            }
                            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                            Err(e) => r.fail(format!("{dev} iosched={sched}: {e}")),
                        }
                    }
                }
            }
            if let Some(ra) = io.read_ahead_kb {
                let path = format!("{q}/read_ahead_kb");
                match self.s.write(&path, &ra.to_string(), false) {
                    Ok(()) => r.ok(format!("{dev} read_ahead_kb={ra}")),
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => r.skip(path),
                    Err(e) => r.fail(format!("{dev} read_ahead_kb={ra}: {e}")),
                }
            }
            if let Some(nr) = io.nr_requests {
                let path = format!("{q}/nr_requests");
                match self.s.write(&path, &nr.to_string(), false) {
                    Ok(()) => r.ok(format!("{dev} nr_requests={nr}")),
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => r.skip(path),
                    Err(e) => r.fail(format!("{dev} nr_requests={nr}: {e}")),
                }
            }
        }
    }

    /// Best-effort restore toward stock: open freq range, schedutil, no boost.
    pub fn reset(&self) -> ApplyReport {
        let mut r = ApplyReport::default();
        for p in &self.t.policies {
            let current_min = self.s.read_u64(&p.rel("scaling_min_freq"));
            let min_first = matches!(current_min, Some(cur) if p.max_hw > 0 && p.min_hw > 0 && p.max_hw < cur);
            let write_min = |r: &mut ApplyReport| {
                if p.min_hw == 0 { return; }
                let path = p.rel("scaling_min_freq");
                match self.s.write(&path, &p.min_hw.to_string(), false) {
                    Ok(()) => r.ok(format!("{}=min_hw {}", p.name, p.min_hw)),
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => r.skip(path),
                    Err(e) => r.fail(format!("{}=min_hw {}: {e}", p.name, p.min_hw)),
                }
            };
            let write_max = |r: &mut ApplyReport| {
                if p.max_hw == 0 { return; }
                let path = p.rel("scaling_max_freq");
                match self.s.write(&path, &p.max_hw.to_string(), false) {
                    Ok(()) => r.ok(format!("{}=max_hw {}", p.name, p.max_hw)),
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => r.skip(path),
                    Err(e) => r.fail(format!("{}=max_hw {}: {e}", p.name, p.max_hw)),
                }
            };
            if min_first { write_min(&mut r); write_max(&mut r); }
            else { write_max(&mut r); write_min(&mut r); }
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
