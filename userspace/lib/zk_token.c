// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
#define _GNU_SOURCE
/*
 * Parse armored ZKFC API Token / revocation-list files (the ".zkl"/".zkcrl"
 * format emitted by tools/zkfc-license). Decoding only; the kernel verifies
 * the signature.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#include "libzkfc.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int b64val(int c)
{
	if (c >= 'A' && c <= 'Z') return c - 'A';
	if (c >= 'a' && c <= 'z') return c - 'a' + 26;
	if (c >= '0' && c <= '9') return c - '0' + 52;
	if (c == '+') return 62;
	if (c == '/') return 63;
	return -1;
}

static long b64_decode(const char *in, unsigned char *out, size_t outcap)
{
	size_t o = 0;
	int quad[4], qn = 0;

	for (; *in; in++) {
		int v;

		if (*in == '=' || *in == '\n' || *in == '\r' || *in == ' ' || *in == '\t')
			continue;
		v = b64val((unsigned char)*in);
		if (v < 0)
			return -EINVAL;
		quad[qn++] = v;
		if (qn == 4) {
			if (o + 3 > outcap)
				return -EOVERFLOW;
			out[o++] = (quad[0] << 2) | (quad[1] >> 4);
			out[o++] = (quad[1] << 4) | (quad[2] >> 2);
			out[o++] = (quad[2] << 6) | quad[3];
			qn = 0;
		}
	}
	if (qn == 3) {
		if (o + 2 > outcap)
			return -EOVERFLOW;
		out[o++] = (quad[0] << 2) | (quad[1] >> 4);
		out[o++] = (quad[1] << 4) | (quad[2] >> 2);
	} else if (qn == 2) {
		if (o + 1 > outcap)
			return -EOVERFLOW;
		out[o++] = (quad[0] << 2) | (quad[1] >> 4);
	} else if (qn != 0) {
		return -EINVAL;
	}
	return (long)o;
}

static int parse_armor(const char *path, const char *begin, const char *end,
		       unsigned char *out, size_t expect)
{
	char *buf, *b, *e;
	long n, got;
	FILE *f = fopen(path, "rb");
	size_t cap = expect + 4096;

	if (!f)
		return -errno;
	buf = calloc(1, cap + 1);
	if (!buf) {
		fclose(f);
		return -ENOMEM;
	}
	n = (long)fread(buf, 1, cap, f);
	fclose(f);
	buf[n > 0 ? n : 0] = '\0';

	b = strstr(buf, begin);
	e = b ? strstr(b, end) : NULL;
	if (!b || !e) {
		free(buf);
		return -EINVAL;
	}
	b += strlen(begin);
	*e = '\0';
	got = b64_decode(b, out, expect);
	free(buf);
	if (got < 0)
		return (int)got;
	if ((size_t)got != expect)
		return -EMSGSIZE;
	return 0;
}

int zkfc_parse_token_file(const char *path, struct zkfc_license_token *out)
{
	return parse_armor(path, "-----BEGIN ZKFC API TOKEN-----",
			   "-----END ZKFC API TOKEN-----",
			   (unsigned char *)out, sizeof(*out));
}

int zkfc_parse_crl_file(const char *path, struct zkfc_crl *out)
{
	return parse_armor(path, "-----BEGIN ZKFC REVOCATION LIST-----",
			   "-----END ZKFC REVOCATION LIST-----",
			   (unsigned char *)out, sizeof(*out));
}
