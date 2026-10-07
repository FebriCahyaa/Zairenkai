// SPDX-License-Identifier: GPL-2.0-only
/*
 * ZKFC kernel capability graph.
 *
 * This file deliberately reports kernel capabilities from compile-time feature
 * contracts and runtime framework state. It does not guess vendor-specific
 * sysfs paths. Vendor/SoC adapters are selected in userspace from the runtime
 * inventory and the versioned device database.
 */
#include <linux/kernel.h>
#include <linux/mm.h>
#include <linux/utsname.h>

#include "../zkfc.h"

void zkfc_capabilities(struct zkfc_capability_info *c)
{
	u64 k = 0, r = 0;

	if (IS_ENABLED(CONFIG_CPU_FREQ))
		k |= ZKFC_KCAP_CPUFREQ;
	if (IS_ENABLED(CONFIG_PM_DEVFREQ))
		k |= ZKFC_KCAP_DEVFREQ;
	if (IS_ENABLED(CONFIG_THERMAL))
		k |= ZKFC_KCAP_THERMAL;
	if (IS_ENABLED(CONFIG_CPU_IDLE))
		k |= ZKFC_KCAP_CPU_IDLE;
	if (IS_ENABLED(CONFIG_SCHED_AUTOGROUP))
		k |= ZKFC_KCAP_AUTOGROUP;
	if (IS_ENABLED(CONFIG_CGROUPS))
		k |= ZKFC_KCAP_CGROUPS;
#ifdef CONFIG_PSI
	k |= ZKFC_KCAP_PSI;
#endif
#ifdef CONFIG_ENERGY_MODEL
	k |= ZKFC_KCAP_ENERGY_MODEL;
#endif
#ifdef ZKFC_HAVE_FREQ_QOS
	k |= ZKFC_KCAP_FREQ_QOS;
#endif
#ifdef ZKFC_HAVE_UCLAMP
	k |= ZKFC_KCAP_UCLAMP;
#endif
#ifdef CONFIG_SCHEDTUNE
	k |= ZKFC_KCAP_SCHEDTUNE;
#endif
#ifdef CONFIG_SCHED_WALT
	k |= ZKFC_KCAP_WALT;
#endif
#ifdef CONFIG_KPROBES
	k |= ZKFC_KCAP_KPROBES;
#endif
#ifdef CONFIG_MODULES
	k |= ZKFC_KCAP_MODULES;
#endif
#ifdef CONFIG_DM_CRYPT
	k |= ZKFC_KCAP_DM_CRYPT;
#endif
#ifdef CONFIG_BPF_SYSCALL
	k |= ZKFC_KCAP_BPF;
#endif

	if (zkfc_hooks_active())
		r |= ZKFC_KCAP_HOOKS_ACTIVE;
	if (zkfc_thermal_tripped())
		r |= ZKFC_KCAP_THERMAL_GUARD_TRIPPED;
	if (zkfc_license_state() == ZKFC_LIC_VALID)
		r |= ZKFC_KCAP_LICENSE_VALID;

	memset(c, 0, sizeof(*c));
	c->kernel_caps = cpu_to_le64(k);
	c->runtime_caps = cpu_to_le64(r);
	c->kernel_major = (LINUX_VERSION_CODE >> 16) & 0xff;
	c->kernel_minor = (LINUX_VERSION_CODE >> 8) & 0xff;
	c->kernel_patch = LINUX_VERSION_CODE & 0xff;
	c->cpu_count = nr_cpu_ids;
	c->page_size = PAGE_SIZE;
	c->kernel_type = zkfc_kernel_type();
	c->hook_mode = zkfc_hook_mode();
	strscpy(c->kernel_release, utsname()->release, sizeof(c->kernel_release));
	strscpy(c->build_id, ZKFC_BUILD_ID, sizeof(c->build_id));
}
