// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
#define _GNU_SOURCE
/*
 * ZKFC tweak catalog.
 *
 * Each tweak lists candidate sysfs/procfs paths; the first that exists on the
 * running device is used, so the same catalog works across Snapdragon,
 * MediaTek, Exynos, Tensor and Kirin without per-SoC builds. Governor and I/O
 * scheduler tweaks fan out to every cpufreq policy / block device.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#include "zk_tweaks.h"
#include "zk_sysfs.h"

#include <dirent.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ---- candidate path tables ---- */
static const char *const P_SWAPPINESS[]   = { "/proc/sys/vm/swappiness", NULL };
static const char *const P_DIRTY_RATIO[]  = { "/proc/sys/vm/dirty_ratio", NULL };
static const char *const P_DIRTY_BG[]     = { "/proc/sys/vm/dirty_background_ratio", NULL };
static const char *const P_VFS_CACHE[]    = { "/proc/sys/vm/vfs_cache_pressure", NULL };
static const char *const P_MIN_FREE[]     = { "/proc/sys/vm/min_free_kbytes", NULL };
static const char *const P_STAT_INT[]     = { "/proc/sys/vm/stat_interval", NULL };
static const char *const P_TCP_CC[]       = { "/proc/sys/net/ipv4/tcp_congestion_control", NULL };
static const char *const P_TCP_FASTOPEN[] = { "/proc/sys/net/ipv4/tcp_fastopen", NULL };
static const char *const P_TCP_LOWLAT[]   = { "/proc/sys/net/ipv4/tcp_low_latency", NULL };
static const char *const P_ZRAM_COMP[]    = { "/sys/block/zram0/comp_algorithm", NULL };
static const char *const P_ZRAM_SIZE[]    = { "/sys/block/zram0/disksize", NULL };
static const char *const P_ZRAM_MAXCOMP[] = { "/sys/block/zram0/max_comp_streams", NULL };
static const char *const P_SCHED_LAT[]    = { "/proc/sys/kernel/sched_latency_ns", NULL };
static const char *const P_SCHED_MINGR[]  = { "/proc/sys/kernel/sched_min_granularity_ns", NULL };
static const char *const P_SCHED_MIG[]    = { "/proc/sys/kernel/sched_migration_cost_ns", NULL };
static const char *const P_SCHED_BOOST[]  = { "/proc/sys/kernel/sched_boost",
					      "/sys/module/sched_walt/parameters/sched_boost", NULL };
static const char *const P_GPU_GOV[]      = {
	"/sys/class/kgsl/kgsl-3d0/devfreq/governor",
	"/sys/class/devfreq/13000000.mali/governor",
	"/sys/kernel/gpu/gpu_governor",
	"/sys/class/devfreq/gpufreq/governor", NULL };
static const char *const P_GPU_MINFREQ[]  = {
	"/sys/class/kgsl/kgsl-3d0/devfreq/min_freq",
	"/sys/class/devfreq/13000000.mali/min_freq", NULL };
static const char *const P_GPU_MAXFREQ[]  = {
	"/sys/class/kgsl/kgsl-3d0/devfreq/max_freq",
	"/sys/class/devfreq/13000000.mali/max_freq", NULL };
static const char *const P_GPU_FORCE[]    = {
	"/sys/class/kgsl/kgsl-3d0/force_clk_on", NULL };
static const char *const P_KCAL[]         = { "/sys/devices/platform/kcal_ctrl.0/kcal", NULL };
static const char *const P_KCAL_SAT[]     = { "/sys/devices/platform/kcal_ctrl.0/kcal_sat", NULL };
static const char *const P_BATT_LIMIT[]   = {
	"/sys/class/power_supply/battery/charge_control_limit",
	"/sys/class/power_supply/battery/batt_slate_mode", NULL };
static const char *const P_FSYNC[]        = { "/sys/module/sync/parameters/fsync_enabled", NULL };
static const char *const P_LMK_MINFREE[]  = { "/sys/module/lowmemorykiller/parameters/minfree", NULL };

#define GOV_PATH "/sys/devices/system/cpu/cpufreq"
#define CPU_PATH "/sys/devices/system/cpu"

static const struct zk_tweak catalog[] = {
	/* CPU */
	{ "cpu_governor", ZC_CPU, ZT_GOV, "CPU governor", NULL, 0, 0, 0 },
	{ "sched_latency_ns", ZC_CPU, ZT_INT, "Scheduler latency (ns)", P_SCHED_LAT, 100000, 100000000, 0 },
	{ "sched_min_granularity_ns", ZC_CPU, ZT_INT, "Scheduler min granularity (ns)", P_SCHED_MINGR, 100000, 50000000, 0 },
	{ "sched_migration_cost_ns", ZC_CPU, ZT_INT, "Task migration cost (ns)", P_SCHED_MIG, 0, 50000000, 0 },
	{ "sched_boost", ZC_CPU, ZT_INT, "WALT scheduler boost", P_SCHED_BOOST, 0, 3, 1 },

	/* GPU */
	{ "gpu_governor", ZC_GPU, ZT_STR, "GPU governor", P_GPU_GOV, 0, 0, 0 },
	{ "gpu_min_freq", ZC_GPU, ZT_INT, "GPU minimum frequency", P_GPU_MINFREQ, 0, 0, 0 },
	{ "gpu_max_freq", ZC_GPU, ZT_INT, "GPU maximum frequency", P_GPU_MAXFREQ, 0, 0, 0 },
	{ "gpu_force_clk", ZC_GPU, ZT_INT, "GPU force clock on", P_GPU_FORCE, 0, 1, 1 },

	/* Memory */
	{ "swappiness", ZC_MEM, ZT_INT, "Swappiness", P_SWAPPINESS, 0, 200, 0 },
	{ "dirty_ratio", ZC_MEM, ZT_INT, "Dirty ratio", P_DIRTY_RATIO, 0, 100, 0 },
	{ "dirty_background_ratio", ZC_MEM, ZT_INT, "Dirty background ratio", P_DIRTY_BG, 0, 100, 0 },
	{ "vfs_cache_pressure", ZC_MEM, ZT_INT, "VFS cache pressure", P_VFS_CACHE, 0, 500, 0 },
	{ "min_free_kbytes", ZC_MEM, ZT_INT, "Min free memory (KiB)", P_MIN_FREE, 1024, 0, 0 },
	{ "vm_stat_interval", ZC_MEM, ZT_INT, "VM stat interval (s)", P_STAT_INT, 1, 120, 1 },
	{ "lmk_minfree", ZC_MEM, ZT_STR, "LMK minfree", P_LMK_MINFREE, 0, 0, 1 },

	/* zram */
	{ "zram_comp_algorithm", ZC_ZRAM, ZT_STR, "zram compression", P_ZRAM_COMP, 0, 0, 0 },
	{ "zram_disksize", ZC_ZRAM, ZT_INT, "zram size (bytes)", P_ZRAM_SIZE, 0, 0, 1 },
	{ "zram_max_comp_streams", ZC_ZRAM, ZT_INT, "zram comp streams", P_ZRAM_MAXCOMP, 1, 16, 1 },

	/* I/O */
	{ "io_scheduler", ZC_IO, ZT_IOSCHED, "I/O scheduler", NULL, 0, 0, 0 },
	{ "fsync", ZC_IO, ZT_INT, "fsync enabled", P_FSYNC, 0, 1, 1 },

	/* Network */
	{ "tcp_congestion_control", ZC_NET, ZT_STR, "TCP congestion control", P_TCP_CC, 0, 0, 0 },
	{ "tcp_fastopen", ZC_NET, ZT_INT, "TCP fast open", P_TCP_FASTOPEN, 0, 3, 0 },
	{ "tcp_low_latency", ZC_NET, ZT_INT, "TCP low latency", P_TCP_LOWLAT, 0, 1, 1 },

	/* Display */
	{ "kcal", ZC_DISPLAY, ZT_STR, "Display color (R G B)", P_KCAL, 0, 0, 1 },
	{ "kcal_sat", ZC_DISPLAY, ZT_INT, "Display saturation", P_KCAL_SAT, 128, 383, 1 },

	/* Battery */
	{ "charge_limit", ZC_BATTERY, ZT_INT, "Charge control limit", P_BATT_LIMIT, 0, 100, 0 },
};

#define CATALOG_N (sizeof(catalog) / sizeof(catalog[0]))

static const char *cat_names[ZC_CAT_COUNT] = {
	"cpu", "gpu", "memory", "zram", "io", "network", "display", "battery", "kernel"
};

const char *zk_tweak_cat_name(enum zk_tweak_cat c)
{
	return (c < ZC_CAT_COUNT) ? cat_names[c] : "other";
}

const struct zk_tweak *zk_tweak_find(const char *id)
{
	size_t i;

	for (i = 0; i < CATALOG_N; i++)
		if (!strcmp(catalog[i].id, id))
			return &catalog[i];
	return NULL;
}

/* ---- fan-out helpers ---- */
typedef int (*node_cb)(const char *path, void *ctx);

static int for_each_dir(const char *base, const char *match, const char *leaf,
			node_cb cb, void *ctx)
{
	DIR *d = opendir(base);
	struct dirent *e;
	char path[512];
	int ret = -ENODEV, r;

	if (!d)
		return -ENODEV;
	while ((e = readdir(d))) {
		if (strncmp(e->d_name, match, strlen(match)))
			continue;
		snprintf(path, sizeof(path), "%s/%s/%s", base, e->d_name, leaf);
		if (!zk_exists(path))
			continue;
		r = cb(path, ctx);
		if (r == 0)
			ret = 0;
		else if (ret != 0)
			ret = r;
	}
	closedir(d);
	return ret;
}

struct wctx { const char *val; };

static int write_cb(const char *path, void *c)
{
	return zk_write(path, ((struct wctx *)c)->val);
}

/* For I/O scheduler, the queue/scheduler node wants the bare name. */
static int iosched_cb(const char *path, void *c)
{
	return zk_write(path, ((struct wctx *)c)->val);
}

static int read_first_cb(const char *path, void *c)
{
	char *out = (char *)c;

	if (out[0])
		return 0; /* already have one */
	return zk_read(path, out, 128);
}

/* ---- apply / read ---- */
static int clampll(const struct zk_tweak *t, const char *value, char *out, size_t n)
{
	char *end;
	long long v = strtoll(value, &end, 0);

	if (end == value)
		return -EINVAL;
	if (t->min || t->max) {
		if (t->min && v < t->min)
			v = t->min;
		if (t->max && v > t->max)
			v = t->max;
	}
	snprintf(out, n, "%lld", v);
	return 0;
}

int zk_tweak_apply(const struct zk_tweak *t, const char *value, int lite_mode)
{
	char norm[64];
	struct wctx ctx;
	const char *p;

	if (!t || !value)
		return -EINVAL;
	if (lite_mode && t->lite)
		return -ENOTSUP;

	switch (t->kind) {
	case ZT_GOV:
		ctx.val = value;
		return for_each_dir(GOV_PATH, "policy", "scaling_governor", write_cb, &ctx);
	case ZT_IOSCHED:
		ctx.val = value;
		return for_each_dir("/sys/block", "", "queue/scheduler", iosched_cb, &ctx);
	case ZT_PERCPU: {
		char path[256];
		int cpu, ok = -ENODEV, r;

		if (clampll(t, value, norm, sizeof(norm)))
			return -EINVAL;
		for (cpu = 0; cpu < 64; cpu++) {
			snprintf(path, sizeof(path), t->paths[0], cpu);
			if (!zk_exists(path))
				continue;
			r = zk_write(path, norm);
			if (r == 0)
				ok = 0;
			else if (ok != 0)
				ok = r;
		}
		return ok;
	}
	case ZT_INT:
		if (clampll(t, value, norm, sizeof(norm)))
			return -EINVAL;
		p = zk_first_existing(t->paths);
		return p ? zk_write(p, norm) : -ENODEV;
	case ZT_STR:
	default:
		p = zk_first_existing(t->paths);
		return p ? zk_write(p, value) : -ENODEV;
	}
}

int zk_tweak_read(const struct zk_tweak *t, char *buf, size_t len)
{
	const char *p;

	if (!t || !buf || !len)
		return -EINVAL;
	buf[0] = '\0';

	switch (t->kind) {
	case ZT_GOV:
		return for_each_dir(GOV_PATH, "policy", "scaling_governor", read_first_cb, buf);
	case ZT_IOSCHED:
		return for_each_dir("/sys/block", "", "queue/scheduler", read_first_cb, buf);
	case ZT_PERCPU: {
		char path[256];

		snprintf(path, sizeof(path), t->paths[0], 0);
		return zk_read(path, buf, len);
	}
	default:
		p = zk_first_existing(t->paths);
		return p ? zk_read(p, buf, len) : -ENODEV;
	}
}

void zk_tweaks_dump(struct zk_json *j)
{
	size_t i;

	zj_arr_open(j, "tweaks");
	for (i = 0; i < CATALOG_N; i++) {
		const struct zk_tweak *t = &catalog[i];
		char cur[160];
		int have = (zk_tweak_read(t, cur, sizeof(cur)) == 0) && cur[0];

		zj_obj_open(j, NULL);
		zj_str(j, "id", t->id);
		zj_str(j, "title", t->title);
		zj_str(j, "category", zk_tweak_cat_name(t->cat));
		zj_bool(j, "available", have);
		zj_bool(j, "lite_hidden", t->lite);
		if (t->min || t->max) {
			zj_int(j, "min", t->min);
			zj_int(j, "max", t->max);
		}
		zj_str(j, "value", have ? cur : NULL);
		zj_obj_close(j);
	}
	zj_arr_close(j);
}
