// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
#define _GNU_SOURCE
/*
 * Safe sysfs/procfs read-write helpers.
 *
 * All writes go through zk_path_allowed(), which resolves the real path and
 * refuses anything outside the kernel tunable trees. This keeps a buggy or
 * hostile profile from pointing a "tweak" at an arbitrary file.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#include "zk_sysfs.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static const char *const zk_roots[] = {
	"/sys/", "/proc/sys/", "/proc/", "/dev/cpuset/", "/dev/stune/", NULL
};

int zk_path_allowed(const char *path)
{
	char real[PATH_MAX];
	const char *check = path;
	char dir[PATH_MAX], *slash;
	int i;

	if (!path || path[0] != '/' || strstr(path, "/.."))
		return 0;

	/* Resolve through the parent so we also catch symlinked leaves. */
	if (!realpath(path, real)) {
		strncpy(dir, path, sizeof(dir) - 1);
		dir[sizeof(dir) - 1] = '\0';
		slash = strrchr(dir, '/');
		if (slash && slash != dir) {
			*slash = '\0';
			if (realpath(dir, real)) {
				size_t n = strlen(real);

				if (n + 1 < sizeof(real)) {
					real[n] = '/';
					strncpy(real + n + 1, slash + 1, sizeof(real) - n - 2);
					real[sizeof(real) - 1] = '\0';
				}
				check = real;
			}
		}
	} else {
		check = real;
	}

	for (i = 0; zk_roots[i]; i++)
		if (!strncmp(check, zk_roots[i], strlen(zk_roots[i])))
			return 1;
	return 0;
}

int zk_read(const char *path, char *buf, size_t len)
{
	int fd, ret = 0;
	ssize_t n;

	if (!buf || len == 0)
		return -EINVAL;
	buf[0] = '\0';
	fd = open(path, O_RDONLY | O_CLOEXEC);
	if (fd < 0)
		return -errno;
	n = read(fd, buf, len - 1);
	if (n < 0)
		ret = -errno;
	else {
		buf[n] = '\0';
		while (n > 0 && (buf[n - 1] == '\n' || buf[n - 1] == '\r' ||
				 buf[n - 1] == ' '))
			buf[--n] = '\0';
	}
	close(fd);
	return ret;
}

long long zk_read_ll(const char *path, long long def)
{
	char buf[64];

	if (zk_read(path, buf, sizeof(buf)) == 0 && buf[0]) {
		char *end;
		long long v = strtoll(buf, &end, 0);

		if (end != buf)
			return v;
	}
	return def;
}

int zk_write(const char *path, const char *val)
{
	int fd, ret = 0;
	size_t vlen;
	ssize_t n;

	if (!zk_path_allowed(path))
		return -EACCES;
	fd = open(path, O_WRONLY | O_CLOEXEC);
	if (fd < 0)
		return -errno;
	vlen = strlen(val);
	n = write(fd, val, vlen);
	if (n < 0)
		ret = -errno;
	else if ((size_t)n != vlen)
		ret = -EIO;
	close(fd);
	return ret;
}

int zk_write_ll(const char *path, long long val)
{
	char buf[32];

	snprintf(buf, sizeof(buf), "%lld", val);
	return zk_write(path, buf);
}

int zk_exists(const char *path)
{
	return access(path, F_OK) == 0;
}

int zk_writable(const char *path)
{
	return access(path, W_OK) == 0;
}

const char *zk_first_existing(const char *const *candidates)
{
	int i;

	for (i = 0; candidates && candidates[i]; i++)
		if (zk_exists(candidates[i]))
			return candidates[i];
	return NULL;
}
