// SPDX-License-Identifier: GPL-2.0-only
/*
 * ZKFC integrity reports: system (kernel hardening state), device (identity
 * of the running kernel) and the ZKFC configuration digest.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#include <linux/cpumask.h>
#include <linux/kernel.h>
#include <linux/module.h>
#include <linux/security.h>
#include <linux/string.h>
#include <linux/utsname.h>
#include <linux/version.h>
#ifdef CONFIG_OF
#include <linux/of.h>
#endif
#ifdef CONFIG_DMI
#include <linux/dmi.h>
#endif

#include "../zkfc.h"
#include "../crypto/zk_sha512.h"
#include "zkfc_owner_key.h"

static u8 zkfc_cfg_digest[32];

static bool zkfc_release_has_android_kmi(const char *release)
{
	const char *p = strstr(release, "-android");
	unsigned int digits = 0;

	if (!p)
		return false;
	p += sizeof("-android") - 1;
	while (p[0] >= '0' && p[0] <= '9') {
		digits++;
		p++;
	}
	return digits >= 2 && p[0] == '-';
}

u32 zkfc_kernel_type(void)
{
	const char *release = utsname()->release;

	/* Explicit override is intended for vendor GKI trees with custom release strings. */
	if (IS_ENABLED(CONFIG_ZKFC_FORCE_GKI))
		return ZKFC_KERNEL_GKI;
	/* Conservative detection: a modern kernel alone is not evidence of GKI. */
	if (LINUX_VERSION_CODE >= KERNEL_VERSION(5, 4, 0) &&
	    zkfc_release_has_android_kmi(release))
		return ZKFC_KERNEL_GKI;
	return ZKFC_KERNEL_NON_GKI;
}

int zkfc_integrity_init(void)
{
	struct zk_sha512_ctx ctx;
	u8 digest[ZK_SHA512_DIGEST_SIZE];
	u8 binding[ZKFC_BINDING_SIZE];
	u32 ver = ZKFC_API_VERSION;

	/*
	 * Digest of everything that decides who may unlock ZKFC. Official
	 * builds publish this value so the manager can detect kernels whose
	 * key, API level or binding were modified.
	 */
	zkfc_kernel_binding(binding);
	zk_sha512_init(&ctx);
	zk_sha512_update(&ctx, "ZKFC-CFG-v1", 11);
	zk_sha512_update(&ctx, &ver, sizeof(ver));
	zk_sha512_update(&ctx, zkfc_owner_pubkey, sizeof(zkfc_owner_pubkey));
	zk_sha512_update(&ctx, binding, sizeof(binding));
	zk_sha512_final(&ctx, digest);
	memcpy(zkfc_cfg_digest, digest, sizeof(zkfc_cfg_digest));
	return 0;
}

void zkfc_integrity_sys(struct zkfc_sys_security *s)
{
	unsigned int i;

	memset(s, 0, sizeof(*s));

#if defined(CONFIG_MODULE_SIG) && LINUX_VERSION_CODE >= KERNEL_VERSION(4, 15, 0)
	if (is_module_sig_enforced())
		s->flags |= ZKFC_SYS_MODULE_SIG_ENFORCED;
#endif
#ifdef ZKFC_HAVE_LOCKDOWN
	if (security_locked_down(LOCKDOWN_INTEGRITY_MAX))
		s->flags |= ZKFC_SYS_LOCKDOWN_INTEGRITY;
	if (security_locked_down(LOCKDOWN_CONFIDENTIALITY_MAX))
		s->flags |= ZKFC_SYS_LOCKDOWN_CONFIDENTIAL;
#endif
	if (IS_ENABLED(CONFIG_KPROBES))
		s->flags |= ZKFC_SYS_KPROBES_ENABLED;
	if (IS_ENABLED(CONFIG_STRICT_KERNEL_RWX))
		s->flags |= ZKFC_SYS_STRICT_KERNEL_RWX;
	if (IS_ENABLED(CONFIG_STACKPROTECTOR) || IS_ENABLED(CONFIG_CC_STACKPROTECTOR))
		s->flags |= ZKFC_SYS_STACKPROTECTOR;
	if (IS_ENABLED(CONFIG_CFI_CLANG))
		s->flags |= ZKFC_SYS_CFI;
	if (IS_ENABLED(CONFIG_SHADOW_CALL_STACK))
		s->flags |= ZKFC_SYS_SCS;
	if (IS_ENABLED(CONFIG_RANDOMIZE_BASE))
		s->flags |= ZKFC_SYS_KASLR;
	if (IS_ENABLED(CONFIG_SECURITY_SELINUX))
		s->flags |= ZKFC_SYS_SECURITY_SELINUX;

	for (i = 0; i < TAINT_FLAGS_COUNT && i < 64; i++)
		if (test_taint(i))
			s->taint_mask |= 1ULL << i;

	s->uptime_ns = zkfc_boottime_ns();
	memcpy(s->zkfc_config_digest, zkfc_cfg_digest, sizeof(s->zkfc_config_digest));
}

void zkfc_integrity_dev(struct zkfc_dev_security *d)
{
	memset(d, 0, sizeof(*d));
	zkfc_kernel_binding(d->kernel_binding);
	strscpy(d->licensee_tag, CONFIG_ZKFC_LICENSEE_TAG, sizeof(d->licensee_tag));
	d->cpu_count = num_possible_cpus();

#ifdef CONFIG_OF
	{
		struct device_node *root = of_find_node_by_path("/");
		const char *str;

		if (root) {
			if (!of_property_read_string(root, "model", &str))
				strscpy(d->model, str, sizeof(d->model));
			if (!of_property_read_string(root, "compatible", &str))
				strscpy(d->compatible, str, sizeof(d->compatible));
			of_node_put(root);
		}
	}
#endif
#ifdef CONFIG_DMI
	if (!d->model[0]) {
		const char *s = dmi_get_system_info(DMI_PRODUCT_NAME);

		if (s)
			strscpy(d->model, s, sizeof(d->model));
		s = dmi_get_system_info(DMI_SYS_VENDOR);
		if (s)
			strscpy(d->compatible, s, sizeof(d->compatible));
	}
#endif
}
