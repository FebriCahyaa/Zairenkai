// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Runtime topology and kernel/provider discovery.
//!
//! Detection is capability-first: kernel flavor and SoC family are evidence,
//! not permission to write. A path is usable only when it actually exists and
//! its interface can be validated at runtime.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::nodes::Sysroot;
use crate::platform::{scan_thermal, GpuProvider, KernelFlavor, PlatformIdentity, ThermalZoneInfo};

#[derive(Debug, Clone)]
pub struct Policy {
    pub name: String,
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

#[derive(Debug, Clone)]
pub struct GpuNode {
    pub provider: GpuProvider,
    pub devfreq: String,
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
    pub kernel_flavor: KernelFlavor,
    pub kernel_generation: String,
    pub cgroup_v2: bool,
    pub has_msm_perf: bool,
    pub policies: Vec<Policy>,
    pub gpu: Option<GpuNode>,
    pub thermal_zones: Vec<ThermalZoneInfo>,
    pub top_app_uclamp: Option<String>,
    pub stune_top: Option<String>,
    pub cpu_boost_dir: Option<String>,
    pub identity: PlatformIdentity,
}

impl Topology {
    pub fn detect(s: &Sysroot) -> Self {
        let release = s.read("/proc/sys/kernel/osrelease").unwrap_or_default();
        let identity = PlatformIdentity::detect(s);
        let has_msm_perf = s.exists("/sys/module/msm_performance/parameters/cpu_max_freq");
        let cgroup_v2 = s.exists("/sys/fs/cgroup/cgroup.controllers");
        let policies = Self::detect_policies(s);
        let gpu = Self::detect_gpu(s);
        let thermal_zones = scan_thermal(s, identity.vendor);
        let top_app_uclamp = s
            .first_existing(&[
                "/dev/cpuctl/top-app/cpu.uclamp.min",
                "/sys/fs/cgroup/cpu/top-app/cpu.uclamp.min",
                "/sys/fs/cgroup/top-app/cpu.uclamp.min",
            ])
            .and_then(|p| p.rsplit_once('/').map(|(d, _)| d.to_string()));
        let stune_top = s
            .exists("/dev/stune/top-app/schedtune.boost")
            .then_some("/dev/stune/top-app".to_string());
        let cpu_boost_dir = s
            .first_existing(&[
                "/sys/devices/system/cpu/cpu_boost/parameters/input_boost_freq",
                "/sys/module/cpu_boost/parameters/input_boost_freq",
            ])
            .and_then(|p| p.rsplit_once('/').map(|(d, _)| d.to_string()));

        let flavor = Self::detect_flavor(&release, cgroup_v2, top_app_uclamp.is_some(), has_msm_perf);
        let gki = flavor == KernelFlavor::Gki;
        let kernel_generation = kernel_generation(&release);

        Self {
            release,
            gki,
            kernel_flavor: flavor,
            kernel_generation,
            cgroup_v2,
            has_msm_perf,
            policies,
            gpu,
            thermal_zones,
            top_app_uclamp,
            stune_top,
            cpu_boost_dir,
            identity,
        }
    }

    pub fn flavor_kind(&self) -> KernelFlavor {
        self.kernel_flavor
    }

    pub fn flavor(&self) -> &'static str {
        self.kernel_flavor.as_str()
    }

    fn detect_flavor(release: &str, cgroup_v2: bool, has_uclamp_cg: bool, has_msm_perf: bool) -> KernelFlavor {
        if has_gki_release_format(release) {
            return KernelFlavor::Gki;
        }
        if release.is_empty() {
            return KernelFlavor::Unknown;
        }
        // A heuristic may identify vendor kernels using modern GKI-like
        // primitives, but it must never label them GKI without an Android/KMI
        // release signature.
        if let Some((maj, min)) = parse_kver(release) {
            if (maj > 5 || (maj == 5 && min >= 10)) && cgroup_v2 && has_uclamp_cg && !has_msm_perf {
                return KernelFlavor::Unknown;
            }
        }
        KernelFlavor::NonGki
    }

    fn detect_policies(s: &Sysroot) -> Vec<Policy> {
        let mut out = Vec::new();
        let mut names: Vec<String> = s
            .list_dir("/sys/devices/system/cpu/cpufreq")
            .into_iter()
            .filter(|n| {
                n.strip_prefix("policy")
                    .map(|v| !v.is_empty() && v.bytes().all(|b| b.is_ascii_digit()))
                    .unwrap_or(false)
            })
            .collect();
        names.sort_by_key(|n| n.trim_start_matches("policy").parse::<u32>().unwrap_or(u32::MAX));

        for name in names {
            let base = format!("/sys/devices/system/cpu/cpufreq/{name}");
            let min_hw = s.read_u64(&format!("{base}/cpuinfo_min_freq")).unwrap_or(0);
            let max_hw = s.read_u64(&format!("{base}/cpuinfo_max_freq")).unwrap_or(0);
            let mut avail = s.read_u64_list(&format!("{base}/scaling_available_frequencies"));
            if avail.is_empty() {
                avail = [min_hw, max_hw].into_iter().filter(|v| *v > 0).collect();
                avail.sort_unstable();
                avail.dedup();
            }
            if max_hw > 0 && min_hw > max_hw {
                continue;
            }
            out.push(Policy { name, avail, min_hw, max_hw });
        }
        out
    }

    fn detect_gpu(s: &Sysroot) -> Option<GpuNode> {
        let kgsl = "/sys/class/kgsl/kgsl-3d0/devfreq";
        if s.exists(&format!("{kgsl}/max_freq")) {
            let avail = s.read_u64_list(&format!("{kgsl}/available_frequencies"));
            return Some(GpuNode { provider: GpuProvider::Kgsl, devfreq: kgsl.to_string(), avail });
        }

        let mut candidates = Vec::new();
        for d in s.list_dir("/sys/class/devfreq") {
            let low = d.to_ascii_lowercase();
            let provider = if low.contains("mali") {
                GpuProvider::Mali
            } else if low.contains("pvr") || low.contains("powervr") {
                GpuProvider::PowerVr
            } else if low.contains("gpu") {
                GpuProvider::GenericDevfreq
            } else {
                continue;
            };
            let devfreq = format!("/sys/class/devfreq/{d}");
            if !s.exists(&format!("{devfreq}/max_freq")) {
                continue;
            }
            let avail = s.read_u64_list(&format!("{devfreq}/available_frequencies"));
            candidates.push(GpuNode { provider, devfreq, avail });
        }
        candidates.sort_by_key(|g| match g.provider {
            GpuProvider::Mali => 0,
            GpuProvider::PowerVr => 1,
            GpuProvider::GenericDevfreq => 2,
            GpuProvider::Kgsl => 3,
        });
        candidates.into_iter().next()
    }
}

fn has_gki_release_format(release: &str) -> bool {
    let mut parts = release.split('-');
    let version = parts.next().unwrap_or_default();
    let android = parts.next().unwrap_or_default();
    let kmi = parts.next().unwrap_or_default();
    let mut nums = version.split('.');
    let version_ok = [nums.next(), nums.next(), nums.next()]
        .into_iter()
        .all(|v| v.map(|x| !x.is_empty() && x.bytes().all(|b| b.is_ascii_digit())).unwrap_or(false))
        && nums.next().is_none();
    let android_num = android.strip_prefix("android").unwrap_or_default();
    let android_ok = !android_num.is_empty() && android_num.bytes().all(|b| b.is_ascii_digit());
    let kmi_ok = !kmi.is_empty() && kmi.bytes().all(|b| b.is_ascii_digit());
    version_ok && android_ok && kmi_ok
}

fn parse_kver(release: &str) -> Option<(u32, u32)> {
    let mut it = release.split(|c: char| c == '.' || c == '-');
    Some((it.next()?.parse().ok()?, it.next()?.parse().ok()?))
}

fn kernel_generation(release: &str) -> String {
    let Some((major, minor)) = parse_kver(release) else { return "unknown".into(); };
    match (major, minor) {
        (4, 14) => "4.14".into(),
        (4, 19) => "4.19".into(),
        (5, 4) => "5.4".into(),
        (5, 10) => "5.10".into(),
        (5, 15) => "5.15".into(),
        (6, 1) => "6.1".into(),
        (6, 6) => "6.6".into(),
        (6, 12) => "6.12".into(),
        (6, 18) => "6.18".into(),
        _ => format!("{major}.{minor}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::path::PathBuf;

    fn root() -> PathBuf {
        let p = std::env::temp_dir().join(format!("zairenkai-topo-{}", std::process::id()));
        let _ = fs::remove_dir_all(&p);
        fs::create_dir_all(&p).unwrap();
        p
    }
    fn w(r: &PathBuf, path: &str, value: &str) {
        let p = r.join(path.trim_start_matches('/'));
        fs::create_dir_all(p.parent().unwrap()).unwrap();
        fs::write(p, value).unwrap();
    }

    #[test]
    fn gki_signature_is_strict() {
        assert!(has_gki_release_format("5.15.74-android13-8-00001"));
        assert!(has_gki_release_format("6.18.32-android17-8-00001"));
        assert!(!has_gki_release_format("5.15.74-androidish-8-00001"));
        assert!(!has_gki_release_format("5.15-android13-8"));
    }

    #[test]
    fn vendor_kernel_is_not_promoted_to_gki_by_heuristics() {
        assert_eq!(Topology::detect_flavor("6.1.99-oem", true, true, false), KernelFlavor::Unknown);
        assert_eq!(Topology::detect_flavor("4.19.325-vendor", false, false, true), KernelFlavor::NonGki);
    }

    #[test]
    fn detects_generic_devfreq_and_thermal_inventory() {
        let r = root();
        w(&r, "/proc/sys/kernel/osrelease", "6.6.1-oem");
        w(&r, "/dev/props/ro.board.platform", "mt6893");
        w(&r, "/sys/class/devfreq/1-gpu/max_freq", "1000000");
        w(&r, "/sys/class/devfreq/1-gpu/available_frequencies", "500000 1000000");
        w(&r, "/sys/class/thermal/thermal_zone0/type", "mtktscpu");
        w(&r, "/sys/class/thermal/thermal_zone0/temp", "42000");
        let t = Topology::detect(&Sysroot::new(&r));
        assert_eq!(t.identity.vendor.as_str(), "mediatek");
        assert_eq!(t.gpu.as_ref().unwrap().provider, GpuProvider::GenericDevfreq);
        assert_eq!(t.thermal_zones.len(), 1);
        assert_eq!(t.thermal_zones[0].provider, crate::platform::ThermalProvider::MediaTek);
        let _ = fs::remove_dir_all(r);
    }
}
