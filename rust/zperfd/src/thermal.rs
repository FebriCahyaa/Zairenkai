// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Thermal policy and boost envelope.
//!
//! Thermal handling is intentionally asymmetric: telemetry can reduce a
//! requested performance envelope, but never increase it. Runtime capability
//! and Sentinel remain the final authority for mutation.
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
            Self::Unknown => Some(80),
            Self::Nominal => None,
            Self::Warm => Some(95),
            Self::Hot => Some(80),
            Self::Critical => Some(65),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Snapshot {
    pub hottest_mdeg: Option<i32>,
    pub control_temp_mdeg: Option<i32>,
    pub performance_trip_mdeg: Option<i32>,
    pub critical_trip_mdeg: Option<i32>,
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

pub fn classify(hottest_mdeg: Option<i32>) -> Band {
    match hottest_mdeg {
        None => Band::Unknown,
        Some(v) if v >= 47_000 => Band::Critical,
        Some(v) if v >= 44_000 => Band::Hot,
        Some(v) if v >= 41_000 => Band::Warm,
        Some(_) => Band::Nominal,
    }
}

fn next_trip_for_control(t: &Topology, temp_mdeg: Option<i32>) -> Option<i32> {
    let temp = temp_mdeg? as i64;
    let mut primary = Vec::new();
    let mut fallback = Vec::new();
    for trip in t.thermal_zones.iter()
        .filter(|z| matches!(z.role, ThermalRole::Cpu | ThermalRole::Gpu | ThermalRole::SoC))
        .flat_map(|z| z.trips.iter())
    {
        if matches!(trip.kind, ThermalTripType::Hot | ThermalTripType::Critical) {
            primary.push(trip.temp_mdeg);
        } else if trip.kind == ThermalTripType::Passive {
            fallback.push(trip.temp_mdeg);
        }
    }
    primary.sort_unstable();
    primary.dedup();
    fallback.sort_unstable();
    fallback.dedup();
    primary.into_iter()
        .find(|&v| v >= temp)
        .or_else(|| fallback.into_iter().find(|&v| v >= temp))
        .or_else(|| {
            t.thermal_zones.iter()
                .filter(|z| matches!(z.role, ThermalRole::Cpu | ThermalRole::Gpu | ThermalRole::SoC))
                .flat_map(|z| z.trips.iter())
                .filter(|trip| matches!(trip.kind, ThermalTripType::Passive | ThermalTripType::Hot | ThermalTripType::Critical))
                .map(|trip| trip.temp_mdeg)
                .max()
        })
        .map(|v| v as i32)
}

fn critical_trip(t: &Topology) -> Option<i32> {
    t.thermal_zones.iter()
        .flat_map(|z| z.trips.iter())
        .filter(|trip| trip.kind == ThermalTripType::Critical)
        .map(|trip| trip.temp_mdeg as i32)
        .min()
}

pub fn snapshot(s: &Sysroot, t: &Topology) -> Snapshot {
    let hottest_mdeg = t.thermal_zones.iter().filter_map(|z| z.temp_mdeg).max();
    let control_temp_mdeg = t.thermal_zones.iter()
        .filter(|z| matches!(z.role, ThermalRole::Cpu | ThermalRole::Gpu | ThermalRole::SoC))
        .filter_map(|z| z.temp_mdeg)
        .max()
        .map(|v| v as i32)
        .or_else(|| hottest_mdeg.map(|v| v as i32));
    let performance_trip_mdeg = next_trip_for_control(t, control_temp_mdeg);
    let critical_trip_mdeg = critical_trip(t);
    Snapshot {
        hottest_mdeg: hottest_mdeg.map(|v| v as i32),
        control_temp_mdeg,
        performance_trip_mdeg,
        critical_trip_mdeg,
        battery_pct: crate::scene::battery_percent(s),
        external_power: crate::scene::charging(s),
    }
}

pub fn envelope(snapshot: Snapshot) -> Envelope {
    let band = if snapshot.critical_trip_mdeg.is_some_and(|v| snapshot.hottest_mdeg.is_some_and(|hot| hot >= v)) {
        Band::Critical
    } else if snapshot.performance_trip_mdeg.is_some_and(|trip| snapshot.control_temp_mdeg.is_some_and(|temp| temp >= trip)) {
        Band::Critical
    } else if let (Some(trip), Some(temp)) = (snapshot.performance_trip_mdeg, snapshot.control_temp_mdeg) {
        match trip.saturating_sub(temp) {
            0..=2_999 => Band::Hot,
            3_000..=6_999 => Band::Warm,
            _ => Band::Nominal,
        }
    } else if snapshot.hottest_mdeg.is_some_and(|v| v >= 50_000) {
        Band::Critical
    } else {
        classify(snapshot.control_temp_mdeg)
    };
    let reason = match band {
        Band::Unknown => "thermal telemetry unavailable; disable performance boost",
        Band::Nominal => "normal thermal headroom",
        Band::Warm => "thermal headroom reduced",
        Band::Hot => "thermal headroom low; reduce sustained performance",
        Band::Critical => "critical thermal state; remove performance boost",
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

/// Produce a constrained clone of a profile mode. Constraints only reduce
/// performance requests. Unknown thermal state removes explicit boost hints.
pub fn kernel_guard_limit(snapshot: Snapshot) -> i32 {
    let raw = snapshot.performance_trip_mdeg.map(|v| v.saturating_sub(2_000)).unwrap_or(48_000);
    raw.clamp(35_000, 95_000)
}

pub fn kernel_guard_release(limit_mdeg: i32) -> i32 {
    limit_mdeg.saturating_sub(3_000)
}

pub fn guard_zones(t: &Topology) -> Vec<String> {
    let mut zones = Vec::new();
    for role in [ThermalRole::SoC, ThermalRole::Cpu, ThermalRole::Gpu] {
        for zone in t.thermal_zones.iter().filter(|z| z.role == role) {
            if zone.name.len() < 32 && !zones.contains(&zone.name) {
                zones.push(zone.name.clone());
                if zones.len() == 4 { return zones; }
            }
        }
    }
    if zones.is_empty() {
        for zone in &t.thermal_zones {
            if zone.name.len() < 32 && !zones.contains(&zone.name) {
                zones.push(zone.name.clone());
                if zones.len() == 4 { break; }
            }
        }
    }
    zones
}

pub fn constrain_mode(mode: &Mode, env: Envelope) -> Mode {
    let mut out = mode.clone();
    out.cpu.uclamp_min_pct = scale_pct(out.cpu.uclamp_min_pct, env.boost_permille);
    out.cpu.input_boost_pct = scale_pct(out.cpu.input_boost_pct, env.boost_permille);
    out.cpu.input_boost_ms = out.cpu.input_boost_ms.map(|ms| {
        let scaled = (ms as u64 * env.boost_permille as u64 + 500) / 1000;
        scaled.clamp(0, 1500) as u32
    });
    out.cpu.sched_boost = match env.band {
        Band::Nominal => out.cpu.sched_boost,
        Band::Warm => out.cpu.sched_boost.map(|v| v.clamp(0, 25)),
        Band::Hot => out.cpu.sched_boost.map(|v| v.clamp(0, 10)),
        Band::Critical | Band::Unknown => Some(0),
    };
    out.gpu.min_perf_pct = scale_pct(out.gpu.min_perf_pct, env.boost_permille);

    if let Some(cap) = env.max_perf_cap_pct {
        out.cpu.max_freq = Some(FreqSpec::Spec(format!("{cap}%")));
        out.gpu.max_freq = Some(FreqSpec::Spec(format!("{cap}%")));
        out.cpu.max_perf_pct = Some(out.cpu.max_perf_pct.unwrap_or(100).min(cap));
        out.gpu.max_perf_pct = Some(out.gpu.max_perf_pct.unwrap_or(100).min(cap));
    }
    if matches!(env.band, Band::Unknown | Band::Critical) {
        out.cpu.input_boost_ms = Some(0);
        out.cpu.input_boost_pct = Some(0);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{Cpu, Gpu, Io, Mem};

    fn mode() -> Mode {
        Mode {
            cpu: Cpu {
                max_perf_pct: Some(100),
                min_perf_pct: Some(50),
                uclamp_min_pct: Some(40),
                input_boost_pct: Some(80),
                input_boost_ms: Some(2000),
                sched_boost: Some(50),
                ..Cpu::default()
            },
            gpu: Gpu { min_perf_pct: Some(50), max_perf_pct: Some(100), ..Gpu::default() },
            mem: Mem::default(),
            io: Io::default(),
        }
    }

    #[test]
    fn critical_thermal_zeroes_boosts_and_caps_frequency() {
        let out = constrain_mode(&mode(), envelope(Snapshot {
            hottest_mdeg: Some(48_000),
            control_temp_mdeg: Some(48_000),
            performance_trip_mdeg: Some(60_000),
            critical_trip_mdeg: Some(90_000),
            battery_pct: Some(60),
            external_power: true,
        }));
        assert_eq!(out.cpu.uclamp_min_pct, Some(0));
        assert_eq!(out.cpu.input_boost_pct, Some(0));
        assert_eq!(out.cpu.sched_boost, Some(0));
        assert!(matches!(out.cpu.max_freq, Some(FreqSpec::Spec(ref s)) if s == "65%"));
    }

    #[test]
    fn unknown_thermal_removes_boost_and_caps_frequency() {
        let out = constrain_mode(&mode(), envelope(Snapshot {
            hottest_mdeg: None,
            control_temp_mdeg: None,
            performance_trip_mdeg: None,
            critical_trip_mdeg: None,
            battery_pct: Some(80),
            external_power: true,
        }));
        assert_eq!(out.cpu.uclamp_min_pct, Some(0));
        assert_eq!(out.cpu.input_boost_pct, Some(0));
        assert!(matches!(out.cpu.max_freq, Some(FreqSpec::Spec(ref s)) if s == "80%"));
    }

    #[test]
    fn trip_headroom_overrides_hardcoded_temperature_band() {
        let e = envelope(Snapshot {
            hottest_mdeg: Some(70_000),
            control_temp_mdeg: Some(70_000),
            performance_trip_mdeg: Some(72_000),
            critical_trip_mdeg: Some(95_000),
            battery_pct: Some(80),
            external_power: true,
        });
        assert_eq!(e.band, Band::Hot);
        assert_eq!(e.boost_permille, 400);
    }

    #[test]
    fn nominal_preserves_profile() {
        let original = mode();
        let out = constrain_mode(&original, envelope(Snapshot {
            hottest_mdeg: Some(35_000),
            control_temp_mdeg: Some(35_000),
            performance_trip_mdeg: Some(80_000),
            critical_trip_mdeg: Some(95_000),
            battery_pct: Some(80),
            external_power: true,
        }));
        assert_eq!(out.cpu.input_boost_pct, original.cpu.input_boost_pct);
        assert_eq!(out.cpu.input_boost_ms, original.cpu.input_boost_ms);
        assert_eq!(out.cpu.sched_boost, original.cpu.sched_boost);
    }
}
