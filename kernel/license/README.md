# ZKFC build-time license

Drop the files you received from the Zairenkai owner here **before** building
the kernel:

| File               | Content                                    |
|--------------------|--------------------------------------------|
| `zkfc_license.inc` | your ZKFC API Token as a C byte list       |
| `zkfc_crl.inc`     | optional signed revocation list            |

Both files are ignored by git, so they are never committed by accident.
Tokens can also be installed at runtime by the Zairenkai app
(`Sistem → Keamanan API → Pasang token`), which is the recommended way for
GKI module builds.
