/* SPDX-License-Identifier: GPL-2.0-only OR MIT */
/*
 * Zairenkai Kernel Framework Core (ZKFC)
 * Portable integer types shared by the kernel module and userspace tools.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#ifndef _ZK_TYPES_H
#define _ZK_TYPES_H

#ifdef __KERNEL__
#include <linux/types.h>
#include <linux/string.h>
typedef u8 zk_u8;
typedef u32 zk_u32;
typedef u64 zk_u64;
typedef s64 zk_i64;
typedef size_t zk_size;
#else
#include <stddef.h>
#include <stdint.h>
#include <string.h>
typedef uint8_t zk_u8;
typedef uint32_t zk_u32;
typedef uint64_t zk_u64;
typedef int64_t zk_i64;
typedef size_t zk_size;
#endif

/* Best-effort wipe that the compiler is not allowed to elide. */
static inline void zk_memzero(void *p, zk_size n)
{
	volatile zk_u8 *v = (volatile zk_u8 *)p;

	while (n--)
		*v++ = 0;
}

#endif /* _ZK_TYPES_H */
