# Zairenkai Adaptive Engine v1

This document describes the first runtime control-plane implementation that turns
`zperfd` from a profile executor into a bounded adaptive controller.

## Control hierarchy

```text
Android framework / procfs / sysfs
               |
               v
        SceneEngine
               |
               v
      WorkloadAnalyzer
               |
       +-------+-------+
       |               |
       v               v
 FrameAnalyzer     thermal snapshot
       |               |
       +-------+-------+
               v
        FasController
               |
       +-------+--------+
       |                |
       v                v
 ThreadTaskController   durable Profile Engine
       |
       v
      ZKFC
       |
       v
 kernel scheduler / cpufreq
```

The durable profile remains the baseline contract. Adaptive actuation is ephemeral
and must be reversible without mutating the profile journal.

## Scene Engine

`scene_engine.rs` models `Idle`, `System`, `App`, `Game`, `Benchmark`, `Camera` and
`Video`. It emits `Enter`, `Switch`, `Stable` and `Exit` events. Runtime foreground
and interactive-state probes are cached so the daemon does not continuously spawn
`dumpsys` processes on every reconciliation tick.

The first implementation recognizes profile-marked performance applications and a
small set of stable application-name heuristics. Future event sources can feed
`observe_package()` directly without changing the controller API.

## Workload Analyzer

`workload.rs` samples `/proc` rather than assuming a particular vendor daemon.
It tracks:

- process CPU time delta and utilization;
- process RSS and read/write bandwidth;
- process run-queue delay from `schedstat`;
- per-thread CPU utilization;
- per-thread run-queue delay;
- thread role classification (`Main`, `Render`, `Game`, `Audio`, `Binder`, `Worker`);
- process/thread start-time ticks for identity validation;
- optional GPU utilization from common devfreq/KGSL nodes.

Counters are delta-based and reset on process identity changes. Cache growth is
bounded for long-running daemons.

## Frame Analyzer

`frame.rs` is opportunistic. It discovers display refresh rate with a bounded and
cached `dumpsys display` probe and consumes `dumpsys gfxinfo <package> framestats`
with a hard output limit and timeout. A rolling 240-frame history produces average
frame time, p95 frame time, FPS and deadline miss/jank ratio.

Only a probe containing newly completed frames is marked `fresh`, preventing the
same historical frame data from driving multiple feedback windows.

## FAS Controller

`fas.rs` combines:

- frame deadline error;
- frame jank ratio;
- process CPU pressure;
- top/render thread pressure;
- process and top-thread run-queue delay;
- optional GPU pressure;
- thermal headroom;
- bounded integral/derivative feedback;
- consecutive bad/good windows.

The output is a bounded transient boost, a uclamp target, an optional performance
CPU floor and a narrow affinity hint. Thermal telemetry failure is fail-closed.
Boost engagement requires consecutive evidence windows, while release is gradual.

## Thread / Task Controller

`task_controller.rs` uses the stable ZKFC task-boost and cpufreq-QoS UAPI for the
privileged mutations. It uses Linux affinity only for a small number of high-value
render/game threads and records the original mask.

Before a direct affinity change, the controller validates the thread's `/proc` start
time and the workload process start time. On reset it only restores an affinity if
the current affinity is still exactly the mask Zairenkai applied, avoiding clobbering
an external scheduler that changed the mask after Zairenkai's hint.

## Performance and safety invariants

1. Adaptive control never rewrites the durable profile state.
2. Safe mode always resets adaptive task state before restoring baseline nodes.
3. Scene switch/exit resets transient task changes.
4. Thermal guard and Sentinel/license checks remain in the mutation path.
5. Missing or incomplete thermal telemetry disables adaptive mutation.
6. Frame probes are rate-limited and gated by active workload pressure.
7. Task identity is checked before direct scheduler-affinity mutation.
8. ZKFC remains the owner of privileged per-task uclamp/CPU QoS state.

## Future expansion points

The interfaces are intentionally ready for:

- SurfaceFlinger/Perfetto frame sources with lower observer overhead;
- explicit touch/interaction events;
- launch/transition boost windows;
- cpuset/cgroup placement policies;
- vendor backends for Qualcomm, MediaTek, Samsung and Google Tensor;
- GPU-aware actuation through ZKFC;
- workload profiles based on measured, device-specific evidence rather than hard-coded heuristics.
