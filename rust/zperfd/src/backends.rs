// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Runtime backend resolver.
//!
//! A backend is selected only from verified runtime surfaces. SoC family data
//! provides hints and ordering; actual capability discovery always wins.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::family::FamilyProfile;
use crate::platform::{GpuProvider, KernelFlavor, PlatformIdentity, SocVendor, ThermalProvider};
use crate::topo::Topology;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CpuPolicyBackend {
    Cpufreq,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BoostBackend {
    Uclamp,
    SchedTune,
    None,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GpuBackend {
    Kgsl,
    Devfreq(GpuProvider),
    None,
}

#[derive(Debug, Clone)]
pub struct BackendPlan {
    pub vendor: SocVendor,
    pub kernel: KernelFlavor,
    pub cpu: Option<CpuPolicyBackend>,
    pub boost: BoostBackend,
    pub gpu: GpuBackend,
    pub thermal: Vec<ThermalProvider>,
    pub vendor_surfaces: Vec<&'static str>,
    pub family_profile: Option<String>,
}

impl BackendPlan {
    pub fn resolve(identity: &PlatformIdentity, topology: &Topology, family: Option<&FamilyProfile>) -> Self {
        let kernel = match topology.flavor_kind() {
            KernelFlavor::Gki => KernelFlavor::Gki,
            KernelFlavor::NonGki => KernelFlavor::NonGki,
            KernelFlavor::Unknown => KernelFlavor::Unknown,
        };
        let boost = if topology.kernel_flavor == KernelFlavor::NonGki
            && family.map(|f| f.boost_prefers("schedtune")).unwrap_or(false)
            && topology.stune_top.is_some()
        {
            BoostBackend::SchedTune
        } else if topology.top_app_uclamp.is_some() {
            BoostBackend::Uclamp
        } else if topology.stune_top.is_some() {
            BoostBackend::SchedTune
        } else {
            BoostBackend::None
        };
        let gpu = match topology.gpu.as_ref() {
            Some(g) => match g.provider {
                GpuProvider::Kgsl => GpuBackend::Kgsl,
                ref p => GpuBackend::Devfreq(p.clone()),
            },
            None => GpuBackend::None,
        };
        let mut thermal = topology
            .thermal_zones
            .iter()
            .map(|z| z.provider.clone())
            .filter(|p| *p != ThermalProvider::Unknown)
            .collect::<Vec<_>>();
        if let Some(family) = family {
            thermal.sort_by_key(|p| family.provider_rank("thermal", p.as_str()));
        } else {
            thermal.sort_by_key(|p| p.as_str());
        }
        thermal.dedup_by(|a, b| a.as_str() == b.as_str());

        let mut vendor_surfaces = Vec::new();
        match identity.vendor {
            SocVendor::Qualcomm => {
                if topology.has_msm_perf { vendor_surfaces.push("msm_performance"); }
                if matches!(gpu, GpuBackend::Kgsl) { vendor_surfaces.push("kgsl"); }
            }
            SocVendor::MediaTek => vendor_surfaces.push("mediatek-generic"),
            SocVendor::SamsungExynos => vendor_surfaces.push("exynos-generic"),
            SocVendor::GoogleTensor => vendor_surfaces.push("tensor-generic"),
            SocVendor::Unknown => {}
        }
        Self {
            vendor: identity.vendor,
            kernel,
            cpu: (!topology.policies.is_empty()).then_some(CpuPolicyBackend::Cpufreq),
            boost,
            gpu,
            thermal,
            vendor_surfaces,
            family_profile: family.map(|f| f.vendor.clone()),
        }
    }

    pub fn conservative(&self) -> bool {
        self.kernel == KernelFlavor::Unknown
            || self.cpu.is_none()
            || self.gpu == GpuBackend::None && self.vendor == SocVendor::Unknown
    }
}


#[cfg(test)]
mod tests {
    use super::*;
    use crate::family::{DiscoveryHints, ProviderOrder};
    use crate::platform::{PlatformIdentity, SocVendor};

    fn topology(flavor: KernelFlavor, uclamp: bool, stune: bool) -> Topology {
        Topology {
            release: "test".into(),
            gki: flavor == KernelFlavor::Gki,
            kernel_flavor: flavor,
            kernel_generation: "6.18".into(),
            cgroup_v2: uclamp,
            has_msm_perf: false,
            policies: vec![crate::topo::Policy {
                name: "policy0".into(),
                avail: vec![300000, 1800000],
                min_hw: 300000,
                max_hw: 1800000,
            }],
            gpu: None,
            thermal_zones: vec![],
            top_app_uclamp: uclamp.then(|| "/dev/cpuctl/top-app".into()),
            stune_top: stune.then(|| "/dev/stune/top-app".into()),
            cpu_boost_dir: None,
            identity: PlatformIdentity {
                vendor: SocVendor::Qualcomm,
                platform: "qcom".into(),
                model: "test".into(),
                compatible: "qcom,test".into(),
                evidence: vec![],
            },
        }
    }

    #[test]
    fn gki_prefers_uclamp_even_when_family_lists_schedtune_first() {
        let family = FamilyProfile {
            schema_version: 1,
            vendor: "qualcomm".into(),
            provider_order: ProviderOrder {
                boost: vec!["schedtune".into(), "uclamp".into()],
                ..ProviderOrder::default()
            },
            discovery: DiscoveryHints::default(),
        };
        let t = topology(KernelFlavor::Gki, true, true);
        let p = BackendPlan::resolve(&t.identity, &t, Some(&family));
        assert_eq!(p.boost, BoostBackend::Uclamp);
    }

    #[test]
    fn nongki_can_prefer_schedtune_from_family_data() {
        let family = FamilyProfile {
            schema_version: 1,
            vendor: "qualcomm".into(),
            provider_order: ProviderOrder {
                boost: vec!["schedtune".into(), "uclamp".into()],
                ..ProviderOrder::default()
            },
            discovery: DiscoveryHints::default(),
        };
        let t = topology(KernelFlavor::NonGki, true, true);
        let p = BackendPlan::resolve(&t.identity, &t, Some(&family));
        assert_eq!(p.boost, BoostBackend::SchedTune);
    }
}
