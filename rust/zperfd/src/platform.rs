// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Evidence-based platform identity and provider classification.
//!
//! This module intentionally separates *observations* from *policy*. It never
//! invents a SoC-specific node path; it records evidence from DT/getprop and
//! lets the backend resolver decide which runtime surfaces are actually safe.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::nodes::Sysroot;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OemVendor {
    Google,
    Xiaomi,
    Samsung,
    Other,
    Unknown,
}

impl OemVendor {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Google => "google",
            Self::Xiaomi => "xiaomi",
            Self::Samsung => "samsung",
            Self::Other => "other",
            Self::Unknown => "unknown",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SocVendor {
    Qualcomm,
    MediaTek,
    SamsungExynos,
    GoogleTensor,
    Unknown,
}

impl SocVendor {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Qualcomm => "qualcomm",
            Self::MediaTek => "mediatek",
            Self::SamsungExynos => "exynos",
            Self::GoogleTensor => "tensor",
            Self::Unknown => "unknown",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KernelFlavor {
    Gki,
    NonGki,
    Unknown,
}

impl KernelFlavor {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Gki => "gki",
            Self::NonGki => "non-gki",
            Self::Unknown => "unknown",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GpuProvider {
    Kgsl,
    Mali,
    PowerVr,
    GenericDevfreq,
}

impl GpuProvider {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Kgsl => "kgsl",
            Self::Mali => "mali",
            Self::PowerVr => "powervr",
            Self::GenericDevfreq => "devfreq",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ThermalProvider {
    LinuxThermal,
    Qualcomm,
    MediaTek,
    Exynos,
    Tensor,
    Unknown,
}

impl ThermalProvider {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::LinuxThermal => "linux-thermal",
            Self::Qualcomm => "qualcomm-thermal",
            Self::MediaTek => "mediatek-thermal",
            Self::Exynos => "exynos-thermal",
            Self::Tensor => "tensor-thermal",
            Self::Unknown => "unknown",
        }
    }
}

#[derive(Debug, Clone)]
pub struct Evidence {
    pub source: &'static str,
    pub value: String,
}

#[derive(Debug, Clone)]
pub struct PlatformIdentity {
    pub vendor: SocVendor,
    pub oem: OemVendor,
    pub platform: String,
    pub model: String,
    pub compatible: String,
    pub evidence: Vec<Evidence>,
}

impl PlatformIdentity {
    pub fn detect(s: &Sysroot) -> Self {
        let mut evidence = Vec::new();
        // When probing an alternate Sysroot (tests, recovery images, or an
        // offline dump), do not spend up to the command timeout probing the
        // host Android `getprop` binary. Synthetic properties take precedence.
        let synthetic_props = [
            "ro.board.platform",
            "ro.soc.model",
            "ro.soc.manufacturer",
            "ro.hardware",
            "ro.product.board",
            "ro.product.manufacturer",
            "ro.product.brand",
            "ro.product.name",
        ]
        .iter()
        .any(|key| s.exists(&format!("/dev/props/{key}")));
        let props = if synthetic_props {
            std::collections::BTreeMap::new()
        } else {
            crate::scene::getprops().unwrap_or_default()
        };
        let prop = |key: &str| {
            props.get(key).cloned().or_else(|| read_prop(s, key)).unwrap_or_default()
        };
        let platform = prop("ro.board.platform");
        let soc_model = prop("ro.soc.model");
        let soc_vendor = prop("ro.soc.manufacturer");
        let hardware = prop("ro.hardware");
        let board = prop("ro.product.board");
        let manufacturer = prop("ro.product.manufacturer");
        let brand = prop("ro.product.brand");
        let product = prop("ro.product.name");
        let model = s.read("/proc/device-tree/model").unwrap_or_default();
        let compatible = s.read("/proc/device-tree/compatible").unwrap_or_default();

        for (source, value) in [
            ("ro.board.platform", platform.clone()),
            ("ro.soc.model", soc_model.clone()),
            ("ro.soc.manufacturer", soc_vendor.clone()),
            ("ro.hardware", hardware.clone()),
            ("ro.product.board", board.clone()),
            ("ro.product.manufacturer", manufacturer.clone()),
            ("ro.product.brand", brand.clone()),
            ("ro.product.name", product.clone()),
            ("/proc/device-tree/model", model.clone()),
            ("/proc/device-tree/compatible", compatible.clone()),
        ] {
            if !value.is_empty() {
                evidence.push(Evidence { source, value });
            }
        }

        let corpus = evidence
            .iter()
            .map(|e| e.value.to_ascii_lowercase().replace('\0', " "))
            .collect::<Vec<_>>()
            .join(" ");

        let vendor = classify_vendor(&corpus);
        let oem = classify_oem(&format!("{} {} {}", manufacturer, brand, product));
        Self {
            vendor,
            oem,
            platform: platform.to_ascii_lowercase(),
            model,
            compatible,
            evidence,
        }
    }

    pub fn soc_key(&self) -> String {
        let raw = if !self.platform.is_empty() {
            self.platform.clone()
        } else if !self.vendor_hint().is_empty() {
            self.vendor_hint().to_string()
        } else {
            "generic".to_string()
        };
        normalize_identifier(&raw)
    }

    fn vendor_hint(&self) -> &'static str {
        self.vendor.as_str()
    }
}

fn read_prop(s: &Sysroot, key: &str) -> Option<String> {
    if key.is_empty() || !key.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'_' || b == b'-') {
        return None;
    }
    // Unit tests and offline probing may provide props directly in sysroot.
    let synthetic = format!("/dev/props/{key}");
    if let Some(v) = s.read(&synthetic) {
        return Some(v);
    }
    crate::scene::getprop(key)
}

fn classify_vendor(corpus: &str) -> SocVendor {
    if contains_any(corpus, &["google tensor", "tensor gs", "gs101", "gs201", "gs301", "tensor g"])
        || corpus.contains("google,gs")
    {
        return SocVendor::GoogleTensor;
    }
    if contains_any(corpus, &["mediatek", "mt6", "mt7", "mt8", "mt9", "dimensity", "helio"]) {
        return SocVendor::MediaTek;
    }
    if contains_any(corpus, &["samsung,exynos", "exynos", "s5e"])
        && !corpus.contains("tensor")
    {
        return SocVendor::SamsungExynos;
    }
    if contains_any(corpus, &["qualcomm", "qcom", "sdm", "sm6", "sm7", "sm8", "sm9", "snapdragon"]) {
        return SocVendor::Qualcomm;
    }
    SocVendor::Unknown
}

fn classify_oem(corpus: &str) -> OemVendor {
    let c = corpus.to_ascii_lowercase();
    if contains_any(&c, &["xiaomi", "redmi", "poco"]) {
        OemVendor::Xiaomi
    } else if contains_any(&c, &["google", "pixel"]) {
        OemVendor::Google
    } else if contains_any(&c, &["samsung", "galaxy"]) {
        OemVendor::Samsung
    } else if c.trim().is_empty() {
        OemVendor::Unknown
    } else {
        OemVendor::Other
    }
}

fn contains_any(haystack: &str, needles: &[&str]) -> bool {
    needles.iter().any(|n| haystack.contains(n))
}

pub fn normalize_identifier(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut last_dash = false;
    for c in s.chars().flat_map(|c| c.to_lowercase()) {
        if c.is_ascii_alphanumeric() || c == '_' || c == '-' {
            out.push(c);
            last_dash = false;
        } else if !last_dash {
            out.push('-');
            last_dash = true;
        }
    }
    out.trim_matches('-').to_string()
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ThermalTripType {
    Active,
    Passive,
    Hot,
    Critical,
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ThermalTrip {
    pub temp_mdeg: i64,
    pub kind: ThermalTripType,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ThermalRole {
    Cpu,
    Gpu,
    SoC,
    Battery,
    Skin,
    Memory,
    Generic,
}

#[derive(Debug, Clone)]
pub struct ThermalZoneInfo {
    pub name: String,
    pub ty: String,
    pub temp_mdeg: Option<i64>,
    pub provider: ThermalProvider,
    pub role: ThermalRole,
    pub trips: Vec<ThermalTrip>,
}

pub fn classify_thermal_role(name: &str, ty: &str) -> ThermalRole {
    let low = format!("{} {}", name, ty).to_ascii_lowercase();
    if contains_any(&low, &["battery", "batt", "charger", "pmic-batt"]) { return ThermalRole::Battery; }
    if contains_any(&low, &["skin", "surface", "backglass", "shell"]) { return ThermalRole::Skin; }
    if contains_any(&low, &["gpu", "kgsl", "adreno", "mali", "xclipse", "g3d"]) { return ThermalRole::Gpu; }
    if contains_any(&low, &["cpu", "cluster", "a7", "a53", "a55", "a7x", "a78", "x1", "x2", "x3", "x4", "cortex"]) { return ThermalRole::Cpu; }
    if contains_any(&low, &["soc", "package", "tsens", "mtktscpu", "lvts", "exynos", "tensor"]) { return ThermalRole::SoC; }
    if contains_any(&low, &["ddr", "dram", "memory", "memctl"]) { return ThermalRole::Memory; }
    ThermalRole::Generic
}

pub fn classify_thermal(vendor: SocVendor, ty: &str) -> ThermalProvider {
    let low = ty.to_ascii_lowercase();
    match vendor {
        SocVendor::Qualcomm if contains_any(&low, &["qcom", "msm", "tsens", "pmic"]) => ThermalProvider::Qualcomm,
        SocVendor::MediaTek if contains_any(&low, &["mtk", "mt6", "mt7", "mt8", "mt9", "lvts"]) => ThermalProvider::MediaTek,
        SocVendor::SamsungExynos if contains_any(&low, &["exynos", "s5e"]) => ThermalProvider::Exynos,
        SocVendor::GoogleTensor if contains_any(&low, &["tensor", "gs", "g3", "g4", "g5"]) => ThermalProvider::Tensor,
        _ if !low.is_empty() => ThermalProvider::LinuxThermal,
        _ => ThermalProvider::Unknown,
    }
}

fn parse_trip_type(raw: &str) -> ThermalTripType {
    match raw.trim().to_ascii_lowercase().as_str() {
        "active" => ThermalTripType::Active,
        "passive" => ThermalTripType::Passive,
        "hot" => ThermalTripType::Hot,
        "critical" => ThermalTripType::Critical,
        _ => ThermalTripType::Unknown,
    }
}

fn scan_trips(s: &Sysroot, base: &str) -> Vec<ThermalTrip> {
    let mut trips = Vec::new();
    for entry in s.list_dir(base) {
        let Some(index) = entry.strip_prefix("trip_point_").and_then(|x| x.strip_suffix("_temp")) else { continue; };
        if index.is_empty() || !index.bytes().all(|b| b.is_ascii_digit()) { continue; }
        let Some(temp) = s.read(&format!("{base}/{entry}")).and_then(|v| v.parse::<i64>().ok()) else { continue; };
        let kind = s.read(&format!("{base}/trip_point_{index}_type")).map(|v| parse_trip_type(&v)).unwrap_or(ThermalTripType::Unknown);
        trips.push(ThermalTrip { temp_mdeg: temp, kind });
    }
    trips.sort_by_key(|t| t.temp_mdeg);
    trips
}

pub fn scan_thermal(s: &Sysroot, vendor: SocVendor) -> Vec<ThermalZoneInfo> {
    let mut zones = Vec::new();
    for name in s.list_dir("/sys/class/thermal") {
        if !name.starts_with("thermal_zone") {
            continue;
        }
        let base = format!("/sys/class/thermal/{name}");
        let ty = s.read(&format!("{base}/type")).unwrap_or_default();
        let temp_mdeg = s.read(&format!("{base}/temp")).and_then(|v| v.parse::<i64>().ok());
        zones.push(ThermalZoneInfo {
            name,
            provider: classify_thermal(vendor, &ty),
            role: classify_thermal_role(&name, &ty),
            trips: scan_trips(s, &base),
            ty,
            temp_mdeg,
        });
    }
    zones.sort_by(|a, b| a.name.cmp(&b.name));
    zones
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::path::PathBuf;

    fn root() -> PathBuf {
        let p = std::env::temp_dir().join(format!("zairenkai-platform-{}", std::process::id()));
        let _ = fs::remove_dir_all(&p);
        fs::create_dir_all(&p).unwrap();
        p
    }

    fn w(r: &PathBuf, path: &str, value: &str) {
        let p = r.join(path.trim_start_matches('/'));
        fs::create_dir_all(p.parent().unwrap()).unwrap();
        fs::write(p, value).unwrap();
    }

    #[test]
    fn classifies_known_vendors_from_evidence() {
        assert_eq!(classify_vendor("qcom,something sm8550"), SocVendor::Qualcomm);
        assert_eq!(classify_vendor("mediatek mt6893 dimensity"), SocVendor::MediaTek);
        assert_eq!(classify_vendor("samsung,exynos 2200 s5e9925"), SocVendor::SamsungExynos);
        assert_eq!(classify_vendor("google gs101 tensor"), SocVendor::GoogleTensor);
    }

    #[test]
    fn platform_collects_synthetic_android_props() {
        let r = root();
        w(&r, "/dev/props/ro.board.platform", "sm8550");
        w(&r, "/dev/props/ro.soc.model", "Snapdragon");
        w(&r, "/proc/device-tree/compatible", "qcom,sm8550\0qcom,foo");
        let p = PlatformIdentity::detect(&Sysroot::new(&r));
        assert_eq!(p.vendor, SocVendor::Qualcomm);
        assert_eq!(p.platform, "sm8550");
        assert_eq!(p.soc_key(), "sm8550");
        let _ = fs::remove_dir_all(r);
    }
}
