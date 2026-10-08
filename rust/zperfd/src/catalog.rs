// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Data-plane resolver for device metadata and tuning catalogs.
//!
//! The static database is a source of hints and identity mappings. Runtime
//! capability discovery remains authoritative; a catalog entry can select a
//! generic tuning file but can never force a node that is absent at runtime.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::platform::{normalize_identifier, PlatformIdentity};
use serde::Deserialize;
use std::fs;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Deserialize)]
pub struct DeviceEntry {
    pub id: String,
    pub vendor: String,
    pub soc: String,
    pub metadata: String,
    pub catalog: String,
}

#[derive(Debug, Deserialize)]
struct Index {
    schema_version: u32,
    device: Vec<DeviceEntry>,
}

#[derive(Debug, Clone)]
pub struct Resolution {
    pub entry: DeviceEntry,
    pub database_root: PathBuf,
}

fn safe_relative_path(raw: &str) -> Option<PathBuf> {
    let p = Path::new(raw);
    if p.is_absolute() {
        return None;
    }
    if p.components().any(|c| matches!(c, std::path::Component::ParentDir)) {
        return None;
    }
    Some(p.to_path_buf())
}

impl Resolution {
    pub fn metadata_path(&self) -> PathBuf {
        safe_relative_path(&self.entry.metadata)
            .map(|p| self.database_root.join(p))
            .unwrap_or_else(|| self.database_root.join("__invalid__"))
    }

    pub fn catalog_path(&self, catalog_root: &Path) -> PathBuf {
        safe_relative_path(&self.entry.catalog)
            .map(|p| catalog_root.join(p))
            .unwrap_or_else(|| catalog_root.join("__invalid__"))
    }
}

#[derive(Debug, Clone, Deserialize, Default)]
struct DeviceMatch {
    #[serde(default)]
    platform_tokens: Vec<String>,
    #[serde(default)]
    compatible_tokens: Vec<String>,
    #[serde(default)]
    model_tokens: Vec<String>,
}

#[derive(Debug, Clone, Deserialize, Default)]
struct DeviceMetadata {
    pub id: String,
    pub vendor: String,
    pub soc: String,
    #[serde(default)]
    pub r#match: DeviceMatch,
}

fn token_match(corpus: &str, tokens: &[String]) -> usize {
    tokens.iter().filter(|t| {
        let normalized = normalize_identifier(t);
        !normalized.is_empty() && corpus.contains(normalized.as_str())
    }).count()
}

pub fn resolve(database_root: &Path, identity: &PlatformIdentity) -> Option<Resolution> {
    let index_path = database_root.join("index.toml");
    let text = fs::read_to_string(index_path).ok()?;
    let index: Index = toml::from_str(&text).ok()?;
    if index.schema_version != 1 { return None; }

    let platform = normalize_identifier(&identity.platform);
    let model = normalize_identifier(&identity.model);
    let compatible = normalize_identifier(&identity.compatible);
    let corpus = format!("{platform} {model} {compatible}");

    let mut ranked = index.device.into_iter().filter_map(|entry| {
        let metadata = safe_relative_path(&entry.metadata)
            .and_then(|p| fs::read_to_string(database_root.join(p)).ok())
            .and_then(|v| toml::from_str::<DeviceMetadata>(&v).ok())?;
        if metadata.id != entry.id || metadata.vendor != entry.vendor || metadata.soc != entry.soc {
            return None;
        }
        let mut score = 0usize;
        let normalized_soc = normalize_identifier(&entry.soc);
        if !platform.is_empty() && platform == normalized_soc { score += 120; }
        if !identity.soc_key().is_empty() && identity.soc_key() == normalized_soc { score += 100; }
        score += token_match(&platform, &metadata.r#match.platform_tokens) * 60;
        score += token_match(&compatible, &metadata.r#match.compatible_tokens) * 40;
        score += token_match(&model, &metadata.r#match.model_tokens) * 50;
        if identity.vendor.as_str() == entry.vendor { score += 20; }
        (score > 0).then(|| (score, entry))
    }).collect::<Vec<_>>();
    ranked.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| a.1.id.cmp(&b.1.id)));
    ranked.into_iter().next().map(|(_, entry)| Resolution { entry, database_root: database_root.to_path_buf() })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;


    #[test]
    fn rejects_path_traversal_in_index() {
        let root = std::env::temp_dir().join(format!("zairenkai-catalog-traversal-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(&root).unwrap();
        fs::write(root.join("index.toml"), "schema_version = 1\n[[device]]\nid=\"qualcomm.bad\"\nvendor=\"qualcomm\"\nsoc=\"sm8550\"\nmetadata=\"../escape.toml\"\ncatalog=\"../escape.toml\"\n").unwrap();
        let identity = PlatformIdentity {
            oem: crate::platform::OemVendor::Unknown,
            vendor: crate::platform::SocVendor::Qualcomm,
            platform: "sm8550".into(),
            model: String::new(),
            compatible: String::new(),
            evidence: Vec::new(),
        };
        assert!(resolve(&root, &identity).is_none());
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn resolves_by_model_and_compatible_tokens() {
        let root = std::env::temp_dir().join(format!("zairenkai-catalog-model-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(root.join("devices/google")).unwrap();
        fs::write(root.join("index.toml"), "schema_version=1\n[[device]]\nid=\"google.pixel10\"\nvendor=\"tensor\"\nsoc=\"tensor-g5\"\nmetadata=\"devices/google/pixel10.toml\"\ncatalog=\"generic.toml\"\n").unwrap();
        fs::write(root.join("devices/google/pixel10.toml"), "id=\"google.pixel10\"\nvendor=\"tensor\"\nsoc=\"tensor-g5\"\n[match]\nmodel_tokens=[\"Pixel 10\"]\ncompatible_tokens=[\"google\"]\nplatform_tokens=[]\n").unwrap();
        let identity = PlatformIdentity {
            oem: crate::platform::OemVendor::Unknown,
            vendor: crate::platform::SocVendor::GoogleTensor,
            platform: String::new(),
            model: "Pixel 10".into(),
            compatible: "google,gs".into(),
            evidence: Vec::new(),
        };
        let r = resolve(&root, &identity).unwrap();
        assert_eq!(r.entry.id, "google.pixel10");
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn resolves_by_normalized_platform() {
        let root = std::env::temp_dir().join(format!("zairenkai-catalog-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(&root).unwrap();
        let mut f = fs::File::create(root.join("index.toml")).unwrap();
        writeln!(f, "schema_version = 1\n[[device]]\nid=\"qualcomm.sm8550\"\nvendor=\"qualcomm\"\nsoc=\"sm8550\"\nmetadata=\"devices/qualcomm/sm8550.toml\"\ncatalog=\"generic.toml\"").unwrap();
        let identity = PlatformIdentity {
            oem: crate::platform::OemVendor::Unknown,
            vendor: crate::platform::SocVendor::Qualcomm,
            platform: "SM8550".into(),
            model: String::new(),
            compatible: String::new(),
            evidence: Vec::new(),
        };
        let r = resolve(&root, &identity).unwrap();
        assert_eq!(r.entry.id, "qualcomm.sm8550");
        let _ = fs::remove_dir_all(root);
    }
}
