# Zairenkai Thermal & Performance Control Plane

## Purpose

The Thermal & Performance Control Plane is a runtime control subsystem for Zairenkai.
It converts live Linux thermal topology, scheduler/devfreq capability, memory pressure,
storage evidence, and network control surfaces into bounded mutations. Atlas may supply
prior knowledge and provider hints, but it never authorizes a live hardware control.

## Authority invariant

Runtime capability is authoritative:

`discover -> assess -> authorize -> Sentinel -> transact -> observe -> reconcile -> audit`

A static device profile can explain what is expected on a device family. It cannot prove
that a node exists, that a vendor driver exposes a particular governor, or that a thermal
trip point currently has the semantics assumed by a profile.

## Thermal authority

`zperfd::thermal` owns the thermal control contract. For each controlled thermal zone it:

1. reads the current temperature;
2. discovers the zone's runtime trip points;
3. identifies the next applicable trip and the zone-local critical trip;
4. computes headroom inside the observed trip interval;
5. produces normalized `headroom_permille`, `critical_reached`, and
   `telemetry_complete` signals.

The selected control zone is the most constrained eligible zone. Temperatures and trip
points from unrelated zones are never combined into one synthetic thermal limit.

### Unknown telemetry

When a controlled zone lacks a usable sensor or trip topology, the thermal plane enters
the `Unknown` envelope. That envelope removes boost and applies a bounded performance
cap. The system does not manufacture a universal Celsius threshold.

### Kernel guard

The kernel-side guard receives a live runtime trip point selected by the thermal authority.
The UAPI validates only structural sanity (`release < limit` and representable values);
it does not define a universal device temperature policy. The UAPI validates only structural sanity (`release < limit` and representable values);
it does not define a universal device temperature policy. The kernel guard remains a
last-line safeguard and can only remove Zairenkai-owned boosts. Guard configuration is
performed only after Core Authority/Sentinel admission and after a transaction has acquired
the mutation lock; it is treated as a safety-floor mutation rather than a profile value.

The thermal snapshot used for the guard and the constrained performance envelope is taken
from the same runtime topology immediately before performance writes. This avoids using a
stale Celsius/trip snapshot to justify a new mutation.

## Performance envelope

The thermal envelope is expressed as normalized headroom rather than temperature:

- `Nominal`: >= 600‰ headroom
- `Warm`: >= 300‰ and < 600‰
- `Hot`: < 300‰
- `Critical`: runtime critical trip reached
- `Unknown`: incomplete thermal telemetry/topology

These are policy bands over a runtime-derived normalized signal. They are not hardware
thresholds.

`constrain_mode()` applies the envelope to CPU uclamp/input boost/scheduler boost and GPU
minimum performance. The final frequency is still resolved against live OPP tables.

## Scheduler backends

Capability discovery chooses the scheduler backend. GKI prefers uclamp only when the
live cgroup/scheduler surface is actually present; NonGKI may use schedtune-style backends
when those nodes are exposed. No unrelated knob such as `nice` is used to emulate uclamp.

Governor selection is exact-token and runtime-allowlist based. An empty or unavailable
allowlist causes the mutation to be rejected rather than accepting arbitrary input.

## Network control

TCP congestion control uses two runtime evidence sets:

- `tcp_available_congestion_control`: algorithms exposed by the kernel;
- `tcp_allowed_congestion_control`: the policy allowlist exposed by the kernel.

Zairenkai computes their intersection when the kernel supplies an explicit allowlist. If
the kernel omits the allowlist, the available set becomes the fallback. A requested value
outside that runtime set is rejected.

## Property broker

Property reads and writes are separate capabilities. Writes are limited to explicitly
registered `persist.zairenkai.*` keys and per-key validators. Reads are limited to the
registered framework properties and a small diagnostic read-only allowlist. Prefix matching
alone is never considered sufficient authority. The zperfd API socket also requires a root
peer, so the property-read capability is not delegated to an unprivileged Android client.

## Storage intelligence

Storage kind is inferred from runtime topology such as UFS-specific device surfaces or
MMC block-device topology. A model string is descriptive evidence only. Health aggregation
examines all eligible storage devices and returns the most conservative state: critical
beats warning, warning beats good, and absence of usable evidence yields unknown.

Queue tuning is never performed on critical storage. Unknown health is not converted into
a positive health assertion.

## Desired vs observed state

`zperfd` maintains desired policy separately from observed kernel state. Reconciliation
rebuilds the managed-node inventory, detects drift, starts a transaction, applies a
thermally constrained mode, commits only when mutations succeed, and otherwise rolls back.

This means thermal changes, vendor/HAL drift, scheduler changes, and externally modified
sysfs/proc state can be reconciled without treating the persisted profile as proof that
the live device still matches it.

## Sentinel

Sentinel is a safety gate after core authority, not a replacement for it. Thermal-sensitive
mutations are blocked on a reached critical trip. Non-profile thermal mutations are
blocked when the controlled thermal topology is incomplete. ApplyProfile remains usable
under incomplete telemetry only because the thermal envelope constrains the resulting mode.

Other fail-closed conditions remain in force: incompatible kernel API, invalid persistent
transaction state, SAFE MODE, critical battery without external power, critical storage,
unknown/insufficient device evidence, and forbidden critical administration under weak
boot integrity.

## Verification discipline

The repository must distinguish three classes of verification:

- **Locally verified**: deterministic source/build/static/fixture tests actually executed.
- **Environment limited**: test could not execute because the required compiler/toolchain
  or dependency is absent.
- **Target hardware required**: GKI/NonGKI kernel integration, real thermal behavior,
  suspend/resume, vendor HAL drift, and performance measurements.

No unexecuted target-device result is reported as PASS.
