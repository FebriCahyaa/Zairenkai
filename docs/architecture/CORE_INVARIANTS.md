# Zairenkai Core Invariants

These are engineering invariants, not feature suggestions.

## Authority

Every mutation has a semantic operation and a required capability. The default
is deny. The kernel remains the hard boundary; zperfd is a second policy gate.

## Capability

A static device or SoC profile may rank providers and describe expected
surfaces. It may not authorize a concrete mutation. Runtime discovery wins.

## State

Desired state, observed state and persistent state are distinct. A mutation is
successful only after the engine can establish that the requested state was
committed and remains observable.

## Recovery

Any ambiguous journal or invalid persistent state is treated as unsafe. The
correct response is rollback or SAFE MODE, never best-effort continuation.

## Hardware neutrality

Qualcomm, MediaTek, Samsung Exynos and Google Tensor are data-provider
families, not hardcoded branches in the runtime engine. Vendor-specific
behavior lives behind provider contracts.

## GKI and NonGKI

Kernel version alone never determines capability. GKI/KMI identity, kernel
configuration and live interfaces are all considered before backend selection.

## Performance evidence

The registry stores hardware facts, capability evidence and measurements as
separate classes. Benchmarks are measurements, not invented device facts.
