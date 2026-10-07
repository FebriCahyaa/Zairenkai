// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Runtime thermal authority and performance envelope.
//!
//! Thermal safety is derived from the live Linux thermal-zone trip topology.
//! Static Atlas data is not consulted to invent thresholds. The selected control
//! zone is the runtime zone with the least normalized headroom; a missing sensor
//! or missing trip topology yields a limited envelope and never an optimistic
//! performance decision.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::config::{FreqSpec, Mode};
use crate::nodes::Sysroot;
use crate::platform::{ThermalRole, ThermalTripType};
use crate::topo::Topology;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Band {
    Unknown,
    Nominal,
    Warm,
    Hot,
    Critical,
}

impl Band {
    pub const fn boost_permille(self) -> u32 {
        match self {
            Self::Unknown => 0,
            Self::Nominal => 1000,
            Self::Warm => 750,
            Self::Hot => 400,
            Self::Critical => 0,
        }
    }

    pub const fn max_perf_cap_pct(self) -> Option<u32> {
        match self {
            Self::Unknown => Some(75),
            Self::Nominal => None,
            Self::Warm => Some(90),
            Self::Hot => Some(75),
            Self::Critical => Some(60),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ZoneSnapshot {
    pub name: String,
    pub role: ThermalRole,
    pub temp_mdeg: i32,
    pub next_trip_mdeg: i32,
    pub previous_trip_mdeg: Option<i32>,
    pub critical_trip_mdeg: Option<i32>,
    pub headroom_mdeg: i32,
    pub headroom_permille: u16,
    pub critical_reached: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Snapshot {
    pub control_zone: Option<String>,
    pub hottest_mdeg: Option<i32>,
    pub control_temp_mdeg: Option<i32>,
    pub performance_trip_mdeg: Option<i32>,
    pub critical_trip_mdeg: Option<i32>,
    pub release_mdeg: Option<i32>,
    pub headroom_mdeg: Option<i32>,
    pub headroom_permille: Option<u16>,
    pub critical_reached: bool,
    pub telemetry_complete: bool,
    pub battery_pct: Option<u32>,
    pub external_power: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Envelope {
    pub band: Band,
    pub boost_permille: u32,
    pub max_perf_cap_pct: Option<u32>,
    pub reason: &'static str,
}

fn controlled_role(role: ThermalRole) -> bool {
    matches!(role, ThermalRole::Cpu | ThermalRole::Gpu | ThermalRole::SoC)
}

fn trips_for_zone(zone: &crate::platform::ThermalZoneInfo) -> Vec<i32> {
    let mut trips = zone.trips.iter()
        .filter(|trip| trip.kind != ThermalTripType::Unknown)
        .map(|trip| trip.temp_mdeg as i32)
        .collect::<Vec<_>>();
    trips.sort_unstable();
    trips.dedup();
    trips
}

fn assess_zone(zone: &crate::platform::ThermalZoneInfo) -> Option<ZoneSnapshot> {
    if !controlled_role(zone.role) {
        return None;
    }
    let temp = zone.temp_mdeg? as i32;
    let trips = trips_for_zone(zone);
    if trips.is_empty() {
        return None;
    }
    let critical_trip = zone.trips.iter()
        .filter(|trip| trip.kind == ThermalTripType::Critical)
        .map(|trip| trip.temp_mdeg as i32)
        .min();
    let critical_reached = critical_trip.is_some_and(|trip| temp >= trip);

    let next_trip = trips.iter().copied().find(|trip| *trip >= temp)
        .or_else(|| critical_trip.filter(|trip| temp >= *trip));
    let next_trip = next_trip?;
    let previous_trip = trips.iter().copied().filter(|trip| *trip < next_trip).max();
    let headroom = next_trip.saturating_sub(temp).max(0);
    let headroom_permille = match previous_trip {
        Some(previous) if next_trip > previous => {
            let span = (next_trip - previous) as i64;
            ((headroom as i64 * 1000) / span).clamp(0, 1000) as u16
        }
        None if next_trip > 0 => {
            ((headroom as i64 * 1000) / next_trip as i64).clamp(0, 1000) as u16
        }
        _ => 0,
    };

    Some(ZoneSnapshot {
        name: zone.name.clone(),
        role: zone.role,
        temp_mdeg: temp,
        next_trip_mdeg: next_trip,
        previous_trip_mdeg: previous_trip,
        critical_trip_mdeg: critical_trip,
        headroom_mdeg: headroom,
        headroom_permille,
        critical_reached,
    })
}

fn select_control_zone(topology: &Topology) -> Option<ZoneSnapshot> {
    let mut zones = topology.thermal_zones.iter().filter_map(assess_zone).collect::<Vec<_>>();
    zones.sort_by_key(|z| (!z.critical_reached, z.headroom_permille, z.headroom_mdeg));
    zones.into_iter().next()
}

pub fn snapshot(s: &Sysroot, topology: &Topology) -> Snapshot {
    let hottest_mdeg = topology.thermal_zones.iter().filter_map(|z| z.temp_mdeg).max().map(|v| v as i32);
    let control = select_control_zone(topology);
    let control_temp_mdeg = control.map(|z| z.temp_mdeg).or_else(|| hottest_mdeg);

    let controlled = topology.thermal_zones.iter().filter(|z| controlled_role(z.role)).collect::<Vec<_>>();
    let telemetry_complete = !controlled.is_empty() && controlled.iter().all(|z| assess_zone(z).is_some());
    let critical_reached = controlled.iter().filter_map(|z| assess_zone(z)).any(|z| z.critical_reached);

    Snapshot {
        control_zone: control.as_ref().map(|z| z.name.clone()),
        hottest_mdeg,
        control_temp_mdeg,
        performance_trip_mdeg: control.as_ref().map(|z| z.next_trip_mdeg),
        critical_trip_mdeg: control.as_ref().and_then(|z| z.critical_trip_mdeg),
        release_mdeg: control.as_ref().and_then(|z| z.previous_trip_mdeg.or(Some(z.temp_mdeg)).filter(|v| *v < z.next_trip_mdeg)),
        headroom_mdeg: control.as_ref().map(|z| z.headroom_mdeg),
        headroom_permille: control.as_ref().map(|z| z.headroom_permille),
        critical_reached,
        telemetry_complete,
        battery_pct: crate::scene::battery_percent(s),
        external_power: crate::scene::charging(s),
    }
}

pub fn envelope(snapshot: &Snapshot) -> Envelope {
    let band = if !snapshot.telemetry_complete {
        Band::Unknown
    } else if snapshot.critical_reached {
        Band::Critical
    } else {
        match snapshot.headroom_permille {
            Some(v) if v >= 600 => Band::Nominal,
            Some(v) if v >= 300 => Band::Warm,
            Some(_) => Band::Hot,
            None => Band::Unknown,
        }
    };
    let reason = match band {
        Band::Unknown => "thermal telemetry or trip topology incomplete; use limited performance envelope",
        Band::Nominal => "runtime thermal trip headroom is nominal",
        Band::Warm => "runtime thermal trip headroom is reduced",
        Band::Hot => "runtime thermal trip headroom is low; reduce sustained performance",
        Band::Critical => "runtime critical thermal trip reached; remove performance boost",
    };
    Envelope {
        band,
        boost_permille: band.boost_permille(),
        max_perf_cap_pct: band.max_perf_cap_pct(),
        reason,
    }
}

fn scale_pct(value: Option<u32>, permille: u32) -> Option<u32> {
    value.map(|v| ((v as u64 * permille as u64 + 500) / 1000).min(100) as u32)
}

/// Runtime-derived trip consumed by the kernel guard. There is no universal
/// Celsius policy here: the value is an actual trip point exposed by the live
/// thermal framework.
pub fn kernel_guard_limit(snapshot: &Snapshot) -> Option<i32> {
    snapshot.performance_trip_mdeg.filter(|trip| snapshot.telemetry_complete && *trip > 0)
}

/// Re-arm point is derived from the same zone's previous hardware trip when it
/// exists, otherwise from the current observed temperature. This makes the
/// hysteresis device-relative rather than a fixed Celsius delta.
pub fn kernel_guard_release(snapshot: &Snapshot) -> Option<i32> {
    let limit = kernel_guard_limit(snapshot)?;
    snapshot.release_mdeg.filter(|release| *release < limit)
}

pub fn guard_zones(snapshot: &Snapshot) -> Vec<String> {
    snapshot.control_zone.iter()
        .filter(|name| name.len() < 32)
        .cloned()
        .collect()
}

pub fn constrain_mode(mode: &Mode, env: Envelope) -> Mode {
    let mut out = mode.clone();
    out.cpu.uclamp_min_pct = scale_pct(out.cpu.uclamp_min_pct, env.boost_permille);
    out.cpu.input_boost_pct = scale_pct(out.cpu.input_boost_pct, env.boost_permille);
    out.cpu.input_boost_ms = out.cpu.input_boost_ms.map(|ms| {
        let scaled = (ms as u64 * env.boost_permille as u64 + 500) / 1000;
        scaled.min(1500) as u32
    });
    out.cpu.sched_boost = match env.band {
        Band::Nominal => out.cpu.sched_boost,
        Band::Warm => out.cpu.sched_boost.map(|v| v.clamp(0, 25)),
        Band::Hot => out.cpu.sched_boost.map(|v| v.clamp(0, 10)),
        Band::Critical | Band::Unknown => Some(0),
    };
    out.gpu.min_perf_pct = scale_pct(out.gpu.min_perf_pct, env.boost_permille);

    if let Some(cap) = env.max_perf_cap_pct {
        out.cpu.max_perf_pct = Some(out.cpu.max_perf_pct.unwrap_or(100).min(cap));
        out.gpu.max_perf_pct = Some(out.gpu.max_perf_pct.unwrap_or(100).min(cap));
        // Percent-based FreqSpec values are resolved against the live OPP table
        // later; no synthetic frequency is invented here.
        if out.cpu.max_freq.is_none() { out.cpu.max_freq = Some(FreqSpec::Spec(format!("{cap}%"))); }
        if out.gpu.max_freq.is_none() { out.gpu.max_freq = Some(FreqSpec::Spec(format!("{cap}%"))); }
    }
    if matches!(env.band, Band::Unknown | Band::Critical) {
        out.cpu.uclamp_min_pct = Some(0);
        out.cpu.input_boost_ms = Some(0);
        out.cpu.input_boost_pct = Some(0);
        out.gpu.min_perf_pct = Some(0);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{Cpu, Gpu, Io, Mem};
    use crate::platform::{ThermalProvider, ThermalRole, ThermalTrip};

    fn mode() -> Mode {
        Mode {
            cpu: Cpu {
                max_perf_pct: Some(100), min_perf_pct: Some(50), uclamp_min_pct: Some(40),
                input_boost_pct: Some(80), input_boost_ms: Some(2000), sched_boost: Some(50), ..Cpu::default()
            },
            gpu: Gpu { min_perf_pct: Some(50), max_perf_pct: Some(100), ..Gpu::default() },
            mem: Mem::default(), io: Io::default(),
        }
    }

    fn zone(temp: i64, trips: &[(i64, ThermalTripType)]) -> crate::platform::ThermalZoneInfo {
        crate::platform::ThermalZoneInfo {
            name: "cpu-zone".into(), ty: "cpu".into(), temp_mdeg: Some(temp), provider: ThermalProvider::LinuxThermal,
            role: ThermalRole::Cpu, trips: trips.iter().map(|(t, k)| ThermalTrip { temp_mdeg: *t, kind: *k }).collect(),
        }
    }

    #[test]
    fn trip_interval_drives_band_not_celsius() {
        let topology = Topology { thermal_zones: vec![zone(70_000, &[(72_000, ThermalTripType::Hot), (95_000, ThermalTripType::Critical)])],
            ..test_topology() };
        let s = snapshot(&Sysroot::new(std::env::temp_dir().join("zairenkai-thermal-test-nonexistent")), &topology);
        assert!(s.telemetry_complete);
        assert_eq!(s.performance_trip_mdeg, Some(72_000));
        assert!(s.headroom_permille.unwrap_or_default() < 400);
        assert_eq!(envelope(&s).band, Band::Hot);
    }

    #[test]
    fn normalized_headroom_is_independent_of_absolute_temperature() {
        let mut a = zone(80_000, &[(70_000, ThermalTripType::Passive), (90_000, ThermalTripType::Hot), (100_000, ThermalTripType::Critical)]);
        a.name = "cpu-a".into();
        let mut b = zone(40_000, &[(30_000, ThermalTripType::Passive), (50_000, ThermalTripType::Hot), (70_000, ThermalTripType::Critical)]);
        b.name = "cpu-b".into();
        let mut sa = test_topology();
        sa.thermal_zones = vec![a];
        let mut sb = test_topology();
        sb.thermal_zones = vec![b];
        let a = snapshot(&Sysroot::new(std::env::temp_dir().join("zairenkai-thermal-test-nonexistent")), &sa);
        let b = snapshot(&Sysroot::new(std::env::temp_dir().join("zairenkai-thermal-test-nonexistent")), &sb);
        assert_eq!(a.headroom_permille, Some(500));
        assert_eq!(b.headroom_permille, Some(500));
        assert_eq!(envelope(&a).band, Band::Warm);
        assert_eq!(envelope(&b).band, Band::Warm);
    }

    #[test]
    fn per_zone_selection_prevents_cross_zone_temperature_mix() {
        let mut a = zone(70_000, &[(72_000, ThermalTripType::Hot), (95_000, ThermalTripType::Critical)]);
        a.name = "cpu".into();
        let mut b = zone(55_000, &[(60_000, ThermalTripType::Hot), (65_000, ThermalTripType::Critical)]);
        b.name = "gpu".into(); b.role = ThermalRole::Gpu;
        let topology = Topology { thermal_zones: vec![a, b], ..test_topology() };
        let s = snapshot(&Sysroot::new(std::env::temp_dir().join("zairenkai-thermal-test-nonexistent")), &topology);
        assert_eq!(s.performance_trip_mdeg, Some(60_000));
        assert_eq!(s.critical_trip_mdeg, Some(65_000));
    }

    #[test]
    fn unknown_thermal_is_limited_not_optimistic() {
        let topology = Topology { thermal_zones: vec![zone(45_000, &[])], ..test_topology() };
        let s = snapshot(&Sysroot::new(std::env::temp_dir().join("zairenkai-thermal-test-nonexistent")), &topology);
        let e = envelope(&s);
        assert_eq!(e.band, Band::Unknown);
        assert_eq!(e.boost_permille, 0);
        let out = constrain_mode(&mode(), e);
        assert_eq!(out.cpu.input_boost_pct, Some(0));
        assert_eq!(out.cpu.uclamp_min_pct, Some(0));
        assert_eq!(out.gpu.min_perf_pct, Some(0));
    }

    #[test]
    fn critical_trip_is_zone_local() {
        let topology = Topology { thermal_zones: vec![zone(90_000, &[(95_000, ThermalTripType::Hot), (100_000, ThermalTripType::Critical)]), {
            let mut z = zone(96_000, &[(97_000, ThermalTripType::Hot), (97_000, ThermalTripType::Critical)]); z.name = "gpu".into(); z.role = ThermalRole::Gpu; z
        }], ..test_topology() };
        let s = snapshot(&Sysroot::new(std::env::temp_dir().join("zairenkai-thermal-test-nonexistent")), &topology);
        assert!(s.critical_reached);
        assert_eq!(s.critical_trip_mdeg, Some(97_000));
    }

    #[test]
    fn guard_release_comes_from_runtime_trip_topology() {
        let topology = Topology { thermal_zones: vec![zone(71_000, &[(60_000, ThermalTripType::Passive), (72_000, ThermalTripType::Hot), (95_000, ThermalTripType::Critical)])], ..test_topology() };
        let s = snapshot(&Sysroot::new(std::env::temp_dir().join("zairenkai-thermal-test-nonexistent")), &topology);
        assert_eq!(kernel_guard_limit(&s), Some(72_000));
        assert_eq!(kernel_guard_release(&s), Some(60_000));
    }

    fn test_topology() -> Topology {
        Topology { release: "test".into(), gki: false, kernel_flavor: crate::platform::KernelFlavor::NonGki,
            kernel_generation: "6.6".into(), cgroup_v2: false, has_msm_perf: false, policies: vec![], gpu: None,
            thermal_zones: vec![], top_app_uclamp: None, stune_top: None, cpu_boost_dir: None,
            identity: crate::platform::PlatformIdentity { vendor: crate::platform::SocVendor::Unknown, platform: "".into(), model: "".into(), compatible: "".into(), evidence: vec![] } }
    }
}
