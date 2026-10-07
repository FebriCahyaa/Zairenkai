// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Deterministic runtime policy evaluator.
//!
//! Thermal input is a normalized runtime signal. The evaluator deliberately
//! does not know Celsius thresholds or vendor thermal naming; those belong to
//! the runtime thermal authority which derives headroom from actual trip points.
//!
//! Copyright (C) 2026 FebriCahyaa

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum PolicyIntent {
    BatterySaver,
    Balanced,
    SustainedPerformance,
    ThermalProtect,
}

impl PolicyIntent {
    pub const fn mode(self) -> &'static str {
        match self {
            Self::BatterySaver => "powersave",
            Self::Balanced => "balance",
            Self::SustainedPerformance => "performance",
            Self::ThermalProtect => "powersave",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum PolicyReason {
    LowBattery,
    ThermalLimit,
    PerformanceHeadroom,
    NormalOperation,
    InsufficientTelemetry,
    HoldBatterySaver,
    HoldPerformance,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Confidence { Low, Medium, High }

/// Normalized thermal authority output.
///
/// `headroom_permille` is derived from the active zone's runtime trip interval,
/// not from a universal Celsius threshold. 0 means the selected next trip is at
/// the current temperature; 1000 means the zone has the full observed interval
/// remaining. `critical_reached` is a direct comparison against that same zone's
/// runtime critical trip point.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct ThermalSignal {
    pub telemetry_complete: bool,
    pub headroom_permille: Option<u16>,
    pub critical_reached: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PolicyInput<'a> {
    pub battery_pct: Option<u8>,
    pub thermal: ThermalSignal,
    pub external_power: bool,
    pub previous_mode: Option<&'a str>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct PolicyDecision {
    pub intent: PolicyIntent,
    pub reason: PolicyReason,
    pub confidence: Confidence,
}

pub fn evaluate(input: PolicyInput<'_>) -> PolicyDecision {
    let low_battery = input.battery_pct.is_some_and(|v| v <= 20);
    let battery_hold = input.battery_pct.is_none_or(|v| v <= 25);
    let confidence = match (input.battery_pct.is_some(), input.thermal.telemetry_complete) {
        (true, true) => Confidence::High,
        (true, false) | (false, true) => Confidence::Medium,
        (false, false) => Confidence::Low,
    };

    if input.thermal.critical_reached {
        return PolicyDecision {
            intent: PolicyIntent::ThermalProtect,
            reason: PolicyReason::ThermalLimit,
            confidence,
        };
    }
    if low_battery {
        return PolicyDecision {
            intent: PolicyIntent::BatterySaver,
            reason: PolicyReason::LowBattery,
            confidence,
        };
    }

    let headroom = input.thermal.headroom_permille;
    let performance_release = input.external_power && input.thermal.telemetry_complete
        && headroom.is_some_and(|v| v >= 600);
    let performance_hold = input.external_power && input.thermal.telemetry_complete
        && headroom.is_some_and(|v| v >= 300);

    match input.previous_mode {
        Some("powersave") if battery_hold && !performance_release => {
            return PolicyDecision { intent: PolicyIntent::BatterySaver, reason: PolicyReason::HoldBatterySaver, confidence };
        }
        Some("performance") if performance_hold => {
            return PolicyDecision { intent: PolicyIntent::SustainedPerformance, reason: PolicyReason::HoldPerformance, confidence };
        }
        _ => {}
    }

    if performance_release {
        PolicyDecision {
            intent: PolicyIntent::SustainedPerformance,
            reason: PolicyReason::PerformanceHeadroom,
            confidence,
        }
    } else if !input.thermal.telemetry_complete {
        PolicyDecision { intent: PolicyIntent::Balanced, reason: PolicyReason::InsufficientTelemetry, confidence }
    } else {
        PolicyDecision { intent: PolicyIntent::Balanced, reason: PolicyReason::NormalOperation, confidence }
    }
}
