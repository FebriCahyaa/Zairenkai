// SPDX-License-Identifier: GPL-2.0-only OR MIT
/*
 * Host test for the ZKFC SHA-512 and Ed25519 verifier.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "zk_ed25519.h"
#include "zk_sha512.h"

static size_t unhex(const char *s, unsigned char **out)
{
	size_t n, i;

	if (!strcmp(s, "-")) {
		*out = malloc(1);
		return 0;
	}
	n = strlen(s) / 2;
	*out = malloc(n + 1);
	for (i = 0; i < n; i++)
		sscanf(s + 2 * i, "%2hhx", &(*out)[i]);
	return n;
}

int main(int argc, char **argv)
{
	static char line[20000];
	struct zk_ed25519_ws ws;
	int fails = 0, total = 0;
	FILE *f = fopen(argc > 1 ? argv[1] : "vectors.txt", "r");

	if (!f) {
		perror("vectors");
		return 2;
	}
	while (fgets(line, sizeof(line), f)) {
		char *tok[5] = { 0 };
		int nt = 0;
		char *p = strtok(line, " \n");

		while (p && nt < 5) {
			tok[nt++] = p;
			p = strtok(NULL, " \n");
		}
		if (nt == 3 && tok[0][0] == 'H') {
			unsigned char *msg, *exp, out[64];
			size_t n = unhex(tok[1], &msg);

			unhex(tok[2], &exp);
			zk_sha512(out, msg, n);
			total++;
			if (memcmp(out, exp, 64)) {
				fails++;
				fprintf(stderr, "SHA-512 mismatch for %zu-byte message\n", n);
			}
			free(msg);
			free(exp);
		} else if (nt == 5 && tok[0][0] == 'V') {
			unsigned char *pk, *sig, *msg;
			size_t n;
			int expect = atoi(tok[1]), got;

			unhex(tok[2], &pk);
			unhex(tok[3], &sig);
			n = unhex(tok[4], &msg);
			got = zk_ed25519_verify(sig, msg, n, pk, &ws) == 0;
			total++;
			if (got != expect) {
				fails++;
				fprintf(stderr, "Ed25519 expected %d got %d (msg %zu bytes)\n",
					expect, got, n);
			}
			free(pk);
			free(sig);
			free(msg);
		}
	}
	fclose(f);
	printf("%d/%d crypto vectors passed\n", total - fails, total);
	return fails ? 1 : 0;
}
