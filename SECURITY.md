<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
# Security policy

Zairenkai modifies low-level device state and requires root. Please report
vulnerabilities privately to the owner (GitHub: @FebriCahyaa) rather than in a
public issue, especially anything touching:

- ZKFC API Token / signature verification (`kernel/crypto`, `kernel/security`),
- the `/dev/zkfc` access policy (`kernel/core/zkfc_policy.c`),
- the root path in the app/engine (`RootShell`, `zkfctl`).

Do not share private signing keys in reports. See docs/security/API_TOKENS.md
for the token trust model.
