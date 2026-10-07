# Zairenkai Android Application Architecture

## Identity

The Android client owns a stable application identity:

- package: `com.zairenkai.app`
- product: `zairenkai`
- core: `zairenkai.core`
- core API: `1`

Subsystem identity is namespaced under `zairenkai.*` and is not inferred from
package names alone.

## Layering

```text
Compose UI
  -> ViewModel
  -> domain / application use-cases
  -> runtime observation boundary
  -> ZKFC / zperfd clients
  -> native / kernel authority
```

The Android UI is a client, never the authority for a hardware mutation.

## Runtime evidence

`RuntimeSnapshotRepository` builds one coherent application observation from
root status, ZKFC, zperfd, thermal authority and security state.

Capabilities are tri-state:

- `AVAILABLE`: runtime evidence proves the primitive is exposed.
- `UNAVAILABLE`: runtime explicitly reports that the primitive is not exposed.
- `UNOBSERVED`: the observation path did not provide enough evidence.

`UNOBSERVED` must never be rendered as `UNAVAILABLE` or used as permission to
invent a fallback primitive.

## Naming

Application source should use the canonical IDs from
`core.identity.ZairenkaiIdentity`, `SubsystemId`, `CapabilityId`, and
`OperationId`. Do not duplicate literal system IDs throughout Compose, services,
repositories or native clients.

## Mutation boundary

The Android application delegates privileged mutations to the existing Zairenkai
runtime engine. UI state may display desired state, but success is only reflected
after the runtime engine reports a committed transaction and the observed state
is reconciled.
