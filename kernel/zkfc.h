/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * Zairenkai Kernel Framework Core (ZKFC) - internal interfaces.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#ifndef _ZKFC_INTERNAL_H
#define _ZKFC_INTERNAL_H

#include <linux/types.h>
#include <linux/version.h>
#include <linux/cred.h>

#include "include/uapi/linux/zkfc.h"
#include "zkfc_compat.h"

#ifndef ZKFC_BUILD_ID
#define ZKFC_BUILD_ID "zkfc-dev"
#endif

#ifndef CONFIG_ZKFC_LICENSEE_TAG
#define CONFIG_ZKFC_LICENSEE_TAG ""
#endif

#define ZKFC_PREFIX "zkfc: "

/* ---- core/zkfc_log.c ---- */
int zkfc_log_init(void);
void zkfc_log_exit(void);
void zkfc_log_set_level(u32 level);
u32 zkfc_log_get_level(void);
__printf(2, 3) void zkfc_log(u32 level, const char *fmt, ...);
int zkfc_log_read(struct zkfc_log_read *rd);

#define zkfc_v(fmt, ...) zkfc_log(ZKFC_LOG_VERBOSE, fmt, ##__VA_ARGS__)
#define zkfc_d(fmt, ...) zkfc_log(ZKFC_LOG_DEBUG, fmt, ##__VA_ARGS__)
#define zkfc_i(fmt, ...) zkfc_log(ZKFC_LOG_INFO, fmt, ##__VA_ARGS__)
#define zkfc_w(fmt, ...) zkfc_log(ZKFC_LOG_WARN, fmt, ##__VA_ARGS__)
#define zkfc_e(fmt, ...) zkfc_log(ZKFC_LOG_ERROR, fmt, ##__VA_ARGS__)

/* ---- hooks/zkfc_sulog.c ---- */
int zkfc_sulog_init(void);
void zkfc_sulog_exit(void);
void zkfc_sulog_add(u32 event, int result, u32 detail, const char *path);
int zkfc_sulog_read(struct zkfc_sulog_read *rd);

/* ---- core/zkfc_policy.c ---- */
int zkfc_policy_init(void);
void zkfc_policy_exit(void);
u32 zkfc_policy_caps(struct zkfc_user_security *who);
void zkfc_policy_get(struct zkfc_policy_table *tbl);
int zkfc_policy_set(const struct zkfc_policy_table *tbl);

/* ---- security/zkfc_license.c ---- */
int zkfc_license_init(void);
void zkfc_license_exit(void);
u32 zkfc_license_state(void);
u32 zkfc_license_features(void);
void zkfc_license_status(struct zkfc_license_status *st);
int zkfc_license_install(const struct zkfc_license_token *tok);
int zkfc_license_install_crl(const struct zkfc_crl *crl);
void zkfc_kernel_binding(u8 out[ZKFC_BINDING_SIZE]);

/* ---- security/zkfc_integrity.c ---- */
int zkfc_integrity_init(void);
void zkfc_integrity_sys(struct zkfc_sys_security *s);
void zkfc_integrity_dev(struct zkfc_dev_security *d);
u32 zkfc_kernel_type(void);

/* ---- perf/ ---- */
int zkfc_task_boost_init(void);
void zkfc_task_boost_exit(void);
int zkfc_task_boost(struct zkfc_task_boost *tb);
void zkfc_task_boost_reset_all(void);
void zkfc_task_boost_suspend(bool suspend);
u32 zkfc_task_boost_count(u32 *inherit_groups);
void zkfc_task_boost_on_new_task(struct task_struct *p);
bool zkfc_uclamp_available(void);

int zkfc_cpufreq_init(void);
void zkfc_cpufreq_exit(void);
int zkfc_cpufreq_qos(const struct zkfc_cpufreq_qos *q);
void zkfc_cpufreq_reset_all(void);
u32 zkfc_cpufreq_request_count(void);
/* Floor/ceiling used by input boost; 0 removes it. */
int zkfc_cpufreq_set_boost_floor(unsigned int cpu, unsigned int min_khz);
void zkfc_cpufreq_clear_boost_floors(void);

int zkfc_input_boost_init(void);
void zkfc_input_boost_exit(void);
int zkfc_input_boost_config(const struct zkfc_input_boost *cfg);
void zkfc_input_boost_suspend(bool suspend);
void zkfc_input_boost_status(struct zkfc_perf_status *st);

/* ---- thermal/zkfc_thermal.c ---- */
int zkfc_thermal_init(void);
void zkfc_thermal_exit(void);
int zkfc_thermal_read(struct zkfc_thermal_read *rd);
int zkfc_thermal_guard_config(const struct zkfc_thermal_guard *cfg);
bool zkfc_thermal_tripped(void);
void zkfc_thermal_status(struct zkfc_perf_status *st);

/* ---- hooks/ ---- */
int zkfc_hooks_init(void);
void zkfc_hooks_exit(void);
u32 zkfc_hook_mode(void);
bool zkfc_hooks_active(void);
/* Called by kprobes (hybrid) or patched kernel code (manual). */
void zkfc_on_exec(const char *filename);
void zkfc_on_new_task(struct task_struct *p);

/* ---- hooks/zkfc_kprobes.c (hybrid mode) ---- */
int zkfc_kprobes_init(void);
void zkfc_kprobes_exit(void);
bool zkfc_kprobes_active(void);

/* ---- arch/ ---- */
u32 zkfc_arch_id(void);
const char *zkfc_arch_name(void);

/* ---- core/zkfc_main.c ---- */
u32 zkfc_features_available(void);

static inline bool zkfc_feature_licensed(u32 feat)
{
	return (zkfc_license_features() & feat) == feat;
}

#endif /* _ZKFC_INTERNAL_H */
