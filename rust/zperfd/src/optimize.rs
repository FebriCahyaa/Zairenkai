// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Read-only optimization planner. It emits bounded, explainable envelopes;
//! actual mutation remains behind Core Authority + Sentinel + transactions.

use crate::inventory::Inventory;
use crate::nodes::Sysroot;
use crate::platform::PlatformIdentity;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OptimizationPlan {
    pub ram_class: &'static str,
    pub zram_present: bool,
    pub storage_class: &'static str,
    pub thermal_safe: bool,
    pub network_control_available: bool,
    pub safe_controls: Vec<&'static str>,
    pub blocked_controls: Vec<&'static str>,
    pub notes: Vec<&'static str>,
}

pub fn plan(s:&Sysroot, identity:&PlatformIdentity, inv:&Inventory)->OptimizationPlan {
    let ram=inv.memory.total_kb.unwrap_or(0);
    let ram_class=match ram { 0=>"unknown", 1..=2_097_151=>"<=2GiB", 2_097_152..=4_194_303=>"<=4GiB", 4_194_304..=6_291_455=>"<=6GiB", _=>">6GiB" };
    let storage_class=if inv.storage.iter().any(|x|x.kind=="ufs") { "ufs" } else if inv.storage.iter().any(|x|x.kind=="emmc") { "emmc" } else if inv.storage.iter().any(|x|x.kind=="sd") { "sd" } else { "generic" };
    let topology = crate::topo::Topology::detect(s);
    let thermal_snapshot = crate::thermal::snapshot(s, &topology);
    let thermal_env = crate::thermal::envelope(&thermal_snapshot);
    let thermal_safe = thermal_snapshot.telemetry_complete && !thermal_snapshot.critical_reached;
    let mut safe=Vec::new(); let mut blocked=Vec::new(); let mut notes=Vec::new();
    if inv.memory.total_kb.is_some() { safe.push("memory.readback"); } else { blocked.push("memory.unknown"); }
    if inv.memory.zram.iter().any(|z|z.size_bytes.unwrap_or(0)>0) { safe.push("zram.readback"); } else { blocked.push("zram.resize"); }
    if inv.network.tcp_available.is_some() { safe.push("network.congestion.select-from-allowlist"); } else { blocked.push("network.congestion.unknown"); }
    if inv.storage.is_empty() { blocked.push("storage.tuning.no-device"); } else { safe.push("storage.queue.readback"); }
    if !thermal_safe { blocked.push("performance.escalation"); notes.push(thermal_env.reason); }
    if let Some(headroom) = thermal_snapshot.headroom_permille {
        if headroom < 600 { notes.push("thermal headroom is below the sustained-performance policy band"); }
    }
    if storage_class=="ufs" { notes.push("treat UFS health and queue surfaces as device-specific; do not infer endurance from model strings"); }
    if storage_class=="emmc" || storage_class=="sd" { notes.push("eMMC/SD lifecycle fields vary by controller; require runtime health evidence before guarded writes"); }
    if ram_class=="<=2GiB" { notes.push("prefer conservative memory pressure policy and avoid stacking multiple reclaim tunables without measurement"); }
    notes.push("planner is advisory; mutation requires runtime capability, authority, Sentinel and transaction readback");
    OptimizationPlan { ram_class,zram_present:!inv.memory.zram.is_empty(),storage_class,thermal_safe,network_control_available:inv.network.tcp_available.is_some(),safe_controls:safe,blocked_controls:blocked,notes }
}

pub fn json(p:&OptimizationPlan)->String {
    let arr=|v:&[&str]|v.iter().map(|x|format!("\"{}\"",x)).collect::<Vec<_>>().join(",");
    format!("{{\"ram_class\":\"{}\",\"zram_present\":{},\"storage_class\":\"{}\",\"thermal_safe\":{},\"network_control_available\":{},\"safe_controls\":[{}],\"blocked_controls\":[{}],\"notes\":[{}]}}",p.ram_class,p.zram_present,p.storage_class,p.thermal_safe,p.network_control_available,arr(&p.safe_controls),arr(&p.blocked_controls),arr(&p.notes))
}
