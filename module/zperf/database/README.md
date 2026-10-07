# Zairenkai Atlas Database

The database is organized as a knowledge plane, not as a list of unconditional
tuning recipes.

```text
registry/    schemas, trust sources, vendors, storage, memory, network,
             thermal, graphics and security semantics
soc/         provider-family hints and supplier product indexes
devices/     device identity and capability metadata
products/    supplier SoC product records
kernels/     GKI/non-GKI generation and capability hints
schema/      versioned data contracts
```

## Hard rules

1. Static metadata is advisory.
2. Runtime discovery is authoritative for live kernel capabilities.
3. Measurements are append-only evidence, not device facts.
4. Remote supplier data must have provenance and immutable snapshot identity.
5. Unknown data is represented explicitly instead of being guessed.
6. Vendor-specific surfaces belong in providers, not in the universal core.

## Storage and memory

UFS and eMMC are different storage classes. Health fields are treated as optional
runtime evidence; the core never assumes endurance semantics merely from a model
string. Likewise, low-RAM policy is derived from observed memory and pressure data,
not from a hardcoded device generation.


## Storage and thermal evidence

UFS/eMMC health fields are treated as evidence, not an optimization command. A descriptor being present does not imply healthy media; health classification only escalates on recognized status values and otherwise remains unknown.
