// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CoreManifest {
    pub schema_version: u32,
    pub id: String,
    pub product: String,
    pub core_api: u32,
    #[serde(default)]
    pub supported_architectures: Vec<String>,
    #[serde(default)]
    pub supported_kernel_models: Vec<String>,
    #[serde(default)]
    pub supported_kernel_generations: Vec<String>,
}

impl CoreManifest {
    pub fn parse(text: &str) -> Result<Self, String> {
        let v: Self = toml::from_str(text).map_err(|e| format!("manifest parse: {e}"))?;
        v.validate()?;
        Ok(v)
    }

    pub fn validate(&self) -> Result<(), String> {
        if self.schema_version != 1 { return Err("unsupported manifest schema".into()); }
        if self.id != "zairenkai.core" { return Err("invalid core id".into()); }
        if self.core_api == 0 { return Err("core_api must be non-zero".into()); }
        if self.supported_architectures.is_empty() { return Err("no architectures declared".into()); }
        if self.supported_kernel_models.is_empty() { return Err("no kernel models declared".into()); }
        if self.supported_kernel_generations.is_empty() { return Err("no kernel generations declared".into()); }
        Ok(())
    }
}
