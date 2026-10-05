// SPDX-License-Identifier: GPL-2.0-only OR MIT
/*
 * ZKFC streaming SHA-512 (FIPS 180-4).
 *
 * The compression function follows the structure of TweetNaCl (public
 * domain); the streaming interface is Zairenkai's own.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#include "zk_sha512.h"

static const zk_u64 zk_sha512_k[80] = {
	0x428a2f98d728ae22ULL, 0x7137449123ef65cdULL, 0xb5c0fbcfec4d3b2fULL,
	0xe9b5dba58189dbbcULL, 0x3956c25bf348b538ULL, 0x59f111f1b605d019ULL,
	0x923f82a4af194f9bULL, 0xab1c5ed5da6d8118ULL, 0xd807aa98a3030242ULL,
	0x12835b0145706fbeULL, 0x243185be4ee4b28cULL, 0x550c7dc3d5ffb4e2ULL,
	0x72be5d74f27b896fULL, 0x80deb1fe3b1696b1ULL, 0x9bdc06a725c71235ULL,
	0xc19bf174cf692694ULL, 0xe49b69c19ef14ad2ULL, 0xefbe4786384f25e3ULL,
	0x0fc19dc68b8cd5b5ULL, 0x240ca1cc77ac9c65ULL, 0x2de92c6f592b0275ULL,
	0x4a7484aa6ea6e483ULL, 0x5cb0a9dcbd41fbd4ULL, 0x76f988da831153b5ULL,
	0x983e5152ee66dfabULL, 0xa831c66d2db43210ULL, 0xb00327c898fb213fULL,
	0xbf597fc7beef0ee4ULL, 0xc6e00bf33da88fc2ULL, 0xd5a79147930aa725ULL,
	0x06ca6351e003826fULL, 0x142929670a0e6e70ULL, 0x27b70a8546d22ffcULL,
	0x2e1b21385c26c926ULL, 0x4d2c6dfc5ac42aedULL, 0x53380d139d95b3dfULL,
	0x650a73548baf63deULL, 0x766a0abb3c77b2a8ULL, 0x81c2c92e47edaee6ULL,
	0x92722c851482353bULL, 0xa2bfe8a14cf10364ULL, 0xa81a664bbc423001ULL,
	0xc24b8b70d0f89791ULL, 0xc76c51a30654be30ULL, 0xd192e819d6ef5218ULL,
	0xd69906245565a910ULL, 0xf40e35855771202aULL, 0x106aa07032bbd1b8ULL,
	0x19a4c116b8d2d0c8ULL, 0x1e376c085141ab53ULL, 0x2748774cdf8eeb99ULL,
	0x34b0bcb5e19b48a8ULL, 0x391c0cb3c5c95a63ULL, 0x4ed8aa4ae3418acbULL,
	0x5b9cca4f7763e373ULL, 0x682e6ff3d6b2b8a3ULL, 0x748f82ee5defb2fcULL,
	0x78a5636f43172f60ULL, 0x84c87814a1f0ab72ULL, 0x8cc702081a6439ecULL,
	0x90befffa23631e28ULL, 0xa4506cebde82bde9ULL, 0xbef9a3f7b2c67915ULL,
	0xc67178f2e372532bULL, 0xca273eceea26619cULL, 0xd186b8c721c0c207ULL,
	0xeada7dd6cde0eb1eULL, 0xf57d4f7fee6ed178ULL, 0x06f067aa72176fbaULL,
	0x0a637dc5a2c898a6ULL, 0x113f9804bef90daeULL, 0x1b710b35131c471bULL,
	0x28db77f523047d84ULL, 0x32caab7b40c72493ULL, 0x3c9ebe0a15c9bebcULL,
	0x431d67c49c100d4cULL, 0x4cc5d4becb3e42b6ULL, 0x597f299cfc657e2aULL,
	0x5fcb6fab3ad6faecULL, 0x6c44198c4a475817ULL,
};

static const zk_u64 zk_sha512_iv[8] = {
	0x6a09e667f3bcc908ULL, 0xbb67ae8584caa73bULL, 0x3c6ef372fe94f82bULL,
	0xa54ff53a5f1d36f1ULL, 0x510e527fade682d1ULL, 0x9b05688c2b3e6c1fULL,
	0x1f83d9abfb41bd6bULL, 0x5be0cd19137e2179ULL,
};

static inline zk_u64 zk_ror64(zk_u64 x, int c)
{
	return (x >> c) | (x << (64 - c));
}

static inline zk_u64 zk_load_be64(const zk_u8 *p)
{
	zk_u64 v = 0;
	int i;

	for (i = 0; i < 8; i++)
		v = (v << 8) | p[i];
	return v;
}

static inline void zk_store_be64(zk_u8 *p, zk_u64 v)
{
	int i;

	for (i = 7; i >= 0; i--) {
		p[i] = (zk_u8)v;
		v >>= 8;
	}
}

static void zk_sha512_block(zk_u64 state[8], const zk_u8 *m)
{
	zk_u64 w[16], a[8], b[8], t;
	int i, j;

	for (i = 0; i < 16; i++)
		w[i] = zk_load_be64(m + 8 * i);
	for (i = 0; i < 8; i++)
		a[i] = state[i];

	for (i = 0; i < 80; i++) {
		for (j = 0; j < 8; j++)
			b[j] = a[j];
		t = a[7] +
		    (zk_ror64(a[4], 14) ^ zk_ror64(a[4], 18) ^ zk_ror64(a[4], 41)) +
		    ((a[4] & a[5]) ^ (~a[4] & a[6])) + zk_sha512_k[i] + w[i % 16];
		b[7] = t +
		       (zk_ror64(a[0], 28) ^ zk_ror64(a[0], 34) ^ zk_ror64(a[0], 39)) +
		       ((a[0] & a[1]) ^ (a[0] & a[2]) ^ (a[1] & a[2]));
		b[3] += t;
		for (j = 0; j < 8; j++)
			a[(j + 1) % 8] = b[j];
		if (i % 16 == 15) {
			for (j = 0; j < 16; j++) {
				zk_u64 s0 = w[(j + 1) % 16], s1 = w[(j + 14) % 16];

				w[j] += w[(j + 9) % 16] +
					(zk_ror64(s0, 1) ^ zk_ror64(s0, 8) ^ (s0 >> 7)) +
					(zk_ror64(s1, 19) ^ zk_ror64(s1, 61) ^ (s1 >> 6));
			}
		}
	}

	for (i = 0; i < 8; i++)
		state[i] += a[i];
	zk_memzero(w, sizeof(w));
}

void zk_sha512_init(struct zk_sha512_ctx *ctx)
{
	int i;

	for (i = 0; i < 8; i++)
		ctx->state[i] = zk_sha512_iv[i];
	ctx->total = 0;
	ctx->buflen = 0;
}

void zk_sha512_update(struct zk_sha512_ctx *ctx, const void *data, zk_size len)
{
	const zk_u8 *p = (const zk_u8 *)data;

	ctx->total += len;
	if (ctx->buflen) {
		zk_size take = ZK_SHA512_BLOCK_SIZE - ctx->buflen;

		if (take > len)
			take = len;
		memcpy(ctx->buf + ctx->buflen, p, take);
		ctx->buflen += take;
		p += take;
		len -= take;
		if (ctx->buflen < ZK_SHA512_BLOCK_SIZE)
			return;
		zk_sha512_block(ctx->state, ctx->buf);
		ctx->buflen = 0;
	}
	while (len >= ZK_SHA512_BLOCK_SIZE) {
		zk_sha512_block(ctx->state, p);
		p += ZK_SHA512_BLOCK_SIZE;
		len -= ZK_SHA512_BLOCK_SIZE;
	}
	if (len) {
		memcpy(ctx->buf, p, len);
		ctx->buflen = len;
	}
}

void zk_sha512_final(struct zk_sha512_ctx *ctx, zk_u8 out[ZK_SHA512_DIGEST_SIZE])
{
	zk_u64 bits = ctx->total << 3;
	int i;

	ctx->buf[ctx->buflen++] = 0x80;
	if (ctx->buflen > ZK_SHA512_BLOCK_SIZE - 16) {
		memset(ctx->buf + ctx->buflen, 0, ZK_SHA512_BLOCK_SIZE - ctx->buflen);
		zk_sha512_block(ctx->state, ctx->buf);
		ctx->buflen = 0;
	}
	memset(ctx->buf + ctx->buflen, 0, ZK_SHA512_BLOCK_SIZE - 16 - ctx->buflen);
	/* 128-bit big-endian length; the upper 64 bits hold total >> 61. */
	zk_store_be64(ctx->buf + ZK_SHA512_BLOCK_SIZE - 16, ctx->total >> 61);
	zk_store_be64(ctx->buf + ZK_SHA512_BLOCK_SIZE - 8, bits);
	zk_sha512_block(ctx->state, ctx->buf);

	for (i = 0; i < 8; i++)
		zk_store_be64(out + 8 * i, ctx->state[i]);
	zk_memzero(ctx, sizeof(*ctx));
}

void zk_sha512(zk_u8 out[ZK_SHA512_DIGEST_SIZE], const void *data, zk_size len)
{
	struct zk_sha512_ctx ctx;

	zk_sha512_init(&ctx);
	zk_sha512_update(&ctx, data, len);
	zk_sha512_final(&ctx, out);
}
