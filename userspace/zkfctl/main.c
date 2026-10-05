// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
#define _GNU_SOURCE
/*
 * zkfctl - Zairenkai command-line engine.
 *
 * The Android app runs `zkfctl <cmd>` as root and parses the JSON on stdout.
 * Every command prints a single JSON object; on error it prints
 * {"ok":false,"error":"...","errno":N} and exits non-zero.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#include <dirent.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "libzkfc.h"
#include "zk_json.h"
#include "zk_sysfs.h"
#include "zk_tweaks.h"

#define ZKFCTL_VERSION "1.0.0"

static int fail(struct zk_json *j, const char *msg, int err)
{
	zj_obj_open(j, NULL);
	zj_bool(j, "ok", 0);
	zj_str(j, "error", msg);
	zj_int(j, "errno", err);
	zj_obj_close(j);
	zj_finish(j);
	return err ? (err < 0 ? -err : err) : 1;
}

static void hexstr(char *dst, const unsigned char *src, int n)
{
	static const char h[] = "0123456789abcdef";
	int i;

	for (i = 0; i < n; i++) {
		dst[2 * i] = h[src[i] >> 4];
		dst[2 * i + 1] = h[src[i] & 0xf];
	}
	dst[2 * n] = '\0';
}

/* ------------------------------------------------------------------ info */
static int cmd_info(struct zkfc *z, struct zk_json *j)
{
	struct zkfc_version_info v;
	int r = zkfc_version(z, &v);

	if (r)
		return fail(j, "ZKFC not available", r);

	zj_obj_open(j, NULL);
	zj_bool(j, "ok", 1);
	zj_str(j, "zkfctl_version", ZKFCTL_VERSION);
	zj_obj_open(j, "api");
	zj_int(j, "major", ZKFC_VER_MAJOR(v.api_version));
	zj_int(j, "minor", ZKFC_VER_MINOR(v.api_version));
	zj_int(j, "patch", ZKFC_VER_PATCH(v.api_version));
	zj_bool(j, "outdated", zkfc_api_outdated(&v));
	zj_obj_close(j);
	zj_str(j, "arch", zkfc_arch_str(v.arch));
	zj_str(j, "hook_mode", zkfc_hook_str(v.hook_mode));
	zj_str(j, "kernel_type", v.kernel_type == ZKFC_KERNEL_GKI ? "gki" : "non-gki");
	zj_str(j, "kernel_release", v.kernel_release);
	zj_str(j, "build_id", v.build_id);
	zj_str(j, "license_state", zkfc_license_state_str(v.license_state));
	zj_uint(j, "features", v.features);
	zj_uint(j, "features_enabled", v.features_enabled);
	zj_obj_close(j);
	zj_finish(j);
	return 0;
}

/* --------------------------------------------------------------- license */
static int cmd_license(struct zkfc *z, struct zk_json *j, int argc, char **argv)
{
	if (argc >= 2 && !strcmp(argv[1], "install")) {
		struct zkfc_license_token tok;
		int r;

		if (argc < 3)
			return fail(j, "usage: license install <file.zkl>", EINVAL);
		r = zkfc_parse_token_file(argv[2], &tok);
		if (r)
			return fail(j, "cannot parse token file", r);
		r = zkfc_install_license(z, &tok);
		if (r)
			return fail(j, "kernel rejected token", r);
	} else if (argc >= 2 && !strcmp(argv[1], "crl")) {
		struct zkfc_crl crl;
		int r;

		if (argc < 3)
			return fail(j, "usage: license crl <file.zkcrl>", EINVAL);
		r = zkfc_parse_crl_file(argv[2], &crl);
		if (r)
			return fail(j, "cannot parse CRL file", r);
		r = zkfc_install_crl(z, &crl);
		if (r)
			return fail(j, "kernel rejected CRL", r);
	}

	struct zkfc_license_status s;
	char fp[65], bind[65];
	int r = zkfc_license(z, &s);

	if (r)
		return fail(j, "cannot read license", r);
	hexstr(fp, s.owner_key_fingerprint, 32);
	hexstr(bind, s.kernel_binding, ZKFC_BINDING_SIZE);

	zj_obj_open(j, NULL);
	zj_bool(j, "ok", 1);
	zj_str(j, "state", zkfc_license_state_str(s.state));
	zj_bool(j, "has_token", s.has_token);
	zj_bool(j, "owner_key_provisioned", s.owner_key_provisioned);
	zj_bool(j, "clock_trusted", s.clock_trusted);
	zj_str(j, "owner_key_fingerprint", fp);
	zj_str(j, "kernel_binding", bind);
	if (s.has_token) {
		char licensee[65];

		memcpy(licensee, s.token.payload.licensee, 64);
		licensee[64] = '\0';
		zj_obj_open(j, "token");
		zj_str(j, "licensee", licensee);
		zj_uint(j, "license_id", s.token.payload.license_id);
		zj_uint(j, "expires_at", s.token.payload.expires_at);
		zj_uint(j, "features", s.token.payload.features);
		zj_obj_close(j);
	}
	zj_obj_close(j);
	zj_finish(j);
	return 0;
}

/* -------------------------------------------------------------- security */
static int cmd_security(struct zkfc *z, struct zk_json *j, int argc, char **argv)
{
	const char *what = argc >= 2 ? argv[1] : "all";
	int all = !strcmp(what, "all");

	zj_obj_open(j, NULL);
	zj_bool(j, "ok", 1);

	if (all || !strcmp(what, "sys")) {
		struct zkfc_sys_security s;

		if (!zkfc_sys_security(z, &s)) {
			char dg[65];

			hexstr(dg, s.zkfc_config_digest, 32);
			zj_obj_open(j, "system");
			zj_uint(j, "flags", s.flags);
			zj_uint(j, "taint_mask", s.taint_mask);
			zj_uint(j, "uptime_ns", s.uptime_ns);
			zj_str(j, "config_digest", dg);
			zj_bool(j, "selinux", !!(s.flags & ZKFC_SYS_SECURITY_SELINUX));
			zj_bool(j, "module_sig_enforced", !!(s.flags & ZKFC_SYS_MODULE_SIG_ENFORCED));
			zj_bool(j, "lockdown", !!(s.flags & (ZKFC_SYS_LOCKDOWN_INTEGRITY | ZKFC_SYS_LOCKDOWN_CONFIDENTIAL)));
			zj_obj_close(j);
		}
	}
	if (all || !strcmp(what, "user")) {
		struct zkfc_user_security s;

		if (!zkfc_user_security(z, &s)) {
			zj_obj_open(j, "user");
			zj_int(j, "uid", s.uid);
			zj_int(j, "euid", s.euid);
			zj_int(j, "gid", s.gid);
			zj_int(j, "egid", s.egid);
			zj_uint(j, "caps", s.caps);
			zj_bool(j, "cap_sys_admin", s.cap_sys_admin);
			zj_uint(j, "session_id", s.session_id);
			zj_obj_close(j);
		}
	}
	if (all || !strcmp(what, "dev")) {
		struct zkfc_dev_security s;

		if (!zkfc_dev_security(z, &s)) {
			char bind[65];

			hexstr(bind, s.kernel_binding, ZKFC_BINDING_SIZE);
			zj_obj_open(j, "device");
			zj_str(j, "model", s.model);
			zj_str(j, "compatible", s.compatible);
			zj_str(j, "licensee_tag", s.licensee_tag);
			zj_int(j, "cpu_count", s.cpu_count);
			zj_str(j, "kernel_binding", bind);
			zj_obj_close(j);
		}
	}
	zj_obj_close(j);
	zj_finish(j);
	return 0;
}

/* ---------------------------------------------------------------- tweaks */
static int cmd_tweak(struct zk_json *j, int argc, char **argv, int lite)
{
	if (argc >= 2 && !strcmp(argv[1], "list")) {
		zj_obj_open(j, NULL);
		zj_bool(j, "ok", 1);
		zj_bool(j, "lite_mode", lite);
		zk_tweaks_dump(j);
		zj_obj_close(j);
		zj_finish(j);
		return 0;
	}
	if (argc >= 3 && !strcmp(argv[1], "get")) {
		const struct zk_tweak *t = zk_tweak_find(argv[2]);
		char cur[160];

		if (!t)
			return fail(j, "unknown tweak", EINVAL);
		zj_obj_open(j, NULL);
		zj_bool(j, "ok", zk_tweak_read(t, cur, sizeof(cur)) == 0);
		zj_str(j, "id", t->id);
		zj_str(j, "value", cur[0] ? cur : NULL);
		zj_obj_close(j);
		zj_finish(j);
		return 0;
	}
	if (argc >= 4 && !strcmp(argv[1], "set")) {
		const struct zk_tweak *t = zk_tweak_find(argv[2]);
		int r;

		if (!t)
			return fail(j, "unknown tweak", EINVAL);
		r = zk_tweak_apply(t, argv[3], lite);
		if (r)
			return fail(j, "apply failed", r);
		zj_obj_open(j, NULL);
		zj_bool(j, "ok", 1);
		zj_str(j, "id", t->id);
		zj_str(j, "value", argv[3]);
		zj_obj_close(j);
		zj_finish(j);
		return 0;
	}
	return fail(j, "usage: tweak list|get <id>|set <id> <value>", EINVAL);
}

/* ----------------------------------------------------------------- boost */
static int cmd_boost(struct zkfc *z, struct zk_json *j, int argc, char **argv)
{
	if (argc >= 2 && !strcmp(argv[1], "reset")) {
		int r = zkfc_perf_reset(z);

		if (r)
			return fail(j, "reset failed", r);
		zj_obj_open(j, NULL);
		zj_bool(j, "ok", 1);
		zj_obj_close(j);
		zj_finish(j);
		return 0;
	}
	if (argc >= 2 && !strcmp(argv[1], "status")) {
		struct zkfc_perf_status st;
		int r = zkfc_perf_status(z, &st);

		if (r)
			return fail(j, "status failed", r);
		zj_obj_open(j, NULL);
		zj_bool(j, "ok", 1);
		zj_bool(j, "input_boost_enabled", st.input_boost_enabled);
		zj_bool(j, "input_boost_active", st.input_boost_active);
		zj_uint(j, "input_boost_count", st.input_boost_count);
		zj_int(j, "boosted_tasks", st.boosted_tasks);
		zj_int(j, "inherit_groups", st.inherit_groups);
		zj_int(j, "cpufreq_requests", st.cpufreq_requests);
		zj_bool(j, "thermal_tripped", st.thermal_tripped);
		zj_int(j, "thermal_last_mdeg", st.thermal_last_mdeg);
		zj_uint(j, "thermal_trip_count", st.thermal_trip_count);
		zj_obj_close(j);
		zj_finish(j);
		return 0;
	}
	if (argc >= 5 && !strcmp(argv[1], "task")) {
		struct zkfc_task_boost tb = { 0 };
		int r;

		tb.pid = atoi(argv[2]);
		tb.uclamp_min = (unsigned)atoi(argv[3]);
		tb.uclamp_max = (unsigned)atoi(argv[4]);
		tb.flags = ZKFC_TB_THREADS;
		if (argc >= 6 && !strcmp(argv[5], "inherit"))
			tb.flags |= ZKFC_TB_INHERIT;
		r = zkfc_task_boost(z, &tb);
		if (r)
			return fail(j, "task boost failed (token required?)", r);
		zj_obj_open(j, NULL);
		zj_bool(j, "ok", 1);
		zj_int(j, "pid", tb.pid);
		zj_int(j, "applied", tb.applied);
		zj_obj_close(j);
		zj_finish(j);
		return 0;
	}
	return fail(j, "usage: boost task <pid> <min> <max> [inherit] | status | reset", EINVAL);
}

/* --------------------------------------------------------------- monitor */
struct cpu_jiffies { unsigned long long idle, total; };

static void read_cpu_jiffies(struct cpu_jiffies *out, int maxcpu)
{
	FILE *f = fopen("/proc/stat", "r");
	char line[256];

	for (int i = 0; i <= maxcpu; i++)
		out[i].idle = out[i].total = 0;
	if (!f)
		return;
	while (fgets(line, sizeof(line), f)) {
		int cpu;
		unsigned long long u, n, s, idle, io, irq, sirq, st = 0;

		if (sscanf(line, "cpu%d %llu %llu %llu %llu %llu %llu %llu %llu",
			   &cpu, &u, &n, &s, &idle, &io, &irq, &sirq, &st) >= 5) {
			if (cpu < 0 || cpu > maxcpu)
				continue;
			out[cpu].idle = idle + io;
			out[cpu].total = u + n + s + idle + io + irq + sirq + st;
		}
	}
	fclose(f);
}

static void enumerate_thermal(struct zk_json *j, struct zkfc *z)
{
	DIR *d = opendir("/sys/class/thermal");
	struct dirent *e;

	zj_arr_open(j, "thermal");
	if (d) {
		while ((e = readdir(d))) {
			char tp[512], np[512], type[64] = "", tmp[32] = "";

			if (strncmp(e->d_name, "thermal_zone", 12))
				continue;
			snprintf(tp, sizeof(tp), "/sys/class/thermal/%s/temp", e->d_name);
			snprintf(np, sizeof(np), "/sys/class/thermal/%s/type", e->d_name);
			zk_read(np, type, sizeof(type));
			zk_read(tp, tmp, sizeof(tmp));
			if (!tmp[0])
				continue;
			zj_obj_open(j, NULL);
			zj_str(j, "zone", e->d_name);
			zj_str(j, "type", type);
			zj_int(j, "temp_mdeg", atoll(tmp));
			zj_obj_close(j);
		}
		closedir(d);
	}
	(void)z;
	zj_arr_close(j);
}

static int cmd_monitor(struct zkfc *z, struct zk_json *j, int argc, char **argv)
{
	int maxcpu = (int)sysconf(_SC_NPROCESSORS_CONF);
	int interval = argc >= 2 ? atoi(argv[1]) : 300;
	struct cpu_jiffies a[64], b[64];
	char path[256], buf[64];
	int cpu;

	if (maxcpu > 64)
		maxcpu = 64;
	if (interval < 50)
		interval = 50;
	if (interval > 2000)
		interval = 2000;

	read_cpu_jiffies(a, maxcpu - 1);
	struct timespec ts = { interval / 1000, (long)(interval % 1000) * 1000000L };

	nanosleep(&ts, NULL);
	read_cpu_jiffies(b, maxcpu - 1);

	zj_obj_open(j, NULL);
	zj_bool(j, "ok", 1);
	zj_int(j, "interval_ms", interval);

	zj_arr_open(j, "cpu");
	for (cpu = 0; cpu < maxcpu; cpu++) {
		unsigned long long dt = b[cpu].total - a[cpu].total;
		unsigned long long di = b[cpu].idle - a[cpu].idle;
		int load = dt ? (int)(100 - (di * 100 / dt)) : -1;
		long long freq, online = 1;

		snprintf(path, sizeof(path),
			 "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_cur_freq", cpu);
		freq = zk_read_ll(path, -1);
		snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/online", cpu);
		if (zk_exists(path))
			online = zk_read_ll(path, 1);

		zj_obj_open(j, NULL);
		zj_int(j, "cpu", cpu);
		zj_bool(j, "online", online != 0);
		zj_int(j, "load_pct", load);
		zj_int(j, "cur_khz", freq);
		zj_obj_close(j);
	}
	zj_arr_close(j);

	/* GPU */
	zj_obj_open(j, "gpu");
	if (!zk_read("/sys/class/kgsl/kgsl-3d0/gpubusy", buf, sizeof(buf)) && buf[0]) {
		unsigned long long busy = 0, total = 0;

		sscanf(buf, "%llu %llu", &busy, &total);
		zj_int(j, "busy_pct", total ? (int)(busy * 100 / total) : -1);
	} else {
		char *p = NULL;
		const char *cand[] = {
			"/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
			"/sys/class/devfreq/13000000.mali/cur_freq", NULL };
		(void)p;
		zj_int(j, "busy_pct", -1);
		const char *fp = zk_first_existing(cand);

		if (fp)
			zj_int(j, "cur_freq", zk_read_ll(fp, -1));
	}
	zj_obj_close(j);

	enumerate_thermal(j, z);

	/* ZKFC perf status, when the module is present. */
	if (z) {
		struct zkfc_perf_status st;

		if (!zkfc_perf_status(z, &st)) {
			zj_obj_open(j, "zkfc");
			zj_bool(j, "input_boost_active", st.input_boost_active);
			zj_int(j, "boosted_tasks", st.boosted_tasks);
			zj_bool(j, "thermal_tripped", st.thermal_tripped);
			zj_obj_close(j);
		}
	}
	zj_obj_close(j);
	zj_finish(j);
	return 0;
}

/* ------------------------------------------------------------------- log */
static int cmd_log(struct zkfc *z, struct zk_json *j, int argc, char **argv)
{
	if (argc >= 3 && !strcmp(argv[1], "level")) {
		static const char *names[] = { "verbose", "debug", "info", "warn", "error", "silent" };
		uint32_t lvl = ZKFC_LOG_INFO;

		for (uint32_t i = 0; i < 6; i++)
			if (!strcmp(argv[2], names[i]))
				lvl = i;
		int r = zkfc_log_set_level(z, lvl);

		if (r)
			return fail(j, "set level failed", r);
		zj_obj_open(j, NULL);
		zj_bool(j, "ok", 1);
		zj_str(j, "level", zkfc_log_level_str(lvl));
		zj_obj_close(j);
		zj_finish(j);
		return 0;
	}

	struct zkfc_log_read rd = { 0 };
	int r;

	rd.from_seq = (argc >= 2) ? strtoull(argv[1], NULL, 10) : 0;
	r = zkfc_log_read(z, &rd);
	if (r)
		return fail(j, "log read failed", r);
	zj_obj_open(j, NULL);
	zj_bool(j, "ok", 1);
	zj_uint(j, "next_seq", rd.next_seq);
	zj_uint(j, "dropped", rd.dropped);
	zj_arr_open(j, "records");
	for (uint32_t i = 0; i < rd.count; i++) {
		zj_obj_open(j, NULL);
		zj_uint(j, "seq", rd.records[i].seq);
		zj_uint(j, "ts_ns", rd.records[i].ts_ns);
		zj_str(j, "level", zkfc_log_level_str(rd.records[i].level));
		zj_int(j, "pid", rd.records[i].pid);
		zj_int(j, "uid", rd.records[i].uid);
		zj_str(j, "msg", rd.records[i].msg);
		zj_obj_close(j);
	}
	zj_arr_close(j);
	zj_obj_close(j);
	zj_finish(j);
	return 0;
}

static int cmd_sulog(struct zkfc *z, struct zk_json *j, int argc, char **argv)
{
	struct zkfc_sulog_read rd = { 0 };
	static const char *ev[] = { "", "exec", "priv_op", "denied", "license" };
	int r;

	rd.from_seq = (argc >= 2) ? strtoull(argv[1], NULL, 10) : 0;
	r = zkfc_sulog_read(z, &rd);
	if (r)
		return fail(j, "sulog read failed", r);
	zj_obj_open(j, NULL);
	zj_bool(j, "ok", 1);
	zj_uint(j, "next_seq", rd.next_seq);
	zj_arr_open(j, "records");
	for (uint32_t i = 0; i < rd.count; i++) {
		struct zkfc_sulog_record *s = &rd.records[i];

		zj_obj_open(j, NULL);
		zj_uint(j, "seq", s->seq);
		zj_uint(j, "ts_ns", s->ts_ns);
		zj_str(j, "event", s->event <= 4 ? ev[s->event] : "?");
		zj_int(j, "uid", s->uid);
		zj_int(j, "pid", s->pid);
		zj_int(j, "result", s->result);
		zj_str(j, "comm", s->comm);
		zj_str(j, "path", s->path);
		zj_obj_close(j);
	}
	zj_arr_close(j);
	zj_obj_close(j);
	zj_finish(j);
	return 0;
}

/* ---------------------------------------------------------------- policy */
static int cmd_policy(struct zkfc *z, struct zk_json *j)
{
	struct zkfc_policy_table t;
	static const char *types[] = { "", "uid", "gid", "group" };
	int r = zkfc_policy_get(z, &t);

	if (r)
		return fail(j, "policy get failed", r);
	zj_obj_open(j, NULL);
	zj_bool(j, "ok", 1);
	zj_arr_open(j, "entries");
	for (uint32_t i = 0; i < t.count; i++) {
		zj_obj_open(j, NULL);
		zj_str(j, "type", t.entries[i].type <= 3 ? types[t.entries[i].type] : "?");
		zj_int(j, "id", t.entries[i].id);
		zj_uint(j, "caps", t.entries[i].caps);
		zj_bool(j, "deny", !!(t.entries[i].flags & ZKFC_POLICY_F_DENY));
		zj_bool(j, "builtin", !!(t.entries[i].flags & ZKFC_POLICY_F_BUILTIN));
		zj_obj_close(j);
	}
	zj_arr_close(j);
	zj_obj_close(j);
	zj_finish(j);
	return 0;
}

static void usage(void)
{
	fprintf(stderr,
		"zkfctl " ZKFCTL_VERSION " - Zairenkai engine\n"
		"Usage: zkfctl [--lite] <command> [args]\n"
		"  info                         kernel / API / license summary\n"
		"  license [install F|crl F]    show or install a ZKFC API token\n"
		"  security [sys|user|dev]      integrity reports\n"
		"  tweak list|get ID|set ID V   device optimizations\n"
		"  boost task PID MIN MAX [inherit] | status | reset\n"
		"  monitor [interval_ms]        one-shot CPU/GPU/thermal sample\n"
		"  log [level L | FROM_SEQ]     live kernel log\n"
		"  sulog [FROM_SEQ]             su / privileged-op audit\n"
		"  policy                       access policy table\n");
}

int main(int argc, char **argv)
{
	struct zk_json j;
	struct zkfc *z;
	int err = 0, lite = 0, rc;
	const char *cmd;

	zj_init(&j, stdout);

	while (argc >= 2 && argv[1][0] == '-') {
		if (!strcmp(argv[1], "--lite"))
			lite = 1;
		else
			break;
		argc--; argv++;
	}
	if (argc < 2) {
		usage();
		return 2;
	}
	cmd = argv[1];
	argc--; argv++;

	/* Tweaks and monitor work without the module; others need /dev/zkfc. */
	if (!strcmp(cmd, "tweak"))
		return cmd_tweak(&j, argc, argv, lite);

	z = zkfc_open(&err);
	if (!z && strcmp(cmd, "monitor"))
		return fail(&j, "cannot open /dev/zkfc (module loaded? root?)", err);

	if (!strcmp(cmd, "info")) rc = cmd_info(z, &j);
	else if (!strcmp(cmd, "license")) rc = cmd_license(z, &j, argc, argv);
	else if (!strcmp(cmd, "security")) rc = cmd_security(z, &j, argc, argv);
	else if (!strcmp(cmd, "boost")) rc = cmd_boost(z, &j, argc, argv);
	else if (!strcmp(cmd, "monitor")) rc = cmd_monitor(z, &j, argc, argv);
	else if (!strcmp(cmd, "log")) rc = cmd_log(z, &j, argc, argv);
	else if (!strcmp(cmd, "sulog")) rc = cmd_sulog(z, &j, argc, argv);
	else if (!strcmp(cmd, "policy")) rc = cmd_policy(z, &j);
	else { usage(); rc = 2; }

	zkfc_close(z);
	return rc;
}
