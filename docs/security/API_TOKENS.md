<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
# ZKFC API Tokens

Zairenkai's performance features (input boost, per-task uclamp boost, cpufreq
QoS, thermal guard, boost inheritance) are unlocked by a **ZKFC API Token** —
an Ed25519-signed grant issued **only by the project owner**. The information
and basic tuning APIs work without a token; the licensed features return
`EKEYREJECTED` until a valid token is installed.

This design keeps the API from being used "sembarangan" (carelessly): a kernel
cannot claim Zairenkai/ZKFC compatibility, and the boost surface cannot be
driven, without a token the owner signed for that licensee.

## Trust model

```
Owner private key (offline, ~/.zkfc, never in git)
        │  signs
        ▼
ZKFC API Token  ──installed──▶  kernel verifies with the compiled-in public key
(payload + Ed25519 sig)                 (kernel/security/zkfc_owner_key.h)
```

- The **public** key is compiled into the kernel and the app. The **private**
  key never leaves the owner's machine.
- The kernel verifies every token itself (`kernel/crypto/zk_ed25519.c`), so a
  token can be installed at runtime by the app without weakening anything — a
  forged or edited token fails the signature check.
- Tokens are **bound** to a kernel: `binding = SHA-512("ZKFC-BIND-v1:" ||
  CONFIG_ZKFC_LICENSEE_TAG)[0..31]`. A token issued for one licensee's kernel
  tag will not unlock a different kernel. "Any kernel" tokens exist only for
  the owner's own builds.
- Tokens can **expire** and can be **revoked** with a signed revocation list
  (CRL), so a leaked token can be disabled.
- The token carries an **API major**. If the kernel's API major is newer than
  the token (i.e. the kernel was updated but the installed ZKFC/token was not),
  the app shows **"API ZKFC usang — perbarui kernel"** (`ZKFC_LIC_API_OUTDATED`)
  and asks you to update.

## How to get a token (request → approval → delivery)

1. **Ask the owner.** Open a GitHub issue using the *"ZKFC API Token request"*
   template on `FebriCahyaa/Zairenkai`, or contact the owner directly. Include:
   - who you are / your kernel project,
   - your kernel tag (`CONFIG_ZKFC_LICENSEE_TAG`, e.g. `acme-sm8250`),
   - intended use (personal, open-source kernel, commercial).
2. **Owner decides.** Tokens are granted individually. The owner may decline.
3. **Owner issues** the token with the private key (never shared):
   ```sh
   tools/zkfc-license/zkfc_license.py issue \
       --licensee "Acme Kernel" --tag acme-sm8250 --days 365
   ```
   This produces `zkfc-<id>.zkl` (armored, for the app / runtime install) and
   `zkfc-<id>.inc` (C bytes, for building into the kernel).
4. **Delivery.** The owner sends you the `.zkl` file privately.

## How to install a token

- **From the app:** *Sistem → Lisensi API → Pasang token (.zkl)*, pick the file.
  The app copies it and runs `zkfctl license install`.
- **From the shell:** `zkfctl license install /sdcard/zkfc-<id>.zkl`
- **Built into the kernel:** drop `zkfc_license.inc` into
  `kernel/license/zkfc_license.inc` before building (the Makefile defines
  `ZKFC_EMBEDDED_LICENSE` automatically).

## For the owner: provisioning the signing key (one time)

```sh
tools/zkfc-license/zkfc_license.py init-owner      # creates ~/.zkfc, writes public key
git add kernel/security/zkfc_owner_key.h           # commit the PUBLIC key only
```

The private key stays in `~/.zkfc/owner_ed25519.pem` (passphrase-encrypted,
mode 0600). **Back it up offline. If it is lost, no new tokens can be issued
for existing kernels; if it leaks, rotate it and re-issue.** Revoke leaked
tokens:

```sh
tools/zkfc-license/zkfc_license.py revoke --serial 2 <license_id>
```

## Delegated issuance (subordinate keys)

So the owner never has to share the owner signing key, issuance can be
delegated to a per-builder subordinate key. The owner signs a **subkey
certificate** that caps what the key may grant (features, flags, kernel scope,
API major, validity); the builder then signs tokens with the subkey and ships a
**bundle** = { owner-signed cert, subkey-signed token }.

```sh
# owner, once per builder (writes acme.zksub — give it to the builder, keep 0600)
tools/zkfc-license/zkfc_license.py issue-subkey --holder "Acme" --tag <tag> \
    --features input_boost,task_boost --days 365

# builder, per kernel (writes *.inc -> kernel/license/zkfc_bundle.inc)
tools/zkfc-license/zkfc_license.py issue --subkey acme.zksub --licensee "Acme" --tag <tag>
```

The kernel verifies the chain against the owner public key: the owner signature
over the cert, then the subkey signature over the token, and that the token
never grants more than the cert (features, flags, expiry, kernel scope). A token
is bound to the API major, so it stops being valid when the API major changes
and a fresh one must be issued. Revoke a leaked subkey by its id, exactly like a
token: `zkfc_license.py revoke --serial N <subkey_id>`. The offline chain test
is `tools/zkfc-license/test_chain.py`.

## States the app can show

| State            | Meaning                                              |
|------------------|------------------------------------------------------|
| `valid`          | token verified, features unlocked                    |
| `missing`        | no token installed                                   |
| `no_owner_key`   | kernel built without the owner public key            |
| `bad_signature`  | token (or subkey cert) not signed by the right key   |
| `wrong_binding`  | token issued for a different kernel tag              |
| `api_outdated`   | kernel API newer than the token — update the kernel  |
| `expired`        | token or subkey past its expiry                      |
| `revoked`        | token or subkey disabled by a revocation list        |
| `subkey_scope`   | delegated token exceeds what its subkey allows       |
