// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
#define _GNU_SOURCE
/*
 * Parse armored ZKFC API tokens, delegated bundles, and revocation lists.
 * Decoding only; the kernel remains authoritative for signature verification.
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
	int quad[4], qn = 0;
	size_t o = 0;
	bool ended = false;

	for (; *in; in++) {
		int v;

		if (*in == '\n' || *in == '\r' || *in == ' ' || *in == '\t')
			continue;
		if (ended)
			return -EINVAL;
		if (*in == '=')
			v = -2;
		else {
			v = b64val((unsigned char)*in);
			if (v < 0)
				return -EINVAL;
		}
		quad[qn++] = v;
		if (qn != 4)
			continue;

		if (quad[0] < 0 || quad[1] < 0)
			return -EINVAL;
		if (quad[2] == -2) {
			if (quad[3] != -2 || (quad[1] & 0x0f))
				return -EINVAL;
			if (o + 1 > outcap)
				return -EOVERFLOW;
			out[o++] = (quad[0] << 2) | (quad[1] >> 4);
			ended = true;
		} else if (quad[3] == -2) {
			if (quad[2] < 0 || (quad[2] & 0x03))
				return -EINVAL;
			if (o + 2 > outcap)
				return -EOVERFLOW;
			out[o++] = (quad[0] << 2) | (quad[1] >> 4);
			out[o++] = (quad[1] << 4) | (quad[2] >> 2);
			ended = true;
		} else {
			if (quad[2] < 0 || quad[3] < 0)
				return -EINVAL;
			if (o + 3 > outcap)
				return -EOVERFLOW;
			out[o++] = (quad[0] << 2) | (quad[1] >> 4);
			out[o++] = (quad[1] << 4) | (quad[2] >> 2);
			out[o++] = (quad[2] << 6) | quad[3];
		}
		qn = 0;
	}
	if (qn != 0)
		return -EINVAL;
	return (long)o;
}

static int is_space_only(const char *s, size_t len)
{
	size_t i;

	for (i = 0; i < len; i++)
		if (s[i] != '\n' && s[i] != '\r' && s[i] != ' ' && s[i] != '\t')
			return 0;
	return 1;
}

static int parse_armor(const char *path, const char *begin, const char *end,
		       unsigned char *out, size_t expect)
{
	char *buf, *b, *e, *after;
	long n, got;
	FILE *f;
	size_t cap = expect + 4096;
	int rc = 0;

	if (!path || !out || !begin || !end)
		return -EINVAL;
	f = fopen(path, "rb");
	if (!f)
		return -errno;
	buf = calloc(1, cap + 2);
	if (!buf) {
		fclose(f);
		return -ENOMEM;
	}
	n = (long)fread(buf, 1, cap + 1, f);
	if (ferror(f))
		rc = -EIO;
	fclose(f);
	if (rc)
		goto out;
	if (n < 0 || (size_t)n > cap) {
		rc = -EFBIG;
		goto out;
	}
	buf[n] = '\0';

	b = strstr(buf, begin);
	if (!b || strstr(b + strlen(begin), begin) ||
	    !is_space_only(buf, (size_t)(b - buf))) {
		rc = -EINVAL;
		goto out;
	}
	e = strstr(b + strlen(begin), end);
	if (!e || strstr(e + strlen(end), end)) {
		rc = -EINVAL;
		goto out;
	}
	after = e + strlen(end);
	if (!is_space_only(after, strlen(after))) {
		rc = -EINVAL;
		goto out;
	}
	b += strlen(begin);
	*e = '\0';
	got = b64_decode(b, out, expect);
	if (got < 0) {
		rc = (int)got;
		goto out;
	}
	if ((size_t)got != expect)
		rc = -EMSGSIZE;
out:
	free(buf);
	return rc;
}

int zkfc_parse_token_file(const char *path, struct zkfc_license_token *out)
{
	return parse_armor(path, "-----BEGIN ZKFC API TOKEN-----",
			   "-----END ZKFC API TOKEN-----",
			   (unsigned char *)out, sizeof(*out));
}

int zkfc_parse_bundle_file(const char *path, struct zkfc_license_bundle *out)
{
	return parse_armor(path, "-----BEGIN ZKFC DELEGATED TOKEN-----",
			   "-----END ZKFC DELEGATED TOKEN-----",
			   (unsigned char *)out, sizeof(*out));
}

int zkfc_parse_crl_file(const char *path, struct zkfc_crl *out)
{
	return parse_armor(path, "-----BEGIN ZKFC REVOCATION LIST-----",
			   "-----END ZKFC REVOCATION LIST-----",
			   (unsigned char *)out, sizeof(*out));
}
