# Zairenkai Device Intelligence

Device intelligence uses a four-stage process:

```text
DISCOVER -> NORMALIZE -> CLASSIFY -> PROVE
```

### Discover

Read immutable and runtime-visible identifiers from Android properties, device tree,
/sys and /proc.

### Normalize

Normalize vendor, SoC, board, kernel release, flavor and architecture into stable
keys. Preserve the raw evidence so later analysis remains explainable.

### Classify

Map the observation to a provider family and backend candidates. Family ordering is
only a preference signal.

### Prove

Require live capability evidence before an operation can mutate a node. When proof is
missing, the engine either selects another proven backend or refuses the operation.
