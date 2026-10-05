/* SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary */
/*
 * libzkfc - userspace wrapper around /dev/zkfc.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#ifndef LIBZKFC_H
#define LIBZKFC_H

#include <stdbool.h>
#include <stdint.h>

#include <linux/zkfc.h>

struct zkfc;

/* Open /dev/zkfc. Returns NULL and sets *err (-errno) on failure. */
struct zkfc *zkfc_open(int *err);
void zkfc_close(struct zkfc *z);
int zkfc_fd(const struct zkfc *z);

/* Information / security. Each returns 0 or -errno. */
int zkfc_version(struct zkfc *z, struct zkfc_version_info *v);
int zkfc_license(struct zkfc *z, struct zkfc_license_status *s);
int zkfc_sys_security(struct zkfc *z, struct zkfc_sys_security *s);
int zkfc_user_security(struct zkfc *z, struct zkfc_user_security *s);
int zkfc_dev_security(struct zkfc *z, struct zkfc_dev_security *s);

/* License management (root). */
int zkfc_install_license(struct zkfc *z, const struct zkfc_license_token *t);
int zkfc_install_crl(struct zkfc *z, const struct zkfc_crl *c);

/* Performance (needs a valid token). */
int zkfc_task_boost(struct zkfc *z, struct zkfc_task_boost *tb);
int zkfc_cpufreq_qos(struct zkfc *z, const struct zkfc_cpufreq_qos *q);
int zkfc_input_boost(struct zkfc *z, const struct zkfc_input_boost *cfg);
int zkfc_thermal_guard(struct zkfc *z, const struct zkfc_thermal_guard *cfg);
int zkfc_thermal_read(struct zkfc *z, struct zkfc_thermal_read *rd);
int zkfc_perf_status(struct zkfc *z, struct zkfc_perf_status *st);
int zkfc_perf_reset(struct zkfc *z);

/* Logs / policy. */
int zkfc_log_set_level(struct zkfc *z, uint32_t level);
int zkfc_log_read(struct zkfc *z, struct zkfc_log_read *rd);
int zkfc_sulog_read(struct zkfc *z, struct zkfc_sulog_read *rd);
int zkfc_policy_get(struct zkfc *z, struct zkfc_policy_table *t);
int zkfc_policy_set(struct zkfc *z, const struct zkfc_policy_table *t);

/* Helpers shared with the CLI/app. */
const char *zkfc_license_state_str(uint32_t state);
const char *zkfc_arch_str(uint32_t arch);
const char *zkfc_hook_str(uint32_t mode);
const char *zkfc_log_level_str(uint32_t level);
/* True when the kernel's API major is older than this build expects. */
bool zkfc_api_outdated(const struct zkfc_version_info *v);

/* Parse an armored ".zkl" token file into a token struct. 0 or -errno. */
int zkfc_parse_token_file(const char *path, struct zkfc_license_token *out);
int zkfc_parse_crl_file(const char *path, struct zkfc_crl *out);

#endif
