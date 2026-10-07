# Zairenkai Sentinel

Sentinel is the runtime safety plane. It does not grant authority; it constrains an
already-authorized operation using current kernel compatibility, persistence state,
thermal state, power state, storage health, boot integrity, device evidence and
Lite-mode policy.

## Decision pipeline

```text
request
  -> Core Authority
  -> Sentinel safety classification
  -> kernel capability/license gate
  -> durable transaction
  -> write
  -> readback
  -> reconciliation
  -> audit
```

## Fail-closed rules

- incompatible ZKFC API blocks mutation
- invalid persistent transaction state blocks mutation
- active SAFE MODE blocks mutation
- hard thermal limit blocks mutation
- critical battery blocks mutation when not externally powered
- critical storage health blocks mutation
- weak/unknown boot integrity blocks critical administration
- heuristic-only device evidence cannot authorize a mutation
- Lite mode blocks critical administration
- unsupported kernel primitives are not emulated with unrelated knobs

## Security boundary

SELinux, Android Verified Boot and the kernel's own capability/authorization checks
remain higher authority than Sentinel. Root access is not treated as an unrestricted
application permission. Sentinel is an additional policy boundary, not a replacement
for kernel or platform security.
