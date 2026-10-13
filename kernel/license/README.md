# ZKFC build-time license

Drop the file you received from the Zairenkai owner here **before** building
the kernel:

| File               | Content                                                  |
|--------------------|----------------------------------------------------------|
| `zkfc_bundle.inc`  | a delegated token (subkey cert + token) as a C byte list |
| `zkfc_license.inc` | a direct owner-signed ZKFC API Token as a C byte list    |
| `zkfc_crl.inc`     | optional signed revocation list                          |

Provide **either** a delegated bundle **or** a direct token. When both are
present the bundle wins. All of these are ignored by git, so they are never
committed by accident.

## Delegated tokens (subordinate keys)

The owner never shares the owner signing key. Instead the owner delegates
issuance to a builder:

1. Owner, once per builder:
   `zkfc-license issue-subkey --holder "Acme" --tag <kernel-tag> --features input_boost,task_boost`
   This writes `*.zksub` — hand it to the builder (keep it `0600`).
2. Builder, per kernel:
   `zkfc-license issue --subkey acme.zksub --licensee "Acme" --tag <kernel-tag>`
   This writes `*.inc` — copy it here as `zkfc_bundle.inc`, then build.

The kernel verifies the whole chain against the owner's public key
(token ← subkey ← owner) and never lets a delegated token grant more than the
subkey certificate allowed. The owner can revoke a leaked subkey by its id with
`zkfc-license revoke`. See `docs/security/API_TOKENS.md`.
