# Atlas supplier pipeline

Supplier and upstream data enter Atlas through a provenance boundary.

```text
supplier/upstream source
        ↓
immutable snapshot
        ↓
SHA-256 verification
        ↓
source registry trust check
        ↓
schema validation
        ↓
Atlas database
        ↓
runtime capability discovery
```

A provider bundle must declare its source id, immutable revision, payload digest,
and (for remote data) an expiry. `tools/atlas_ingest.py` verifies these properties
before the bundle can be accepted. Trust cannot be raised by a bundle itself.

The repository deliberately does not embed supplier API credentials. The API layer
is local-only; remote acquisition belongs to a separate supply-chain job that
materializes a signed/digested snapshot before ingestion.
