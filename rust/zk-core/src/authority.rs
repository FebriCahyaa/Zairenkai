// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
use serde::{Deserialize, Serialize};

use crate::capability::CapabilitySet;
use crate::operation::Operation;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Decision { Allow, Deny, Degrade }

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Authorization {
    pub decision: Decision,
    pub required: crate::capability::Capability,
    pub risk_weight: u8,
    pub reason: String,
}

pub fn authorize(capabilities: &CapabilitySet, operation: Operation) -> Authorization {
    let required = operation.required_capability();
    if capabilities.contains(required) {
        Authorization {
            decision: Decision::Allow,
            required,
            risk_weight: operation.risk_weight(),
            reason: "required capability granted".into(),
        }
    } else {
        Authorization {
            decision: Decision::Deny,
            required,
            risk_weight: operation.risk_weight(),
            reason: format!("missing capability: {:?}", required),
        }
    }
}
