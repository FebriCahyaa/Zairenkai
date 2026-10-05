// SPDX-License-Identifier: GPL-2.0-only
/*
 * ZKFC hook dispatch.
 *
 * Hook mode is chosen at build time:
 *  - HYBRID: kprobes on stable kernel functions (GKI and most non-GKI
 *    kernels with CONFIG_KPROBES=y); no kernel source changes required.
 *  - MANUAL: the kernel source calls zkfc_on_exec()/zkfc_on_new_task()
 *    directly (see hooks/manual/). Required when kprobes are unavailable or
 *    when the kernel is not one of the Zairenkai-maintained trees.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#include <linux/export.h>
#include <linux/kernel.h>
#include <linux/sched.h>
#include <linux/string.h>

#include "../zkfc.h"

static bool zkfc_hooks_ready;

u32 zkfc_hook_mode(void)
{
#if defined(CONFIG_ZKFC_HOOK_MANUAL)
	return ZKFC_HOOK_MANUAL;
#elif defined(CONFIG_ZKFC_HOOK_HYBRID)
	return zkfc_kprobes_active() ? ZKFC_HOOK_HYBRID : ZKFC_HOOK_NONE;
#else
	return ZKFC_HOOK_NONE;
#endif
}

bool zkfc_hooks_active(void)
{
	return READ_ONCE(zkfc_hooks_ready) && zkfc_hook_mode() != ZKFC_HOOK_NONE;
}

void zkfc_on_exec(const char *filename)
{
	const char *base;

	if (!READ_ONCE(zkfc_hooks_ready) || !filename)
		return;
	base = strrchr(filename, '/');
	base = base ? base + 1 : filename;
	if (!strcmp(base, "su"))
		zkfc_sulog_add(ZKFC_SU_EXEC, 0, 0, filename);
}

void zkfc_on_new_task(struct task_struct *p)
{
	if (!READ_ONCE(zkfc_hooks_ready) || !p)
		return;
	zkfc_task_boost_on_new_task(p);
}

#ifdef CONFIG_ZKFC_HOOK_MANUAL
/* Built-in only: referenced from patched fs/exec.c and kernel/fork.c. */
EXPORT_SYMBOL_GPL(zkfc_on_exec);
EXPORT_SYMBOL_GPL(zkfc_on_new_task);
#endif

int zkfc_hooks_init(void)
{
	int ret = 0;

#ifdef CONFIG_ZKFC_HOOK_HYBRID
	ret = zkfc_kprobes_init();
	if (ret)
		zkfc_w("kprobes unavailable (%d): sulog/boost inheritance disabled", ret);
#endif
	WRITE_ONCE(zkfc_hooks_ready, true);
	zkfc_i("hook mode: %s",
	       zkfc_hook_mode() == ZKFC_HOOK_MANUAL ? "manual" :
	       zkfc_hook_mode() == ZKFC_HOOK_HYBRID ? "hybrid" : "none");
	return 0;
}

void zkfc_hooks_exit(void)
{
	WRITE_ONCE(zkfc_hooks_ready, false);
#ifdef CONFIG_ZKFC_HOOK_HYBRID
	zkfc_kprobes_exit();
#endif
}
