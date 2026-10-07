// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Deterministic runtime policy evaluator.
//!
//! The evaluator consumes normalized telemetry rather than reading platform
//! nodes itself. This keeps policy portable across Qualcomm, MediaTek, Exynos,
//! Tensor, GKI and NonGKI implementations.

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

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PolicyInput<'a> {
    pub battery_pct: Option<u8>,
    pub hottest_mdeg: Option<i32>,
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
    let thermal_limit = input.hottest_mdeg.is_some_and(|v| v >= 46_000);
    let performance_release = input.hottest_mdeg.is_some_and(|v| v < 42_000);
    let performance_hold = input.hottest_mdeg.is_some_and(|v| v < 44_000);
    let confidence = match (input.battery_pct.is_some(), input.hottest_mdeg.is_some()) {
        (true, true) => Confidence::High,
        (true, false) | (false, true) => Confidence::Medium,
        (false, false) => Confidence::Low,
    };

    if low_battery || thermal_limit {
        return PolicyDecision {
            intent: if thermal_limit { PolicyIntent::ThermalProtect } else { PolicyIntent::BatterySaver },
            reason: if thermal_limit { PolicyReason::ThermalLimit } else { PolicyReason::LowBattery },
            confidence,
        };
    }

    match input.previous_mode {
        Some("powersave") if battery_hold && !performance_release => {
            return PolicyDecision { intent: PolicyIntent::BatterySaver, reason: PolicyReason::HoldBatterySaver, confidence };
        }
        Some("performance") if input.external_power && performance_hold => {
            return PolicyDecision { intent: PolicyIntent::SustainedPerformance, reason: PolicyReason::HoldPerformance, confidence };
        }
        _ => {}
    }

    if input.external_power && performance_release {
        PolicyDecision {
            intent: PolicyIntent::SustainedPerformance,
            reason: PolicyReason::PerformanceHeadroom,
            confidence,
        }
    } else if confidence == Confidence::Low {
        PolicyDecision { intent: PolicyIntent::Balanced, reason: PolicyReason::InsufficientTelemetry, confidence }
    } else {
        PolicyDecision { intent: PolicyIntent::Balanced, reason: PolicyReason::NormalOperation, confidence }
    }
}
