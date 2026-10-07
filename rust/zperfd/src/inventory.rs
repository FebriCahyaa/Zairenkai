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
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NetworkInfo { pub interface_count: usize, pub interfaces: Vec<String>, pub tcp_congestion: Option<String>, pub tcp_available: Option<String> }
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
fn storage(s: &Sysroot) -> Vec<StorageInfo> {
    let mut out=Vec::new();
    for name in s.list_dir("/sys/block") {
        if name.starts_with("loop") || name.starts_with("ram") || name.starts_with("zram") || name.starts_with("dm-") { continue; }
        let base=format!("/sys/block/{name}");
        let model=s.read(&format!("{base}/device/model"));
        let device_type=s.read(&format!("{base}/device/type")).unwrap_or_default();
        let kind=if s.exists(&format!("{base}/device/ufs")) {
            "ufs"
        } else if name.starts_with("mmcblk") && device_type.to_ascii_lowercase().contains("sd") {
            "sd"
        } else if name.starts_with("mmcblk") {
            "emmc"
        } else {
            "generic-block"
        };
        let health=if s.exists(&format!("{base}/device/pre_eol_info")) || s.exists(&format!("{base}/device/life_time")) || s.exists(&format!("{base}/device/life_time_a")) { "reported" } else { "unknown" };
        out.push(StorageInfo { name, kind:kind.into(), model, size_bytes:s.read_u64(&format!("{base}/size")).map(|v|v.saturating_mul(512)), scheduler:s.read(&format!("{base}/queue/scheduler")), read_ahead_kb:s.read_u64(&format!("{base}/queue/read_ahead_kb")), health:health.into() });
    }
    out.sort_by(|a,b|a.name.cmp(&b.name)); out
}
fn network(s: &Sysroot) -> NetworkInfo {
    let interfaces=s.read("/proc/net/dev").map(|t|t.lines().skip(2).filter_map(|l|l.split(':').next().map(|x|x.trim().to_string())).filter(|x|!x.is_empty()).collect::<Vec<_>>()).unwrap_or_default();
    NetworkInfo { interface_count:interfaces.len(), interfaces, tcp_congestion:s.read("/proc/sys/net/ipv4/tcp_congestion_control"), tcp_available:s.read("/proc/sys/net/ipv4/tcp_available_congestion_control") }
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
    for name in s.list_dir("/sys/block") {
        let base=format!("/sys/block/{name}/device");
        if let Some(raw)=s.read(&format!("{base}/pre_eol_info")) {
            if let Ok(v)=parse_u32_auto(&raw) { if v >= 3 { return zairenkai_core::sentinel::StorageHealth::Critical; } }
        }
        if s.exists(&format!("{base}/life_time_a")) || s.exists(&format!("{base}/life_time")) { return zairenkai_core::sentinel::StorageHealth::Good; }
    }
    zairenkai_core::sentinel::StorageHealth::Unknown
}

pub fn hottest_mdeg(s:&Sysroot, identity:&PlatformIdentity)->Option<i32> {
    crate::platform::scan_thermal(s, identity.vendor).iter().filter_map(|z|z.temp_mdeg).max().map(|v|v as i32)
}

fn q(s:Option<&str>)->String { s.map(|x|format!("\"{}\"",x.replace('\\',"\\\\").replace('"',"\\\""))).unwrap_or_else(||"null".into()) }
fn opt(v:Option<u64>)->String { v.map(|x|x.to_string()).unwrap_or_else(||"null".into()) }
fn optb(v:Option<bool>)->String { v.map(|x|x.to_string()).unwrap_or_else(||"null".into()) }
fn arr(v:&[String])->String { v.iter().map(|x|format!("\"{}\"",x.replace('\\',"\\\\").replace('"',"\\\""))).collect::<Vec<_>>().join(",") }
pub fn json(inv:&Inventory)->String {
    let z=inv.memory.zram.iter().map(|v|format!("{{\"name\":\"{}\",\"size_bytes\":{},\"algorithm\":{}}}",v.name,opt(v.size_bytes),q(v.algorithm.as_deref()))).collect::<Vec<_>>().join(",");
    let st=inv.storage.iter().map(|v|format!("{{\"name\":\"{}\",\"kind\":\"{}\",\"model\":{},\"size_bytes\":{},\"scheduler\":{},\"read_ahead_kb\":{},\"health\":\"{}\"}}",v.name,v.kind,q(v.model.as_deref()),opt(v.size_bytes),q(v.scheduler.as_deref()),opt(v.read_ahead_kb),v.health)).collect::<Vec<_>>().join(",");
    format!("{{\"identity\":{{\"vendor\":\"{}\",\"platform\":{},\"model\":{}}},\"memory\":{{\"total_kb\":{},\"available_kb\":{},\"swap_total_kb\":{},\"swap_free_kb\":{},\"zram\":[{}]}},\"storage\":[{}],\"network\":{{\"interface_count\":{},\"interfaces\":[{}],\"tcp_congestion\":{},\"tcp_available\":{}}},\"security\":{{\"selinux_enforcing\":{},\"verified_boot\":{},\"verity_mode\":{},\"device_mapper_devices\":[{}],\"module_sig_enforce\":{}}}}}",inv.identity.vendor.as_str(),q(Some(&inv.identity.platform)),q(Some(&inv.identity.model)),opt(inv.memory.total_kb),opt(inv.memory.available_kb),opt(inv.memory.swap_total_kb),opt(inv.memory.swap_free_kb),z,st,inv.network.interface_count,arr(&inv.network.interfaces),q(inv.network.tcp_congestion.as_deref()),q(inv.network.tcp_available.as_deref()),optb(inv.security.selinux_enforcing),q(inv.security.verified_boot.as_deref()),q(inv.security.verity_mode.as_deref()),arr(&inv.security.device_mapper_devices),optb(inv.security.module_sig_enforce))
}

#[cfg(test)]
mod tests {
    use super::*; use std::fs; use std::path::PathBuf;
    fn root()->PathBuf { let p=std::env::temp_dir().join(format!("zairenkai-inventory-{}",std::process::id())); let _=fs::remove_dir_all(&p); fs::create_dir_all(&p).unwrap(); p }
    #[test]
    fn inventory_reads_memory_and_network() { let r=root(); fs::create_dir_all(r.join("proc" )).unwrap(); fs::write(r.join("proc/meminfo"),"MemTotal:       4096000 kB\nMemAvailable:   2048000 kB\nSwapTotal:      1024000 kB\nSwapFree:        900000 kB\n").unwrap(); fs::create_dir_all(r.join("proc/net")).unwrap(); fs::write(r.join("proc/net/dev"),"Inter-| Receive | Transmit |\n face | bytes\neth0: 0 0 0 0 0 0 0 0\n").unwrap(); fs::write(r.join("proc/sys/net/ipv4/tcp_congestion_control"),"cubic\n").unwrap(); let i=collect(&Sysroot::new(&r),crate::platform::PlatformIdentity{vendor:crate::platform::SocVendor::Unknown,platform:"x".into(),model:"m".into(),compatible:"".into(),evidence:vec![]}); assert_eq!(i.memory.total_kb,Some(4096000)); assert_eq!(i.network.interface_count,1); assert_eq!(i.network.tcp_congestion.as_deref(),Some("cubic")); let _=fs::remove_dir_all(r); }
}
