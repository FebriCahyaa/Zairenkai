// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
use serde::{Deserialize, Serialize};

use crate::capability::Capability;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Operation {
    Probe,
    ReadStatus,
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
}

impl Operation {
    pub const fn required_capability(self) -> Capability {
        use Capability::*;
        match self {
            Self::Probe => ReadDevice,
            Self::ReadStatus => ReadPerformance,
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
        }
    }

    pub const fn risk_weight(self) -> u8 {
        match self {
            Self::Probe | Self::ReadStatus | Self::ReadLogs => 1,
            Self::ApplyProfile | Self::SetCpuTweak | Self::SetGpuTweak |
            Self::SetMemoryTweak | Self::SetIoTweak | Self::SetPowerTweak => 3,
            Self::SetThermalPolicy | Self::ResetRuntime => 4,
            Self::InstallLicense | Self::ModifyPolicy | Self::ManageHooks => 5,
        }
    }
}
