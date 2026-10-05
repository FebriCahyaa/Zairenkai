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
	0x67, 0x56, 0x67, 0x64, 0x57, 0x9f, 0xd5, 0x43,
	0x86, 0x19, 0xa5, 0xc8, 0xf6, 0x6a, 0x02, 0x70,
	0x4b, 0x89, 0x80, 0xb0, 0x6c, 0xa9, 0xa4, 0x1e,
	0x57, 0x00, 0x73, 0xda, 0x92, 0xff, 0xd3, 0x14,
};

#endif /* _ZKFC_OWNER_KEY_H */
