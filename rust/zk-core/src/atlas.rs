// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Zairenkai Atlas: provenance and trust contracts for the device knowledge plane.
//! Static data is advisory unless promoted by runtime observation or explicit
//! verification. Performance measurements are never treated as hardware facts.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[repr(u8)]
pub enum TrustTier {
    Heuristic = 1,
    Community = 2,
    VendorRepository = 3,
    Upstream = 4,
    Official = 5,
    Runtime = 6,
}


#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub enum KnowledgeKind {
    Identity,
    Capability,
    Thermal,
    Storage,
    Memory,
    Network,
    Security,
    Performance,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AtlasClaim {
    pub key: String,
    pub value: String,
    pub kind: KnowledgeKind,
    pub trust: TrustTier,
    pub source_id: String,
    pub observed_at_unix_s: Option<u64>,
    pub evidence_id: Option<String>,
}

impl AtlasClaim {
    pub fn may_control(&self) -> bool {
        matches!(self.trust, TrustTier::Runtime | TrustTier::Official | TrustTier::Upstream)
    }

    pub fn conflicts_with(&self, other: &Self) -> bool {
        self.key == other.key && self.value != other.value
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum SourceClass {
    Runtime,
    OfficialVendor,
    AndroidOpenSource,
    UpstreamKernel,
    VendorRepository,
    Community,
    Measurement,
}

impl SourceClass {
    pub const fn trust(self) -> TrustTier {
        match self {
            Self::Runtime => TrustTier::Runtime,
            Self::OfficialVendor | Self::AndroidOpenSource => TrustTier::Official,
            Self::UpstreamKernel => TrustTier::Upstream,
            Self::VendorRepository => TrustTier::VendorRepository,
            Self::Community => TrustTier::Community,
            Self::Measurement => TrustTier::Official,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DeviceKnowledge {
    pub device_id: String,
    pub vendor: String,
    pub soc: String,
    pub kernel_generation: Option<String>,
    pub claims: Vec<AtlasClaim>,
}

impl DeviceKnowledge {
    pub fn strongest(&self, key: &str) -> Option<&AtlasClaim> {
        self.claims.iter()
            .filter(|c| c.key == key)
            .max_by_key(|c| c.trust)
    }

    pub fn is_runtime_verified(&self) -> bool {
        self.claims.iter().any(|c| c.trust == TrustTier::Runtime)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn runtime_claim_wins_over_static_data() {
        let k = DeviceKnowledge {
            device_id: "x".into(), vendor: "qualcomm".into(), soc: "sm8550".into(),
            kernel_generation: Some("6.1".into()),
            claims: vec![
                AtlasClaim { key: "thermal.provider".into(), value: "generic".into(), kind: KnowledgeKind::Thermal, trust: TrustTier::Official, source_id: "aosp".into(), observed_at_unix_s: None, evidence_id: None },
                AtlasClaim { key: "thermal.provider".into(), value: "qcom".into(), kind: KnowledgeKind::Thermal, trust: TrustTier::Runtime, source_id: "runtime".into(), observed_at_unix_s: Some(1), evidence_id: Some("obs".into()) },
            ],
        };
        assert_eq!(k.strongest("thermal.provider").unwrap().value, "qcom");
        assert!(k.is_runtime_verified());
    }

    #[test]
    fn heuristic_claim_cannot_authorize_control() {
        let c = AtlasClaim { key: "storage.kind".into(), value: "ufs".into(), kind: KnowledgeKind::Storage, trust: TrustTier::Heuristic, source_id: "heuristic".into(), observed_at_unix_s: None, evidence_id: None };
        assert!(!c.may_control());
    }
}
