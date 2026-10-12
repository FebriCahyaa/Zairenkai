// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! The apply engine. Turns a device-agnostic [Mode] into concrete node writes
//! for the detected device: per-policy cpufreq caps, governor, top-app boost
//! (backend selected by the runtime capability/family resolver), input boost,
//! GPU devfreq, VM and I/O
//! knobs. Every write is probe-gated; transaction locking is owned by StateStore.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::backends::{BackendPlan, BoostBackend};
use crate::config::{self, Cpu, Gpu, Io, Mem, Mode};
use crate::nodes::Sysroot;
use crate::topo::Topology;

#[derive(Default, Debug)]
pub struct ApplyReport {
    /// Written and read back with the requested value.
    pub applied: Vec<String>,
    /// Not attempted: the node or backend does not exist on this device.
    pub skipped: Vec<String>,
    /// Written, but the kernel reports a different effective value (QoS,
    /// thermal or another floor aggregates frequency limits). Not a failure.
    pub constrained: Vec<String>,
    /// Written; the node's format cannot be compared reliably.
    pub unverified: Vec<String>,
    /// Rejected by the kernel or not observable after write; the caller
    /// rolls the transaction back.
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

    /// True when at least one write reached the kernel.
    pub fn wrote_anything(&self) -> bool {
        !self.applied.is_empty() || !self.constrained.is_empty() || !self.unverified.is_empty()
    }
}

/// How the value read back after a write is compared with the request.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Check {
    /// Plain value node (sysctl, governor, integer knob): must match exactly.
    Exact,
    /// sysfs choice list: the bracketed active entry must be the request.
    Choice,
    /// cgroup uclamp percent, printed as "20.00" or "max".
    Percent,
    /// Frequency limit. The kernel aggregates QoS, thermal and boost
    /// requests, so a different effective value is constrained, not failed.
    Limit,
    /// Vendor multi-value node whose read format is not reliably comparable.
    Unverified,
}

#[derive(Debug, PartialEq, Eq)]
enum Observation {
    Matches,
    Constrained(String),
    Unverified,
    Mismatch(String),
}

fn parse_percent(text: &str) -> Option<f64> {
    let text = text.trim();
    if text == "max" {
        return Some(100.0);
    }
    text.parse::<f64>().ok()
}

fn observe(check: Check, expected: &str, observed: Option<&str>) -> Observation {
    let Some(observed) = observed.map(str::trim) else {
        return match check {
            Check::Limit | Check::Unverified => Observation::Unverified,
            _ => Observation::Mismatch("<unreadable>".into()),
        };
    };
    let expected = expected.trim();
    match check {
        Check::Exact if observed == expected => Observation::Matches,
        Check::Choice if crate::tweak::active_choice(observed).as_deref() == Some(expected) => {
            Observation::Matches
        }
        Check::Percent => match (parse_percent(expected), parse_percent(observed)) {
            (Some(want), Some(got)) if (want - got).abs() < 0.01 => Observation::Matches,
            _ => Observation::Mismatch(observed.to_string()),
        },
        Check::Limit if observed == expected => Observation::Matches,
        Check::Limit => Observation::Constrained(observed.to_string()),
        Check::Unverified => Observation::Unverified,
        _ => Observation::Mismatch(observed.to_string()),
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum RangeOrder {
    MinThenMax,
    MaxThenMin,
}

fn choose_range_order(
    current_min: Option<u64>,
    current_max: Option<u64>,
    target_min: u64,
    target_max: u64,
) -> Result<RangeOrder, String> {
    if target_min > target_max {
        return Err(format!(
            "target min {target_min} exceeds target max {target_max}"
        ));
    }

    match (current_min, current_max) {
        (Some(cur_min), Some(cur_max)) => {
            if cur_min > cur_max {
                return Err(format!(
                    "current min {cur_min} exceeds current max {cur_max}"
                ));
            }
            if target_max < cur_min {
                Ok(RangeOrder::MinThenMax)
            } else if target_min > cur_max {
                Ok(RangeOrder::MaxThenMin)
            } else {
                // Both writes are safe from the current interval; expand the
                // upper bound first by convention.
                Ok(RangeOrder::MaxThenMin)
            }
        }
        (Some(cur_min), None) => {
            if target_min <= cur_min {
                // Lowering min first cannot violate the unknown old max because
                // old max must be >= old min.
                Ok(RangeOrder::MinThenMax)
            } else {
                Err("current max is unreadable; refusing to raise min before proving the intermediate range is safe".into())
            }
        }
        (None, Some(cur_max)) => {
            if target_max >= cur_max {
                Ok(RangeOrder::MaxThenMin)
            } else if target_min <= cur_max {
                Ok(RangeOrder::MinThenMax)
            } else {
                Err("current min is unreadable; refusing to lower max before proving the intermediate range is safe".into())
            }
        }
        (None, None) => Err(
            "current min/max are unreadable; refusing a potentially invalid intermediate range"
                .into(),
        ),
    }
}

pub struct Engine<'a> {
    s: &'a Sysroot,
    t: &'a Topology,
    plan: &'a BackendPlan,
}

impl<'a> Engine<'a> {
    pub fn new(s: &'a Sysroot, t: &'a Topology, plan: &'a BackendPlan) -> Self {
        Engine { s, t, plan }
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
            for leaf in ["scaling_min_freq", "scaling_max_freq", "scaling_governor"] {
                out.push(p.rel(leaf));
            }
        }
        if self.t.has_msm_perf {
            out.push("/sys/module/msm_performance/parameters/cpu_min_freq".into());
            out.push("/sys/module/msm_performance/parameters/cpu_max_freq".into());
        }
        if let Some(dir) = &self.t.top_app_uclamp {
            out.push(format!("{dir}/cpu.uclamp.min"));
            out.push(format!("{dir}/cpu.uclamp.max"));
        }
        if let Some(dir) = &self.t.stune_top {
            out.push(format!("{dir}/schedtune.boost"));
        }
        if let Some(dir) = &self.t.cpu_boost_dir {
            out.push(format!("{dir}/input_boost_ms"));
            out.push(format!("{dir}/input_boost_freq"));
        }
        if let Some(g) = &self.t.gpu {
            for leaf in ["min_freq", "max_freq", "governor"] {
                out.push(g.rel(leaf));
            }
        }
        for dev in crate::storage::safe_block_devices(self.s) {
            let q = format!("/sys/block/{dev}/queue");
            for leaf in ["scheduler", "read_ahead_kb", "nr_requests"] {
                out.push(format!("{q}/{leaf}"));
            }
        }
        out.sort();
        out.dedup();
        out
    }

    /// Write one node and classify the value the kernel reports afterwards.
    /// Returns true when the write itself succeeded (even if it was then
    /// reported as constrained or failed verification).
    fn write_verified(
        &self,
        r: &mut ApplyReport,
        node: &str,
        value: &str,
        label: String,
        check: Check,
    ) -> bool {
        match self.s.write(node, value) {
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                r.skip(node.to_string());
                false
            }
            Err(e) => {
                r.fail(format!("{label}: {e}"));
                false
            }
            Ok(()) => {
                match observe(check, value, self.s.read(node).as_deref()) {
                    Observation::Matches => r.ok(label),
                    Observation::Constrained(seen) => r
                        .constrained
                        .push(format!("{label} (kernel reports {seen})")),
                    Observation::Unverified => r.unverified.push(label),
                    Observation::Mismatch(seen) => r.fail(format!(
                        "{label}: not observable after write (kernel reports '{seen}')"
                    )),
                }
                true
            }
        }
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
                .or_else(|| {
                    c.max_perf_pct
                        .and_then(|pct| config::resolve_pct(pct, &p.avail, false))
                });
            let minf = c
                .min_freq
                .as_ref()
                .and_then(|f| config::resolve_freq(f, &p.avail, true))
                .or_else(|| {
                    c.min_perf_pct
                        .and_then(|pct| config::resolve_pct(pct, &p.avail, true))
                });

            // A contradictory resolved range is a profile error, not a value
            // to silently rewrite. Failing here lets the outer transaction
            // roll back every write performed by this apply operation.
            let (minf, maxf) = match (minf, maxf) {
                (Some(mi), Some(ma)) if mi > ma => {
                    r.fail(format!(
                        "{} frequency targets invalid: min {mi} > max {ma}",
                        p.name
                    ));
                    continue;
                }
                other => other,
            };

            let current_min = self.s.read_u64(&p.rel("scaling_min_freq"));
            let current_max = self.s.read_u64(&p.rel("scaling_max_freq"));

            let mut write_limit = |leaf: &str, value: u64, label: String| {
                self.write_verified(r, &p.rel(leaf), &value.to_string(), label, Check::Limit);
            };

            match (minf, maxf) {
                (Some(mi), Some(ma)) => {
                    match choose_range_order(current_min, current_max, mi, ma) {
                        Ok(RangeOrder::MinThenMax) => {
                            write_limit("scaling_min_freq", mi, format!("{}=min {mi}", p.name));
                            write_limit("scaling_max_freq", ma, format!("{}=max {ma}", p.name));
                        }
                        Ok(RangeOrder::MaxThenMin) => {
                            write_limit("scaling_max_freq", ma, format!("{}=max {ma}", p.name));
                            write_limit("scaling_min_freq", mi, format!("{}=min {mi}", p.name));
                        }
                        Err(reason) => r.fail(format!("{} frequency range: {reason}", p.name)),
                    }
                }
                (Some(mi), None) => {
                    write_limit("scaling_min_freq", mi, format!("{}=min {mi}", p.name))
                }
                (None, Some(ma)) => {
                    write_limit("scaling_max_freq", ma, format!("{}=max {ma}", p.name))
                }
                (None, None) => {}
            }
            if !c.governor.is_empty() {
                self.apply_governor(p, &c.governor, r);
            }
        }

        // msm_performance mirror (non-GKI Qualcomm) — "cpuIdx:freq ..." form.
        if self.t.has_msm_perf {
            if let Some(ma) = self.perf_param_str(c, false) {
                self.write_verified(
                    r,
                    "/sys/module/msm_performance/parameters/cpu_max_freq",
                    &ma,
                    format!("msm_performance.max={ma}"),
                    Check::Unverified,
                );
            }
            if let Some(mi) = self.perf_param_str(c, true) {
                self.write_verified(
                    r,
                    "/sys/module/msm_performance/parameters/cpu_min_freq",
                    &mi,
                    format!("msm_performance.min={mi}"),
                    Check::Unverified,
                );
            }
        }

        self.apply_boost(c, r);
        self.apply_input_boost(c, r);

        if let Some(b) = c.sched_boost {
            if self
                .s
                .write_first(
                    &["/proc/sys/walt/sched_boost", "/proc/sys/kernel/sched_boost"],
                    &b.to_string(),
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
                    .or_else(|| {
                        c.min_perf_pct
                            .and_then(|pct| config::resolve_pct(pct, &p.avail, true))
                    })
            } else {
                c.max_freq
                    .as_ref()
                    .and_then(|f| config::resolve_freq(f, &p.avail, false))
                    .or_else(|| {
                        c.max_perf_pct
                            .and_then(|pct| config::resolve_pct(pct, &p.avail, false))
                    })
            };
            if let Some(freq) = f {
                parts.push(format!("{idx}:{freq}"));
            }
        }
        if parts.is_empty() {
            None
        } else {
            Some(parts.join(" "))
        }
    }

    fn apply_governor(&self, p: &crate::topo::Policy, pref: &[String], r: &mut ApplyReport) {
        let avail = self
            .s
            .read(&p.rel("scaling_available_governors"))
            .unwrap_or_default();
        let have: Vec<&str> = avail.split_whitespace().collect();
        for g in pref {
            if !have.is_empty()
                && have.contains(&g.as_str())
                && self.write_verified(
                    r,
                    &p.rel("scaling_governor"),
                    g,
                    format!("{} gov={g}", p.name),
                    Check::Exact,
                )
            {
                return;
            }
        }
        r.skip(format!("{} governor (none of {:?})", p.name, pref));
    }

    fn apply_boost(&self, c: &Cpu, r: &mut ApplyReport) {
        match self.plan.boost {
            BoostBackend::Uclamp => {
                if let Some(dir) = &self.t.top_app_uclamp {
                    // The cgroup interface takes a percentage ("20" or
                    // "20.00", at most 100). The 0..1024 utilization scale
                    // belongs to sched_setattr/ZKFC task boosts only.
                    if let Some(min) = c.uclamp_min_pct {
                        let pct = min.min(100);
                        self.write_verified(
                            r,
                            &format!("{dir}/cpu.uclamp.min"),
                            &pct.to_string(),
                            format!("uclamp.min={pct}%"),
                            Check::Percent,
                        );
                    }
                    if let Some(max) = c.uclamp_max_pct {
                        let pct = max.min(100);
                        self.write_verified(
                            r,
                            &format!("{dir}/cpu.uclamp.max"),
                            &pct.to_string(),
                            format!("uclamp.max={pct}%"),
                            Check::Percent,
                        );
                    }
                } else {
                    r.skip("uclamp backend unavailable".into());
                }
            }
            BoostBackend::SchedTune => {
                if let (Some(dir), Some(min)) = (&self.t.stune_top, c.uclamp_min_pct) {
                    self.write_verified(
                        r,
                        &format!("{dir}/schedtune.boost"),
                        &min.to_string(),
                        format!("schedtune.boost={min}"),
                        Check::Exact,
                    );
                } else {
                    r.skip("schedtune backend unavailable".into());
                }
            }
            BoostBackend::None => {
                if c.uclamp_min_pct.is_some() || c.uclamp_max_pct.is_some() {
                    r.skip("no compatible boost backend".into());
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
            self.write_verified(
                r,
                &format!("{dir}/input_boost_ms"),
                &ms.to_string(),
                format!("input_boost_ms={ms}"),
                Check::Exact,
            );
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
                self.write_verified(
                    r,
                    &format!("{dir}/input_boost_freq"),
                    &parts.join(" "),
                    "input_boost_freq".into(),
                    Check::Unverified,
                );
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
                .or_else(|| {
                    g.max_perf_pct
                        .and_then(|pct| config::resolve_pct(pct, &node.avail, false))
                });
            let min_target = g
                .min_freq
                .as_ref()
                .and_then(|f| config::resolve_freq(f, &node.avail, true))
                .or_else(|| {
                    g.min_perf_pct
                        .and_then(|pct| config::resolve_pct(pct, &node.avail, true))
                });
            let current_min = self.s.read_u64(&node.rel("min_freq"));
            let current_max = self.s.read_u64(&node.rel("max_freq"));
            let write_min = |value: u64, r: &mut ApplyReport| {
                let label = format!("gpu min={value}");
                self.write_verified(
                    r,
                    &node.rel("min_freq"),
                    &value.to_string(),
                    label,
                    Check::Limit,
                );
            };
            let write_max = |value: u64, r: &mut ApplyReport| {
                let label = format!("gpu max={value}");
                self.write_verified(
                    r,
                    &node.rel("max_freq"),
                    &value.to_string(),
                    label,
                    Check::Limit,
                );
            };
            match (min_target, max_target) {
                (Some(mi), Some(ma)) => {
                    match choose_range_order(current_min, current_max, mi, ma) {
                        Ok(RangeOrder::MinThenMax) => {
                            write_min(mi, r);
                            write_max(ma, r);
                        }
                        Ok(RangeOrder::MaxThenMin) => {
                            write_max(ma, r);
                            write_min(mi, r);
                        }
                        Err(reason) => r.fail(format!("gpu frequency range: {reason}")),
                    }
                }
                (Some(mi), None) => write_min(mi, r),
                (None, Some(ma)) => write_max(ma, r),
                (None, None) => {}
            }
        }
        for gov in &g.governor {
            let avail = self
                .s
                .read(&node.rel("available_governors"))
                .unwrap_or_default();
            let have: Vec<&str> = avail.split_whitespace().collect();
            if !have.is_empty()
                && have.contains(&gov.as_str())
                && self.write_verified(
                    r,
                    &node.rel("governor"),
                    gov,
                    format!("gpu gov={gov}"),
                    Check::Exact,
                )
            {
                break;
            }
        }
    }

    fn set_proc_vm(&self, leaf: &str, val: Option<String>, r: &mut ApplyReport) {
        if let Some(v) = val {
            let node = format!("/proc/sys/vm/{leaf}");
            self.write_verified(r, &node, &v, format!("vm.{leaf}={v}"), Check::Exact);
        }
    }

    fn apply_mem(&self, m: &Mem, r: &mut ApplyReport) {
        let inv = crate::inventory::collect(self.s, self.t.identity.clone());
        let envelope = crate::memory::envelope(&inv);
        let swappiness = match (
            m.swappiness,
            envelope.recommended_swappiness_floor,
            envelope.pressure,
        ) {
            (
                Some(v),
                Some(floor),
                crate::memory::Pressure::Critical | crate::memory::Pressure::Elevated,
            ) if v < floor => {
                r.ok(format!("vm.swappiness safety-floor: {v} -> {floor}"));
                Some(floor)
            }
            (Some(v), _, _) => Some(v),
            (None, _, _) => None,
        };
        self.set_proc_vm("swappiness", swappiness.map(|v| v.to_string()), r);
        self.set_proc_vm(
            "vfs_cache_pressure",
            m.vfs_cache_pressure.map(|v| v.to_string()),
            r,
        );
        self.set_proc_vm(
            "watermark_scale_factor",
            m.watermark_scale_factor.map(|v| v.to_string()),
            r,
        );
        self.set_proc_vm("page-cluster", m.page_cluster.map(|v| v.to_string()), r);
        self.set_proc_vm(
            "extra_free_kbytes",
            m.extra_free_kbytes.map(|v| v.to_string()),
            r,
        );
    }

    fn apply_io(&self, io: &Io, r: &mut ApplyReport) {
        if io.scheduler.is_empty() && io.read_ahead_kb.is_none() && io.nr_requests.is_none() {
            return;
        }
        for dev in crate::storage::safe_block_devices(self.s) {
            let q = format!("/sys/block/{dev}/queue");
            if !self.s.exists(&format!("{q}/scheduler")) {
                continue;
            }
            if !io.scheduler.is_empty() {
                let avail = self.s.read(&format!("{q}/scheduler")).unwrap_or_default();
                // Token match: a substring test would accept "deadline"
                // against "mq-deadline".
                let offered = crate::tweak::choice_tokens(&avail);
                for sched in &io.scheduler {
                    if offered.iter().any(|o| o == sched)
                        && self.write_verified(
                            r,
                            &format!("{q}/scheduler"),
                            sched,
                            format!("{dev} iosched={sched}"),
                            Check::Choice,
                        )
                    {
                        break;
                    }
                }
            }
            if let Some(ra) = io.read_ahead_kb {
                // Stored in pages: the kernel rounds to the page size.
                self.write_verified(
                    r,
                    &format!("{q}/read_ahead_kb"),
                    &ra.to_string(),
                    format!("{dev} read_ahead_kb={ra}"),
                    Check::Limit,
                );
            }
            if let Some(nr) = io.nr_requests {
                // blk-mq raises values below its minimum queue depth.
                self.write_verified(
                    r,
                    &format!("{q}/nr_requests"),
                    &nr.to_string(),
                    format!("{dev} nr_requests={nr}"),
                    Check::Limit,
                );
            }
        }
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn range_order_moves_lower_bound_before_lowering_upper_bound() {
        assert_eq!(
            choose_range_order(Some(100), Some(1000), 50, 80),
            Ok(RangeOrder::MinThenMax)
        );
    }

    #[test]
    fn range_order_moves_upper_bound_before_raising_lower_bound() {
        assert_eq!(
            choose_range_order(Some(100), Some(1000), 1200, 1500),
            Ok(RangeOrder::MaxThenMin)
        );
    }

    #[test]
    fn range_order_rejects_unverifiable_intermediate_state() {
        assert!(choose_range_order(None, None, 500, 1000).is_err());
        assert!(choose_range_order(Some(500), None, 800, 1000).is_err());
    }

    use super::*;
    use crate::config::Profile;
    use std::fs;
    use std::path::PathBuf;

    fn mkroot() -> PathBuf {
        use std::time::{SystemTime, UNIX_EPOCH};
        let tag = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let d = std::env::temp_dir().join(format!("zperf-eng-{tag}"));
        let _ = fs::remove_dir_all(&d);
        d
    }
    fn w(root: &std::path::Path, rel: &str, val: &str) {
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
        w(
            &root,
            &format!("{base}/scaling_available_frequencies"),
            "300000 1017600 1843200",
        );
        w(
            &root,
            &format!("{base}/scaling_available_governors"),
            "schedutil performance powersave",
        );
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
        let family = crate::family::load(
            &std::path::PathBuf::from("/dev/null/zairenkai"),
            t.identity.vendor,
        );
        let plan = crate::backends::BackendPlan::resolve(&t.identity, &t, family.as_ref());
        let eng = Engine::new(&s, &t, &plan);
        let rep = eng.apply_mode(prof.mode("performance").unwrap());

        assert_eq!(
            s.read(&format!("{base}/scaling_max_freq")).as_deref(),
            Some("1843200")
        );
        // 50% of 1843200 = 921600 -> floor-snap up to 1017600
        assert_eq!(
            s.read(&format!("{base}/scaling_min_freq")).as_deref(),
            Some("1017600")
        );
        assert_eq!(
            s.read(&format!("{base}/scaling_governor")).as_deref(),
            Some("performance")
        );
        assert_eq!(
            s.read("/dev/stune/top-app/schedtune.boost").as_deref(),
            Some("30")
        );
        assert_eq!(s.read("/proc/sys/vm/swappiness").as_deref(), Some("60"));
        assert!(rep.applied.iter().any(|x| x.contains("gov=performance")));
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn observation_classifies_exact_choice_percent_limit_and_unverified() {
        assert_eq!(
            observe(Check::Exact, "60", Some("60\n")),
            Observation::Matches
        );
        assert_eq!(
            observe(Check::Exact, "60", Some("100")),
            Observation::Mismatch("100".into())
        );
        assert_eq!(
            observe(Check::Exact, "60", None),
            Observation::Mismatch("<unreadable>".into())
        );
        assert_eq!(
            observe(Check::Choice, "kyber", Some("mq-deadline [kyber] none")),
            Observation::Matches
        );
        assert!(matches!(
            observe(Check::Choice, "kyber", Some("[mq-deadline] kyber")),
            Observation::Mismatch(_)
        ));
        assert_eq!(
            observe(Check::Percent, "20", Some("20.00")),
            Observation::Matches
        );
        assert_eq!(
            observe(Check::Percent, "100", Some("max")),
            Observation::Matches
        );
        assert!(matches!(
            observe(Check::Percent, "20", Some("19.53")),
            Observation::Mismatch(_)
        ));
        // Frequency limits may be aggregated by QoS/thermal: constrained, not failed.
        assert_eq!(
            observe(Check::Limit, "1843200", Some("1017600")),
            Observation::Constrained("1017600".into())
        );
        assert_eq!(
            observe(Check::Limit, "1843200", None),
            Observation::Unverified
        );
        assert_eq!(
            observe(Check::Unverified, "0:1200000", Some("0:0 1:0")),
            Observation::Unverified
        );
    }

    #[test]
    fn gki_uclamp_cgroup_receives_a_percentage_and_iosched_matches_tokens() {
        let root = mkroot();
        w(
            &root,
            "/proc/sys/kernel/osrelease",
            "6.1.75-android14-11-g0",
        );
        let base = "/sys/devices/system/cpu/cpufreq/policy0";
        w(&root, &format!("{base}/cpuinfo_min_freq"), "300000");
        w(&root, &format!("{base}/cpuinfo_max_freq"), "2000000");
        w(
            &root,
            &format!("{base}/scaling_available_frequencies"),
            "300000 2000000",
        );
        w(&root, &format!("{base}/scaling_max_freq"), "2000000");
        w(&root, &format!("{base}/scaling_min_freq"), "300000");
        w(&root, "/dev/cpuctl/top-app/cpu.uclamp.min", "0.00");
        // "deadline" must not match "mq-deadline" by substring.
        w(
            &root,
            "/sys/block/sda/queue/scheduler",
            "[mq-deadline] kyber none",
        );
        w(&root, "/sys/block/sda/queue/rotational", "0");

        let s = Sysroot::new(&root);
        let t = Topology::detect(&s);
        let prof = Profile::parse(
            r#"
            [mode.fast.cpu]
            uclamp_min_pct = 40
            [mode.fast.io]
            scheduler = ["deadline", "kyber"]
            "#,
        )
        .unwrap();
        let family = crate::family::load(
            &std::path::PathBuf::from("/dev/null/zairenkai"),
            t.identity.vendor,
        );
        let plan = crate::backends::BackendPlan::resolve(&t.identity, &t, family.as_ref());
        let rep = Engine::new(&s, &t, &plan).apply_mode(prof.mode("fast").unwrap());

        // 40 %, not the 0..1024 utilization value 410 the cgroup would reject.
        assert_eq!(
            s.read("/dev/cpuctl/top-app/cpu.uclamp.min").as_deref(),
            Some("40")
        );
        assert!(rep.applied.iter().any(|x| x == "uclamp.min=40%"), "{rep:?}");
        assert!(
            rep.applied.iter().any(|x| x == "sda iosched=kyber"),
            "{rep:?}"
        );
        assert!(rep.failed.is_empty(), "{rep:?}");
        assert!(rep.wrote_anything());
        let _ = fs::remove_dir_all(&root);
    }
}
