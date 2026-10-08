// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Read-only hardware/system inventory. Inventory never grants mutation rights.

use crate::nodes::Sysroot;
use crate::platform::PlatformIdentity;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ZramInfo { pub name: String, pub size_bytes: Option<u64>, pub algorithm: Option<String> }
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StorageInfo {
    pub name: String,
    pub kind: String,
    pub model: Option<String>,
    pub size_bytes: Option<u64>,
    pub scheduler: Option<String>,
    pub read_ahead_kb: Option<u64>,
    pub health: String,
    pub kind_evidence: String,
    pub health_evidence: String,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NetworkInfo { pub interface_count: usize, pub interfaces: Vec<String>, pub tcp_congestion: Option<String>, pub tcp_allowed: Option<String>, pub tcp_available: Option<String> }
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MemoryInfo { pub total_kb: Option<u64>, pub available_kb: Option<u64>, pub swap_total_kb: Option<u64>, pub swap_free_kb: Option<u64>, pub zram: Vec<ZramInfo> }
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SecurityInfo { pub selinux_enforcing: Option<bool>, pub verified_boot: Option<String>, pub verity_mode: Option<String>, pub device_mapper_devices: Vec<String>, pub module_sig_enforce: Option<bool> }
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Inventory { pub identity: PlatformIdentity, pub memory: MemoryInfo, pub storage: Vec<StorageInfo>, pub network: NetworkInfo, pub security: SecurityInfo }

pub fn collect(s: &Sysroot, identity: PlatformIdentity) -> Inventory {
    Inventory { identity, memory: memory(s), storage: storage(s), network: network(s), security: security(s) }
}

fn meminfo(s: &Sysroot, key: &str) -> Option<u64> {
    let text = s.read("/proc/meminfo")?;
    text.lines().find_map(|line| { let mut p = line.split_whitespace(); if p.next()? != key { return None; } p.next()?.parse::<u64>().ok() })
}
fn memory(s: &Sysroot) -> MemoryInfo {
    let mut zram=Vec::new();
    for name in s.list_dir("/sys/block") {
        if !name.starts_with("zram") { continue; }
        let base=format!("/sys/block/{name}");
        zram.push(ZramInfo { name, size_bytes:s.read_u64(&format!("{base}/disksize")), algorithm:s.read(&format!("{base}/comp_algorithm")) });
    }
    zram.sort_by(|a,b|a.name.cmp(&b.name));
    MemoryInfo { total_kb:meminfo(s,"MemTotal:"), available_kb:meminfo(s,"MemAvailable:"), swap_total_kb:meminfo(s,"SwapTotal:"), swap_free_kb:meminfo(s,"SwapFree:"), zram }
}
fn classify_pre_eol(v: u32) -> (&'static str, u8) {
    match v {
        1 => ("good", 1),
        2 => ("warning", 2),
        3..=u32::MAX => ("critical", 3),
        _ => ("unknown", 0),
    }
}

fn classify_life_time(v: u32) -> (&'static str, u8) {
    match v {
        1..=8 => ("good", 1),
        9 => ("warning", 2),
        10..=u32::MAX => ("critical", 3),
        _ => ("unknown", 0),
    }
}

fn classify_health(pre_eol: Option<u32>, life_time: Option<(u32, &str)>) -> (String, String) {
    let mut rank = 0u8;
    let mut evidence = Vec::new();
    if let Some(v) = pre_eol {
        let (health, r) = classify_pre_eol(v);
        rank = rank.max(r);
        evidence.push(format!("runtime:pre_eol_info={v}"));
        if health == "critical" { rank = 3; }
    }
    if let Some((v, key)) = life_time {
        let (health, r) = classify_life_time(v);
        rank = rank.max(r);
        evidence.push(format!("runtime:{key}={v}"));
        if health == "critical" { rank = 3; }
    }
    let health = match rank {
        3 => "critical",
        2 => "warning",
        1 => "good",
        _ => "unknown",
    };
    let evidence = if evidence.is_empty() {
        "runtime:health-unavailable".to_string()
    } else {
        evidence.join(";")
    };
    (health.to_string(), evidence)
}

fn storage(s: &Sysroot) -> Vec<StorageInfo> {
    let mut out=Vec::new();
    for name in s.list_dir("/sys/block") {
        if name.starts_with("loop") || name.starts_with("ram") || name.starts_with("zram") || name.starts_with("dm-") { continue; }
        let base=format!("/sys/block/{name}");
        let model=s.read(&format!("{base}/device/model"));
        let device_type=s.read(&format!("{base}/device/type")).unwrap_or_default();
        let (kind, kind_evidence) = if s.exists(&format!("{base}/device/ufs")) {
            ("ufs", "runtime:/device/ufs")
        } else if name.starts_with("mmcblk") && device_type.to_ascii_lowercase().contains("sd") {
            ("sd", "runtime:mmcblk+device/type=sd")
        } else if name.starts_with("mmcblk") {
            ("emmc", "runtime:mmcblk topology")
        } else {
            ("generic-block", "runtime:/sys/block topology")
        };
        let pre_eol = s.read(&format!("{base}/device/pre_eol_info")).and_then(|raw| parse_u32_auto(&raw).ok());
        let life_time = s.read(&format!("{base}/device/life_time_a")).and_then(|raw| parse_u32_auto(&raw).ok())
            .map(|v| (v, "life_time_a"))
            .or_else(|| s.read(&format!("{base}/device/life_time")).and_then(|raw| parse_u32_auto(&raw).ok()).map(|v| (v, "life_time")));
        let (health, health_evidence) = classify_health(pre_eol, life_time);
        out.push(StorageInfo { name, kind:kind.into(), kind_evidence:kind_evidence.into(), model, size_bytes:s.read_u64(&format!("{base}/size")).map(|v|v.saturating_mul(512)), scheduler:s.read(&format!("{base}/queue/scheduler")), read_ahead_kb:s.read_u64(&format!("{base}/queue/read_ahead_kb")), health, health_evidence });
    }
    out.sort_by(|a,b|a.name.cmp(&b.name)); out
}

fn network(s: &Sysroot) -> NetworkInfo {
    let interfaces=s.read("/proc/net/dev").map(|t|t.lines().skip(2).filter_map(|l|l.split(':').next().map(|x|x.trim().to_string())).filter(|x|!x.is_empty()).collect::<Vec<_>>()).unwrap_or_default();
    NetworkInfo {
        interface_count: interfaces.len(),
        interfaces,
        tcp_congestion: s.read("/proc/sys/net/ipv4/tcp_congestion_control"),
        tcp_allowed: s.read("/proc/sys/net/ipv4/tcp_allowed_congestion_control"),
        tcp_available: s.read("/proc/sys/net/ipv4/tcp_available_congestion_control"),
    }
}
fn security(s: &Sysroot) -> SecurityInfo {
    let selinux=s.read("/sys/fs/selinux/enforce").and_then(|v|v.parse::<u8>().ok()).map(|v|v==1);
    let verified=s.read("/dev/props/ro.boot.verifiedbootstate").or_else(||crate::scene::getprop("ro.boot.verifiedbootstate"));
    let verity=s.read("/dev/props/ro.boot.veritymode").or_else(||crate::scene::getprop("ro.boot.veritymode"));
    let mut dm=Vec::new();
    for name in s.list_dir("/sys/block") { if let Some(v)=s.read(&format!("/sys/block/{name}/dm/name")) { dm.push(format!("{name}:{v}")); } }
    dm.sort();
    let sig=s.read("/sys/module/module/parameters/sig_enforce").and_then(|v|v.parse::<u8>().ok()).map(|v|v==1);
    SecurityInfo { selinux_enforcing:selinux, verified_boot:verified, verity_mode:verity, device_mapper_devices:dm, module_sig_enforce:sig }
}

fn parse_u32_auto(raw: &str) -> Result<u32, String> {
    let v = raw.trim();
    if let Some(hex) = v.strip_prefix("0x").or_else(|| v.strip_prefix("0X")) {
        u32::from_str_radix(hex, 16).map_err(|e| e.to_string())
    } else {
        v.parse::<u32>().map_err(|e| e.to_string())
    }
}

pub fn storage_health(s: &Sysroot) -> zairenkai_core::sentinel::StorageHealth {
    use zairenkai_core::sentinel::StorageHealth::{Critical, Good, Unknown, Warning};
    let mut saw_good = false;
    let mut saw_warning = false;
    for name in s.list_dir("/sys/block") {
        if name.starts_with("loop") || name.starts_with("ram") || name.starts_with("zram") || name.starts_with("dm-") {
            continue;
        }
        let base=format!("/sys/block/{name}/device");
        let pre_eol = s.read(&format!("{base}/pre_eol_info")).and_then(|raw| parse_u32_auto(&raw).ok());
        let life_time = s.read(&format!("{base}/life_time_a")).and_then(|raw| parse_u32_auto(&raw).ok())
            .map(|v| (v, "life_time_a"))
            .or_else(|| s.read(&format!("{base}/life_time")).and_then(|raw| parse_u32_auto(&raw).ok()).map(|v| (v, "life_time")));
        let (health, _) = classify_health(pre_eol, life_time);
        match health.as_str() {
            "critical" => return Critical,
            "warning" => saw_warning = true,
            "good" => saw_good = true,
            _ => {},
        }
    }
    if saw_warning { Warning } else if saw_good { Good } else { Unknown }
}



fn q(s:Option<&str>)->String { s.map(|x|format!("\"{}\"",x.replace('\\',"\\\\").replace('"',"\\\""))).unwrap_or_else(||"null".into()) }
fn opt(v:Option<u64>)->String { v.map(|x|x.to_string()).unwrap_or_else(||"null".into()) }
fn optb(v:Option<bool>)->String { v.map(|x|x.to_string()).unwrap_or_else(||"null".into()) }
fn arr(v:&[String])->String { v.iter().map(|x|format!("\"{}\"",x.replace('\\',"\\\\").replace('"',"\\\""))).collect::<Vec<_>>().join(",") }
pub fn json(inv:&Inventory)->String {
    let z=inv.memory.zram.iter().map(|v|format!("{{\"name\":\"{}\",\"size_bytes\":{},\"algorithm\":{}}}",v.name,opt(v.size_bytes),q(v.algorithm.as_deref()))).collect::<Vec<_>>().join(",");
    let st=inv.storage.iter().map(|v|format!("{{\"name\":\"{}\",\"kind\":\"{}\",\"kind_evidence\":\"{}\",\"model\":{},\"size_bytes\":{},\"scheduler\":{},\"read_ahead_kb\":{},\"health\":\"{}\",\"health_evidence\":\"{}\"}}",v.name,v.kind,q(Some(v.kind_evidence.as_str())),q(v.model.as_deref()),opt(v.size_bytes),q(v.scheduler.as_deref()),opt(v.read_ahead_kb),v.health,q(Some(v.health_evidence.as_str())))).collect::<Vec<_>>().join(",");
    format!("{{\"identity\":{{\"vendor\":\"{}\",\"platform\":{},\"model\":{}}},\"memory\":{{\"total_kb\":{},\"available_kb\":{},\"swap_total_kb\":{},\"swap_free_kb\":{},\"zram\":[{}]}},\"storage\":[{}],\"network\":{{\"interface_count\":{},\"interfaces\":[{}],\"tcp_congestion\":{},\"tcp_allowed\":{},\"tcp_available\":{}}},\"security\":{{\"selinux_enforcing\":{},\"verified_boot\":{},\"verity_mode\":{},\"device_mapper_devices\":[{}],\"module_sig_enforce\":{}}}}}",inv.identity.vendor.as_str(),q(Some(&inv.identity.platform)),q(Some(&inv.identity.model)),opt(inv.memory.total_kb),opt(inv.memory.available_kb),opt(inv.memory.swap_total_kb),opt(inv.memory.swap_free_kb),z,st,inv.network.interface_count,arr(&inv.network.interfaces),q(inv.network.tcp_congestion.as_deref()),q(inv.network.tcp_allowed.as_deref()),q(inv.network.tcp_available.as_deref()),optb(inv.security.selinux_enforcing),q(inv.security.verified_boot.as_deref()),q(inv.security.verity_mode.as_deref()),arr(&inv.security.device_mapper_devices),optb(inv.security.module_sig_enforce))
}

#[cfg(test)]
mod tests {
    use super::*; use std::fs; use std::path::PathBuf;
    fn root()->PathBuf { let p=std::env::temp_dir().join(format!("zairenkai-inventory-{}",std::process::id())); let _=fs::remove_dir_all(&p); fs::create_dir_all(&p).unwrap(); p }
    fn write(r:&PathBuf, path:&str, value:&str) { let p=r.join(path.trim_start_matches('/')); fs::create_dir_all(p.parent().unwrap()).unwrap(); fs::write(p,value).unwrap(); }
    #[test]
    fn storage_health_aggregates_all_devices_before_deciding() {
        let r = root();
        for (name, pre_eol) in [("mmcblk0", "1"), ("sda", "2")] {
            write(&r, &format!("/sys/block/{name}/device/pre_eol_info"), pre_eol);
        }
        assert_eq!(storage_health(&Sysroot::new(&r)), zairenkai_core::sentinel::StorageHealth::Warning);
        write(&r, "/sys/block/sda/device/pre_eol_info", "3");
        assert_eq!(storage_health(&Sysroot::new(&r)), zairenkai_core::sentinel::StorageHealth::Critical);
        let _=fs::remove_dir_all(r);
    }

    #[test]
    fn storage_health_combines_pre_eol_and_lifetime_evidence_conservatively() {
        let r = root();
        write(&r, "/sys/block/mmcblk0/device/pre_eol_info", "1");
        write(&r, "/sys/block/mmcblk0/device/life_time_a", "10");
        assert_eq!(storage_health(&Sysroot::new(&r)), zairenkai_core::sentinel::StorageHealth::Critical);
        let _=fs::remove_dir_all(r);
    }

    #[test]
    fn inventory_reads_memory_and_network() { let r=root(); fs::create_dir_all(r.join("proc" )).unwrap(); fs::write(r.join("proc/meminfo"),"MemTotal:       4096000 kB\nMemAvailable:   2048000 kB\nSwapTotal:      1024000 kB\nSwapFree:        900000 kB\n").unwrap(); fs::create_dir_all(r.join("proc/net")).unwrap(); fs::write(r.join("proc/net/dev"),"Inter-| Receive | Transmit |\n face | bytes\neth0: 0 0 0 0 0 0 0 0\n").unwrap(); fs::write(r.join("proc/sys/net/ipv4/tcp_congestion_control"),"cubic\n").unwrap(); fs::write(r.join("proc/sys/net/ipv4/tcp_allowed_congestion_control"),"cubic bbr\n").unwrap(); fs::write(r.join("proc/sys/net/ipv4/tcp_available_congestion_control"),"reno cubic bbr\n").unwrap(); let i=collect(&Sysroot::new(&r),crate::platform::PlatformIdentity{oem:crate::platform::OemVendor::Unknown,vendor:crate::platform::SocVendor::Unknown,platform:"x".into(),model:"m".into(),compatible:"".into(),evidence:vec![]}); assert_eq!(i.memory.total_kb,Some(4096000)); assert_eq!(i.network.interface_count,1); assert_eq!(i.network.tcp_congestion.as_deref(),Some("cubic")); assert_eq!(i.network.tcp_allowed.as_deref(),Some("cubic bbr")); let _=fs::remove_dir_all(r); }
}
