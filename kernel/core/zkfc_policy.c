// SPDX-License-Identifier: GPL-2.0-only
/*
 * ZKFC access policy.
 *
 * Every ioctl requires one ZKFC_CAP_* capability. Capabilities are granted
 * by policy entries that match the caller's UID, primary GID or one of its
 * supplementary groups. Deny entries win over allow entries. Built-in
 * entries cannot be removed, so root can never lock itself out:
 *
 *   UID 0     (root)    -> ZKFC_CAP_ALL                       (builtin)
 *   UID 1000  (system)  -> READ_INFO                          (builtin)
 *
 * In addition, every capability except READ_INFO requires CAP_SYS_ADMIN in
 * the initial user namespace, no matter what the policy says.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#include <linux/capability.h>
#include <linux/cred.h>
#include <linux/kernel.h>
#include <linux/mutex.h>
#include <linux/rcupdate.h>
#include <linux/slab.h>
#include <linux/string.h>
#include <linux/uidgid.h>

#include "../zkfc.h"

#define ZKFC_AID_ROOT	0
#define ZKFC_AID_SYSTEM	1000

struct zkfc_policy {
	struct rcu_head rcu;
	u32 count;
	struct zkfc_policy_entry e[ZKFC_POLICY_MAX];
};

static struct zkfc_policy __rcu *zkfc_pol;
static DEFINE_MUTEX(zkfc_pol_mutex);

static const struct zkfc_policy_entry zkfc_builtin[] = {
	{ ZKFC_POLICY_UID, ZKFC_AID_ROOT, ZKFC_CAP_ALL, ZKFC_POLICY_F_BUILTIN },
	{ ZKFC_POLICY_UID, ZKFC_AID_SYSTEM, ZKFC_CAP_READ_INFO, ZKFC_POLICY_F_BUILTIN },
};

int zkfc_policy_init(void)
{
	struct zkfc_policy *p = kzalloc(sizeof(*p), GFP_KERNEL);

	if (!p)
		return -ENOMEM;
	memcpy(p->e, zkfc_builtin, sizeof(zkfc_builtin));
	p->count = ARRAY_SIZE(zkfc_builtin);
	rcu_assign_pointer(zkfc_pol, p);
	return 0;
}

static bool zkfc_entry_matches(const struct zkfc_policy_entry *e,
			       const struct cred *cred)
{
	switch (e->type) {
	case ZKFC_POLICY_UID:
		return from_kuid(&init_user_ns, cred->uid) == e->id ||
		       from_kuid(&init_user_ns, cred->euid) == e->id;
	case ZKFC_POLICY_GID:
		return from_kgid(&init_user_ns, cred->gid) == e->id ||
		       from_kgid(&init_user_ns, cred->egid) == e->id;
	case ZKFC_POLICY_GROUP: {
		kgid_t kg = make_kgid(&init_user_ns, e->id);

		/* in_group_p() checks current's fsgid and supplementary groups. */
		return gid_valid(kg) && in_group_p(kg);
	}
	default:
		return false;
	}
}

u32 zkfc_policy_caps(struct zkfc_user_security *who)
{
	const struct cred *cred = current_cred();
	struct zkfc_policy *p;
	u32 allow = 0, deny = 0, i;

	who->uid = from_kuid(&init_user_ns, cred->uid);
	who->euid = from_kuid(&init_user_ns, cred->euid);
	who->gid = from_kgid(&init_user_ns, cred->gid);
	who->egid = from_kgid(&init_user_ns, cred->egid);
	who->cap_sys_admin = capable(CAP_SYS_ADMIN) ? 1 : 0;
	who->matched_type = 0;
	who->matched_id = 0;

	rcu_read_lock();
	p = rcu_dereference(zkfc_pol);
	for (i = 0; p && i < p->count; i++) {
		const struct zkfc_policy_entry *e = &p->e[i];

		if (!zkfc_entry_matches(e, cred))
			continue;
		if (e->flags & ZKFC_POLICY_F_DENY) {
			deny |= e->caps;
		} else {
			if (!who->matched_type) {
				who->matched_type = e->type;
				who->matched_id = e->id;
			}
			allow |= e->caps;
		}
	}
	rcu_read_unlock();

	allow &= ~deny;
	/* Expand legacy semantic groups into granular capabilities without
	 * invalidating policies written for API 1.0. */
	if (allow & ZKFC_CAP_READ_INFO)
		allow |= ZKFC_CAP_READ_DEVICE | ZKFC_CAP_READ_KERNEL |
			  ZKFC_CAP_READ_THERMAL | ZKFC_CAP_READ_PERF;
	if (allow & ZKFC_CAP_TUNE_PERF)
		allow |= ZKFC_CAP_TUNE_CPU | ZKFC_CAP_TUNE_GPU |
			  ZKFC_CAP_TUNE_MEMORY | ZKFC_CAP_TUNE_IO;
	if (!who->cap_sys_admin)
		allow &= ZKFC_CAP_READ_INFO;
	who->caps = allow;
	return allow;
}

/* Built-in root/system entries are immutable and implicit in POLICY_GET.
 * The returned table contains only mutable custom entries so its result can
 * be fed back into POLICY_SET without duplicating the built-ins. */
void zkfc_policy_get(struct zkfc_policy_table *tbl)
{
	struct zkfc_policy *p;

	memset(tbl, 0, sizeof(*tbl));
	rcu_read_lock();
	p = rcu_dereference(zkfc_pol);
	if (p) {
		const u32 builtin = ARRAY_SIZE(zkfc_builtin);

		tbl->count = p->count > builtin ? p->count - builtin : 0;
		if (tbl->count)
			memcpy(tbl->entries, &p->e[builtin],
			       sizeof(p->e[0]) * tbl->count);
	}
	rcu_read_unlock();
}

static int zkfc_policy_validate(const struct zkfc_policy_table *tbl)
{
	u32 i;

	if (tbl->count > ZKFC_POLICY_MAX - ARRAY_SIZE(zkfc_builtin))
		return -E2BIG;
	for (i = 0; i < tbl->count; i++) {
		const struct zkfc_policy_entry *e = &tbl->entries[i];

		if (e->type < ZKFC_POLICY_UID || e->type > ZKFC_POLICY_GROUP)
			return -EINVAL;
		if (e->caps & ~ZKFC_CAP_ALL)
			return -EINVAL;
		if (e->flags & ~(ZKFC_POLICY_F_DENY))
			return -EINVAL;
		/* Root must stay administrator: no deny entries for uid 0. */
		if (e->type == ZKFC_POLICY_UID && e->id == ZKFC_AID_ROOT)
			return -EPERM;
		/* Only root may hold ADMIN. */
		if (!(e->flags & ZKFC_POLICY_F_DENY) && (e->caps & ZKFC_CAP_ADMIN))
			return -EPERM;
	}
	return 0;
}

int zkfc_policy_set(const struct zkfc_policy_table *tbl)
{
	struct zkfc_policy *np, *old;
	int ret;
	u32 i;

	ret = zkfc_policy_validate(tbl);
	if (ret)
		return ret;

	np = kzalloc(sizeof(*np), GFP_KERNEL);
	if (!np)
		return -ENOMEM;
	memcpy(np->e, zkfc_builtin, sizeof(zkfc_builtin));
	np->count = ARRAY_SIZE(zkfc_builtin);
	for (i = 0; i < tbl->count; i++) {
		np->e[np->count] = tbl->entries[i];
		np->e[np->count].flags &= ZKFC_POLICY_F_DENY;
		np->count++;
	}

	mutex_lock(&zkfc_pol_mutex);
	old = rcu_dereference_protected(zkfc_pol, lockdep_is_held(&zkfc_pol_mutex));
	rcu_assign_pointer(zkfc_pol, np);
	mutex_unlock(&zkfc_pol_mutex);
	if (old)
		kfree_rcu(old, rcu);

	zkfc_i("policy updated: %u custom entries", tbl->count);
	return 0;
}

void zkfc_policy_exit(void)
{
	struct zkfc_policy *old;

	mutex_lock(&zkfc_pol_mutex);
	old = rcu_dereference_protected(zkfc_pol, lockdep_is_held(&zkfc_pol_mutex));
	RCU_INIT_POINTER(zkfc_pol, NULL);
	mutex_unlock(&zkfc_pol_mutex);
	synchronize_rcu();
	kfree(old);
}
