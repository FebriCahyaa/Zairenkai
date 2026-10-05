// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
#define _GNU_SOURCE
/*
 * libzkfc - userspace wrapper around /dev/zkfc.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#include "libzkfc.h"

#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

/* ZKFC_API_MAJOR this userspace was built against. */
#define LIBZKFC_EXPECT_MAJOR ZKFC_API_MAJOR

struct zkfc {
	int fd;
};

struct zkfc *zkfc_open(int *err)
{
	struct zkfc *z = calloc(1, sizeof(*z));

	if (!z) {
		if (err)
			*err = -ENOMEM;
		return NULL;
	}
	z->fd = open(ZKFC_DEVICE_PATH, O_RDWR | O_CLOEXEC);
	if (z->fd < 0) {
		if (err)
			*err = -errno;
		free(z);
		return NULL;
	}
	if (err)
		*err = 0;
	return z;
}

void zkfc_close(struct zkfc *z)
{
	if (!z)
		return;
	if (z->fd >= 0)
		close(z->fd);
	free(z);
}

int zkfc_fd(const struct zkfc *z)
{
	return z ? z->fd : -1;
}

static int zk_ioctl(struct zkfc *z, unsigned long req, void *arg)
{
	if (!z || z->fd < 0)
		return -EINVAL;
	if (ioctl(z->fd, req, arg) < 0)
		return -errno;
	return 0;
}

int zkfc_version(struct zkfc *z, struct zkfc_version_info *v)
{
	return zk_ioctl(z, ZKFC_IOC_GET_VERSION, v);
}
int zkfc_license(struct zkfc *z, struct zkfc_license_status *s)
{
	return zk_ioctl(z, ZKFC_IOC_GET_LICENSE, s);
}
int zkfc_sys_security(struct zkfc *z, struct zkfc_sys_security *s)
{
	return zk_ioctl(z, ZKFC_IOC_GET_SYS_SECURITY, s);
}
int zkfc_user_security(struct zkfc *z, struct zkfc_user_security *s)
{
	return zk_ioctl(z, ZKFC_IOC_GET_USER_SECURITY, s);
}
int zkfc_dev_security(struct zkfc *z, struct zkfc_dev_security *s)
{
	return zk_ioctl(z, ZKFC_IOC_GET_DEV_SECURITY, s);
}
int zkfc_install_license(struct zkfc *z, const struct zkfc_license_token *t)
{
	return zk_ioctl(z, ZKFC_IOC_INSTALL_LICENSE, (void *)t);
}
int zkfc_install_crl(struct zkfc *z, const struct zkfc_crl *c)
{
	return zk_ioctl(z, ZKFC_IOC_INSTALL_CRL, (void *)c);
}
int zkfc_task_boost(struct zkfc *z, struct zkfc_task_boost *tb)
{
	return zk_ioctl(z, ZKFC_IOC_TASK_BOOST, tb);
}
int zkfc_cpufreq_qos(struct zkfc *z, const struct zkfc_cpufreq_qos *q)
{
	return zk_ioctl(z, ZKFC_IOC_CPUFREQ_QOS, (void *)q);
}
int zkfc_input_boost(struct zkfc *z, const struct zkfc_input_boost *cfg)
{
	return zk_ioctl(z, ZKFC_IOC_INPUT_BOOST, (void *)cfg);
}
int zkfc_thermal_guard(struct zkfc *z, const struct zkfc_thermal_guard *cfg)
{
	return zk_ioctl(z, ZKFC_IOC_THERMAL_GUARD, (void *)cfg);
}
int zkfc_thermal_read(struct zkfc *z, struct zkfc_thermal_read *rd)
{
	return zk_ioctl(z, ZKFC_IOC_THERMAL_READ, rd);
}
int zkfc_perf_status(struct zkfc *z, struct zkfc_perf_status *st)
{
	return zk_ioctl(z, ZKFC_IOC_PERF_STATUS, st);
}
int zkfc_perf_reset(struct zkfc *z)
{
	return zk_ioctl(z, ZKFC_IOC_PERF_RESET, NULL);
}
int zkfc_log_set_level(struct zkfc *z, uint32_t level)
{
	return zk_ioctl(z, ZKFC_IOC_LOG_SET_LEVEL, &level);
}
int zkfc_log_read(struct zkfc *z, struct zkfc_log_read *rd)
{
	return zk_ioctl(z, ZKFC_IOC_LOG_READ, rd);
}
int zkfc_sulog_read(struct zkfc *z, struct zkfc_sulog_read *rd)
{
	return zk_ioctl(z, ZKFC_IOC_SULOG_READ, rd);
}
int zkfc_policy_get(struct zkfc *z, struct zkfc_policy_table *t)
{
	return zk_ioctl(z, ZKFC_IOC_POLICY_GET, t);
}
int zkfc_policy_set(struct zkfc *z, const struct zkfc_policy_table *t)
{
	return zk_ioctl(z, ZKFC_IOC_POLICY_SET, (void *)t);
}

const char *zkfc_license_state_str(uint32_t s)
{
	switch (s) {
	case ZKFC_LIC_MISSING: return "missing";
	case ZKFC_LIC_VALID: return "valid";
	case ZKFC_LIC_BAD_SIGNATURE: return "bad_signature";
	case ZKFC_LIC_EXPIRED: return "expired";
	case ZKFC_LIC_WRONG_BINDING: return "wrong_binding";
	case ZKFC_LIC_API_OUTDATED: return "api_outdated";
	case ZKFC_LIC_MALFORMED: return "malformed";
	case ZKFC_LIC_NO_OWNER_KEY: return "no_owner_key";
	case ZKFC_LIC_REVOKED: return "revoked";
	default: return "unknown";
	}
}

const char *zkfc_arch_str(uint32_t a)
{
	switch (a) {
	case ZKFC_ARCH_ARM64: return "arm64";
	case ZKFC_ARCH_X86_64: return "x86_64";
	case ZKFC_ARCH_RISCV64: return "riscv64";
	default: return "unknown";
	}
}

const char *zkfc_hook_str(uint32_t m)
{
	switch (m) {
	case ZKFC_HOOK_HYBRID: return "hybrid";
	case ZKFC_HOOK_MANUAL: return "manual";
	default: return "none";
	}
}

const char *zkfc_log_level_str(uint32_t l)
{
	switch (l) {
	case ZKFC_LOG_VERBOSE: return "verbose";
	case ZKFC_LOG_DEBUG: return "debug";
	case ZKFC_LOG_INFO: return "info";
	case ZKFC_LOG_WARN: return "warn";
	case ZKFC_LOG_ERROR: return "error";
	case ZKFC_LOG_SILENT: return "silent";
	default: return "info";
	}
}

bool zkfc_api_outdated(const struct zkfc_version_info *v)
{
	return v && ZKFC_VER_MAJOR(v->api_version) < LIBZKFC_EXPECT_MAJOR;
}
