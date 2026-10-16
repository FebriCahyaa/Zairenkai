/* SPDX-License-Identifier: GPL-2.0-only OR MIT */
/*
 * ZKFC Owner public key (Ed25519).
 *
 * This file contains ONLY the public half of the Owner's signing key. The
 * private key never leaves the Owner's machine. Generate or rotate it with:
 *
 *     python3 tools/zkfc-license/zkfc_license.py init-owner
 *
 * which rewrites the bytes below. While ZKFC_OWNER_KEY_PROVISIONED is 0 every
 * token is rejected with ZKFC_LIC_NO_OWNER_KEY.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#ifndef _ZKFC_OWNER_KEY_H
#define _ZKFC_OWNER_KEY_H

#define ZKFC_OWNER_KEY_PROVISIONED 1

static const unsigned char zkfc_owner_pubkey[32] = {
	0x51, 0x93, 0xaa, 0x9d, 0x7d, 0x2c, 0xc4, 0x1d,
	0x9b, 0xcc, 0x69, 0x74, 0x2b, 0xfb, 0x7d, 0xc7,
	0xde, 0x3d, 0x50, 0x77, 0x3a, 0x65, 0x2a, 0xb7,
	0xaf, 0x33, 0xe2, 0x64, 0xf9, 0xca, 0x96, 0x0a,
};

#endif /* _ZKFC_OWNER_KEY_H */
