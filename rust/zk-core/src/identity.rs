// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SystemIdentity {
    pub vendor: String,
    pub soc: String,
    pub board: String,
    pub model: String,
    pub architecture: String,
    pub kernel_release: String,
    pub kernel_generation: String,
    pub kernel_flavor: String,
    pub page_size: u32,
}

impl SystemIdentity {
    pub fn canonical_key(&self) -> String {
        format!("{}:{}:{}:{}:{}:{}", self.vendor, self.soc, self.board,
                self.architecture, self.kernel_generation, self.kernel_flavor)
    }
}
