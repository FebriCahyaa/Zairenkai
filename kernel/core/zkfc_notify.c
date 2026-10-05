// SPDX-License-Identifier: GPL-2.0-only
/*
 * ZKFC fail-safe notifiers.
 *
 * ZKFC raises cpufreq floors, task uclamp and input boosts. If the system goes
 * down we must not leave those overrides pinned for the next boot, and we must
 * not add risk during a crash. So:
 *
 *   - reboot/shutdown (process context, orderly): fully reset every ZKFC
 *     performance request back to the kernel defaults;
 *   - panic / oops / die (atomic, may be mid-crash): do only atomic-safe work —
 *     flip the thermal/boost "suspended" flags so any surviving code path stops
 *     boosting, and leave a marker in the kernel log for post-mortem.
 *
 * A separate userspace watchdog (zkfctl confirm-boot) handles the "sudden
 * reboot / bootloop" case by rolling back the boot profile; see
 * docs/security/FAILSAFE.md.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#include <linux/kernel.h>
#include <linux/notifier.h>
#include <linux/reboot.h>
#include <linux/version.h>
#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 15, 0)
#include <linux/panic_notifier.h>	/* panic_notifier_list moved here in 5.15 */
#endif

#include "../zkfc.h"

static atomic_t zkfc_going_down = ATOMIC_INIT(0);

/* True once a reboot/panic has started; callers can cheaply bail out. */
bool zkfc_is_going_down(void)
{
	return atomic_read(&zkfc_going_down) != 0;
}

static int zkfc_reboot_cb(struct notifier_block *nb, unsigned long action,
			  void *data)
{
	if (atomic_xchg(&zkfc_going_down, 1))
		return NOTIFY_DONE;
	zkfc_i("shutdown/reboot: resetting all performance requests");
	/* Process context here: the full (sleeping) reset is safe. */
	zkfc_input_boost_suspend(true);
	zkfc_task_boost_reset_all();
	zkfc_cpufreq_reset_all();
	return NOTIFY_DONE;
}

static int zkfc_panic_cb(struct notifier_block *nb, unsigned long action,
			 void *data)
{
	atomic_set(&zkfc_going_down, 1);
	/*
	 * Atomic / crash context: do NOT call anything that can sleep
	 * (cpufreq/freq_qos, workqueues). Only flip the suspend flags so any
	 * boost path that still runs becomes a no-op, and record the event.
	 */
	zkfc_input_boost_suspend(true);
	zkfc_task_boost_suspend(true);
	pr_emerg(ZKFC_PREFIX "panic notifier: ZKFC boosts suspended\n");
	return NOTIFY_DONE;
}

static struct notifier_block zkfc_reboot_nb = {
	.notifier_call = zkfc_reboot_cb,
	.priority = 0,
};

static struct notifier_block zkfc_panic_nb = {
	.notifier_call = zkfc_panic_cb,
	.priority = INT_MAX,	/* run early, before the box is fully gone */
};

int zkfc_notify_init(void)
{
	register_reboot_notifier(&zkfc_reboot_nb);
	atomic_notifier_chain_register(&panic_notifier_list, &zkfc_panic_nb);
	return 0;
}

void zkfc_notify_exit(void)
{
	atomic_notifier_chain_unregister(&panic_notifier_list, &zkfc_panic_nb);
	unregister_reboot_notifier(&zkfc_reboot_nb);
}
