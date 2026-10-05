/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * ZKFC manual hooks - include this from patched kernel files.
 *
 * Copy to include/linux/zkfc_hooks.h in your kernel tree (setup.sh does not
 * do it, because manual mode is a conscious choice), then add the calls
 * documented in README.md. With CONFIG_ZKFC_HOOK_MANUAL unset the macros
 * compile to nothing, so the patch can stay in the tree permanently.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#ifndef _LINUX_ZKFC_HOOKS_H
#define _LINUX_ZKFC_HOOKS_H

struct task_struct;

#if defined(CONFIG_ZKFC) && defined(CONFIG_ZKFC_HOOK_MANUAL)
void zkfc_on_exec(const char *filename);
void zkfc_on_new_task(struct task_struct *p);

#define ZKFC_HOOK_EXEC(filename)	zkfc_on_exec(filename)
#define ZKFC_HOOK_NEW_TASK(p)		zkfc_on_new_task(p)
#else
#define ZKFC_HOOK_EXEC(filename)	do { } while (0)
#define ZKFC_HOOK_NEW_TASK(p)		do { } while (0)
#endif

#endif /* _LINUX_ZKFC_HOOKS_H */
