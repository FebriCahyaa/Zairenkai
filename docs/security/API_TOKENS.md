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

## How to install a token or delegated bundle

- **From the app:** *Sistem → Lisensi API → Pasang token (.zkl)*, pick the file.
  The app copies it and runs `zkfctl license install`.
- **From the shell:** `zkfctl license install /sdcard/zkfc-<id>.zkl`.
  The CLI detects either a standalone `ZKFC API TOKEN` or an Owner-certified
  `ZKFC DELEGATED TOKEN` bundle. The kernel verifies the standalone token or
  the complete delegated chain before replacing the active grant.
- **Runtime bundle ABI:** API minor 1.2 adds `ZKFC_IOC_INSTALL_BUNDLE` at ioctl
  number `0x0A`; it accepts the 432-byte `{ owner-signed certificate, token }`
  structure. Existing token v1 layout (136-byte payload + 64-byte signature)
  and ioctl `ZKFC_IOC_INSTALL_LICENSE` remain unchanged.
- **Built into the kernel:** drop `zkfc_license.inc` into
  `kernel/license/zkfc_license.inc`, or the generated `zkfc_bundle.inc` into
  `kernel/license/zkfc_bundle.inc`, before building. The Makefile chooses the
  corresponding embedded path.

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

## Per-build kernel integration (opt-in)

A build ID and the licensing API major are separate values. The application records a new `ZKFC_BUILD_ID` for each CI commit, while `API_MAJOR` changes only when the signed token/API contract becomes incompatible. Re-signing a fresh token on every ordinary app version would add needless expiry/rotation problems; do not put a private signing key in an APK, kernel image, module ZIP, or public CI workflow.

For a kernel build whose `CONFIG_ZKFC_LICENSEE_TAG` exactly matches the grant scope, use the helper before compiling. The direct-owner path requires the owner's local signing key:

```sh
ZKFC_AUTO_ISSUE_TOKEN=1 \
ZKFC_LICENSEE="My Kernel Build" \
ZKFC_TAG="my-kernel-tag" \
ZKFC_TOKEN_DAYS=365 \
bash tools/zkfc-license/auto-embed-token.sh
# Then run the kernel build as usual.
```

A builder who was delegated a scoped issuance key uses the same helper with `ZKFC_SUBKEY_FILE=/secure/path/builder.zksub`; this places the signed certificate+token bundle in `kernel/license/zkfc_bundle.inc` rather than the direct-token `zkfc_license.inc`. The helper fails closed if the owner public key is not provisioned or the opposite token type already exists, preventing the kernel Makefile's precedence rules from silently selecting a stale grant. It writes the corresponding ignored `.zkl` file for import from Zairenkai → Sistem → Lisensi API. The direct-owner path prompts for the owner's passphrase unless `ZKFC_OWNER_PASSPHRASE` is supplied by a trusted, protected local/CI secret; never echo that variable or store it in build logs. Set `ZKFC_TOKEN_OUT=/secure/path/grant.zkl` to control runtime token output. Keep both direct and delegated private signing material outside the repository and never add it to public CI artifacts.

Do not enable this helper in a public release job that anyone can trigger unless that job is protected by an approval boundary and the license scope is intentional. Public builds without authorized key material must continue without an embedded grant; the app should report `no_owner_key`/`missing` rather than silently manufacturing a license.

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

## Module integrity scope

The release APK pins the complete embedded module ZIP SHA-256, and the app verifies that pin before calling a root manager installer. The ZIP also carries `SHA256SUMS`, which is checked during packaging, installation, and at boot against the copied `zkfctl`/`zperfd` binaries. These checks detect accidental corruption and uncoordinated payload edits. A checksum manifest by itself is **not a digital signature**: an attacker with sufficient root access who can replace both a module file and its manifest can recompute the hashes. Do not interpret local SHA-256 checks as protection against a fully privileged adversary; stronger authenticity requires a detached release signature verified against a separately trusted public key.
