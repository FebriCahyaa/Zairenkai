// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[repr(u8)]
pub enum Capability {
    ReadDevice,
    ReadKernel,
    ReadThermal,
    ReadPerformance,
    ReadLogs,
    TuneCpu,
    TuneGpu,
    TuneMemory,
    TuneIo,
    TunePower,
    TuneThermal,
    ManageLicense,
    ManagePolicy,
    ManageHooks,
    ResetRuntime,
    ReadStorage,
    ReadNetwork,
    ReadMemory,
    ReadSecurity,
    TuneStorage,
    TuneNetwork,
    TuneZram,
    ManageDeviceRegistry,
    ManageDataSources,
    ManageEvidence,
    ManageRecovery,
    Experimental,
    TuneProperties,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct CapabilitySet {
    #[serde(default)]
    values: BTreeSet<Capability>,
}

impl CapabilitySet {
    pub fn new() -> Self { Self::default() }

    pub fn full_runtime() -> Self {
        use Capability::*;
        Self::from_iter([
            ReadDevice, ReadKernel, ReadThermal, ReadPerformance, ReadLogs,
            TuneCpu, TuneGpu, TuneMemory, TuneIo, TunePower, TuneThermal,
            ManageLicense, ManagePolicy, ManageHooks, ResetRuntime,
            ReadStorage, ReadNetwork, ReadMemory, ReadSecurity,
            TuneStorage, TuneNetwork, TuneZram,
            ManageDeviceRegistry, ManageDataSources, ManageEvidence,
            ManageRecovery, TuneProperties,
        ])
    }

    pub fn from_iter<I>(iter: I) -> Self
    where I: IntoIterator<Item = Capability> {
        Self { values: iter.into_iter().collect() }
    }

    pub fn contains(&self, cap: Capability) -> bool { self.values.contains(&cap) }
    pub fn insert(&mut self, cap: Capability) { self.values.insert(cap); }
    pub fn remove(&mut self, cap: Capability) { self.values.remove(&cap); }
    pub fn iter(&self) -> impl Iterator<Item = Capability> { self.values.iter().copied() }
    pub fn is_empty(&self) -> bool { self.values.is_empty() }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum SupportLevel { Unsupported, Experimental, Supported }
