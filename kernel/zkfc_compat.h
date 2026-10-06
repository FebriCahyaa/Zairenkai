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

/*
 * Monotonic nanoseconds since boot.
 *
 * ktime_get_boottime_ns() is mainline only from 5.3; the pre-5.3 spelling was
 * ktime_get_boot_ns(). But Android LTS trees (notably 4.19, e.g. sdm660) back-
 * port ktime_get_boottime_ns() *and drop* ktime_get_boot_ns(), so a plain
 * LINUX_VERSION_CODE test picks the missing name on exactly those kernels and
 * fails with "implicit declaration of ktime_get_boot_ns". ktime_get_boottime()
 * -> ktime_t is stable on 4.18+ and every GKI branch, and ktime_to_ns() of it
 * is precisely what the kernel's own helper computes, so derive the value
 * directly and never reference the version-fragile wrapper names.
 */
static inline u64 zkfc_boottime_ns(void)
{
	return ktime_to_ns(ktime_get_boottime());
}

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
