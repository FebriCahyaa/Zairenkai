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

#define ZKFC_OWNER_KEY_PROVISIONED 0

static const unsigned char zkfc_owner_pubkey[32] = {
	0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
	0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
	0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
	0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
};

#endif /* _ZKFC_OWNER_KEY_H */
