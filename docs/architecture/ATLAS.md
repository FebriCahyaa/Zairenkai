# Zairenkai Atlas

Atlas is Zairenkai's device and platform knowledge plane.

## Purpose

Atlas stores structured knowledge about OEMs, SoCs, kernel generations, thermal
providers, graphics, memory, storage, networking, security surfaces and measured
performance provenance. Atlas is deliberately **not** the runtime authority.

The authority order is:

```text
supplier/upstream data -> Atlas knowledge -> runtime observation -> Core Authority -> Sentinel -> ZKFC
```

A static record can explain what is expected, but it cannot prove that a specific
sysfs node, scheduler backend, thermal provider or vendor hook exists on the running
kernel.

## Evidence classes

`UNKNOWN`, `HEURISTIC`, `OBSERVED`, `VERIFIED`, and `MEASURED` are kept separate.
Only runtime evidence can promote a capability to an actionable state. Benchmark
numbers are never stored as static hardware facts.

## Sources and suppliers

`database/registry/sources.toml` is the trust registry. Current upstream/supplier
seed sources include AOSP kernel/GKI/security documentation, Linux kernel docs,
Qualcomm, MediaTek, Samsung Semiconductor and Google Tensor information. Community
sources are explicitly labelled community and cannot silently become official facts.

Remote provider bundles must declare a source id, immutable revision, canonical
snapshot digest, provenance and expiry. The repository never stores owner secrets,
runtime license tokens or signing keys.

## Device coverage

The registry is intentionally ingestion-driven instead of pretending that a finite
hand-maintained list is "all Android devices". New models can be added without
changing the kernel engine: add verified identity metadata, source provenance and
capability hints, then let runtime discovery determine the actual backend.
