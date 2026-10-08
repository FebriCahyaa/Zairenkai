# Zairenkai Adaptive Engine v2

Zairenkai Adaptive Engine v2 extends the v1 feedback loop with lower-overhead
frame telemetry, an event-driven interaction path, capability-aware thread
placement, explicit OEM backend selection, and kernel uclamp baseline
preservation.

## Control-plane shape

```text
                 ┌──────────────────────────────┐
                 │         Scene Engine         │
                 │ app / screen / power / mode │
                 └──────────────┬───────────────┘
                                │
        ┌───────────────────────┼────────────────────────┐
        ▼                       ▼                        ▼
  Workload Analyzer      Frame Analyzer         Interaction Engine
  pid/tid/cpu/rq/rss     FrameTimeline first     direct evdev reader
  thread classes/gpu    gfxinfo fallback        burst + touch state
        │                       │                        │
        └───────────────────────┼────────────────────────┘
                                ▼
                         FAS Controller
                  bounded closed-loop decision
                                │
               ┌────────────────┼─────────────────┐
               ▼                ▼                 ▼
       Thread/Task Ctrl   cpufreq QoS        profile/thermal
               │
       ┌───────┴────────┐
       ▼                ▼
   cgroup/cpuset    sched affinity
       │                │
       └───────┬────────┘
               ▼
              ZKFC
        licensed kernel API
```

The invariant remains:

`Observe → Interpret → Measure → Decide → Constrain → Actuate → Observe again`

No adaptive path writes privileged scheduler state directly. Persistent profile
mutation stays transactional; adaptive state is ephemeral.

## Frame source

`Frame Analyzer` now prefers the native SurfaceFlinger FrameTimeline dump:

`dumpsys SurfaceFlinger --frametimeline -all`

This is intentionally used as a targeted compatibility probe rather than
starting a continuous userspace Perfetto trace for every control iteration.
SurfaceFlinger owns the FrameTimeline machinery and registers the Perfetto data
source `android.surfaceflinger.frametimeline`. `gfxinfo <package> framestats`
remains available only as an explicit fallback for older or vendor-modified
builds.

The parser accepts both compact millisecond values and large monotonic
nanosecond timestamps, filters by package/layer and owner PID, and feeds the
same rolling FPS, frame-time, P95 and jank window used by FAS.

Frame probes are cost-gated by workload pressure and limited by
`adaptive.frame_probe_ms`; idle/system/powersave scenes do not pay the probe
cost.

## Input / touch / interaction

`InteractionEngine` no longer launches `getevent` on the policy loop.
A dedicated `zperfd-input` reader continuously drains eligible evdev devices,
keeps the latest interaction snapshot in shared state, and rescans device nodes
only periodically.

Captured signals are intentionally coarse:

- touch down/move/up
- key/gamepad activity
- scroll/relative input
- burst count and age

The event reader is disabled outside adaptive interactive scenes, so powersave
and system scenes do not keep polling input devices.

When the kernel input-boost handler is available, ZKFC remains the lowest
latency actuator. The userspace interaction engine is then telemetry/context;
it does not create a second privileged boost path.

## Per-thread cgroup / cpuset

`ThreadCgroupController` is capability-first and fail-closed:

1. require cgroup v2;
2. resolve the target thread's existing unified cgroup;
3. require `cgroup.threads`, cpuset files, and an already delegated `cpuset`
   controller in the parent subtree;
4. intersect requested CPUs with `cpuset.cpus.effective`;
5. use a transient Zairenkai child when the hierarchy permits thread placement;
6. otherwise return `false` and let the task controller use `sched_setaffinity`.

Zairenkai never enables `cpuset` globally and never assumes cgroup v2 alone
means per-thread cpuset control is available.

Assignments are keyed by `(tid, start_time_ticks)` and remember the original
cgroup, so a recycled Linux TID cannot inherit another task's placement state.
Repeated writes for an unchanged target CPU set are suppressed.

## Vendor backend abstraction

`BackendPlan` separates vendor identity from authorization.
Supported families are:

- Qualcomm
- MediaTek
- Google / Tensor
- Xiaomi
- Generic fallback

OEM matching can override SoC family where an OEM wrapper materially changes
the exposed runtime surface. The backend exposes stable operation families and
capability flags, while actual writes still depend on runtime topology,
filesystem/service discovery, ZKFC capability and Sentinel policy.

Vendor-specific labels such as `msm_performance`, `kgsl`, `mediatek-runtime`,
`aosp-runtime` and `miui-runtime` are discovery metadata only; identity by
itself never authorizes a mutation.

## Kernel uclamp preservation

The ZKFC task-boost layer now records the task's pre-existing uclamp request
before the first mutation. The snapshot includes:

- `UCLAMP_MIN` value
- `UCLAMP_MAX` value
- `user_defined` state for both clamps
- stable `struct pid` identity

Reset semantics are deliberately different from writing numeric defaults:

- a previously user-defined clamp is restored to its exact requested value;
- a non-user-defined request is cleared with the scheduler's `-1` sentinel so
  the task returns to scheduler/system defaults instead of being forced to
  `0/1024`;
- newly inherited threads use the parent's saved baseline instead of treating
  the already-boosted value as their original state.

This baseline survives adaptive updates, thermal suspend/resume, and rollback.
Dead-task pruning restores any live saved tasks before releasing the stored
identity references.

## Failure and safety semantics

A missing capability is not converted into a guessed vendor write. The decision
matrix is:

```text
FrameTimeline unavailable ──► gfxinfo fallback ──► workload-only control
Evdev unavailable          ──► no input context
cgroup/cpuset unavailable  ──► sched_setaffinity fallback
ZKFC performance missing   ──► no adaptive mutation
thermal headroom unsafe    ──► release / clamp adaptive boost
identity mismatch          ──► reject actuation
transaction commit failure ─► disable transient input boost + recover state
```

The result is a layered engine rather than a collection of vendor-specific
shell tweaks: native telemetry when available, bounded fallbacks when not, and
one licensed kernel actuation boundary.
