// SPDX-License-Identifier: GPL-2.0-only
/*
 * Zairenkai Kernel Framework Core (ZKFC) - main driver.
 *
 * Exposes /dev/zkfc (root only, mode 0600). Every ioctl is checked against
 * the caller's current credentials on each call (not only at open), so a
 * file descriptor passed to a less privileged process gains it nothing.
 * Performance ioctls additionally require a valid ZKFC API Token.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#include <linux/atomic.h>
#include <linux/build_bug.h>
#include <linux/fs.h>
#include <linux/kernel.h>
#include <linux/miscdevice.h>
#include <linux/module.h>
#include <linux/slab.h>
#include <linux/uaccess.h>
#include <linux/utsname.h>

#include "../zkfc.h"

struct zkfc_session {
	u64 id;
	atomic64_t ops;
};

static atomic64_t zkfc_session_seq = ATOMIC64_INIT(0);
static bool zkfc_input_boost_ok;

u32 zkfc_features_available(void)
{
	u32 f = ZKFC_FEAT_INTEGRITY | ZKFC_FEAT_POLICY | ZKFC_FEAT_SULOG;

	if (zkfc_uclamp_available())
		f |= ZKFC_FEAT_TASK_BOOST;

	if (IS_ENABLED(CONFIG_THERMAL))
		f |= ZKFC_FEAT_THERMAL_GUARD;
	if (zkfc_uclamp_available())
		f |= ZKFC_FEAT_UCLAMP;
	if (zkfc_input_boost_ok)
		f |= ZKFC_FEAT_INPUT_BOOST;
	/* CPU QoS is a distinct capability; legacy kernels use the notifier path. */
#ifdef ZKFC_HAVE_FREQ_QOS
	f |= ZKFC_FEAT_CPUFREQ_QOS;
#endif
	if (zkfc_hooks_active() && zkfc_uclamp_available()) {
		f |= ZKFC_FEAT_BOOST_INHERIT;
		if (zkfc_hook_mode() == ZKFC_HOOK_HYBRID)
			f |= ZKFC_FEAT_KPROBES;
	}
	return f;
}

static void zkfc_fill_version(struct zkfc_version_info *v)
{
	u32 avail = zkfc_features_available();

	v->api_version = ZKFC_API_VERSION;
	v->api_min_supported = ZKFC_API_MIN_SUPPORTED;
	v->arch = zkfc_arch_id();
	v->hook_mode = zkfc_hook_mode();
	v->kernel_type = zkfc_kernel_type();
	v->features = avail;
	/* Unlicensed features stay available for reporting only. */
	v->features_enabled = (avail & ~ZKFC_FEAT_LICENSED) |
			      (avail & zkfc_license_features());
	v->license_state = zkfc_license_state();
	v->kernel_version = LINUX_VERSION_CODE;
	strscpy(v->build_id, ZKFC_BUILD_ID, sizeof(v->build_id));
	strscpy(v->kernel_release, utsname()->release, sizeof(v->kernel_release));
}

/* Capability and licensed feature required by each ioctl. */
static int zkfc_requirements(unsigned int cmd, u32 *cap, u32 *feat, bool *mutating)
{
	*feat = 0;
	*mutating = false;

	switch (cmd) {
	case ZKFC_IOC_GET_VERSION:
	case ZKFC_IOC_GET_LICENSE:
	case ZKFC_IOC_GET_SYS_SECURITY:
	case ZKFC_IOC_GET_USER_SECURITY:
	case ZKFC_IOC_GET_DEV_SECURITY:
	case ZKFC_IOC_GET_CAPABILITIES:
	case ZKFC_IOC_POLICY_GET:
	case ZKFC_IOC_PERF_STATUS:
	case ZKFC_IOC_THERMAL_READ:
		/* Keep API 1.0 read access compatible; the kernel still exposes the
		 * granular capability vocabulary for policy authors. */
		*cap = ZKFC_CAP_READ_INFO;
		return 0;
	case ZKFC_IOC_INSTALL_LICENSE:
	case ZKFC_IOC_INSTALL_CRL:
		*cap = ZKFC_CAP_LICENSE;
		*mutating = true;
		return 0;
	case ZKFC_IOC_TASK_BOOST:
	case ZKFC_IOC_CPUFREQ_QOS:
	case ZKFC_IOC_INPUT_BOOST:
		*cap = ZKFC_CAP_TUNE_CPU;
		*feat = cmd == ZKFC_IOC_TASK_BOOST ? ZKFC_FEAT_TASK_BOOST :
			cmd == ZKFC_IOC_CPUFREQ_QOS ? ZKFC_FEAT_CPUFREQ_QOS :
			ZKFC_FEAT_INPUT_BOOST;
		*mutating = true;
		return 0;
	case ZKFC_IOC_THERMAL_GUARD:
		*cap = ZKFC_CAP_TUNE_THERMAL;
		*feat = ZKFC_FEAT_THERMAL_GUARD;
		*mutating = true;
		return 0;
	case ZKFC_IOC_PERF_RESET:
		/* Always allowed for tuners, even without a license: safety. */
		*cap = ZKFC_CAP_TUNE_PERF;
		*mutating = true;
		return 0;
	case ZKFC_IOC_LOG_READ:
	case ZKFC_IOC_SULOG_READ:
		*cap = ZKFC_CAP_READ_LOG;
		return 0;
	case ZKFC_IOC_LOG_SET_LEVEL:
	case ZKFC_IOC_POLICY_SET:
		*cap = ZKFC_CAP_ADMIN;
		*mutating = true;
		return 0;
	default:
		return -ENOTTY;
	}
}

static long zkfc_dispatch(struct zkfc_session *s, unsigned int cmd, void *buf,
			  struct zkfc_user_security *who)
{
	switch (cmd) {
	case ZKFC_IOC_GET_VERSION:
		zkfc_fill_version(buf);
		return 0;
	case ZKFC_IOC_GET_LICENSE:
		zkfc_license_status(buf);
		return 0;
	case ZKFC_IOC_GET_SYS_SECURITY:
		zkfc_integrity_sys(buf);
		return 0;
	case ZKFC_IOC_GET_USER_SECURITY:
		who->session_id = s->id;
		who->session_ops = atomic64_read(&s->ops);
		memcpy(buf, who, sizeof(*who));
		return 0;
	case ZKFC_IOC_GET_DEV_SECURITY:
		zkfc_integrity_dev(buf);
		return 0;
	case ZKFC_IOC_GET_CAPABILITIES:
		zkfc_capabilities(buf);
		return 0;
	case ZKFC_IOC_INSTALL_LICENSE:
		return zkfc_license_install(buf);
	case ZKFC_IOC_INSTALL_CRL:
		return zkfc_license_install_crl(buf);
	case ZKFC_IOC_TASK_BOOST:
		return zkfc_task_boost(buf);
	case ZKFC_IOC_CPUFREQ_QOS:
		return zkfc_cpufreq_qos(buf);
	case ZKFC_IOC_INPUT_BOOST:
		return zkfc_input_boost_config(buf);
	case ZKFC_IOC_THERMAL_GUARD:
		return zkfc_thermal_guard_config(buf);
	case ZKFC_IOC_THERMAL_READ:
		return zkfc_thermal_read(buf);
	case ZKFC_IOC_PERF_STATUS: {
		struct zkfc_perf_status *st = buf;

		zkfc_input_boost_status(st);
		zkfc_thermal_status(st);
		st->boosted_tasks = zkfc_task_boost_count(&st->inherit_groups);
		st->cpufreq_requests = zkfc_cpufreq_request_count();
		return 0;
	}
	case ZKFC_IOC_PERF_RESET: {
		struct zkfc_input_boost off = { 0 };

		zkfc_input_boost_config(&off);
		zkfc_task_boost_reset_all();
		zkfc_cpufreq_reset_all();
		zkfc_i("all performance requests reset");
		return 0;
	}
	case ZKFC_IOC_LOG_SET_LEVEL:
		zkfc_log_set_level(*(u32 *)buf);
		return 0;
	case ZKFC_IOC_LOG_READ:
		return zkfc_log_read(buf);
	case ZKFC_IOC_SULOG_READ:
		return zkfc_sulog_read(buf);
	case ZKFC_IOC_POLICY_GET:
		zkfc_policy_get(buf);
		return 0;
	case ZKFC_IOC_POLICY_SET:
		if (!who->cap_sys_admin || who->euid != 0)
			return -EPERM;
		return zkfc_policy_set(buf);
	default:
		return -ENOTTY;
	}
}

static long zkfc_ioctl(struct file *file, unsigned int cmd, unsigned long arg)
{
	struct zkfc_session *s = file->private_data;
	struct zkfc_user_security who;
	void __user *uarg = (void __user *)arg;
	size_t size = _IOC_SIZE(cmd);
	bool mutating;
	u32 cap, feat;
	void *buf = NULL;
	long ret;

	if (_IOC_TYPE(cmd) != ZKFC_IOC_MAGIC)
		return -ENOTTY;
	ret = zkfc_requirements(cmd, &cap, &feat, &mutating);
	if (ret)
		return ret;

	/* Re-check the caller on every call (fd passing, setuid changes). */
	zkfc_policy_caps(&who);
	if ((who.caps & cap) != cap) {
		zkfc_sulog_add(ZKFC_SU_DENIED, -EPERM, _IOC_NR(cmd), NULL);
		zkfc_d("ioctl %#x denied for uid %u (caps %#x need %#x)",
		       _IOC_NR(cmd), who.uid, who.caps, cap);
		return -EPERM;
	}
	if (feat && !zkfc_feature_licensed(feat)) {
		zkfc_d("ioctl %#x needs a valid API token (state %u)",
		       _IOC_NR(cmd), zkfc_license_state());
		return -EKEYREJECTED;
	}

	if (size) {
		buf = kzalloc(size, GFP_KERNEL);
		if (!buf)
			return -ENOMEM;
		if ((_IOC_DIR(cmd) & _IOC_WRITE) && copy_from_user(buf, uarg, size)) {
			ret = -EFAULT;
			goto out;
		}
	}

	ret = zkfc_dispatch(s, cmd, buf, &who);
	atomic64_inc(&s->ops);

	if ((_IOC_DIR(cmd) & _IOC_READ) && size && copy_to_user(uarg, buf, size))
		ret = ret ?: -EFAULT;
	if (mutating)
		zkfc_sulog_add(cmd == ZKFC_IOC_INSTALL_LICENSE || cmd == ZKFC_IOC_INSTALL_CRL ?
			       ZKFC_SU_LICENSE : ZKFC_SU_PRIV_OP,
			       (int)ret, _IOC_NR(cmd), NULL);
out:
	kfree(buf);
	return ret;
}

static int zkfc_open(struct inode *inode, struct file *file)
{
	struct zkfc_user_security who;
	struct zkfc_session *s;

	zkfc_policy_caps(&who);
	if (!who.caps) {
		zkfc_sulog_add(ZKFC_SU_DENIED, -EPERM, 0, "open");
		return -EPERM;
	}
	s = kzalloc(sizeof(*s), GFP_KERNEL);
	if (!s)
		return -ENOMEM;
	s->id = atomic64_inc_return(&zkfc_session_seq);
	atomic64_set(&s->ops, 0);
	file->private_data = s;
	zkfc_v("session %llu opened by uid %u", s->id, who.uid);
	return nonseekable_open(inode, file);
}

static int zkfc_release(struct inode *inode, struct file *file)
{
	struct zkfc_session *s = file->private_data;

	if (s)
		zkfc_v("session %llu closed after %lld ops", s->id,
		       (long long)atomic64_read(&s->ops));
	kfree(s);
	return 0;
}

static const struct file_operations zkfc_fops = {
	.owner = THIS_MODULE,
	.open = zkfc_open,
	.release = zkfc_release,
	.unlocked_ioctl = zkfc_ioctl,
	.compat_ioctl = compat_ptr_ioctl,
#if LINUX_VERSION_CODE < KERNEL_VERSION(6, 12, 0)
	.llseek = no_llseek,
#endif
};

static struct miscdevice zkfc_misc = {
	.minor = MISC_DYNAMIC_MINOR,
	.name = ZKFC_DEVICE_NAME,
	.fops = &zkfc_fops,
	.mode = 0600,
};

static void zkfc_abi_checks(void)
{
	/* The ABI must be identical for 64-bit and compat callers. */
	BUILD_BUG_ON(sizeof(struct zkfc_license_payload) != 136);
	BUILD_BUG_ON(sizeof(struct zkfc_license_token) != 200);
	BUILD_BUG_ON(sizeof(struct zkfc_crl) != 600);
	BUILD_BUG_ON(sizeof(struct zkfc_version_info) != 160);
	BUILD_BUG_ON(sizeof(struct zkfc_capability_info) != 168);
	BUILD_BUG_ON(sizeof(struct zkfc_license_status) != 288);
	BUILD_BUG_ON(sizeof(struct zkfc_sys_security) != 56);
	BUILD_BUG_ON(sizeof(struct zkfc_user_security) != 48);
	BUILD_BUG_ON(sizeof(struct zkfc_dev_security) != 232);
	BUILD_BUG_ON(sizeof(struct zkfc_task_boost) != 24);
	BUILD_BUG_ON(sizeof(struct zkfc_cpufreq_qos) != 16);
	BUILD_BUG_ON(sizeof(struct zkfc_input_boost) != 80);
	BUILD_BUG_ON(sizeof(struct zkfc_thermal_guard) != 152);
	BUILD_BUG_ON(sizeof(struct zkfc_thermal_read) != 40);
	BUILD_BUG_ON(sizeof(struct zkfc_perf_status) != 48);
	BUILD_BUG_ON(sizeof(struct zkfc_log_record) != 128);
	BUILD_BUG_ON(sizeof(struct zkfc_log_read) != 2072);
	BUILD_BUG_ON(sizeof(struct zkfc_sulog_record) != 120);
	BUILD_BUG_ON(sizeof(struct zkfc_sulog_read) != 1944);
	BUILD_BUG_ON(sizeof(struct zkfc_policy_table) != 520);
}

static int __init zkfc_init(void)
{
	int ret;

	zkfc_abi_checks();
	zkfc_log_init();
	zkfc_sulog_init();

	ret = zkfc_policy_init();
	if (ret)
		return ret;
	zkfc_license_init();
	zkfc_integrity_init();

	ret = zkfc_cpufreq_init();
	if (ret)
		goto err_policy;
	zkfc_task_boost_init();
	zkfc_input_boost_ok = !zkfc_input_boost_init();
	zkfc_thermal_init();
	zkfc_hooks_init();
	zkfc_notify_init();	/* reboot/panic fail-safe */

	ret = misc_register(&zkfc_misc);
	if (ret) {
		zkfc_e("misc_register failed: %d", ret);
		goto err_perf;
	}

	zkfc_i("ZKFC API %u.%u.%u on %s (%s), license state %u",
	       ZKFC_API_MAJOR, ZKFC_API_MINOR, ZKFC_API_PATCH, zkfc_arch_name(),
	       zkfc_kernel_type() == ZKFC_KERNEL_GKI ? "GKI" : "non-GKI",
	       zkfc_license_state());
	return 0;

err_perf:
	zkfc_notify_exit();
	zkfc_hooks_exit();
	zkfc_thermal_exit();
	if (zkfc_input_boost_ok)
		zkfc_input_boost_exit();
	zkfc_task_boost_exit();
	zkfc_cpufreq_exit();
err_policy:
	zkfc_license_exit();
	zkfc_policy_exit();
	return ret;
}

static void __exit zkfc_exit(void)
{
	misc_deregister(&zkfc_misc);
	zkfc_notify_exit();
	zkfc_hooks_exit();
	zkfc_thermal_exit();
	if (zkfc_input_boost_ok)
		zkfc_input_boost_exit();
	zkfc_task_boost_exit();
	zkfc_cpufreq_exit();
	zkfc_license_exit();
	zkfc_policy_exit();
	zkfc_sulog_exit();
	zkfc_log_exit();
}

module_init(zkfc_init);
module_exit(zkfc_exit);

MODULE_LICENSE("GPL");
MODULE_AUTHOR("FebriCahyaa");
MODULE_DESCRIPTION("Zairenkai Kernel Framework Core (ZKFC)");
MODULE_VERSION("1.0.0");
