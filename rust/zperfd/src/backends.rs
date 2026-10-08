// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Capability-first SoC/OEM backend abstraction.
//!
//! The backend selects an implementation family; runtime capability checks still
//! decide whether a particular surface may be touched. Vendor identity never
//! authorizes a write by itself.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::family::FamilyProfile;
use crate::platform::{GpuProvider, KernelFlavor, OemVendor, PlatformIdentity, SocVendor, ThermalProvider};
use crate::topo::Topology;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CpuPolicyBackend { Cpufreq }

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BoostBackend { Uclamp, SchedTune, None }

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GpuBackend { Kgsl, Devfreq(GpuProvider), None }

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VendorBackendKind { Qualcomm, MediaTek, Google, Xiaomi, Generic }

impl VendorBackendKind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Qualcomm => "qualcomm",
            Self::MediaTek => "mediatek",
            Self::Google => "google",
            Self::Xiaomi => "xiaomi",
            Self::Generic => "generic",
        }
    }

    /// Stable, vendor-neutral operation family. Concrete sysfs/service writes
    /// are still authorized by runtime topology/capability probes.
    pub fn operation_family(self) -> &'static str {
        match self {
            Self::Qualcomm => "qcom",
            Self::MediaTek => "mtk",
            Self::Google => "aosp",
            Self::Xiaomi => "xiaomi",
            Self::Generic => "generic",
        }
    }
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct BackendCapabilities {
    pub frame_timeline: bool,
    pub perfetto_frametimeline: bool,
    pub evdev_input: bool,
    pub cgroup_v2: bool,
    pub per_thread_cgroup: bool,
    pub cpuset: bool,
    pub uclamp: bool,
    pub gpu_devfreq: bool,
    pub thermal_standard: bool,
}

#[derive(Debug, Clone)]
pub struct BackendPlan {
    pub vendor: SocVendor,
    pub oem: OemVendor,
    pub backend: VendorBackendKind,
    pub kernel: KernelFlavor,
    pub cpu: Option<CpuPolicyBackend>,
    pub boost: BoostBackend,
    pub gpu: GpuBackend,
    pub thermal: Vec<ThermalProvider>,
    pub vendor_surfaces: Vec<&'static str>,
    pub family_profile: Option<String>,
    pub capabilities: BackendCapabilities,
}

impl BackendPlan {
    pub fn resolve(identity: &PlatformIdentity, topology: &Topology, family: Option<&FamilyProfile>) -> Self {
        let kernel = topology.flavor_kind();
        let boost = if topology.kernel_flavor == KernelFlavor::NonGki
            && family.map(|f| f.boost_prefers("schedtune")).unwrap_or(false)
            && topology.stune_top.is_some()
        { BoostBackend::SchedTune
        } else if topology.top_app_uclamp.is_some() { BoostBackend::Uclamp
        } else if topology.stune_top.is_some() { BoostBackend::SchedTune
        } else { BoostBackend::None };
        let gpu = match topology.gpu.as_ref() {
            Some(g) => match g.provider { GpuProvider::Kgsl => GpuBackend::Kgsl, ref p => GpuBackend::Devfreq(p.clone()) },
            None => GpuBackend::None,
        };
        let mut thermal = topology.thermal_zones.iter().map(|z| z.provider.clone()).filter(|p| *p != ThermalProvider::Unknown).collect::<Vec<_>>();
        if let Some(family) = family { thermal.sort_by_key(|p| family.provider_rank("thermal", p.as_str())); }
        else { thermal.sort_by_key(|p| p.as_str()); }
        thermal.dedup_by(|a,b| a.as_str() == b.as_str());

        let backend = match identity.oem {
            OemVendor::Xiaomi => VendorBackendKind::Xiaomi,
            OemVendor::Google => VendorBackendKind::Google,
            _ => match identity.vendor {
                SocVendor::Qualcomm => VendorBackendKind::Qualcomm,
                SocVendor::MediaTek => VendorBackendKind::MediaTek,
                SocVendor::GoogleTensor => VendorBackendKind::Google,
                _ => VendorBackendKind::Generic,
            },
        };
        let mut vendor_surfaces = Vec::new();
        match backend {
            VendorBackendKind::Qualcomm => {
                if topology.has_msm_perf { vendor_surfaces.push("msm_performance"); }
                if matches!(gpu, GpuBackend::Kgsl) { vendor_surfaces.push("kgsl"); }
            }
            VendorBackendKind::MediaTek => vendor_surfaces.push("mediatek-runtime");
            VendorBackendKind::Google => vendor_surfaces.push("aosp-runtime");
            VendorBackendKind::Xiaomi => vendor_surfaces.push("miui-runtime");
            VendorBackendKind::Generic => {}
        }
        let capabilities = BackendCapabilities {
            frame_timeline: !topology.policies.is_empty(),
            perfetto_frametimeline: true,
            evdev_input: true,
            cgroup_v2: topology.cgroup_v2,
            // Runtime cgroup discovery owns these two capabilities. Keeping them
            // false here prevents the vendor resolver from claiming delegated
            // cpuset control merely because cgroup-v2 exists.
            per_thread_cgroup: false,
            cpuset: false,
            uclamp: boost == BoostBackend::Uclamp,
            gpu_devfreq: !matches!(gpu, GpuBackend::None),
            thermal_standard: !topology.thermal_zones.is_empty(),
        };
        Self {
            vendor: identity.vendor,
            oem: identity.oem,
            backend,
            kernel,
            cpu: (!topology.policies.is_empty()).then_some(CpuPolicyBackend::Cpufreq),
            boost,
            gpu,
            thermal,
            vendor_surfaces,
            family_profile: family.map(|f| f.vendor.clone()),
            capabilities,
        }
    }

    pub fn conservative(&self) -> bool {
        self.kernel == KernelFlavor::Unknown || self.cpu.is_none() || self.gpu == GpuBackend::None && self.vendor == SocVendor::Unknown
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::family::{DiscoveryHints, ProviderOrder};
    use crate::platform::{OemVendor, PlatformIdentity, SocVendor};
    fn topology(flavor: KernelFlavor, uclamp: bool, stune: bool) -> Topology {
        Topology {
            release: "test".into(), gki: flavor == KernelFlavor::Gki, kernel_flavor: flavor, kernel_generation: "6.18".into(), cgroup_v2: uclamp,
            has_msm_perf: false, policies: vec![crate::topo::Policy { name:"policy0".into(), avail:vec![300000,1800000], min_hw:300000,max_hw:1800000,cpus:vec![0,1,2,3] }],
            gpu: None, thermal_zones: vec![], top_app_uclamp: uclamp.then(|| "/dev/cpuctl/top-app".into()), stune_top: stune.then(|| "/dev/stune/top-app".into()), cpu_boost_dir:None,
            identity: PlatformIdentity { oem: OemVendor::Xiaomi, vendor: SocVendor::Qualcomm, platform:"qcom".into(), model:"test".into(), compatible:"qcom,test".into(), evidence:vec![] },
        }
    }
    #[test] fn gki_prefers_uclamp_even_when_family_lists_schedtune_first() {
        let family=FamilyProfile{schema_version:1,vendor:"qualcomm".into(),provider_order:ProviderOrder{boost:vec!["schedtune".into(),"uclamp".into()],..ProviderOrder::default()},discovery:DiscoveryHints::default()};
        let p=BackendPlan::resolve(&topology(KernelFlavor::Gki,true,true).identity,&topology(KernelFlavor::Gki,true,true),Some(&family)); assert_eq!(p.boost,BoostBackend::Uclamp); assert_eq!(p.backend,VendorBackendKind::Xiaomi);
    }
    #[test] fn nongki_can_prefer_schedtune_from_family_data() {
        let family=FamilyProfile{schema_version:1,vendor:"qualcomm".into(),provider_order:ProviderOrder{boost:vec!["schedtune".into(),"uclamp".into()],..ProviderOrder::default()},discovery:DiscoveryHints::default()};
        let t=topology(KernelFlavor::NonGki,true,true); let p=BackendPlan::resolve(&t.identity,&t,Some(&family)); assert_eq!(p.boost,BoostBackend::SchedTune);
    }
    #[test] fn vendor_backend_selection_is_capability_first() {
        let mut t=topology(KernelFlavor::Gki,true,false);
        for (vendor,oem,expected) in [
            (SocVendor::Qualcomm,OemVendor::Other,VendorBackendKind::Qualcomm),
            (SocVendor::MediaTek,OemVendor::Other,VendorBackendKind::MediaTek),
            (SocVendor::GoogleTensor,OemVendor::Other,VendorBackendKind::Google),
            (SocVendor::Qualcomm,OemVendor::Xiaomi,VendorBackendKind::Xiaomi),
            (SocVendor::Qualcomm,OemVendor::Google,VendorBackendKind::Google),
        ] {
            t.identity.vendor=vendor;
            t.identity.oem=oem;
            let p=BackendPlan::resolve(&t.identity,&t,None);
            assert_eq!(p.backend,expected);
        }
    }
}
