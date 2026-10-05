// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
#define _GNU_SOURCE
/*
 * Minimal streaming JSON writer.
 * Copyright (C) 2026 FebriCahyaa
 */
#include "zk_json.h"

#include <stdarg.h>

void zj_init(struct zk_json *j, FILE *f)
{
	j->f = f;
	j->depth = 0;
	j->seen = 0;
}

static void zj_sep(struct zk_json *j)
{
	if (j->depth > 0 && (j->seen & (1u << j->depth)))
		fputc(',', j->f);
	j->seen |= (1u << j->depth);
}

static void zj_key(struct zk_json *j, const char *key)
{
	zj_sep(j);
	if (key) {
		fputc('"', j->f);
		fputs(key, j->f);
		fputs("\":", j->f);
	}
}

static void zj_enter(struct zk_json *j)
{
	if (++j->depth < (int)(8 * sizeof(j->seen)))
		j->seen &= ~(1u << j->depth);
}

static void zj_leave(struct zk_json *j)
{
	if (j->depth > 0)
		j->depth--;
}

void zj_obj_open(struct zk_json *j, const char *key)
{
	zj_key(j, key);
	fputc('{', j->f);
	zj_enter(j);
}

void zj_obj_close(struct zk_json *j)
{
	zj_leave(j);
	fputc('}', j->f);
}

void zj_arr_open(struct zk_json *j, const char *key)
{
	zj_key(j, key);
	fputc('[', j->f);
	zj_enter(j);
}

void zj_arr_close(struct zk_json *j)
{
	zj_leave(j);
	fputc(']', j->f);
}

static void zj_escape(struct zk_json *j, const char *s)
{
	fputc('"', j->f);
	for (; s && *s; s++) {
		unsigned char c = (unsigned char)*s;

		switch (c) {
		case '"': fputs("\\\"", j->f); break;
		case '\\': fputs("\\\\", j->f); break;
		case '\n': fputs("\\n", j->f); break;
		case '\r': fputs("\\r", j->f); break;
		case '\t': fputs("\\t", j->f); break;
		default:
			if (c < 0x20)
				fprintf(j->f, "\\u%04x", c);
			else
				fputc(c, j->f);
		}
	}
	fputc('"', j->f);
}

void zj_str(struct zk_json *j, const char *key, const char *val)
{
	zj_key(j, key);
	if (val)
		zj_escape(j, val);
	else
		fputs("null", j->f);
}

void zj_int(struct zk_json *j, const char *key, long long val)
{
	zj_key(j, key);
	fprintf(j->f, "%lld", val);
}

void zj_uint(struct zk_json *j, const char *key, unsigned long long val)
{
	zj_key(j, key);
	fprintf(j->f, "%llu", val);
}

void zj_bool(struct zk_json *j, const char *key, int val)
{
	zj_key(j, key);
	fputs(val ? "true" : "false", j->f);
}

void zj_raw(struct zk_json *j, const char *key, const char *raw_val)
{
	zj_key(j, key);
	fputs(raw_val, j->f);
}

void zj_finish(struct zk_json *j)
{
	fputc('\n', j->f);
	fflush(j->f);
}
