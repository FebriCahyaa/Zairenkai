// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Versioned SoC-family hints used to order runtime backend candidates.
//!
//! Family data is descriptive and advisory. A provider listed here is never
//! assumed to exist: the topology detector must observe the live interface
//! before the backend can use it.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::platform::SocVendor;
use serde::Deserialize;
use std::fs;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Deserialize, Default)]
pub struct ProviderOrder {
    #[serde(default)]
    pub cpu: Vec<String>,
    #[serde(default)]
    pub gpu: Vec<String>,
    #[serde(default)]
    pub thermal: Vec<String>,
    #[serde(default)]
    pub boost: Vec<String>,
}

#[derive(Debug, Clone, Deserialize, Default)]
pub struct DiscoveryHints {
    #[serde(default)]
    pub platform_tokens: Vec<String>,
    #[serde(default)]
    pub compatible_tokens: Vec<String>,
    #[serde(default)]
    pub thermal_type_patterns: Vec<String>,
}

#[derive(Debug, Clone, Deserialize, Default)]
pub struct FamilyProfile {
    pub schema_version: u32,
    pub vendor: String,
    #[serde(default)]
    pub provider_order: ProviderOrder,
    #[serde(default)]
    pub discovery: DiscoveryHints,
}

impl FamilyProfile {
    pub fn provider_rank(&self, class: &str, provider: &str) -> usize {
        let list = match class {
            "cpu" => &self.provider_order.cpu,
            "gpu" => &self.provider_order.gpu,
            "thermal" => &self.provider_order.thermal,
            "boost" => &self.provider_order.boost,
            _ => return usize::MAX,
        };
        list.iter().position(|x| x == provider).unwrap_or(usize::MAX)
    }

    pub fn boost_prefers(&self, provider: &str) -> bool {
        self.provider_order
            .boost
            .first()
            .map(|x| x == provider)
            .unwrap_or(false)
    }
}

pub fn path_for(root: &Path, vendor: SocVendor) -> PathBuf {
    root.join("soc").join(vendor.as_str()).join("family.toml")
}

pub fn load(root: &Path, vendor: SocVendor) -> Option<FamilyProfile> {
    if vendor == SocVendor::Unknown {
        return None;
    }
    let path = path_for(root, vendor);
    let text = fs::read_to_string(path).ok()?;
    let profile: FamilyProfile = toml::from_str(&text).ok()?;
    if profile.schema_version != 1 || profile.vendor != vendor.as_str() {
        return None;
    }
    Some(profile)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn provider_rank_follows_family_order() {
        let profile = FamilyProfile {
            schema_version: 1,
            vendor: "qualcomm".into(),
            provider_order: ProviderOrder {
                boost: vec!["uclamp".into(), "schedtune".into()],
                ..ProviderOrder::default()
            },
            discovery: DiscoveryHints::default(),
        };
        assert_eq!(profile.provider_rank("boost", "uclamp"), 0);
        assert_eq!(profile.provider_rank("boost", "schedtune"), 1);
        assert!(profile.boost_prefers("uclamp"));
        assert!(!profile.boost_prefers("schedtune"));
    }

    #[test]
    fn load_rejects_vendor_mismatch() {
        let root = std::env::temp_dir().join(format!("zairenkai-family-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(root.join("soc/qualcomm")).unwrap();
        fs::write(
            root.join("soc/qualcomm/family.toml"),
            "schema_version=1\nvendor=\"mediatek\"\n",
        )
        .unwrap();
        assert!(load(&root, SocVendor::Qualcomm).is_none());
        let _ = fs::remove_dir_all(root);
    }
}
