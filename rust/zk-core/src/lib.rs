// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Zairenkai Core Platform contracts.
//!
//! This crate deliberately contains policy contracts, not device assumptions.
//! Runtime capability discovery remains authoritative in zperfd/ZKFC.
//!
//! Copyright (C) 2026 FebriCahyaa

pub mod api;
pub mod atlas;
pub mod audit;
pub mod authority;
pub mod capability;
pub mod health;
pub mod intelligence;
pub mod identity;
pub mod manifest;
pub mod operation;
pub mod sentinel;
pub mod policy;

pub const CORE_NAME: &str = "Zairenkai Core Platform";
pub const CORE_ID: &str = "zairenkai.core";
pub const CORE_API_VERSION: u32 = 1;
pub const ATLAS_API_VERSION: u32 = 1;
pub const SENTINEL_API_VERSION: u32 = 1;

#[cfg(test)]
mod tests {
    use super::*;
    use authority::Decision;
    use capability::{Capability, CapabilitySet};
    use operation::Operation;

    #[test]
    fn least_privilege_denies_missing_capability() {
        let caps = CapabilitySet::from_iter([Capability::ReadDevice]);
        let a = authority::authorize(&caps, Operation::SetCpuTweak);
        assert_eq!(a.decision, Decision::Deny);
    }

    #[test]
    fn full_runtime_authority_covers_every_operation() {
        let caps = CapabilitySet::full_runtime();
        let ops = [
            Operation::Probe, Operation::ReadStatus, Operation::ReadLogs, Operation::ReadInventory,
            Operation::ReadStorage, Operation::ReadNetwork, Operation::ReadMemory, Operation::ReadSecurity,
            Operation::ApplyProfile, Operation::SetCpuTweak,
            Operation::SetGpuTweak, Operation::SetMemoryTweak,
            Operation::SetIoTweak, Operation::SetPowerTweak,
            Operation::SetThermalPolicy, Operation::ResetRuntime,
            Operation::TuneStorage, Operation::TuneNetwork, Operation::TuneZram,
            Operation::ManageDeviceRegistry, Operation::ManageDataSources, Operation::ManageEvidence, Operation::ManageRecovery,
            Operation::InstallLicense, Operation::ModifyPolicy,
            Operation::ManageHooks,
        ];
        assert!(ops.iter().all(|op| authority::authorize(&caps, *op).decision == Decision::Allow));
    }


    #[test]
    fn policy_evaluator_is_conservative_without_thermal_data() {
        let decision = policy::evaluate(policy::PolicyInput {
            battery_pct: Some(80),
            hottest_mdeg: None,
            external_power: true,
            previous_mode: None,
        });
        assert_eq!(decision.intent, policy::PolicyIntent::Balanced);
        assert_eq!(decision.confidence, policy::Confidence::Medium);
    }


    #[test]
    fn measured_evidence_is_not_heuristic() {
        assert!(!intelligence::can_authorize_from_evidence(intelligence::EvidenceLevel::Heuristic));
        assert!(intelligence::can_authorize_from_evidence(intelligence::EvidenceLevel::Observed));
        assert!(intelligence::can_authorize_from_evidence(intelligence::EvidenceLevel::Measured));
    }

    #[test]
    fn sentinel_blocks_unsafe_runtime_write() {
        let d = sentinel::evaluate(&sentinel::SentinelInput {
            operation: Operation::SetCpuTweak,
            kernel_api_compatible: true,
            persistent_state_valid: true,
            safe_mode_active: false,
            runtime_reconciled: true,
            hottest_mdeg: Some(49_000),
            battery_pct: Some(50),
            external_power: true,
            storage_health: sentinel::StorageHealth::Good,
            boot_integrity: sentinel::BootIntegrity::Yellow,
            device_known: true,
            evidence_level: intelligence::EvidenceLevel::Observed,
            lite_mode: false,
        });
        assert!(!d.allowed);
        assert!(d.reasons.contains(&sentinel::SentinelReason::ThermalLimit));
    }

    #[test]
    fn sentinel_allows_observation_without_mutation_prerequisites() {
        let d = sentinel::evaluate(&sentinel::SentinelInput {
            operation: Operation::ReadSecurity,
            kernel_api_compatible: false,
            persistent_state_valid: false,
            safe_mode_active: true,
            runtime_reconciled: false,
            hottest_mdeg: None,
            battery_pct: None,
            external_power: false,
            storage_health: sentinel::StorageHealth::Unknown,
            boot_integrity: sentinel::BootIntegrity::Unknown,
            device_known: false,
            evidence_level: intelligence::EvidenceLevel::Unknown,
            lite_mode: true,
        });
        assert!(d.allowed);
    }

    #[test]
    fn sentinel_allows_reset_for_recovery_in_safe_mode() {
        let d = sentinel::evaluate(&sentinel::SentinelInput {
            operation: Operation::ResetRuntime,
            kernel_api_compatible: false,
            persistent_state_valid: true,
            safe_mode_active: true,
            runtime_reconciled: false,
            hottest_mdeg: None,
            battery_pct: Some(50),
            external_power: true,
            storage_health: sentinel::StorageHealth::Good,
            boot_integrity: sentinel::BootIntegrity::Orange,
            device_known: true,
            evidence_level: intelligence::EvidenceLevel::Observed,
            lite_mode: true,
        });
        assert!(d.allowed);
    }

    #[test]
    fn health_evaluation_is_fail_closed() {
        let mut health = health::HealthSnapshot {
            level: health::HealthLevel::Healthy,
            kernel_api_compatible: true,
            runtime_reconciled: true,
            persistent_state_valid: false,
            thermal_safe: true,
            identity_confident: true,
        };
        assert_eq!(health.evaluate(), health::HealthLevel::SafeMode);
        health.persistent_state_valid = true;
        health.kernel_api_compatible = false;
        assert_eq!(health.evaluate(), health::HealthLevel::Incompatible);
    }
}
