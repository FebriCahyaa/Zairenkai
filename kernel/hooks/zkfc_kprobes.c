// SPDX-License-Identifier: GPL-2.0-only
/*
 * ZKFC hybrid hooks: kprobes on functions whose signature has been stable
 * from 4.14 to 6.x on arm64, x86_64 and riscv64.
 *
 *   security_bprm_check(struct linux_binprm *bprm)  -> sulog of "su" exec
 *   wake_up_new_task(struct task_struct *p)          -> boost inheritance
 *
 * Arguments are fetched with the per-architecture zkfc_regs_arg() helper.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#include <linux/binfmts.h>
#include <linux/kernel.h>
#include <linux/kprobes.h>
#include <linux/sched.h>

#include "../zkfc.h"
#include "../arch/zkfc_arch.h"

#if defined(CONFIG_KPROBES) && !defined(ZKFC_ARCH_NO_KPROBE_ARGS)

static int zkfc_kp_exec_pre(struct kprobe *kp, struct pt_regs *regs)
{
	struct linux_binprm *bprm = (struct linux_binprm *)zkfc_regs_arg(regs, 0);

	if (bprm)
		zkfc_on_exec(bprm->filename);
	return 0;
}

static int zkfc_kp_newtask_pre(struct kprobe *kp, struct pt_regs *regs)
{
	zkfc_on_new_task((struct task_struct *)zkfc_regs_arg(regs, 0));
	return 0;
}

static struct kprobe zkfc_kp_exec = {
	.symbol_name = "security_bprm_check",
	.pre_handler = zkfc_kp_exec_pre,
};

static struct kprobe zkfc_kp_newtask = {
	.symbol_name = "wake_up_new_task",
	.pre_handler = zkfc_kp_newtask_pre,
};

static bool zkfc_kp_exec_on, zkfc_kp_newtask_on;

int zkfc_kprobes_init(void)
{
	int r1, r2;

	r1 = register_kprobe(&zkfc_kp_exec);
	zkfc_kp_exec_on = !r1;
	r2 = register_kprobe(&zkfc_kp_newtask);
	zkfc_kp_newtask_on = !r2;

	if (r1)
		zkfc_w("kprobe %s: %d", zkfc_kp_exec.symbol_name, r1);
	if (r2)
		zkfc_w("kprobe %s: %d", zkfc_kp_newtask.symbol_name, r2);
	return (zkfc_kp_exec_on || zkfc_kp_newtask_on) ? 0 : (r1 ?: r2);
}

void zkfc_kprobes_exit(void)
{
	if (zkfc_kp_exec_on)
		unregister_kprobe(&zkfc_kp_exec);
	if (zkfc_kp_newtask_on)
		unregister_kprobe(&zkfc_kp_newtask);
	zkfc_kp_exec_on = zkfc_kp_newtask_on = false;
}

bool zkfc_kprobes_active(void)
{
	return zkfc_kp_exec_on || zkfc_kp_newtask_on;
}

#else /* no kprobes */

int zkfc_kprobes_init(void)
{
	return -EOPNOTSUPP;
}

void zkfc_kprobes_exit(void)
{
}

bool zkfc_kprobes_active(void)
{
	return false;
}

#endif
