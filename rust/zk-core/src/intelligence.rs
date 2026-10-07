// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Device intelligence contracts: evidence, observations and measurements.
//! Static profile data is never promoted to runtime truth without observation.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub enum EvidenceLevel {
    Unknown,
    Heuristic,
    Observed,
    Verified,
    Measured,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Evidence {
    pub level: EvidenceLevel,
    pub source: String,
    pub value: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Observation {
    pub name: String,
    pub present: bool,
    pub value: Option<String>,
    pub source: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MeasurementProvenance {
    pub run_id: String,
    pub device_id: String,
    pub kernel_release: String,
    pub profile_id: String,
    pub workload: String,
    pub tool_version: String,
    pub collected_at_unix_s: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Measurement {
    pub provenance: MeasurementProvenance,
    pub metric: String,
    pub value: i64,
    pub unit: String,
    pub sample_index: u32,
}

pub fn can_authorize_from_evidence(level: EvidenceLevel) -> bool {
    matches!(level, EvidenceLevel::Observed | EvidenceLevel::Verified | EvidenceLevel::Measured)
}
