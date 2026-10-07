// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum HealthLevel { Healthy, Degraded, SafeMode, Incompatible, Unknown }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HealthSnapshot {
    pub level: HealthLevel,
    pub kernel_api_compatible: bool,
    pub runtime_reconciled: bool,
    pub persistent_state_valid: bool,
    pub thermal_safe: bool,
    pub identity_confident: bool,
}

impl HealthSnapshot {
    pub fn evaluate(&self) -> HealthLevel {
        if !self.persistent_state_valid { return HealthLevel::SafeMode; }
        if !self.kernel_api_compatible { return HealthLevel::Incompatible; }
        if !self.identity_confident { return HealthLevel::Degraded; }
        if !self.thermal_safe || !self.runtime_reconciled { return HealthLevel::Degraded; }
        HealthLevel::Healthy
    }
}
