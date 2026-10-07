/* SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary */
/*
 * ZKFC tweak catalog: device optimizations applied through sysfs/procfs.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#ifndef ZK_TWEAKS_H
#define ZK_TWEAKS_H

#include "zk_json.h"

enum zk_tweak_kind {
	ZT_INT,		/* integer node, first existing candidate */
	ZT_STR,		/* string node */
	ZT_GOV,		/* cpufreq scaling_governor on every policy */
	ZT_IOSCHED,	/* block queue/scheduler on every block device */
	ZT_PERCPU,	/* integer node per online CPU (path has %d) */
};

enum zk_tweak_cat {
	ZC_CPU, ZC_GPU, ZC_MEM, ZC_ZRAM, ZC_IO, ZC_NET,
	ZC_DISPLAY, ZC_BATTERY, ZC_KERNEL, ZC_CAT_COUNT
};

struct zk_tweak {
	const char *id;
	enum zk_tweak_cat cat;
	enum zk_tweak_kind kind;
	const char *title;
	const char *const *paths;	/* NULL-terminated candidates */
	long long min, max;		/* for ZT_INT / ZT_PERCPU; 0,0 = unchecked */
	unsigned int lite : 1;		/* disabled in lite mode */
};

const char *zk_tweak_cat_name(enum zk_tweak_cat c);

/* Walk the catalog, omitting heavy entries when lite_mode is non-zero. */
void zk_tweaks_dump(struct zk_json *j, int lite_mode);

/* Find a tweak by id, or NULL. */
const struct zk_tweak *zk_tweak_find(const char *id);

/* Apply one tweak. lite_mode skips tweaks flagged .lite. Returns 0 or -errno
 * (-ENODEV if no candidate path exists on this device). */
int zk_tweak_apply(const struct zk_tweak *t, const char *value, int lite_mode);

/* Read the current value of a tweak into buf. 0 or -errno. */
int zk_tweak_read(const struct zk_tweak *t, char *buf, size_t len);

#endif
