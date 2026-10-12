// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Read-only telemetry for the app's monitor screens: the battery and
//! charger power supplies, and a frame stream for watched apps. Nothing here
//! writes a node; both commands only read sysfs and `dumpsys` output.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::frame::FrameTiming;
use crate::json_escape;
use crate::nodes::Sysroot;

const PS: &str = "/sys/class/power_supply";

// ---------------------------------------------------------------- battery

fn read_i64(s: &Sysroot, path: &str) -> Option<i64> {
    s.read(path).and_then(|v| v.trim().parse().ok())
}

fn json_num(v: Option<i64>) -> String {
    v.map(|v| v.to_string()).unwrap_or_else(|| "null".into())
}

fn json_str(v: Option<String>) -> String {
    v.filter(|v| !v.is_empty())
        .map(|v| format!("\"{}\"", json_escape(&v)))
        .unwrap_or_else(|| "null".into())
}

/// The supply that describes the cell: `battery`, else the first one whose
/// `type` is Battery.
fn battery_supply(s: &Sysroot) -> Option<String> {
    let entries = s.list_dir(PS);
    if entries.iter().any(|e| e == "battery") {
        return Some("battery".into());
    }
    entries
        .into_iter()
        .find(|e| s.read(&format!("{PS}/{e}/type")).as_deref() == Some("Battery"))
}

/// Battery and charger power supplies as one JSON object. Values are the
/// kernel's raw units (µA, µV, µAh, tenths of °C); signs and µA/mA quirks
/// are left to the client, which also has the Android-side readings.
pub fn battery_json(s: &Sysroot) -> String {
    let battery = battery_supply(s).map(|name| {
        let p = |leaf: &str| format!("{PS}/{name}/{leaf}");
        // Some fuel gauges expose capacity data on a separate `bms` supply.
        let alt = |leaf: &str| read_i64(s, &p(leaf)).or_else(|| read_i64(s, &format!("{PS}/bms/{leaf}")));
        format!(
            "{{\"name\":\"{}\",\"capacity\":{},\"status\":{},\"health\":{},\"technology\":{},\"cycle_count\":{},\
\"charge_full_uah\":{},\"charge_full_design_uah\":{},\"charge_counter_uah\":{},\"current_now_ua\":{},\
\"current_avg_ua\":{},\"voltage_now_uv\":{},\"voltage_max_uv\":{},\"temp_decic\":{},\"charge_type\":{},\
\"constant_charge_current_max_ua\":{},\"time_to_full_s\":{}}}",
            json_escape(&name),
            json_num(read_i64(s, &p("capacity"))),
            json_str(s.read(&p("status"))),
            json_str(s.read(&p("health"))),
            json_str(s.read(&p("technology"))),
            json_num(alt("cycle_count")),
            json_num(alt("charge_full")),
            json_num(alt("charge_full_design")),
            json_num(read_i64(s, &p("charge_counter"))),
            json_num(read_i64(s, &p("current_now"))),
            json_num(read_i64(s, &p("current_avg"))),
            json_num(read_i64(s, &p("voltage_now"))),
            json_num(read_i64(s, &p("voltage_max"))),
            json_num(read_i64(s, &p("temp"))),
            json_str(s.read(&p("charge_type"))),
            json_num(read_i64(s, &p("constant_charge_current_max"))),
            json_num(read_i64(s, &p("time_to_full_now"))),
        )
    });
    let chargers = s
        .list_dir(PS)
        .into_iter()
        .filter(|name| {
            matches!(
                s.read(&format!("{PS}/{name}/type")).as_deref(),
                Some("USB" | "USB_PD" | "USB_C" | "USB_DCP" | "USB_CDP" | "USB_HVDCP" | "Mains" | "Wireless")
            )
        })
        .map(|name| {
            let p = |leaf: &str| format!("{PS}/{name}/{leaf}");
            format!(
                "{{\"name\":\"{}\",\"type\":{},\"real_type\":{},\"online\":{},\"voltage_now_uv\":{},\"current_now_ua\":{},\
\"current_max_ua\":{},\"input_current_limit_ua\":{}}}",
                json_escape(&name),
                json_str(s.read(&p("type"))),
                json_str(s.read(&p("real_type")).or_else(|| s.read(&p("usb_type")))),
                read_i64(s, &p("online")).is_some_and(|v| v > 0),
                json_num(read_i64(s, &p("voltage_now"))),
                json_num(read_i64(s, &p("current_now")).or_else(|| read_i64(s, &p("input_current_now")))),
                json_num(read_i64(s, &p("current_max"))),
                json_num(read_i64(s, &p("input_current_limit"))),
            )
        })
        .collect::<Vec<_>>()
        .join(",");
    format!(
        "{{\"ok\":true,\"battery\":{},\"chargers\":[{}]}}",
        battery.unwrap_or_else(|| "null".into()),
        chargers
    )
}

// ---------------------------------------------------------------- frames

/// Package names from `--watch a.b,c.d`; anything that is not a package
/// name is dropped.
pub fn parse_watch(list: &str) -> Vec<String> {
    list.split(',')
        .map(str::trim)
        .filter(|p| {
            let parts: Vec<&str> = p.split('.').collect();
            parts.len() >= 2
                && p.len() <= 255
                && parts.iter().all(|q| {
                    !q.is_empty() && q.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
                })
        })
        .map(String::from)
        .collect()
}

/// Remembers the newest frame already sent, so each batch carries only new
/// frames. Retained frame buffers overlap from one read to the next.
#[derive(Default)]
pub struct FrameCursor {
    package: String,
    last_token: i64,
    last_completed: i64,
}

impl FrameCursor {
    /// Frames newer than the previous call for the same package, oldest
    /// first. A different package starts over.
    pub fn take_new(&mut self, package: &str, mut frames: Vec<FrameTiming>) -> Vec<FrameTiming> {
        if self.package != package {
            *self = FrameCursor {
                package: package.to_string(),
                ..FrameCursor::default()
            };
        }
        frames.sort_by_key(|f| f.completed_ns);
        frames
            .into_iter()
            .filter(|f| {
                if f.token != 0 {
                    if f.token <= self.last_token {
                        return false;
                    }
                    self.last_token = f.token;
                } else {
                    if f.completed_ns <= self.last_completed {
                        return false;
                    }
                }
                self.last_completed = self.last_completed.max(f.completed_ns);
                true
            })
            .collect()
    }
}

/// One stream line. `frames` rows are `[completed_ns, render_us, jank]`.
pub struct Batch<'a> {
    pub t_ms: u128,
    pub foreground: Option<&'a str>,
    pub watched: bool,
    pub source: Option<&'a str>,
    pub refresh_hz: Option<f64>,
    pub frames: &'a [FrameTiming],
}

impl Batch<'_> {
    pub fn json(&self) -> String {
        let frames = self
            .frames
            .iter()
            .map(|f| {
                format!(
                    "[{},{},{}]",
                    f.completed_ns,
                    f.duration_ns / 1000,
                    u8::from(f.jank)
                )
            })
            .collect::<Vec<_>>()
            .join(",");
        format!(
            "{{\"t_ms\":{},\"fg\":{},\"watched\":{},\"source\":{},\"refresh_hz\":{},\"frames\":[{}]}}",
            self.t_ms,
            self.foreground
                .map(|p| format!("\"{}\"", json_escape(p)))
                .unwrap_or_else(|| "null".into()),
            self.watched,
            self.source
                .map(|p| format!("\"{}\"", json_escape(p)))
                .unwrap_or_else(|| "null".into()),
            self.refresh_hz
                .filter(|v| v.is_finite())
                .map(|v| format!("{v:.1}"))
                .unwrap_or_else(|| "null".into()),
            frames,
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::unique_temp_dir;
    use std::fs;

    fn put(root: &std::path::Path, rel: &str, v: &str) {
        let p = root.join(rel.trim_start_matches('/'));
        fs::create_dir_all(p.parent().unwrap()).unwrap();
        fs::write(p, v).unwrap();
    }

    fn frame(token: i64, completed: i64, dur_us: u64, jank: bool) -> FrameTiming {
        FrameTiming {
            token,
            intended_ns: completed - 16_000_000,
            completed_ns: completed,
            duration_ns: dur_us * 1000,
            jank,
        }
    }

    #[test]
    fn battery_reports_cell_capacity_and_online_chargers() {
        let root = unique_temp_dir("battery");
        for (k, v) in [
            ("type", "Battery"),
            ("capacity", "78"),
            ("status", "Charging"),
            ("health", "Good"),
            ("technology", "Li-poly"),
            ("charge_full", "4100000"),
            ("current_now", "-1850000"),
            ("voltage_now", "4210000"),
            ("temp", "335"),
            ("charge_type", "Fast"),
        ] {
            put(&root, &format!("/sys/class/power_supply/battery/{k}"), v);
        }
        // Design capacity and cycle count on a separate fuel gauge.
        put(
            &root,
            "/sys/class/power_supply/bms/charge_full_design",
            "5000000",
        );
        put(&root, "/sys/class/power_supply/bms/cycle_count", "312");
        put(&root, "/sys/class/power_supply/usb/type", "USB_PD");
        put(&root, "/sys/class/power_supply/usb/online", "1");
        put(&root, "/sys/class/power_supply/usb/voltage_now", "9000000");
        put(
            &root,
            "/sys/class/power_supply/usb/input_current_now",
            "2000000",
        );
        put(&root, "/sys/class/power_supply/wireless/type", "Wireless");
        put(&root, "/sys/class/power_supply/wireless/online", "0");

        let json = battery_json(&Sysroot::new(&root));
        for needle in [
            "\"capacity\":78",
            "\"status\":\"Charging\"",
            "\"cycle_count\":312",
            "\"charge_full_uah\":4100000",
            "\"charge_full_design_uah\":5000000",
            "\"current_now_ua\":-1850000",
            "\"temp_decic\":335",
            "\"charge_counter_uah\":null",
            "{\"name\":\"usb\",\"type\":\"USB_PD\",\"real_type\":null,\"online\":true,\"voltage_now_uv\":9000000,\"current_now_ua\":2000000",
            "{\"name\":\"wireless\",\"type\":\"Wireless\",\"real_type\":null,\"online\":false",
        ] {
            assert!(json.contains(needle), "{needle} not in {json}");
        }
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn no_battery_supply_is_reported_as_null() {
        let root = unique_temp_dir("nobattery");
        fs::create_dir_all(root.join("sys/class/power_supply")).unwrap();
        assert_eq!(
            battery_json(&Sysroot::new(&root)),
            "{\"ok\":true,\"battery\":null,\"chargers\":[]}"
        );
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn watch_list_keeps_package_names_only() {
        assert_eq!(
            parse_watch("com.game.one, com.other_2.app ,bad,../etc,a.,com.x;rm"),
            vec!["com.game.one", "com.other_2.app"]
        );
    }

    #[test]
    fn cursor_sends_each_frame_once_and_restarts_per_package() {
        let mut c = FrameCursor::default();
        let first = c.take_new(
            "a.b",
            vec![frame(2, 200, 8000, false), frame(1, 100, 9000, false)],
        );
        assert_eq!(
            first.iter().map(|f| f.token).collect::<Vec<_>>(),
            vec![1, 2]
        );
        // Overlapping read: only token 3 is new.
        let second = c.take_new(
            "a.b",
            vec![
                frame(1, 100, 9000, false),
                frame(2, 200, 8000, false),
                frame(3, 300, 30000, true),
            ],
        );
        assert_eq!(second.iter().map(|f| f.token).collect::<Vec<_>>(), vec![3]);
        // gfxinfo rows have no token: dedupe by completion time.
        let mut g = FrameCursor::default();
        assert_eq!(
            g.take_new(
                "c.d",
                vec![frame(0, 100, 1, false), frame(0, 200, 1, false)]
            )
            .len(),
            2
        );
        assert_eq!(
            g.take_new(
                "c.d",
                vec![frame(0, 200, 1, false), frame(0, 250, 1, false)]
            )
            .len(),
            1
        );
        // Another package starts over.
        assert_eq!(c.take_new("e.f", vec![frame(1, 100, 9000, false)]).len(), 1);
    }

    #[test]
    fn batch_lines_are_compact_json() {
        let frames = [
            frame(7, 1_000_000_000, 8_333, false),
            frame(8, 1_016_000_000, 41_000, true),
        ];
        let b = Batch {
            t_ms: 1234,
            foreground: Some("com.game"),
            watched: true,
            source: Some("surfaceflinger-frametimeline"),
            refresh_hz: Some(120.0),
            frames: &frames,
        };
        assert_eq!(
            b.json(),
            "{\"t_ms\":1234,\"fg\":\"com.game\",\"watched\":true,\"source\":\"surfaceflinger-frametimeline\",\
\"refresh_hz\":120.0,\"frames\":[[1000000000,8333,0],[1016000000,41000,1]]}"
        );
        let idle = Batch {
            t_ms: 1,
            foreground: None,
            watched: false,
            source: None,
            refresh_hz: None,
            frames: &[],
        };
        assert_eq!(
            idle.json(),
            "{\"t_ms\":1,\"fg\":null,\"watched\":false,\"source\":null,\"refresh_hz\":null,\"frames\":[]}"
        );
    }
}
