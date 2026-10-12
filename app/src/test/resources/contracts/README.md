<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
# Engine JSON contract fixtures

These files are **captured output of the real binaries**, not hand-written
JSON. `EngineContractTest` decodes them with the app models, so a change to an
engine's output that the app does not understand fails the unit tests.

| File | Command |
|------|---------|
| `zperfd-tweak-list.json` | `zperfd --root <fixture> --state <tmp> --json tweak list` |
| `zperfd-tweak-list-lite.json` | same, with `--lite` |
| `zperfd-tweak-set-framework-unavailable.json` | `zperfd --root <fixture> --state <tmp> tweak set swappiness 100` on a host without `/dev/zkfc` |
| `zperfd-thermal.json` | `zperfd --root <fixture> --state <tmp> thermal --json` |
| `zperfd-status.json` | `zperfd --root <fixture> --state <tmp> status --json` (no saved mode) |
| `zperfd-status-revalidation.json` | same, after writing `performance` to `<tmp>/mode` with no `mode.fingerprint` |
| `zperfd-set-framework-unavailable.json` | `zperfd --root <fixture> --state <tmp> --json set fast` on a host without `/dev/zkfc` |
| `zperfd-modes.json` | `zperfd --state <tmp> --json modes` (built-in generic profile, no custom profiles) |
| `zperfd-core.json` | `zperfd core --json` |
| `zperfd-battery.json` | `zperfd --root <power fixture> battery` (battery 78 %, charging at -1.85 A raw, 4.1 of 5.0 Ah design on a separate `bms` gauge, 312 cycles, USB-PD 9 V / 2 A online, wireless offline) |
| `zperfd-probe-clusters.json` | `zperfd --root <power fixture> --state <tmp> --json probe` with three cpufreq policies (0-3 schedutil, 4-6 schedutil, 7 performance; space-separated `related_cpus` as the kernel prints them) |
| `zperfd-frames-follow.jsonl` | `zperfd frames --follow --watch com.game.demo` line format, exactly as asserted by `observe.rs` (`Batch::json`): an unwatched launcher line, a watched batch (one 41 ms janky frame), and an empty heartbeat. Frames need a device, so the values are the test vectors, not a capture |
| `zperfd-custom-put.json` | `zperfd --state <tmp> custom put custom-game-cool <def>` (`base = "fast"`, `cpu.max_perf_pct = 85`, `cpu.governor = ["schedutil"]`, `mem.swappiness = 60`) |
| `zperfd-custom-put-rejected.json` | same state, `custom put custom-bad` with `mem.swappiness = 300` |
| `zperfd-custom-list.json` | same state plus a hand-added `custom-old` entry with `base = "turbo"`, `--json custom list` |
| `zperfd-modes-custom.json` | same state, `--json modes` |
| `zperfd-custom-delete-in-use.json` | same state with `custom-game-cool` written to `<tmp>/mode`, `custom delete custom-game-cool` |
| `zkfctl-tweak-list.json` | `zkfctl tweak list` (host build, `userspace/build.sh host`) |
| `zkfctl-monitor-host.json` | `zkfctl monitor 100` (host build; reads the host's `/proc/stat`, so no cpufreq, GPU or thermal nodes: every "not reported" encoding at once) |

The `<fixture>` sysroot is synthetic: two cpufreq policies (`policy0` offers
`schedutil performance powersave`, `policy4` offers `schedutil performance`),
one non-rotational block device `sda` (`[mq-deadline] kyber none`), an
initialized `zram0` (`lzo [lz4] zstd`, 2 GiB), `MemTotal: 8000000 kB`,
`swappiness=60`, `tcp_congestion_control=cubic` with `reno cubic bbr`
available, and one CPU thermal zone (trips 72 °C passive, 95 °C critical).
No value describes a real device. The `modes`/`custom` fixtures resolve the
built-in generic profile, which does not depend on the sysroot.

When an engine's JSON output changes intentionally, regenerate the affected
file with the command above and update the app model in the same change.
