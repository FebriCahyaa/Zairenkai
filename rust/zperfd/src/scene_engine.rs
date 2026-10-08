// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Runtime scene model inspired by Scene's event-driven policy layer, but kept
//! capability-first and vendor-neutral.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::config::Profile;
use crate::nodes::Sysroot;
use std::time::{Duration, Instant};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SceneKind {
    Idle,
    System,
    App,
    Game,
    Benchmark,
    Camera,
    Video,
}

impl SceneKind {
    pub fn performance_candidate(self) -> bool {
        matches!(self, Self::Game | Self::Benchmark | Self::Camera | Self::Video | Self::App)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SceneEvent {
    Enter,
    Switch,
    Stable,
    Exit,
}

#[derive(Clone, Debug)]
pub struct SceneSnapshot {
    pub package: Option<String>,
    pub kind: SceneKind,
    pub event: SceneEvent,
    pub interactive: bool,
    pub age: Duration,
    pub reason: &'static str,
}

pub struct SceneEngine {
    last_package: Option<String>,
    last_kind: SceneKind,
    entered_at: Option<Instant>,
    last_runtime_probe: Option<Instant>,
    cached_package: Option<String>,
    cached_interactive: bool,
}

impl Default for SceneEngine {
    fn default() -> Self { Self::new() }
}

impl SceneEngine {
    pub fn new() -> Self {
        Self {
            last_package: None,
            last_kind: SceneKind::Idle,
            entered_at: None,
            last_runtime_probe: None,
            cached_package: None,
            cached_interactive: true,
        }
    }

    pub fn reset(&mut self) {
        self.last_package = None;
        self.last_kind = SceneKind::Idle;
        self.entered_at = None;
        self.last_runtime_probe = None;
        self.cached_package = None;
        self.cached_interactive = true;
    }

    pub fn observe(&mut self, s: &Sysroot, profile: &Profile) -> SceneSnapshot {
        let runtime_root = s.root() == std::path::Path::new("/");
        let package;
        let interactive;
        if runtime_root {
            let fresh = self
                .last_runtime_probe
                .is_none_or(|t| Instant::now().saturating_duration_since(t) >= Duration::from_millis(700));
            if fresh {
                self.last_runtime_probe = Some(Instant::now());
                self.cached_package = crate::scene::foreground_pkg();
                self.cached_interactive = crate::scene::interactive_power_state();
            }
            package = self.cached_package.clone();
            interactive = self.cached_interactive;
        } else {
            package = None;
            interactive = true;
        }
        self.observe_package(package, interactive, profile)
    }

    /// Testable path and useful for future event sources (Accessibility,
    /// ActivityTaskManager, SurfaceFlinger notifications, app widgets, etc.).
    pub fn observe_package(&mut self, package: Option<String>, interactive: bool, profile: &Profile) -> SceneSnapshot {
        let kind = classify(package.as_deref(), profile);
        let now = Instant::now();
        let changed = self.last_package != package || self.last_kind != kind;
        let event = if package.is_none() && self.last_package.is_some() {
            SceneEvent::Exit
        } else if changed {
            if self.last_package.is_none() { SceneEvent::Enter } else { SceneEvent::Switch }
        } else {
            SceneEvent::Stable
        };
        if changed {
            self.entered_at = Some(now);
        }
        self.last_package = package.clone();
        self.last_kind = kind;
        SceneSnapshot {
            package,
            kind,
            event,
            interactive,
            age: self.entered_at.map(|v| now.saturating_duration_since(v)).unwrap_or_default(),
            reason: reason_for(kind),
        }
    }
}

fn classify(package: Option<&str>, profile: &Profile) -> SceneKind {
    let Some(pkg) = package else { return SceneKind::Idle; };
    let low = pkg.to_ascii_lowercase();
    if low == "com.android.systemui" || low.contains("launcher") || low.contains("home") {
        return SceneKind::System;
    }
    if low.contains("camera") || low.contains("gallery") && low.contains("camera") {
        return SceneKind::Camera;
    }
    if low.contains("youtube") || low.contains("netflix") || low.contains("vidio")
        || low.contains("mxplayer") || low.contains("video") {
        return SceneKind::Video;
    }
    if low.contains("antutu") || low.contains("geekbench") || low.contains("3dmark")
        || low.contains("gfxbench") || low.contains("pcmark") {
        return SceneKind::Benchmark;
    }
    if profile.perapp.get(pkg).is_some_and(|m| matches!(m.as_str(), "performance" | "fast")) {
        return SceneKind::Game;
    }
    SceneKind::App
}

fn reason_for(kind: SceneKind) -> &'static str {
    match kind {
        SceneKind::Idle => "no foreground application",
        SceneKind::System => "system or launcher",
        SceneKind::App => "foreground application",
        SceneKind::Game => "profile-marked performance application",
        SceneKind::Benchmark => "benchmark workload",
        SceneKind::Camera => "camera workload",
        SceneKind::Video => "media workload",
    }
}


#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Profile;

    fn profile() -> Profile {
        Profile::parse(r#"
[meta]
name="test"
default_mode="balance"
[mode.balance]
[mode.performance]
[perapp]
"com.game.demo"="performance"
"#).unwrap()
    }

    #[test]
    fn recognizes_profile_marked_game() {
        let mut e = SceneEngine::new();
        let s = e.observe_package(Some("com.game.demo".into()), true, &profile());
        assert_eq!(s.kind, SceneKind::Game);
        assert_eq!(s.event, SceneEvent::Enter);
    }

    #[test]
    fn switch_is_distinct_from_stable() {
        let mut e = SceneEngine::new();
        let p = profile();
        assert_eq!(e.observe_package(Some("com.game.demo".into()), true, &p).event, SceneEvent::Enter);
        assert_eq!(e.observe_package(Some("com.game.demo".into()), true, &p).event, SceneEvent::Stable);
        assert_eq!(e.observe_package(Some("com.other".into()), true, &p).event, SceneEvent::Switch);
    }
}
