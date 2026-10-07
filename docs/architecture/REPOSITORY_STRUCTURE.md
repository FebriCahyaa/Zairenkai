# Zairenkai Repository Structure

```text
app/
  src/main/java/com/zairenkai/app/
    core/              stable application identity, capability and runtime contracts
      identity/        Zairenkai product, subsystem, capability and operation IDs
      capability/      tri-state runtime capability evidence
      runtime/         coherent runtime observation boundary
    data/              Android adapters for zkfctl, zperfd, storage and persistence
    domain/            user-facing profile/domain rules
    ui/                Compose presentation and navigation only
      components/      reusable visual primitives
      onboarding/      first-run flow
      screens/         feature screens
      theme/           Material 3/theme system

core/                   Core Platform identity and subsystem registry
  manifest.toml
  subsystems.toml

rust/
  zk-core/              authority, policy, operation and Sentinel primitives
  zperfd/                resident runtime orchestrator and control plane
  zkfc-sys/              Rust FFI bindings to ZKFC UAPI

userspace/              C compatibility and low-level diagnostic client
kernel/                 ZKFC kernel implementation and UAPI
module/                 Android root-module packaging
database/               Atlas evidence/provider knowledge base
tests/                  deterministic validation and regression gates
docs/                   architecture and security contracts
api/                    Zairenkai Local Protocol specification
```

## Naming rule

Stable system identity is namespaced under `zairenkai.*`. Android package names are
implementation details and must not be used as subsystem identity. Application code
should import canonical IDs from `com.zairenkai.app.core.identity`.

## Boundary rule

The UI observes application state. The application layer asks the runtime engine for
observations or requests. The runtime engine owns transaction and reconciliation.
ZKFC remains the kernel authority.
