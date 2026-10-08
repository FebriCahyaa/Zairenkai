// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Closed-loop Frame Aware Scheduler controller.
//!
//! The controller deliberately separates observation from actuation. It uses
//! frame deadline error, jank, process utilization and thermal headroom to
//! calculate a bounded transient boost. It never bypasses ZKFC's thermal or
//! license gate.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::config::AdaptiveConfig;
use crate::frame::FrameMetrics;
use crate::interaction::InteractionSnapshot;
use crate::scene_engine::{SceneKind, SceneSnapshot};
use crate::thermal::Snapshot as ThermalSnapshot;
use crate::workload::WorkloadSnapshot;

#[derive(Clone, Debug)]
pub struct FasDecision {
    pub active: bool,
    pub confidence: u8,
    pub extra_boost_pct: u32,
    pub target_uclamp_min_pct: u32,
    pub target_uclamp_max_pct: u32,
    pub cpu_floor_pct: u32,
    pub affinity_hint: bool,
    pub reason: &'static str,
}

impl Default for FasDecision {
    fn default() -> Self {
        Self {
            active: false,
            confidence: 0,
            extra_boost_pct: 0,
            target_uclamp_min_pct: 0,
            target_uclamp_max_pct: 100,
            cpu_floor_pct: 0,
            affinity_hint: false,
            reason: "inactive",
        }
    }
}

pub struct FasController {
    integral: f64,
    previous_error: f64,
    bad_windows: u8,
    good_windows: u8,
    last_pid: Option<i32>,
    last_boost_pct: u32,
}

impl Default for FasController {
    fn default() -> Self { Self::new() }
}

impl FasController {
    pub fn new() -> Self {
        Self { integral: 0.0, previous_error: 0.0, bad_windows: 0, good_windows: 0, last_pid: None, last_boost_pct: 0 }
    }

    pub fn reset(&mut self) {
        self.integral = 0.0;
        self.previous_error = 0.0;
        self.bad_windows = 0;
        self.good_windows = 0;
        self.last_pid = None;
        self.last_boost_pct = 0;
    }

    pub fn update(
        &mut self,
        scene: &SceneSnapshot,
        workload: Option<&WorkloadSnapshot>,
        frame: &FrameMetrics,
        thermal: &ThermalSnapshot,
        interaction: &InteractionSnapshot,
        base_uclamp_min_pct: u32,
        config: &AdaptiveConfig,
    ) -> FasDecision {
        if !config.enabled { return self.inactive(); }
        let Some(workload) = workload else { return self.inactive(); };
        if !scene.interactive || matches!(scene.kind, SceneKind::Idle | SceneKind::System) || !workload.active {
            return self.inactive();
        }
        if self.last_pid != Some(workload.pid) {
            self.reset_session(workload.pid);
        }

        let headroom = thermal.headroom_permille.unwrap_or(0) as f64 / 1000.0;
        if thermal.critical_reached || !thermal.telemetry_complete || headroom < 0.10 {
            self.bad_windows = 0;
            self.good_windows = 0;
            self.integral *= 0.5;
            return FasDecision {
                active: false,
                confidence: workload.confidence.min(frame.confidence),
                target_uclamp_min_pct: base_uclamp_min_pct.min(100),
                target_uclamp_max_pct: 100,
                reason: "thermal guard",
                ..FasDecision::default()
            };
        }

        let budget_ms = if frame.available { frame.budget_ms.max(1.0) } else { config.frame_budget_ms.max(1.0) };
        let p95_error = if frame.available { (frame.p95_ms - budget_ms) / budget_ms } else { 0.0 };
        let jank_error = if frame.available { frame.jank_ratio } else { 0.0 };
        let top_thread_util = workload.top_thread_util_pct();
        let render_util = workload.render_util_pct();
        let run_queue_delay_ms = workload.run_queue_delay_ms.unwrap_or(0.0);
        let top_thread_run_queue_delay_ms = workload.top_thread_run_queue_delay_ms();
        let gpu_util_pct = workload.gpu_util_pct.unwrap_or(0.0);
        let cpu_error = ((workload.cpu_util_pct - 80.0) / 40.0).max(0.0);
        let contention_error = ((run_queue_delay_ms.max(top_thread_run_queue_delay_ms) - 1.0) / 4.0).clamp(0.0, 1.5);
        let thread_error = ((top_thread_util.max(render_util) - 70.0) / 30.0).max(0.0);
        let gpu_error = ((gpu_util_pct - 82.0) / 18.0).max(0.0);
        let frame_error = if frame.available && frame.fresh {
            p95_error.max(0.0) * 1.0 + jank_error * 0.8
        } else {
            0.0
        };
        let raw_error = (frame_error + cpu_error + thread_error * 0.6 + contention_error * 0.4 + gpu_error * 0.25).clamp(0.0, 2.0);
        let derivative = raw_error - self.previous_error;
        if frame.fresh || !frame.available {
            self.previous_error = raw_error;
            self.integral = (self.integral + raw_error).clamp(0.0, 6.0);
        }

        let bad_frame = frame.available && frame.fresh
            && (p95_error > 0.03 || jank_error * 100.0 > config.jank_threshold_pct as f64);
        let bad_workload = workload.cpu_util_pct > 88.0
            || top_thread_util > 90.0
            || render_util > 90.0
            || run_queue_delay_ms > 3.0
            || top_thread_run_queue_delay_ms > 3.0
            || gpu_util_pct > 92.0;
        let bad = bad_frame || bad_workload;
        let good_frame = !frame.available || !frame.fresh || (p95_error < -0.03 && jank_error < 0.03);
        let good = good_frame
            && workload.cpu_util_pct < 75.0
            && top_thread_util < 80.0
            && render_util < 80.0
            && top_thread_run_queue_delay_ms < 2.0
            && workload.gpu_util_pct.is_none_or(|v| v < 80.0);
        // Workload pressure is sampled every control period, so a stale frame
        // probe must not freeze the controller. Fresh frame windows refine the
        // signal; workload-only windows keep the loop responsive between probes.
        if frame.fresh || !frame.available || bad_workload {
            if bad { self.bad_windows = self.bad_windows.saturating_add(1); self.good_windows = 0; }
            else if good { self.good_windows = self.good_windows.saturating_add(1); self.bad_windows = 0; }
            else { self.good_windows = 0; }
        }

        let mut extra = (raw_error * 18.0 + self.integral * 1.2 + derivative.max(0.0) * 2.0).round() as i32;
        if interaction.recent {
            let boost_window = config.interaction_boost_ms.max(20) as f64;
            let hold_window = config.interaction_hold_ms.max(20) as f64;
            let age = interaction.age_ms.unwrap_or(config.interaction_hold_ms as u64) as f64;
            let decay_window = boost_window.max(hold_window);
            let pressure = (1.0 - age / (decay_window * 2.0)).clamp(0.0, 1.0);
            extra += (config.interaction_boost_pct as f64 * pressure).round() as i32;
        }
        if scene.kind == SceneKind::Benchmark { extra += 4; }
        if scene.kind == SceneKind::Game { extra += 2; }
        extra = extra.clamp(0, config.max_extra_boost_pct as i32);

        // Thermal headroom is a direct multiplier, not a Celsius heuristic.
        let thermal_scale = if headroom < 0.25 { 0.35 } else if headroom < 0.40 { 0.60 } else if headroom < 0.60 { 0.80 } else { 1.0 };
        let extra = (extra as f64 * thermal_scale).round() as u32;
        let engage = self.bad_windows >= config.engage_windows || (!frame.available && workload.cpu_util_pct >= 92.0);
        let release = self.good_windows >= config.release_windows;
        let extra = if release {
            // Slew the boost down instead of abruptly dropping it. A smooth
            // release avoids oscillation when the workload hovers near the
            // engage/release boundaries.
            self.last_boost_pct.saturating_sub(5)
        } else if engage {
            extra
        } else {
            self.last_boost_pct.min(extra.saturating_add(2))
        };
        self.last_boost_pct = extra;

        let active = extra > 0;
        let severe_frame = frame.available && frame.fresh && (p95_error > 0.10 || jank_error * 100.0 > (config.jank_threshold_pct as f64 * 1.5));
        let cpu_floor_pct = if active && severe_frame {
            base_uclamp_min_pct.saturating_add(extra).clamp(35, 85)
        } else {
            0
        };
        let confidence = if frame.available { workload.confidence.min(frame.confidence) } else { workload.confidence.min(55) };
        FasDecision {
            active,
            confidence,
            extra_boost_pct: extra,
            target_uclamp_min_pct: base_uclamp_min_pct.saturating_add(extra).min(100),
            target_uclamp_max_pct: 100,
            cpu_floor_pct,
            affinity_hint: config.affinity_hint && active && matches!(scene.kind, SceneKind::Game | SceneKind::Benchmark),
            reason: if frame.available && frame.fresh {
                if interaction.recent && raw_error < 0.20 {
                    "input interaction"
                } else if jank_error > 0.08 {
                    "frame jank"
                } else if p95_error > 0.03 {
                    "frame deadline pressure"
                } else if gpu_util_pct > 90.0 {
                    "gpu pressure"
                } else if top_thread_run_queue_delay_ms > 3.0 {
                    "scheduler contention"
                } else {
                    "workload tracking"
                }
            } else {
                "workload fallback"
            },
        }
    }

    fn reset_session(&mut self, pid: i32) {
        self.integral = 0.0;
        self.previous_error = 0.0;
        self.bad_windows = 0;
        self.good_windows = 0;
        self.last_pid = Some(pid);
        self.last_boost_pct = 0;
    }

    fn inactive(&mut self) -> FasDecision {
        self.last_boost_pct = self.last_boost_pct.saturating_sub(5);
        FasDecision::default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::scene_engine::{SceneEvent, SceneKind};
    use crate::workload::WorkloadSnapshot;

    fn thermal() -> ThermalSnapshot {
        ThermalSnapshot {
            hottest_mdeg: Some(50_000), control_temp_mdeg: Some(50_000), performance_trip_mdeg: Some(90_000),
            critical_trip_mdeg: Some(110_000), control_zone: Some("cpu".into()), release_mdeg: Some(85_000),
            headroom_mdeg: Some(35_000), headroom_permille: Some(875), critical_reached: false,
            telemetry_complete: true, battery_pct: Some(80), external_power: true,
        }
    }

    fn workload() -> WorkloadSnapshot {
        WorkloadSnapshot { package: "com.game".into(), pid: 123, start_time_ticks: 1, cpu_util_pct: 92.0, io_read_bps: None, io_write_bps: None, rss_kb: None, run_queue_delay_ms: None, threads: vec![], top_threads: vec![], active: true, gpu_util_pct: None, confidence: 90 }
    }

    #[test]
    fn controller_requires_consecutive_bad_windows() {
        let scene = SceneSnapshot { package: Some("com.game".into()), kind: SceneKind::Game, event: SceneEvent::Enter, interactive: true, age: Default::default(), reason: "game" };
        let frame = FrameMetrics { available: true, source: "test".into(), frames: 10, new_frames: 2, fps: 55.0, avg_ms: 18.0, p95_ms: 22.0, jank_ratio: 0.4, budget_ms: 16.67, confidence: 90, fresh: true };
        let mut c = FasController::new();
        let a = c.update(&scene, Some(&workload()), &frame, &thermal(), &InteractionSnapshot::default(), 20, &AdaptiveConfig::default());
        assert!(!a.active);
        let b = c.update(&scene, Some(&workload()), &frame, &thermal(), &InteractionSnapshot::default(), 20, &AdaptiveConfig::default());
        assert!(b.active);
        assert!(b.target_uclamp_min_pct > 20);
    }
    #[test]
    fn controller_releases_gradually_after_good_windows() {
        let scene = SceneSnapshot {
            package: Some("com.game".into()),
            kind: SceneKind::Game,
            event: SceneEvent::Stable,
            interactive: true,
            age: Default::default(),
            reason: "game",
        };
        let bad_frame = FrameMetrics {
            available: true,
            source: "test".into(),
            frames: 10,
            new_frames: 2,
            fps: 55.0,
            avg_ms: 18.0,
            p95_ms: 22.0,
            jank_ratio: 0.4,
            budget_ms: 16.67,
            confidence: 90,
            fresh: true,
        };
        let good_frame = FrameMetrics {
            p95_ms: 15.5,
            jank_ratio: 0.0,
            ..bad_frame
        };
        let mut c = FasController::new();
        let _ = c.update(&scene, Some(&workload()), &bad_frame, &thermal(), &InteractionSnapshot::default(), 20, &AdaptiveConfig::default());
        let engaged = c.update(&scene, Some(&workload()), &bad_frame, &thermal(), &InteractionSnapshot::default(), 20, &AdaptiveConfig::default());
        assert!(engaged.active);
        let _ = c.update(&scene, Some(&WorkloadSnapshot { cpu_util_pct: 50.0, active: true, ..workload() }), &good_frame, &thermal(), &InteractionSnapshot::default(), 20, &AdaptiveConfig::default());
        let _ = c.update(&scene, Some(&WorkloadSnapshot { cpu_util_pct: 50.0, active: true, ..workload() }), &good_frame, &thermal(), &InteractionSnapshot::default(), 20, &AdaptiveConfig::default());
        let released = c.update(&scene, Some(&WorkloadSnapshot { cpu_util_pct: 50.0, active: true, ..workload() }), &good_frame, &thermal(), &InteractionSnapshot::default(), 20, &AdaptiveConfig::default());
        assert!(released.extra_boost_pct < engaged.extra_boost_pct);
    }

}
