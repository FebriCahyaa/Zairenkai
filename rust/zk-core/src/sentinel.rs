// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Zairenkai Sentinel: fail-closed safety decisions for runtime operations.
//! Sentinel does not grant authority; it only constrains an already-authorized
//! operation using current integrity, thermal, power and device evidence.

use serde::{Deserialize, Serialize};
use crate::operation::Operation;
use crate::intelligence::EvidenceLevel;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum SafetyClass {
    Observe,
    FrameworkConfig,
    SafeTune,
    GuardedTune,
    Critical,
    Forbidden,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum BootIntegrity {
    Green,
    Yellow,
    Orange,
    Red,
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum StorageHealth {
    Good,
    Warning,
    Critical,
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum SentinelReason {
    ObservationOnly,
    ConfigurationOnly,
    KernelIncompatible,
    PersistentStateInvalid,
    RuntimeDrift,
    ThermalLimit,
    ThermalTelemetryUnavailable,
    BatteryCritical,
    StorageCritical,
    WeakBootIntegrity,
    UnknownDevice,
    InsufficientEvidence,
    OperationForbidden,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SentinelInput {
    pub operation: Operation,
    pub kernel_api_compatible: bool,
    pub persistent_state_valid: bool,
    pub safe_mode_active: bool,
    pub runtime_reconciled: bool,
    pub thermal_telemetry_complete: bool,
    pub thermal_headroom_permille: Option<u16>,
    pub thermal_critical_reached: bool,
    pub battery_pct: Option<u8>,
    pub external_power: bool,
    pub storage_health: StorageHealth,
    pub boot_integrity: BootIntegrity,
    pub device_known: bool,
    pub evidence_level: EvidenceLevel,
    pub lite_mode: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SentinelDecision {
    pub allowed: bool,
    pub class: SafetyClass,
    pub reasons: Vec<SentinelReason>,
}

pub fn classify(operation: Operation) -> SafetyClass {
    match operation {
        Operation::Probe | Operation::ReadStatus | Operation::ReadPerformance |
        Operation::ReadThermal | Operation::ReadLogs |
        Operation::ReadInventory | Operation::ReadStorage | Operation::ReadNetwork |
        Operation::ReadMemory | Operation::ReadSecurity | Operation::ReadProperties => SafetyClass::Observe,
        Operation::SetProperty => SafetyClass::FrameworkConfig,
        Operation::ApplyProfile | Operation::SetCpuTweak | Operation::SetGpuTweak |
        Operation::SetMemoryTweak | Operation::SetIoTweak | Operation::TuneStorage |
        Operation::TuneNetwork | Operation::TuneZram => SafetyClass::SafeTune,
        Operation::SetPowerTweak | Operation::SetThermalPolicy | Operation::ResetRuntime => SafetyClass::GuardedTune,
        Operation::InstallLicense | Operation::ModifyPolicy | Operation::ManageHooks |
        Operation::ManageDeviceRegistry | Operation::ManageDataSources |
        Operation::ManageEvidence | Operation::ManageRecovery => SafetyClass::Critical,
    }
}

pub fn evaluate(input: &SentinelInput) -> SentinelDecision {
    let class = classify(input.operation);
    if matches!(class, SafetyClass::Observe) {
        return SentinelDecision { allowed: true, class, reasons: vec![SentinelReason::ObservationOnly] };
    }
    if matches!(class, SafetyClass::FrameworkConfig) {
        return SentinelDecision { allowed: true, class, reasons: vec![SentinelReason::ConfigurationOnly] };
    }
    let mut reasons = Vec::new();
    if !input.kernel_api_compatible && input.operation != Operation::ResetRuntime {
        reasons.push(SentinelReason::KernelIncompatible);
    }
    if !input.persistent_state_valid { reasons.push(SentinelReason::PersistentStateInvalid); }
    if input.safe_mode_active && input.operation != Operation::ResetRuntime {
        reasons.push(SentinelReason::OperationForbidden);
    }
    if !input.runtime_reconciled { reasons.push(SentinelReason::RuntimeDrift); }
    let thermal_sensitive = matches!(
        input.operation,
        Operation::ApplyProfile
            | Operation::SetCpuTweak
            | Operation::SetGpuTweak
            | Operation::SetPowerTweak
            | Operation::SetThermalPolicy
    );
    if input.thermal_critical_reached && thermal_sensitive {
        reasons.push(SentinelReason::ThermalLimit);
    } else if thermal_sensitive
        && !input.thermal_telemetry_complete
        && !matches!(input.operation, Operation::ApplyProfile)
    {
        reasons.push(SentinelReason::ThermalTelemetryUnavailable);
    }
    if input.battery_pct.is_some_and(|v| v <= 5) && !input.external_power { reasons.push(SentinelReason::BatteryCritical); }
    if input.storage_health == StorageHealth::Critical { reasons.push(SentinelReason::StorageCritical); }
    if !input.device_known && !matches!(class, SafetyClass::Critical) { reasons.push(SentinelReason::UnknownDevice); }
    if input.evidence_level == EvidenceLevel::Heuristic && class != SafetyClass::Observe {
        reasons.push(SentinelReason::InsufficientEvidence);
    }
    if matches!(input.boot_integrity, BootIntegrity::Red | BootIntegrity::Unknown) && class == SafetyClass::Critical {
        reasons.push(SentinelReason::WeakBootIntegrity);
    }
    if input.lite_mode && class == SafetyClass::Critical {
        reasons.push(SentinelReason::OperationForbidden);
    }
    if class == SafetyClass::Forbidden { reasons.push(SentinelReason::OperationForbidden); }
    SentinelDecision { allowed: reasons.is_empty(), class, reasons }
}
