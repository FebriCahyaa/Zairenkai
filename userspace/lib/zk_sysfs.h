/* SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary */
/*
 * Safe sysfs/procfs read-write helpers.
 * Copyright (C) 2026 FebriCahyaa
 */
#ifndef ZK_SYSFS_H
#define ZK_SYSFS_H

#include <stddef.h>

/* Read a node into buf (NUL terminated, trailing newline stripped).
 * Returns 0 on success, -errno on failure. */
int zk_read(const char *path, char *buf, size_t len);
long long zk_read_ll(const char *path, long long def);

/* Write a string/number. Returns 0 or -errno. Never follows symlinks out of
 * the allowed roots (see zk_path_allowed). */
int zk_write(const char *path, const char *val);
int zk_write_ll(const char *path, long long val);

/* True if the node exists and is writable by the current process. */
int zk_exists(const char *path);
int zk_writable(const char *path);

/* First existing path from a NULL-terminated candidate list, or NULL. */
const char *zk_first_existing(const char *const *candidates);

/* Reject paths that escape the tunable roots (/sys, /proc, /dev/zkfc...). */
int zk_path_allowed(const char *path);

#endif
