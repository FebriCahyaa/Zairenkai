// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Runtime topology + kernel-flavor detection. Decides GKI vs non-GKI, maps the
//! cpufreq policies and their OPP tables, locates the GPU devfreq, and finds the
//! right boost surfaces (uclamp cgroup vs schedtune, cpu_boost path variant).
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::nodes::Sysroot;

#[derive(Debug, Clone)]
pub struct Policy {
    /// e.g. "policy0"
    pub name: String,
    /// available OPPs (kHz), ascending; falls back to [min_hw, max_hw].
    pub avail: Vec<u64>,
    pub min_hw: u64,
    pub max_hw: u64,
}

impl Policy {
    fn base(&self) -> String {
        format!("/sys/devices/system/cpu/cpufreq/{}", self.name)
    }
    pub fn rel(&self, leaf: &str) -> String {
        format!("{}/{}", self.base(), leaf)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GpuKind {
    Kgsl,
    Mali,
}

#[derive(Debug, Clone)]
pub struct GpuNode {
    pub kind: GpuKind,
    pub devfreq: String, // directory, relative to root
    pub avail: Vec<u64>,
}

impl GpuNode {
    pub fn rel(&self, leaf: &str) -> String {
        format!("{}/{}", self.devfreq, leaf)
    }
}

#[derive(Debug, Clone)]
pub struct Topology {
    pub release: String,
    pub gki: bool,
    pub cgroup_v2: bool,
    pub has_msm_perf: bool,
    pub policies: Vec<Policy>,
    pub gpu: Option<GpuNode>,
    pub top_app_uclamp: Option<String>, // dir holding cpu.uclamp.{min,max}
    pub stune_top: Option<String>,      // dir holding schedtune.boost
    pub cpu_boost_dir: Option<String>,  // dir holding input_boost_* params
}

impl Topology {
    pub fn detect(s: &Sysroot) -> Topology {
        let release = s.read("/proc/sys/kernel/osrelease").unwrap_or_default();
        let has_msm_perf = s.exists("/sys/module/msm_performance/parameters/cpu_max_freq");
        let cgroup_v2 = s.exists("/sys/fs/cgroup/cgroup.controllers");

        let policies = Self::detect_policies(s);
        let gpu = Self::detect_gpu(s);

        let top_app_uclamp = s
            .first_existing(&[
                "/dev/cpuctl/top-app/cpu.uclamp.min",
                "/sys/fs/cgroup/cpu/top-app/cpu.uclamp.min",
            ])
            .map(|p| p.rsplit_once('/').map(|(d, _)| d.to_string()).unwrap_or(p));

        let stune_top = if s.exists("/dev/stune/top-app/schedtune.boost") {
            Some("/dev/stune/top-app".to_string())
        } else {
            None
        };

        let cpu_boost_dir = s.first_existing(&[
            "/sys/devices/system/cpu/cpu_boost/parameters/input_boost_freq",
            "/sys/module/cpu_boost/parameters/input_boost_freq",
        ]);
        let cpu_boost_dir = cpu_boost_dir.map(|p| p.rsplit_once('/').map(|(d, _)| d.to_string()).unwrap_or(p));

        let gki = Self::is_gki(&release, has_msm_perf, cgroup_v2, top_app_uclamp.is_some());

        Topology { release, gki, cgroup_v2, has_msm_perf, policies, gpu, top_app_uclamp, stune_top, cpu_boost_dir }
    }

    fn is_gki(release: &str, has_msm_perf: bool, cgroup_v2: bool, has_uclamp_cg: bool) -> bool {
        // Android GKI releases carry an explicit Android/KMI generation in
        // the release string (for example 5.15.x-android13-*-...). A loose
        // substring check would misclassify vendor releases such as
        // "5.15.x-androidish-oem".
        if has_gki_release_format(release) {
            return true;
        }
        // Mainline >= 5.10 with unified cgroup + uclamp cgroup and no vendor
        // msm_performance is the GKI signature.
        if let Some((maj, min)) = parse_kver(release) {
            if (maj > 5 || (maj == 5 && min >= 10)) && cgroup_v2 && has_uclamp_cg && !has_msm_perf {
                return true;
            }
        }
        false
    }

    fn detect_policies(s: &Sysroot) -> Vec<Policy> {
        let mut out = Vec::new();
        let mut names: Vec<String> = s
            .list_dir("/sys/devices/system/cpu/cpufreq")
            .into_iter()
            .filter(|n| n.starts_with("policy"))
            .collect();
        // numeric order (policy0, policy2, ...)
        names.sort_by_key(|n| n.trim_start_matches("policy").parse::<u32>().unwrap_or(0));
        for name in names {
            let base = format!("/sys/devices/system/cpu/cpufreq/{name}");
            let min_hw = s.read_u64(&format!("{base}/cpuinfo_min_freq")).unwrap_or(0);
            let max_hw = s.read_u64(&format!("{base}/cpuinfo_max_freq")).unwrap_or(0);
            let mut avail = s.read_u64_list(&format!("{base}/scaling_available_frequencies"));
            if avail.is_empty() {
                avail = [min_hw, max_hw].into_iter().filter(|&v| v > 0).collect();
                avail.sort_unstable();
                avail.dedup();
            }
            out.push(Policy { name, avail, min_hw, max_hw });
        }
        out
    }

    fn detect_gpu(s: &Sysroot) -> Option<GpuNode> {
        // Qualcomm KGSL (Adreno)
        let kgsl = "/sys/class/kgsl/kgsl-3d0/devfreq";
        if s.exists(&format!("{kgsl}/max_freq")) {
            let avail = s.read_u64_list(&format!("{kgsl}/available_frequencies"));
            return Some(GpuNode { kind: GpuKind::Kgsl, devfreq: kgsl.to_string(), avail });
        }
        // Mali under /sys/class/devfreq/*gpu* or *mali*
        for d in s.list_dir("/sys/class/devfreq") {
            let low = d.to_ascii_lowercase();
            if low.contains("gpu") || low.contains("mali") {
                let devfreq = format!("/sys/class/devfreq/{d}");
                if s.exists(&format!("{devfreq}/max_freq")) {
                    let avail = s.read_u64_list(&format!("{devfreq}/available_frequencies"));
                    return Some(GpuNode { kind: GpuKind::Mali, devfreq, avail });
                }
            }
        }
        None
    }

    pub fn flavor(&self) -> &'static str {
        if self.gki { "GKI" } else { "non-GKI" }
    }
}

fn has_gki_release_format(release: &str) -> bool {
    let mut parts = release.split('-');
    let version = parts.next().unwrap_or_default();
    let android = parts.next().unwrap_or_default();
    let kmi = parts.next().unwrap_or_default();

    let mut nums = version.split('.');
    let major_ok = nums.next().map(|v| !v.is_empty() && v.chars().all(|c| c.is_ascii_digit())).unwrap_or(false);
    let minor_ok = nums.next().map(|v| !v.is_empty() && v.chars().all(|c| c.is_ascii_digit())).unwrap_or(false);
    let patch_ok = nums.next().map(|v| !v.is_empty() && v.chars().all(|c| c.is_ascii_digit())).unwrap_or(false);
    let no_extra_version = nums.next().is_none();
    let android_num = android.strip_prefix("android").unwrap_or_default();
    let android_ok = !android_num.is_empty() && android_num.chars().all(|c| c.is_ascii_digit());
    let kmi_ok = !kmi.is_empty() && kmi.chars().all(|c| c.is_ascii_digit());

    major_ok && minor_ok && patch_ok && no_extra_version && android_ok && kmi_ok
}

fn parse_kver(release: &str) -> Option<(u32, u32)> {
    let mut it = release.split(|c: char| c == '.' || c == '-');
    let maj: u32 = it.next()?.parse().ok()?;
    let min: u32 = it.next()?.parse().ok()?;
    Some((maj, min))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::path::PathBuf;

    fn mkroot() -> PathBuf {
        let d = std::env::temp_dir().join(format!("zperf-topo-{}-{}", std::process::id(), rand_tag()));
        let _ = fs::remove_dir_all(&d);
        d
    }
    fn rand_tag() -> u64 {
        use std::time::{SystemTime, UNIX_EPOCH};
        SystemTime::now().duration_since(UNIX_EPOCH).unwrap().subsec_nanos() as u64
    }
    fn w(root: &PathBuf, rel: &str, val: &str) {
        let p = root.join(rel.trim_start_matches('/'));
        fs::create_dir_all(p.parent().unwrap()).unwrap();
        fs::write(p, val).unwrap();
    }

    #[test]
    fn detect_nongki_sdm660_like() {
        let root = mkroot();
        w(&root, "/proc/sys/kernel/osrelease", "4.19.157-lavender");
        w(&root, "/sys/module/msm_performance/parameters/cpu_max_freq", "0:0");
        w(&root, "/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_min_freq", "300000");
        w(&root, "/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq", "1843200");
        w(&root, "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies", "300000 1017600 1843200");
        w(&root, "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq", "650000000");
        w(&root, "/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies", "650000000 465000000 160000000");
        w(&root, "/dev/stune/top-app/schedtune.boost", "0");

        let s = Sysroot::new(&root);
        let t = Topology::detect(&s);
        assert!(!t.gki, "4.19 lavender w/ msm_performance must be non-GKI");
        assert!(t.has_msm_perf);
        assert_eq!(t.policies.len(), 1);
        assert_eq!(t.policies[0].avail, vec![300000, 1017600, 1843200]);
        assert_eq!(t.gpu.as_ref().unwrap().kind, GpuKind::Kgsl);
        assert_eq!(t.gpu.as_ref().unwrap().avail, vec![160000000, 465000000, 650000000]);
        assert!(t.stune_top.is_some());
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn gki_release_format_is_strict() {
        assert!(has_gki_release_format("5.15.74-android13-8-00001"));
        assert!(!has_gki_release_format("5.15.74-androidish-8-00001"));
        assert!(!has_gki_release_format("5.15-android13-8"));
    }

    #[test]
    fn detect_gki_like() {
        let root = mkroot();
        w(&root, "/proc/sys/kernel/osrelease", "5.15.74-android13-8-00001");
        w(&root, "/sys/fs/cgroup/cgroup.controllers", "cpu cpuset");
        w(&root, "/dev/cpuctl/top-app/cpu.uclamp.min", "0");
        w(&root, "/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_min_freq", "300000");
        w(&root, "/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq", "1800000");
        let s = Sysroot::new(&root);
        let t = Topology::detect(&s);
        assert!(t.gki, "android13 release must be GKI");
        assert!(t.cgroup_v2);
        assert!(t.top_app_uclamp.is_some());
        let _ = fs::remove_dir_all(&root);
    }
}
