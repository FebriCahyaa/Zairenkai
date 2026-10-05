// SPDX-License-Identifier: GPL-2.0-only OR MIT
/*
 * ZKFC Ed25519 signature verification (RFC 8032, verify only).
 *
 * Field and group arithmetic are derived from TweetNaCl (public domain,
 * https://tweetnacl.cr.yp.to/). Changes made for Zairenkai:
 *  - verification only, no secret-key code paths;
 *  - streaming SHA-512 so the message is never copied;
 *  - rejection of non-canonical S (S >= L), RFC 8032 section 5.1.7;
 *  - no left shifts of negative values (undefined behaviour in C);
 *  - caller-provided workspace to keep kernel stack usage small.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#include "zk_ed25519.h"

static const zk_gf zk_gf0;
static const zk_gf zk_gf1 = { 1 };
static const zk_gf zk_D = { 0x78a3, 0x1359, 0x4dca, 0x75eb, 0xd8ab, 0x4141,
			    0x0a4d, 0x0070, 0xe898, 0x7779, 0x4079, 0x8cc7,
			    0xfe73, 0x2b6f, 0x6cee, 0x5203 };
static const zk_gf zk_D2 = { 0xf159, 0x26b2, 0x9b94, 0xebd6, 0xb156, 0x8283,
			     0x149a, 0x00e0, 0xd130, 0xeef3, 0x80f2, 0x198e,
			     0xfce7, 0x56df, 0xd9dc, 0x2406 };
static const zk_gf zk_X = { 0xd51a, 0x8f25, 0x2d60, 0xc956, 0xa7b2, 0x9525,
			    0xc760, 0x692c, 0xdc5c, 0xfdd6, 0xe231, 0xc0a4,
			    0x53fe, 0xcd6e, 0x36d3, 0x2169 };
static const zk_gf zk_Y = { 0x6658, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666,
			    0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666,
			    0x6666, 0x6666, 0x6666, 0x6666 };
static const zk_gf zk_I = { 0xa0b0, 0x4a0e, 0x1b27, 0xc4ee, 0xe478, 0xad2f,
			    0x1806, 0x2f43, 0xd7a7, 0x3dfb, 0x0099, 0x2b4d,
			    0xdf0b, 0x4fc1, 0x2480, 0x2b83 };

/* Group order L, little endian. */
static const zk_u8 zk_L[32] = { 0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58,
				0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9, 0xde, 0x14,
				0,    0,    0,    0,    0,    0,    0,    0,
				0,    0,    0,    0,    0,    0,    0,    0x10 };

static int zk_verify32(const zk_u8 *x, const zk_u8 *y)
{
	zk_u32 d = 0;
	int i;

	for (i = 0; i < 32; i++)
		d |= x[i] ^ y[i];
	return (1 & ((d - 1) >> 8)) - 1;
}

static void zk_set(zk_gf r, const zk_gf a)
{
	int i;

	for (i = 0; i < 16; i++)
		r[i] = a[i];
}

static void zk_car(zk_gf o)
{
	zk_i64 c;
	int i;

	for (i = 0; i < 16; i++) {
		o[i] += 65536;
		c = o[i] >> 16;
		o[(i + 1) * (i < 15)] += c - 1 + 37 * (c - 1) * (i == 15);
		o[i] -= c * 65536;
	}
}

static void zk_sel(zk_gf p, zk_gf q, int b)
{
	zk_i64 t, c = ~(zk_i64)(b - 1);
	int i;

	for (i = 0; i < 16; i++) {
		t = c & (p[i] ^ q[i]);
		p[i] ^= t;
		q[i] ^= t;
	}
}

static void zk_pack25519(zk_u8 *o, const zk_gf n)
{
	zk_gf m, t;
	int i, j, b;

	zk_set(t, n);
	zk_car(t);
	zk_car(t);
	zk_car(t);
	for (j = 0; j < 2; j++) {
		m[0] = t[0] - 0xffed;
		for (i = 1; i < 15; i++) {
			m[i] = t[i] - 0xffff - ((m[i - 1] >> 16) & 1);
			m[i - 1] &= 0xffff;
		}
		m[15] = t[15] - 0x7fff - ((m[14] >> 16) & 1);
		b = (m[15] >> 16) & 1;
		m[14] &= 0xffff;
		zk_sel(t, m, 1 - b);
	}
	for (i = 0; i < 16; i++) {
		o[2 * i] = t[i] & 0xff;
		o[2 * i + 1] = (t[i] >> 8) & 0xff;
	}
}

static int zk_neq25519(const zk_gf a, const zk_gf b)
{
	zk_u8 c[32], d[32];

	zk_pack25519(c, a);
	zk_pack25519(d, b);
	return zk_verify32(c, d);
}

static zk_u8 zk_par25519(const zk_gf a)
{
	zk_u8 d[32];

	zk_pack25519(d, a);
	return d[0] & 1;
}

static void zk_unpack25519(zk_gf o, const zk_u8 *n)
{
	int i;

	for (i = 0; i < 16; i++)
		o[i] = n[2 * i] + ((zk_i64)n[2 * i + 1] << 8);
	o[15] &= 0x7fff;
}

static void zk_A(zk_gf o, const zk_gf a, const zk_gf b)
{
	int i;

	for (i = 0; i < 16; i++)
		o[i] = a[i] + b[i];
}

static void zk_Z(zk_gf o, const zk_gf a, const zk_gf b)
{
	int i;

	for (i = 0; i < 16; i++)
		o[i] = a[i] - b[i];
}

static void zk_M(zk_gf o, const zk_gf a, const zk_gf b)
{
	zk_i64 t[31];
	int i, j;

	for (i = 0; i < 31; i++)
		t[i] = 0;
	for (i = 0; i < 16; i++)
		for (j = 0; j < 16; j++)
			t[i + j] += a[i] * b[j];
	for (i = 0; i < 15; i++)
		t[i] += 38 * t[i + 16];
	for (i = 0; i < 16; i++)
		o[i] = t[i];
	zk_car(o);
	zk_car(o);
}

static void zk_S(zk_gf o, const zk_gf a)
{
	zk_M(o, a, a);
}

static void zk_inv25519(zk_gf o, const zk_gf in)
{
	zk_gf c;
	int a;

	zk_set(c, in);
	for (a = 253; a >= 0; a--) {
		zk_S(c, c);
		if (a != 2 && a != 4)
			zk_M(c, c, in);
	}
	zk_set(o, c);
}

static void zk_pow2523(zk_gf o, const zk_gf in)
{
	zk_gf c;
	int a;

	zk_set(c, in);
	for (a = 250; a >= 0; a--) {
		zk_S(c, c);
		if (a != 1)
			zk_M(c, c, in);
	}
	zk_set(o, c);
}

static void zk_add(zk_gf p[4], zk_gf q[4])
{
	/* Temporaries are reused to keep the frame small for kernel stacks. */
	zk_gf a, b, c, d, t;

	zk_Z(a, p[1], p[0]);
	zk_Z(t, q[1], q[0]);
	zk_M(a, a, t);
	zk_A(b, p[0], p[1]);
	zk_A(t, q[0], q[1]);
	zk_M(b, b, t);
	zk_M(c, p[3], q[3]);
	zk_M(c, c, zk_D2);
	zk_M(d, p[2], q[2]);
	zk_A(d, d, d);
	zk_Z(t, b, a);		/* e = b - a */
	zk_A(b, b, a);		/* h = b + a */
	zk_Z(a, d, c);		/* f = d - c */
	zk_A(d, d, c);		/* g = d + c */

	zk_M(p[0], t, a);	/* e * f */
	zk_M(p[1], b, d);	/* h * g */
	zk_M(p[2], d, a);	/* g * f */
	zk_M(p[3], t, b);	/* e * h */
}

static void zk_cswap(zk_gf p[4], zk_gf q[4], zk_u8 b)
{
	int i;

	for (i = 0; i < 4; i++)
		zk_sel(p[i], q[i], b);
}

static void zk_pack(zk_u8 *r, zk_gf p[4])
{
	zk_gf tx, ty, zi;

	zk_inv25519(zi, p[2]);
	zk_M(tx, p[0], zi);
	zk_M(ty, p[1], zi);
	zk_pack25519(r, ty);
	r[31] ^= zk_par25519(tx) << 7;
}

static void zk_scalarmult(zk_gf p[4], zk_gf q[4], const zk_u8 *s)
{
	int i;

	zk_set(p[0], zk_gf0);
	zk_set(p[1], zk_gf1);
	zk_set(p[2], zk_gf1);
	zk_set(p[3], zk_gf0);
	for (i = 255; i >= 0; --i) {
		zk_u8 b = (s[i / 8] >> (i & 7)) & 1;

		zk_cswap(p, q, b);
		zk_add(q, p);
		zk_add(p, p);
		zk_cswap(p, q, b);
	}
}

static void zk_scalarbase(zk_gf p[4], zk_gf q[4], const zk_u8 *s)
{
	zk_set(q[0], zk_X);
	zk_set(q[1], zk_Y);
	zk_set(q[2], zk_gf1);
	zk_M(q[3], zk_X, zk_Y);
	zk_scalarmult(p, q, s);
}

static void zk_modL(zk_u8 *r, zk_i64 x[64])
{
	zk_i64 carry;
	int i, j;

	for (i = 63; i >= 32; --i) {
		carry = 0;
		for (j = i - 32; j < i - 12; ++j) {
			x[j] += carry - 16 * x[i] * zk_L[j - (i - 32)];
			carry = (x[j] + 128) >> 8;
			x[j] -= carry * 256;
		}
		x[j] += carry;
		x[i] = 0;
	}
	carry = 0;
	for (j = 0; j < 32; j++) {
		x[j] += carry - (x[31] >> 4) * zk_L[j];
		carry = x[j] >> 8;
		x[j] &= 255;
	}
	for (j = 0; j < 32; j++)
		x[j] -= carry * zk_L[j];
	for (i = 0; i < 32; i++) {
		x[i + 1] += x[i] >> 8;
		r[i] = x[i] & 255;
	}
}

static void zk_reduce(zk_u8 *r)
{
	zk_i64 x[64];
	int i;

	for (i = 0; i < 64; i++)
		x[i] = (zk_u64)r[i];
	for (i = 0; i < 64; i++)
		r[i] = 0;
	zk_modL(r, x);
}

/* Decode -A from the encoded public key. */
static int zk_unpackneg(zk_gf r[4], const zk_u8 p[32])
{
	zk_gf t, chk, num, den, den2;

	zk_set(r[2], zk_gf1);
	zk_unpack25519(r[1], p);
	zk_S(num, r[1]);
	zk_M(den, num, zk_D);
	zk_Z(num, num, r[2]);
	zk_A(den, r[2], den);

	zk_S(den2, den);
	zk_S(t, den2);		/* den^4 */
	zk_M(t, t, den2);	/* den^6 */
	zk_M(t, t, num);
	zk_M(t, t, den);

	zk_pow2523(t, t);
	zk_M(t, t, num);
	zk_M(t, t, den);
	zk_M(t, t, den);
	zk_M(r[0], t, den);

	zk_S(chk, r[0]);
	zk_M(chk, chk, den);
	if (zk_neq25519(chk, num))
		zk_M(r[0], r[0], zk_I);

	zk_S(chk, r[0]);
	zk_M(chk, chk, den);
	if (zk_neq25519(chk, num))
		return -1;

	if (zk_par25519(r[0]) == (p[31] >> 7))
		zk_Z(r[0], zk_gf0, r[0]);

	zk_M(r[3], r[0], r[1]);
	return 0;
}

/* Returns 1 when the little-endian scalar s is strictly below L. */
static int zk_scalar_is_canonical(const zk_u8 s[32])
{
	int i;

	for (i = 31; i >= 0; i--) {
		if (s[i] < zk_L[i])
			return 1;
		if (s[i] > zk_L[i])
			return 0;
	}
	return 0; /* s == L */
}

int zk_ed25519_verify(const zk_u8 sig[ZK_ED25519_SIG_SIZE], const zk_u8 *msg,
		      zk_size msglen, const zk_u8 pk[ZK_ED25519_PUBKEY_SIZE],
		      struct zk_ed25519_ws *ws)
{
	int ret = -1;

	if (!sig || !pk || !ws || (!msg && msglen))
		return -1;
	if (!zk_scalar_is_canonical(sig + 32))
		return -1;
	if (zk_unpackneg(ws->q, pk))
		goto out;

	/* h = SHA-512(R || A || M) mod L */
	zk_sha512_init(&ws->sha);
	zk_sha512_update(&ws->sha, sig, 32);
	zk_sha512_update(&ws->sha, pk, 32);
	zk_sha512_update(&ws->sha, msg, msglen);
	zk_sha512_final(&ws->sha, ws->h);
	zk_reduce(ws->h);

	/* Check R == [S]B + [h](-A). */
	zk_scalarmult(ws->p, ws->q, ws->h);
	zk_scalarbase(ws->q, ws->r, sig + 32);
	zk_add(ws->p, ws->q);
	zk_pack(ws->t, ws->p);
	ret = zk_verify32(sig, ws->t);
out:
	zk_memzero(ws, sizeof(*ws));
	return ret;
}
