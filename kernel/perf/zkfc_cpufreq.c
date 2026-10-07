// SPDX-License-Identifier: GPL-2.0-only
/*
 * ZKFC cpufreq QoS.
 *
 * Two independent request sets per cpufreq policy:
 *  - "user" min/max requested by the manager (ZKFC_IOC_CPUFREQ_QOS);
 *  - "boost" floor raised briefly by the input booster.
 *
 * On 5.4+ both are freq_qos requests, which the cpufreq core aggregates with
 * thermal and vendor limits, so ZKFC can never push a CPU above what the
 * thermal framework allows. Older kernels use the policy notifier.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#include <linux/cpu.h>
#include <linux/cpufreq.h>
#include <linux/kernel.h>
#include <linux/mutex.h>
#include <linux/slab.h>

#include "../zkfc.h"

struct zkfc_cq {
	bool active;
	unsigned int first_cpu;
	unsigned int user_min;
	unsigned int user_max;
	unsigned int boost_min;
#ifdef ZKFC_HAVE_FREQ_QOS
	struct cpufreq_policy *policy;	/* reference held while active */
	struct freq_qos_request req_min;
	struct freq_qos_request req_max;
	struct freq_qos_request req_boost;
#endif
};

static struct zkfc_cq *zkfc_cq;		/* indexed by policy->cpu */
static DEFINE_MUTEX(zkfc_cq_mutex);

#ifdef ZKFC_HAVE_FREQ_QOS

static struct zkfc_cq *zkfc_cq_get(unsigned int cpu)
{
	struct cpufreq_policy *policy;
	struct zkfc_cq *c;
	int ret;

	policy = cpufreq_cpu_get(cpu);
	if (!policy)
		return NULL;
	c = &zkfc_cq[policy->cpu];
	if (c->active) {
		cpufreq_cpu_put(policy);
		return c;
	}

	ret = freq_qos_add_request(&policy->constraints, &c->req_min,
				   FREQ_QOS_MIN, FREQ_QOS_MIN_DEFAULT_VALUE);
	if (ret < 0)
		goto err;
	ret = freq_qos_add_request(&policy->constraints, &c->req_max,
				   FREQ_QOS_MAX, FREQ_QOS_MAX_DEFAULT_VALUE);
	if (ret < 0)
		goto err_min;
	ret = freq_qos_add_request(&policy->constraints, &c->req_boost,
				   FREQ_QOS_MIN, FREQ_QOS_MIN_DEFAULT_VALUE);
	if (ret < 0)
		goto err_max;

	c->policy = policy;	/* keep the reference */
	c->first_cpu = policy->cpu;
	c->active = true;
	return c;

err_max:
	freq_qos_remove_request(&c->req_max);
err_min:
	freq_qos_remove_request(&c->req_min);
err:
	cpufreq_cpu_put(policy);
	zkfc_w("cpufreq qos init for cpu%u failed: %d", cpu, ret);
	return NULL;
}

static void zkfc_cq_release(struct zkfc_cq *c)
{
	if (!c->active)
		return;
	freq_qos_remove_request(&c->req_boost);
	freq_qos_remove_request(&c->req_max);
	freq_qos_remove_request(&c->req_min);
	cpufreq_cpu_put(c->policy);
	c->policy = NULL;
	c->active = false;
	c->user_min = c->user_max = c->boost_min = 0;
}

static int zkfc_cq_update(struct zkfc_cq *c)
{
	int r1, r2, r3;

	r1 = freq_qos_update_request(&c->req_min,
				     c->user_min ?: FREQ_QOS_MIN_DEFAULT_VALUE);
	r2 = freq_qos_update_request(&c->req_max,
				     c->user_max ?: FREQ_QOS_MAX_DEFAULT_VALUE);
	r3 = freq_qos_update_request(&c->req_boost,
				     c->boost_min ?: FREQ_QOS_MIN_DEFAULT_VALUE);
	if (r1 < 0)
		return r1;
	if (r2 < 0)
		return r2;
	return r3 < 0 ? r3 : 0;
}

#else /* !ZKFC_HAVE_FREQ_QOS: policy notifier (kernels < 5.4) */

static int zkfc_cq_notifier(struct notifier_block *nb, unsigned long val,
			    void *data)
{
	struct cpufreq_policy *policy = data;
	struct zkfc_cq *c;
	unsigned int min, max;

	if (val != CPUFREQ_ADJUST || !zkfc_cq)
		return NOTIFY_DONE;
	c = &zkfc_cq[policy->cpu];
	if (!c->active)
		return NOTIFY_DONE;

	min = max(c->user_min, c->boost_min);
	max = c->user_max ?: policy->cpuinfo.max_freq;
	if (min)
		min = clamp(min, policy->cpuinfo.min_freq, policy->cpuinfo.max_freq);
	/* Never raise the ceiling another driver (thermal) has set. */
	cpufreq_verify_within_limits(policy, min ?: policy->min, max);
	return NOTIFY_OK;
}

static struct notifier_block zkfc_cq_nb = {
	.notifier_call = zkfc_cq_notifier,
};

static struct zkfc_cq *zkfc_cq_get(unsigned int cpu)
{
	struct cpufreq_policy *policy = cpufreq_cpu_get(cpu);
	struct zkfc_cq *c;

	if (!policy)
		return NULL;
	c = &zkfc_cq[policy->cpu];
	c->first_cpu = policy->cpu;
	c->active = true;
	cpufreq_cpu_put(policy);
	return c;
}

static void zkfc_cq_release(struct zkfc_cq *c)
{
	unsigned int cpu = c->first_cpu;

	if (!c->active)
		return;
	c->active = false;
	c->user_min = c->user_max = c->boost_min = 0;
	cpufreq_update_policy(cpu);
}

static int zkfc_cq_update(struct zkfc_cq *c)
{
	cpufreq_update_policy(c->first_cpu);
	return 0;
}

#endif /* ZKFC_HAVE_FREQ_QOS */

int zkfc_cpufreq_qos(const struct zkfc_cpufreq_qos *q)
{
	struct zkfc_cq *c;
	unsigned int old_min, old_max;
	int ret;

	if (q->cpu >= nr_cpu_ids || !cpu_possible(q->cpu) || q->flags & ~ZKFC_CQ_CLEAR)
		return -EINVAL;
	if (q->min_khz && q->max_khz && q->min_khz > q->max_khz)
		return -EINVAL;

	mutex_lock(&zkfc_cq_mutex);
	c = zkfc_cq_get(q->cpu);
	if (!c) {
		mutex_unlock(&zkfc_cq_mutex);
		return -ENODEV;
	}
	old_min = c->user_min;
	old_max = c->user_max;
	if (q->flags & ZKFC_CQ_CLEAR) {
		c->user_min = 0;
		c->user_max = 0;
	} else {
		if (q->min_khz)
			c->user_min = q->min_khz;
		if (q->max_khz)
			c->user_max = q->max_khz;
	}
	ret = zkfc_cq_update(c);
	if (ret) {
		/* Keep the software state aligned with the kernel constraint set when
		 * one of the QoS updates fails after the other one succeeded. */
		c->user_min = old_min;
		c->user_max = old_max;
		if (zkfc_cq_update(c))
			zkfc_w("cpufreq qos rollback failed for cpu%u", q->cpu);
	}
	mutex_unlock(&zkfc_cq_mutex);

	zkfc_d("cpufreq qos cpu%u min %u max %u -> %d", q->cpu,
	       q->min_khz, q->max_khz, ret);
	return ret;
}

int zkfc_cpufreq_set_boost_floor(unsigned int cpu, unsigned int min_khz)
{
	struct zkfc_cq *c;
	int ret;

	if (cpu >= nr_cpu_ids || !cpu_possible(cpu))
		return -EINVAL;
	mutex_lock(&zkfc_cq_mutex);
	c = zkfc_cq_get(cpu);
	if (!c) {
		mutex_unlock(&zkfc_cq_mutex);
		return -ENODEV;
	}
	c->boost_min = min_khz;
	ret = zkfc_cq_update(c);
	mutex_unlock(&zkfc_cq_mutex);
	return ret;
}

void zkfc_cpufreq_clear_boost_floors(void)
{
	unsigned int cpu;

	mutex_lock(&zkfc_cq_mutex);
	for_each_possible_cpu(cpu) {
		struct zkfc_cq *c = &zkfc_cq[cpu];

		if (c->active && c->boost_min) {
			c->boost_min = 0;
			zkfc_cq_update(c);
		}
	}
	mutex_unlock(&zkfc_cq_mutex);
}

void zkfc_cpufreq_reset_all(void)
{
	unsigned int cpu;

	mutex_lock(&zkfc_cq_mutex);
	for_each_possible_cpu(cpu)
		zkfc_cq_release(&zkfc_cq[cpu]);
	mutex_unlock(&zkfc_cq_mutex);
}

u32 zkfc_cpufreq_request_count(void)
{
	unsigned int cpu;
	u32 n = 0;

	mutex_lock(&zkfc_cq_mutex);
	for_each_possible_cpu(cpu) {
		struct zkfc_cq *c = &zkfc_cq[cpu];

		if (c->active && (c->user_min || c->user_max || c->boost_min))
			n++;
	}
	mutex_unlock(&zkfc_cq_mutex);
	return n;
}

int zkfc_cpufreq_init(void)
{
	zkfc_cq = kcalloc(nr_cpu_ids, sizeof(*zkfc_cq), GFP_KERNEL);
	if (!zkfc_cq)
		return -ENOMEM;
#ifndef ZKFC_HAVE_FREQ_QOS
	cpufreq_register_notifier(&zkfc_cq_nb, CPUFREQ_POLICY_NOTIFIER);
#endif
	return 0;
}

void zkfc_cpufreq_exit(void)
{
	if (!zkfc_cq)
		return;
	zkfc_cpufreq_reset_all();
#ifndef ZKFC_HAVE_FREQ_QOS
	cpufreq_unregister_notifier(&zkfc_cq_nb, CPUFREQ_POLICY_NOTIFIER);
#endif
	kfree(zkfc_cq);
	zkfc_cq = NULL;
}
