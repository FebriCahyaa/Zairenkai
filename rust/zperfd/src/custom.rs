// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! User-defined modes ("custom profiles").
//!
//! A custom mode is a built-in mode of the resolved device profile plus
//! overrides of any [Mode] field. Fields left out follow the base, so a
//! catalog update to the base still reaches the custom mode. The set lives in
//! `<state>/custom.toml`, is written only by `zperfd custom put/delete`, and
//! is merged into the profile when it is loaded. From then on a custom mode is
//! an ordinary mode key: `set` applies it through the same authorization,
//! kernel gate, Sentinel, thermal envelope, transaction and readback as the
//! built-in modes. Custom modes are selected manually only: the profile's
//! per-app rules and Auto never resolve to one.
//!
//! Copyright (C) 2026 FebriCahyaa

use crate::config::{freq_spec_valid, Derived, FreqSpec, Mode, Profile};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::io;
use std::path::{Path, PathBuf};
use std::time::SystemTime;

pub const PREFIX: &str = "custom-";
pub const FILE: &str = "custom.toml";
pub const MAX: usize = 8;
const MAX_FILE_BYTES: u64 = 64 * 1024;

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq)]
pub struct Custom {
    pub label: String,
    pub base: String,
    /// Overrides; an unset field follows the base mode.
    #[serde(flatten)]
    pub overrides: Mode,
}

#[derive(Debug, Default, Deserialize, Serialize)]
struct Store {
    #[serde(default)]
    custom: BTreeMap<String, Custom>,
}

/// One stored custom mode and what it resolves to on this device profile.
pub struct Resolved {
    pub id: String,
    pub custom: Custom,
    pub mode: Result<Mode, String>,
}

pub fn path(state: &Path) -> PathBuf {
    state.join(FILE)
}

/// Modification time of the store, so the daemon can notice a put/delete.
pub fn stamp(state: &Path) -> Option<SystemTime> {
    std::fs::metadata(path(state))
        .and_then(|m| m.modified())
        .ok()
}

pub fn id_valid(id: &str) -> bool {
    id.strip_prefix(PREFIX).is_some_and(|rest| {
        (1..=24).contains(&rest.len())
            && rest
                .bytes()
                .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || matches!(b, b'_' | b'-'))
    })
}

fn label_valid(label: &str) -> bool {
    let n = label.chars().count();
    !label.trim().is_empty() && n <= 32 && !label.chars().any(char::is_control)
}

const SECTIONS: &[(&str, &[&str])] = &[
    (
        "cpu",
        &[
            "min_freq",
            "max_freq",
            "min_perf_pct",
            "max_perf_pct",
            "governor",
            "uclamp_min_pct",
            "uclamp_max_pct",
            "input_boost_pct",
            "input_boost_ms",
            "sched_boost",
        ],
    ),
    (
        "gpu",
        &[
            "min_freq",
            "max_freq",
            "min_perf_pct",
            "max_perf_pct",
            "governor",
        ],
    ),
    (
        "mem",
        &[
            "swappiness",
            "vfs_cache_pressure",
            "watermark_scale_factor",
            "page_cluster",
            "extra_free_kbytes",
        ],
    ),
    ("io", &["scheduler", "read_ahead_kb", "nr_requests"]),
];

/// Reject keys the model does not know: serde would silently drop a typo
/// such as `swapiness`, storing a mode that does not do what was asked.
fn check_keys(value: &toml::Value) -> Result<(), String> {
    let table = value.as_table().ok_or("custom mode must be a table")?;
    for (key, item) in table {
        match key.as_str() {
            "label" | "base" => {}
            section => {
                let Some((_, known)) = SECTIONS.iter().find(|(name, _)| *name == section) else {
                    return Err(format!("unknown key '{section}'"));
                };
                let fields = item
                    .as_table()
                    .ok_or_else(|| format!("'{section}' must be a table"))?;
                if let Some(field) = fields.keys().find(|f| !known.contains(&f.as_str())) {
                    return Err(format!("unknown key '{section}.{field}'"));
                }
            }
        }
    }
    Ok(())
}

/// Parse one custom mode as submitted to `custom put` (TOML: `label`, `base`
/// and optional `[cpu]`, `[gpu]`, `[mem]`, `[io]` override tables).
pub fn parse_entry(text: &str) -> Result<Custom, String> {
    let value: toml::Value = toml::from_str(text).map_err(|e| e.to_string())?;
    check_keys(&value)?;
    value.try_into::<Custom>().map_err(|e| e.to_string())
}

fn freqs(mode: &Mode) -> [(&'static str, &Option<FreqSpec>); 4] {
    [
        ("cpu.min_freq", &mode.cpu.min_freq),
        ("cpu.max_freq", &mode.cpu.max_freq),
        ("gpu.min_freq", &mode.gpu.min_freq),
        ("gpu.max_freq", &mode.gpu.max_freq),
    ]
}

/// The base with every set override applied.
pub fn merge(base: &Mode, o: &Mode) -> Mode {
    fn pick<T: Clone>(over: &Option<T>, base: &Option<T>) -> Option<T> {
        over.clone().or_else(|| base.clone())
    }
    fn list(over: &[String], base: &[String]) -> Vec<String> {
        if over.is_empty() { base } else { over }.to_vec()
    }
    let mut m = base.clone();
    m.cpu.min_freq = pick(&o.cpu.min_freq, &base.cpu.min_freq);
    m.cpu.max_freq = pick(&o.cpu.max_freq, &base.cpu.max_freq);
    m.cpu.min_perf_pct = pick(&o.cpu.min_perf_pct, &base.cpu.min_perf_pct);
    m.cpu.max_perf_pct = pick(&o.cpu.max_perf_pct, &base.cpu.max_perf_pct);
    m.cpu.governor = list(&o.cpu.governor, &base.cpu.governor);
    m.cpu.uclamp_min_pct = pick(&o.cpu.uclamp_min_pct, &base.cpu.uclamp_min_pct);
    m.cpu.uclamp_max_pct = pick(&o.cpu.uclamp_max_pct, &base.cpu.uclamp_max_pct);
    m.cpu.input_boost_pct = pick(&o.cpu.input_boost_pct, &base.cpu.input_boost_pct);
    m.cpu.input_boost_ms = pick(&o.cpu.input_boost_ms, &base.cpu.input_boost_ms);
    m.cpu.sched_boost = pick(&o.cpu.sched_boost, &base.cpu.sched_boost);
    m.gpu.min_freq = pick(&o.gpu.min_freq, &base.gpu.min_freq);
    m.gpu.max_freq = pick(&o.gpu.max_freq, &base.gpu.max_freq);
    m.gpu.min_perf_pct = pick(&o.gpu.min_perf_pct, &base.gpu.min_perf_pct);
    m.gpu.max_perf_pct = pick(&o.gpu.max_perf_pct, &base.gpu.max_perf_pct);
    m.gpu.governor = list(&o.gpu.governor, &base.gpu.governor);
    m.mem.swappiness = pick(&o.mem.swappiness, &base.mem.swappiness);
    m.mem.vfs_cache_pressure = pick(&o.mem.vfs_cache_pressure, &base.mem.vfs_cache_pressure);
    m.mem.watermark_scale_factor = pick(
        &o.mem.watermark_scale_factor,
        &base.mem.watermark_scale_factor,
    );
    m.mem.page_cluster = pick(&o.mem.page_cluster, &base.mem.page_cluster);
    m.mem.extra_free_kbytes = pick(&o.mem.extra_free_kbytes, &base.mem.extra_free_kbytes);
    m.io.scheduler = list(&o.io.scheduler, &base.io.scheduler);
    m.io.read_ahead_kb = pick(&o.io.read_ahead_kb, &base.io.read_ahead_kb);
    m.io.nr_requests = pick(&o.io.nr_requests, &base.io.nr_requests);
    m
}

/// Resolve a custom mode against the built-in modes of `profile`: the base
/// must be one of them, and the merged mode must pass the same validation as
/// a catalog mode plus a frequency syntax check.
pub fn resolve_one(profile: &Profile, custom: &Custom) -> Result<Mode, String> {
    if !label_valid(&custom.label) {
        return Err("label must be 1..32 printable characters".into());
    }
    let base = profile
        .mode
        .get(&custom.base)
        .filter(|_| !profile.derived.contains_key(&custom.base))
        .ok_or_else(|| {
            format!(
                "base '{}' is not a mode of device profile '{}'",
                custom.base, profile.meta.name
            )
        })?;
    if let Some((name, _)) = freqs(&custom.overrides)
        .into_iter()
        .find(|(_, spec)| spec.as_ref().is_some_and(|s| !freq_spec_valid(s)))
    {
        return Err(format!("{name} is not a valid frequency"));
    }
    let merged = merge(base, &custom.overrides);
    merged.validate()?;
    Ok(merged)
}

fn load_store(state: &Path) -> Result<Store, String> {
    let file = path(state);
    match std::fs::metadata(&file) {
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(Store::default()),
        Err(e) => return Err(format!("{}: {e}", file.display())),
        Ok(meta) if meta.len() > MAX_FILE_BYTES => {
            return Err(format!(
                "{} is larger than {MAX_FILE_BYTES} bytes",
                file.display()
            ))
        }
        Ok(_) => {}
    }
    let text = std::fs::read_to_string(&file).map_err(|e| format!("{}: {e}", file.display()))?;
    toml::from_str(&text).map_err(|e| format!("{}: {e}", file.display()))
}

/// Every stored custom mode, resolved against `profile`'s built-in modes.
pub fn list(profile: &Profile, state: &Path) -> Result<Vec<Resolved>, String> {
    Ok(load_store(state)?
        .custom
        .into_iter()
        .map(|(id, custom)| {
            let mode = if id_valid(&id) {
                resolve_one(profile, &custom)
            } else {
                Err(format!("invalid id '{id}'"))
            };
            Resolved { id, custom, mode }
        })
        .collect())
}

/// Replace the custom modes merged into `profile` with the stored set. An
/// entry that no longer resolves (its base left the device profile) is left
/// out and reported; a broken store never takes the built-in modes with it.
pub fn inject(profile: &mut Profile, state: &Path) {
    for id in std::mem::take(&mut profile.derived).into_keys() {
        profile.mode.remove(&id);
    }
    let resolved = match list(profile, state) {
        Ok(resolved) => resolved,
        Err(e) => {
            eprintln!("zperfd: custom modes ignored: {e}");
            return;
        }
    };
    for r in resolved {
        match r.mode {
            Ok(mode) => {
                profile.derived.insert(
                    r.id.clone(),
                    Derived {
                        base: r.custom.base,
                        label: r.custom.label,
                    },
                );
                profile.mode.insert(r.id, mode);
            }
            Err(e) => eprintln!("zperfd: custom mode '{}' ignored: {e}", r.id),
        }
    }
}

/// Why a put or delete was refused, as a stable kind for clients.
#[derive(Debug, PartialEq)]
pub enum Refusal {
    Rejected(String),
    Limit,
    InUse,
    NotFound,
    State(String),
}

fn save(state: &Path, store: &Store) -> Result<(), Refusal> {
    let text = toml::to_string(store).map_err(|e| Refusal::State(e.to_string()))?;
    std::fs::create_dir_all(state).map_err(|e| Refusal::State(e.to_string()))?;
    crate::state::atomic_write(&path(state), &text).map_err(|e| Refusal::State(e.to_string()))
}

/// Store (create or replace) a custom mode after resolving it against the
/// built-in modes of `profile`.
pub fn put(profile: &Profile, state: &Path, id: &str, custom: Custom) -> Result<(), Refusal> {
    if !id_valid(id) {
        return Err(Refusal::Rejected(format!(
            "id must be '{PREFIX}' followed by 1..24 of a-z 0-9 _ -"
        )));
    }
    resolve_one(profile, &custom).map_err(Refusal::Rejected)?;
    let mut store = load_store(state).map_err(Refusal::State)?;
    if !store.custom.contains_key(id) && store.custom.len() >= MAX {
        return Err(Refusal::Limit);
    }
    store.custom.insert(id.to_string(), custom);
    save(state, &store)
}

/// Remove a custom mode. The persisted selection may not point at it: the
/// daemon would otherwise replay the profile default in its place.
pub fn delete(state: &Path, id: &str, saved_mode: Option<&str>) -> Result<(), Refusal> {
    if saved_mode == Some(id) {
        return Err(Refusal::InUse);
    }
    let mut store = load_store(state).map_err(Refusal::State)?;
    if store.custom.remove(id).is_none() {
        return Err(Refusal::NotFound);
    }
    save(state, &store)
}

/// Every field of a mode as JSON (null when unset), for editors that show
/// a base's values and a custom mode's overrides.
pub fn mode_json(mode: &Mode) -> String {
    use crate::json_escape;
    fn freq(spec: &Option<FreqSpec>) -> String {
        match spec {
            Some(FreqSpec::Khz(khz)) => khz.to_string(),
            Some(FreqSpec::Spec(text)) => format!("\"{}\"", json_escape(text)),
            None => "null".into(),
        }
    }
    fn num<T: std::fmt::Display>(v: Option<T>) -> String {
        v.map(|v| v.to_string()).unwrap_or_else(|| "null".into())
    }
    fn list(items: &[String]) -> String {
        let items = items
            .iter()
            .map(|v| format!("\"{}\"", json_escape(v)))
            .collect::<Vec<_>>()
            .join(",");
        format!("[{items}]")
    }
    let (c, g, m, i) = (&mode.cpu, &mode.gpu, &mode.mem, &mode.io);
    format!(
        "{{\"cpu\":{{\"min_freq\":{},\"max_freq\":{},\"min_perf_pct\":{},\"max_perf_pct\":{},\"governor\":{},\"uclamp_min_pct\":{},\"uclamp_max_pct\":{},\"input_boost_pct\":{},\"input_boost_ms\":{},\"sched_boost\":{}}},\
\"gpu\":{{\"min_freq\":{},\"max_freq\":{},\"min_perf_pct\":{},\"max_perf_pct\":{},\"governor\":{}}},\
\"mem\":{{\"swappiness\":{},\"vfs_cache_pressure\":{},\"watermark_scale_factor\":{},\"page_cluster\":{},\"extra_free_kbytes\":{}}},\
\"io\":{{\"scheduler\":{},\"read_ahead_kb\":{},\"nr_requests\":{}}}}}",
        freq(&c.min_freq),
        freq(&c.max_freq),
        num(c.min_perf_pct),
        num(c.max_perf_pct),
        list(&c.governor),
        num(c.uclamp_min_pct),
        num(c.uclamp_max_pct),
        num(c.input_boost_pct),
        num(c.input_boost_ms),
        num(c.sched_boost),
        freq(&g.min_freq),
        freq(&g.max_freq),
        num(g.min_perf_pct),
        num(g.max_perf_pct),
        list(&g.governor),
        num(m.swappiness),
        num(m.vfs_cache_pressure),
        num(m.watermark_scale_factor),
        num(m.page_cluster),
        num(m.extra_free_kbytes),
        list(&i.scheduler),
        num(i.read_ahead_kb),
        num(i.nr_requests),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    const PROFILE: &str = r#"
        [meta]
        name = "test"
        default_mode = "balance"
        [mode.powersave.cpu]
        max_perf_pct = 55
        input_boost_ms = 0
        [mode.balance.cpu]
        governor = ["schedutil"]
        input_boost_pct = 40
        input_boost_ms = 600
        [mode.balance.mem]
        swappiness = 100
        [mode.fast.cpu]
        min_freq = "60%"
        uclamp_min_pct = 40
    "#;

    fn profile() -> Profile {
        let p = Profile::parse(PROFILE).unwrap();
        p.validate().unwrap();
        p
    }

    fn tmp(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("zperfd-custom-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn entry(text: &str) -> Custom {
        parse_entry(text).unwrap()
    }

    #[test]
    fn unset_fields_follow_the_base_and_set_fields_override_it() {
        let p = profile();
        let c = entry(
            "label = \"Game dingin\"\nbase = \"balance\"\n[cpu]\nmax_perf_pct = 80\ngovernor = [\"performance\", \"schedutil\"]\n[io]\nread_ahead_kb = 512\n",
        );
        let m = resolve_one(&p, &c).unwrap();
        assert_eq!(m.cpu.max_perf_pct, Some(80));
        assert_eq!(m.cpu.governor, vec!["performance", "schedutil"]);
        assert_eq!(m.cpu.input_boost_pct, Some(40));
        assert_eq!(m.mem.swappiness, Some(100));
        assert_eq!(m.io.read_ahead_kb, Some(512));
    }

    #[test]
    fn typos_unknown_sections_and_unsafe_values_are_rejected() {
        assert!(
            parse_entry("label=\"x\"\nbase=\"balance\"\n[mem]\nswapiness = 10\n")
                .unwrap_err()
                .contains("mem.swapiness")
        );
        assert!(
            parse_entry("label=\"x\"\nbase=\"balance\"\n[thermal]\ntrip = 1\n")
                .unwrap_err()
                .contains("thermal")
        );
        let p = profile();
        let unsafe_swap = entry("label=\"x\"\nbase=\"balance\"\n[mem]\nswappiness = 300\n");
        assert!(resolve_one(&p, &unsafe_swap).is_err());
        let bad_freq = entry("label=\"x\"\nbase=\"fast\"\n[cpu]\nmax_freq = \"fast\"\n");
        assert_eq!(
            resolve_one(&p, &bad_freq).unwrap_err(),
            "cpu.max_freq is not a valid frequency"
        );
        let inverted = entry("label=\"x\"\nbase=\"fast\"\n[cpu]\nuclamp_max_pct = 20\n");
        assert!(resolve_one(&p, &inverted).unwrap_err().contains("uclamp"));
        let blank = entry("label=\"  \"\nbase=\"fast\"\n");
        assert!(resolve_one(&p, &blank).is_err());
    }

    #[test]
    fn base_must_be_a_built_in_mode_of_this_device_profile() {
        let mut p = profile();
        let state = tmp("base");
        put(
            &p,
            &state,
            "custom-a",
            entry("label=\"A\"\nbase=\"fast\"\n"),
        )
        .unwrap();
        inject(&mut p, &state);
        assert_eq!(p.base_of("custom-a"), "fast");
        let chained = entry("label=\"B\"\nbase=\"custom-a\"\n");
        assert!(matches!(
            put(&p, &state, "custom-b", chained),
            Err(Refusal::Rejected(_))
        ));
        let missing = entry("label=\"C\"\nbase=\"performance\"\n");
        assert!(matches!(
            put(&p, &state, "custom-c", missing),
            Err(Refusal::Rejected(_))
        ));
        for id in ["fast", "custom-", "custom-UPPER", "custom-a/b"] {
            let c = entry("label=\"D\"\nbase=\"fast\"\n");
            assert!(
                matches!(put(&p, &state, id, c), Err(Refusal::Rejected(_))),
                "{id}"
            );
        }
    }

    #[test]
    fn put_persists_inject_merges_and_delete_refuses_the_saved_mode() {
        let mut p = profile();
        let state = tmp("roundtrip");
        let c = entry("label=\"Hemat+\"\nbase=\"powersave\"\n[cpu]\nmax_freq = \"1.2GHz\"\nmin_freq = 300000\n");
        put(&p, &state, "custom-hemat", c.clone()).unwrap();
        let stored = list(&p, &state).unwrap();
        assert_eq!(stored.len(), 1);
        assert_eq!(stored[0].custom, c);

        inject(&mut p, &state);
        let m = p.mode("custom-hemat").unwrap();
        assert_eq!(m.cpu.max_perf_pct, Some(55));
        assert_eq!(m.cpu.max_freq, Some(FreqSpec::Spec("1.2GHz".into())));
        assert_eq!(m.cpu.min_freq, Some(FreqSpec::Khz(300_000)));
        assert_eq!(p.base_of("custom-hemat"), "powersave");
        assert_eq!(p.base_of("balance"), "balance");

        assert_eq!(
            delete(&state, "custom-hemat", Some("custom-hemat")),
            Err(Refusal::InUse)
        );
        assert_eq!(delete(&state, "custom-none", None), Err(Refusal::NotFound));
        delete(&state, "custom-hemat", Some("balance")).unwrap();
        inject(&mut p, &state);
        assert!(p.mode("custom-hemat").is_none());
        assert!(p.derived.is_empty());
        assert!(p.mode("balance").is_some());
    }

    #[test]
    fn the_store_is_bounded_and_a_broken_store_keeps_the_built_in_modes() {
        let mut p = profile();
        let state = tmp("bounds");
        for i in 0..MAX {
            put(
                &p,
                &state,
                &format!("custom-{i}"),
                entry("label=\"x\"\nbase=\"balance\"\n"),
            )
            .unwrap();
        }
        let extra = entry("label=\"x\"\nbase=\"balance\"\n");
        assert_eq!(
            put(&p, &state, "custom-extra", extra.clone()),
            Err(Refusal::Limit)
        );
        // Replacing an existing entry is not a new one.
        put(&p, &state, "custom-0", extra).unwrap();

        std::fs::write(path(&state), "not = [toml").unwrap();
        inject(&mut p, &state);
        assert!(p.derived.is_empty());
        assert_eq!(p.mode.len(), 3);
    }

    #[test]
    fn an_entry_whose_base_left_the_profile_is_skipped_not_fatal() {
        let state = tmp("orphan");
        std::fs::write(
            path(&state),
            "[custom.custom-old]\nlabel = \"Old\"\nbase = \"turbo\"\n\n[custom.custom-ok]\nlabel = \"Ok\"\nbase = \"fast\"\n",
        )
        .unwrap();
        let mut p = profile();
        inject(&mut p, &state);
        assert!(p.mode("custom-old").is_none());
        assert!(p.mode("custom-ok").is_some());
        let listed = list(&p, &state).unwrap();
        assert!(listed
            .iter()
            .any(|r| r.id == "custom-old" && r.mode.is_err()));
    }

    #[test]
    fn known_keys_cover_every_mode_field() {
        let full = Mode {
            cpu: crate::config::Cpu {
                min_freq: Some(FreqSpec::Khz(1)),
                max_freq: Some(FreqSpec::Khz(1)),
                min_perf_pct: Some(1),
                max_perf_pct: Some(1),
                governor: vec!["g".into()],
                uclamp_min_pct: Some(1),
                uclamp_max_pct: Some(1),
                input_boost_pct: Some(1),
                input_boost_ms: Some(1),
                sched_boost: Some(1),
            },
            gpu: crate::config::Gpu {
                min_freq: Some(FreqSpec::Khz(1)),
                max_freq: Some(FreqSpec::Khz(1)),
                min_perf_pct: Some(1),
                max_perf_pct: Some(1),
                governor: vec!["g".into()],
            },
            mem: crate::config::Mem {
                swappiness: Some(1),
                vfs_cache_pressure: Some(1),
                watermark_scale_factor: Some(1),
                page_cluster: Some(1),
                extra_free_kbytes: Some(1),
            },
            io: crate::config::Io {
                scheduler: vec!["s".into()],
                read_ahead_kb: Some(1),
                nr_requests: Some(1),
            },
        };
        let value = toml::Value::try_from(&full).unwrap();
        check_keys(&value).unwrap();
        let fields: usize = value
            .as_table()
            .unwrap()
            .values()
            .map(|t| t.as_table().unwrap().len())
            .sum();
        assert_eq!(fields, SECTIONS.iter().map(|(_, k)| k.len()).sum::<usize>());
        // Every field also appears in the editor JSON.
        let json = mode_json(&full);
        for (section, keys) in SECTIONS {
            for key in *keys {
                assert!(json.contains(&format!("\"{key}\":")), "{section}.{key}");
            }
        }
    }
}
