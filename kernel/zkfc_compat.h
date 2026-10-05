/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * ZKFC compatibility shims for kernels 4.14 .. 6.x (GKI and non-GKI).
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#ifndef _ZKFC_COMPAT_H
#define _ZKFC_COMPAT_H

#include <linux/version.h>
#include <linux/timekeeping.h>
#include <linux/fs.h>

#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 3, 0)
#define ktime_get_boottime_ns() ktime_get_boot_ns()
#endif

#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 5, 0)
#ifdef CONFIG_COMPAT
#include <linux/compat.h>
static inline long zkfc_compat_ptr_ioctl(struct file *file, unsigned int cmd,
					 unsigned long arg)
{
	if (!file->f_op->unlocked_ioctl)
		return -ENOIOCTLCMD;
	return file->f_op->unlocked_ioctl(file, cmd, (unsigned long)compat_ptr(arg));
}
#define compat_ptr_ioctl zkfc_compat_ptr_ioctl
#else
#define compat_ptr_ioctl NULL
#endif
#endif

/* freq_qos replaced the cpufreq policy notifier in 5.4. */
#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 4, 0)
#define ZKFC_HAVE_FREQ_QOS 1
#endif

/* Utilization clamping (uclamp) landed in 5.3. */
#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 3, 0) && defined(CONFIG_UCLAMP_TASK)
#define ZKFC_HAVE_UCLAMP 1
#endif

/* Kernel lockdown LSM hooks landed in 5.4. */
#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 4, 0) && defined(CONFIG_SECURITY)
#define ZKFC_HAVE_LOCKDOWN 1
#endif

#endif /* _ZKFC_COMPAT_H */
