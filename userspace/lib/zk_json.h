/* SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary */
/*
 * Minimal streaming JSON writer for zkfctl output.
 * Copyright (C) 2026 FebriCahyaa
 */
#ifndef ZK_JSON_H
#define ZK_JSON_H

#include <stdio.h>

struct zk_json {
	FILE *f;
	int depth;
	/* bit n set => a value has already been written at depth n */
	unsigned int seen;
};

void zj_init(struct zk_json *j, FILE *f);
void zj_obj_open(struct zk_json *j, const char *key);
void zj_obj_close(struct zk_json *j);
void zj_arr_open(struct zk_json *j, const char *key);
void zj_arr_close(struct zk_json *j);
void zj_str(struct zk_json *j, const char *key, const char *val);
void zj_int(struct zk_json *j, const char *key, long long val);
void zj_uint(struct zk_json *j, const char *key, unsigned long long val);
void zj_bool(struct zk_json *j, const char *key, int val);
void zj_raw(struct zk_json *j, const char *key, const char *raw_val);
void zj_finish(struct zk_json *j);

#endif
