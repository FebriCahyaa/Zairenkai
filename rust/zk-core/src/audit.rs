// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
use serde::{Deserialize, Serialize};
use crate::operation::Operation;
use crate::authority::Decision;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuditEvent {
    pub sequence: u64,
    pub timestamp_monotonic_ns: u64,
    pub operation: Operation,
    pub decision: Decision,
    pub actor: String,
    pub target: String,
    pub result: String,
}
