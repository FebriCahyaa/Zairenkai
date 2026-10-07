// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
use serde::{Deserialize, Serialize};

use crate::capability::Capability;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Operation {
    Probe,
    ReadStatus,
    ReadPerformance,
    ReadThermal,
    ReadLogs,
    ApplyProfile,
    SetCpuTweak,
    SetGpuTweak,
    SetMemoryTweak,
    SetIoTweak,
    SetPowerTweak,
    SetThermalPolicy,
    ResetRuntime,
    InstallLicense,
    ModifyPolicy,
    ManageHooks,
    ReadInventory,
    ReadStorage,
    ReadNetwork,
    ReadMemory,
    ReadSecurity,
    ReadProperties,
    TuneStorage,
    TuneNetwork,
    TuneZram,
    ManageDeviceRegistry,
    ManageDataSources,
    ManageEvidence,
    ManageRecovery,
    SetProperty,
}

impl Operation {
    pub const fn required_capability(self) -> Capability {
        use Capability::*;
        match self {
            Self::Probe => ReadDevice,
            Self::ReadStatus => ReadPerformance,
            Self::ReadPerformance => ReadPerformance,
            Self::ReadThermal => ReadThermal,
            Self::ReadLogs => ReadLogs,
            Self::ApplyProfile => TuneCpu,
            Self::SetCpuTweak => TuneCpu,
            Self::SetGpuTweak => TuneGpu,
            Self::SetMemoryTweak => TuneMemory,
            Self::SetIoTweak => TuneIo,
            Self::SetPowerTweak => TunePower,
            Self::SetThermalPolicy => TuneThermal,
            Self::ResetRuntime => ResetRuntime,
            Self::InstallLicense => ManageLicense,
            Self::ModifyPolicy => ManagePolicy,
            Self::ManageHooks => ManageHooks,
            Self::ReadInventory => ReadDevice,
            Self::ReadStorage => ReadStorage,
            Self::ReadNetwork => ReadNetwork,
            Self::ReadMemory => ReadMemory,
            Self::ReadSecurity => ReadSecurity,
            Self::ReadProperties => ReadProperties,
            Self::TuneStorage => TuneStorage,
            Self::TuneNetwork => TuneNetwork,
            Self::TuneZram => TuneZram,
            Self::ManageDeviceRegistry => ManageDeviceRegistry,
            Self::ManageDataSources => ManageDataSources,
            Self::ManageEvidence => ManageEvidence,
            Self::ManageRecovery => ManageRecovery,
            Self::SetProperty => TuneProperties,
        }
    }

    pub const fn risk_weight(self) -> u8 {
        match self {
            Self::Probe | Self::ReadStatus | Self::ReadPerformance | Self::ReadThermal | Self::ReadLogs => 1,
            Self::ApplyProfile | Self::SetCpuTweak | Self::SetGpuTweak |
            Self::SetMemoryTweak | Self::SetIoTweak | Self::SetPowerTweak => 3,
            Self::SetThermalPolicy | Self::ResetRuntime => 4,
            Self::InstallLicense | Self::ModifyPolicy | Self::ManageHooks |
            Self::ManageDeviceRegistry | Self::ManageDataSources | Self::ManageEvidence |
            Self::ManageRecovery => 5,
            Self::SetProperty => 2,
            Self::TuneStorage | Self::TuneNetwork | Self::TuneZram => 3,
            Self::ReadInventory | Self::ReadStorage | Self::ReadNetwork |
            Self::ReadMemory | Self::ReadSecurity | Self::ReadProperties => 1,
        }
    }
}
