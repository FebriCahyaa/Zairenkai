# Zairenkai Core Platform

Zairenkai Core Platform is the stable architectural identity above ZKFC and
zperfd. It is deliberately not a device-tweak database. Its contracts are:

`identity -> capability -> authority -> policy -> transaction -> observation -> reconciliation -> audit`

## Components

- **ZKFC** — kernel authority and hard capability/security boundary.
- **zperfd** — resident runtime orchestrator and transaction owner.
- **Device Registry** — versioned evidence and provider hints for Qualcomm,
  MediaTek, Samsung Exynos and Google Tensor.
- **Core Authority** — semantic permission model mapping operations to
  least-privilege capabilities.
- **Measurement Registry** — stores measured device behavior; it never invents
  benchmark values as hardware facts.
- **Android Client** — presentation/control client; it is not the kernel
  authority.

## Design rule

Static data can describe what a device commonly exposes. Only runtime
observation can authorize a concrete backend. A missing or ambiguous
capability must result in `deny` or a documented `degrade`, never a guessed
fallback.

## Kernel coverage

The framework models legacy vendor kernels and modern Android GKI branches,
including GKI 6.18 for Android 17. The actual supported interface set is
reported per boot from the running kernel.
