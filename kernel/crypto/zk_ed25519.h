/* SPDX-License-Identifier: GPL-2.0-only OR MIT */
/*
 * ZKFC Ed25519 signature verification (RFC 8032, verify only).
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#ifndef _ZK_ED25519_H
#define _ZK_ED25519_H

#include "zk_types.h"
#include "zk_sha512.h"

#define ZK_ED25519_PUBKEY_SIZE 32
#define ZK_ED25519_SIG_SIZE 64

typedef zk_i64 zk_gf[16];

/*
 * Scratch memory for one verification. It is about 1.9 KiB, so the kernel
 * allocates it on the heap instead of the (small) kernel stack.
 */
struct zk_ed25519_ws {
	zk_gf p[4];
	zk_gf q[4];
	zk_gf r[4];
	zk_u8 h[64];
	zk_u8 t[32];
	struct zk_sha512_ctx sha;
};

/*
 * Returns 0 when @sig is a valid signature of @msg under @pk, -1 otherwise.
 * Non-canonical signatures (S >= L) and invalid public keys are rejected.
 */
int zk_ed25519_verify(const zk_u8 sig[ZK_ED25519_SIG_SIZE], const zk_u8 *msg,
		      zk_size msglen, const zk_u8 pk[ZK_ED25519_PUBKEY_SIZE],
		      struct zk_ed25519_ws *ws);

#endif /* _ZK_ED25519_H */
