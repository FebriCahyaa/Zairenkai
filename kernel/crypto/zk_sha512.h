/* SPDX-License-Identifier: GPL-2.0-only OR MIT */
/*
 * ZKFC streaming SHA-512 (FIPS 180-4).
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#ifndef _ZK_SHA512_H
#define _ZK_SHA512_H

#include "zk_types.h"

#define ZK_SHA512_DIGEST_SIZE 64
#define ZK_SHA512_BLOCK_SIZE 128

struct zk_sha512_ctx {
	zk_u64 state[8];
	zk_u64 total;		/* bytes hashed so far */
	zk_u8 buf[ZK_SHA512_BLOCK_SIZE];
	zk_size buflen;
};

void zk_sha512_init(struct zk_sha512_ctx *ctx);
void zk_sha512_update(struct zk_sha512_ctx *ctx, const void *data, zk_size len);
void zk_sha512_final(struct zk_sha512_ctx *ctx, zk_u8 out[ZK_SHA512_DIGEST_SIZE]);
void zk_sha512(zk_u8 out[ZK_SHA512_DIGEST_SIZE], const void *data, zk_size len);

#endif /* _ZK_SHA512_H */
