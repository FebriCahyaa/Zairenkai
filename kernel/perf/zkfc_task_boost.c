// SPDX-License-Identifier: GPL-2.0-only
/*
 * ZKFC task boost: per-process utilization clamping (uclamp) with optional
 * inheritance to threads created later (game engines spawn worker threads
 * long after start-up).
 *
 * Task identity is tracked by struct pid references rather than numeric PIDs,
 * so PID reuse cannot transfer an old boost policy to a new process. Older
 * kernels without uclamp fail closed instead of corrupting the caller's nice
 * value as a fake uclamp substitute.
 *
 * Boosts are suspended while the thermal guard is tripped and re-applied
 * when the device cools down.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#include <linux/kernel.h>
#include <linux/kfifo.h>
#include <linux/list.h>
#include <linux/mutex.h>
#include <linux/pid.h>
#include <linux/rcupdate.h>
#include <linux/sched.h>
#include <linux/sched/signal.h>
#include <linux/sched/task.h>
#include <linux/slab.h>
#include <linux/spinlock.h>
#include <linux/workqueue.h>
#include <uapi/linux/sched/types.h>

#include "../zkfc.h"

#define ZKFC_TB_MAX_GROUPS	16
#define ZKFC_TB_MAX_THREADS	512

struct zkfc_tb_group {
	struct list_head node;
	pid_t pid;
	struct pid *target_pid;
	struct pid *tgid_pid;
	u32 umin;
	u32 umax;
	u32 flags;
	u32 threads;
};

static LIST_HEAD(zkfc_tb_groups);
static DEFINE_SPINLOCK(zkfc_tb_lock);
static DEFINE_MUTEX(zkfc_tb_mutex);
static bool zkfc_tb_suspended;

static DEFINE_SPINLOCK(zkfc_tb_fifo_lock);
static DECLARE_KFIFO(zkfc_tb_fifo, struct pid *, 128);
static void zkfc_tb_work_fn(struct work_struct *w);
static DECLARE_WORK(zkfc_tb_work, zkfc_tb_work_fn);

bool zkfc_uclamp_available(void)
{
#ifdef ZKFC_HAVE_UCLAMP
	return true;
#else
	return false;
#endif
}

static int zkfc_tb_apply_one(struct task_struct *p, u32 umin, u32 umax, bool reset)
{
#ifdef ZKFC_HAVE_UCLAMP
	struct sched_attr attr = {
		.size = sizeof(attr),
		.sched_policy = p->policy,
		.sched_flags = SCHED_FLAG_KEEP_ALL | SCHED_FLAG_UTIL_CLAMP,
		.sched_util_min = reset ? 0 : umin,
		.sched_util_max = reset ? SCHED_CAPACITY_SCALE : umax,
	};

	return sched_setattr_nocheck(p, &attr);
#else
	/* Never emulate uclamp with nice: the original priority is not reliably
	 * recoverable once multiple tasks have been modified. */
	(void)p;
	(void)umin;
	(void)umax;
	(void)reset;
	return -EOPNOTSUPP;
#endif
}

/*
 * Apply to @target_pid (and its thread group if @threads). The caller owns a
 * reference to @target_pid. Every task is pinned before sched_setattr runs,
 * because sched_setattr may sleep and cannot run under RCU.
 */
static int zkfc_tb_apply_pid(struct pid *target_pid, bool threads, u32 umin,
			     u32 umax, bool reset, u32 *applied,
			     struct pid **tgid_pid_out)
{
	struct task_struct **list, *task, *t;
	struct pid *tgid_pid = NULL;
	u32 n = 0, i, ok = 0;
	int ret = 0;

	list = kmalloc_array(ZKFC_TB_MAX_THREADS, sizeof(*list), GFP_KERNEL);
	if (!list)
		return -ENOMEM;

	rcu_read_lock();
	task = pid_task(target_pid, PIDTYPE_PID);
	if (!task) {
		rcu_read_unlock();
		kfree(list);
		return -ESRCH;
	}
	tgid_pid = task_tgid(task);
	get_pid(tgid_pid);
	get_task_struct(task);
	list[n++] = task;
	if (threads) {
		for_each_thread(task, t) {
			if (n >= ZKFC_TB_MAX_THREADS)
				break;
			get_task_struct(t);
			list[n++] = t;
		}
	}
	rcu_read_unlock();

	for (i = 0; i < n; i++) {
		int r = zkfc_tb_apply_one(list[i], umin, umax, reset);

		if (!r)
			ok++;
		else if (!ret)
			ret = r;
		put_task_struct(list[i]);
	}
	kfree(list);

	if (applied)
		*applied = ok;
	if (tgid_pid_out)
		*tgid_pid_out = tgid_pid;
	else
		put_pid(tgid_pid);
	return ok ? 0 : ret;
}

static struct zkfc_tb_group *zkfc_tb_find(struct pid *target_pid)
{
	struct zkfc_tb_group *g;

	list_for_each_entry(g, &zkfc_tb_groups, node)
		if (g->target_pid == target_pid)
			return g;
	return NULL;
}

static u32 zkfc_tb_group_count(void)
{
	struct zkfc_tb_group *g;
	u32 n = 0;

	list_for_each_entry(g, &zkfc_tb_groups, node)
		n++;
	return n;
}

static void zkfc_tb_free_group(struct zkfc_tb_group *g)
{
	put_pid(g->target_pid);
	put_pid(g->tgid_pid);
	kfree(g);
}

/* Remove entries whose target process no longer exists. */
static void zkfc_tb_prune_dead(void)
{
	struct zkfc_tb_group *g, *tmp;
	LIST_HEAD(dead);

	rcu_read_lock();
	spin_lock_irq(&zkfc_tb_lock);
	list_for_each_entry_safe(g, tmp, &zkfc_tb_groups, node) {
		if (!pid_task(g->target_pid, PIDTYPE_PID))
			list_move_tail(&g->node, &dead);
	}
	spin_unlock_irq(&zkfc_tb_lock);
	rcu_read_unlock();

	list_for_each_entry_safe(g, tmp, &dead, node) {
		list_del(&g->node);
		zkfc_tb_free_group(g);
	}
}

int zkfc_task_boost(struct zkfc_task_boost *tb)
{
	bool reset = tb->flags & ZKFC_TB_RESET;
	bool threads = tb->flags & (ZKFC_TB_THREADS | ZKFC_TB_INHERIT);
	struct zkfc_tb_group *g, *spare = NULL, *dead = NULL;
	struct pid *target_pid = NULL, *tgid_pid = NULL;
	u32 applied = 0;
	int ret;

	if (tb->pid <= 0 ||
	    tb->flags & ~(ZKFC_TB_THREADS | ZKFC_TB_INHERIT | ZKFC_TB_RESET))
		return -EINVAL;
	if (!reset && !zkfc_uclamp_available())
		return -EOPNOTSUPP;
	if (!reset && (tb->uclamp_min > ZKFC_UCLAMP_SCALE ||
		       tb->uclamp_max > ZKFC_UCLAMP_SCALE ||
		       tb->uclamp_min > tb->uclamp_max))
		return -EINVAL;
	if (!reset) {
		spare = kzalloc(sizeof(*spare), GFP_KERNEL);
		if (!spare)
			return -ENOMEM;
	}

	target_pid = find_get_pid(tb->pid);
	if (!target_pid) {
		kfree(spare);
		return reset ? 0 : -ESRCH;
	}

	mutex_lock(&zkfc_tb_mutex);
	zkfc_tb_prune_dead();
	g = zkfc_tb_find(target_pid);

	if (reset) {
		/* Never reset an unrelated task that happens to reuse the numeric PID. */
		if (!g) {
			ret = 0;
			goto unlock;
		}
	} else {
		if (zkfc_tb_suspended) {
			ret = -EBUSY;
			goto unlock;
		}
		if (!g && zkfc_tb_group_count() >= ZKFC_TB_MAX_GROUPS) {
			ret = -ENOSPC;
			goto unlock;
		}
	}

	ret = zkfc_tb_apply_pid(target_pid, threads, tb->uclamp_min,
				tb->uclamp_max, reset, &applied, &tgid_pid);

	spin_lock_irq(&zkfc_tb_lock);
	g = zkfc_tb_find(target_pid);
	if (reset) {
		if (g) {
			list_del(&g->node);
			dead = g;
		}
		if (ret == -ESRCH)
			ret = 0;
	} else if (!ret) {
		if (!g) {
			g = spare;
			spare = NULL;
			g->pid = tb->pid;
			g->target_pid = get_pid(target_pid);
			g->tgid_pid = tgid_pid;
			tgid_pid = NULL;
			list_add_tail(&g->node, &zkfc_tb_groups);
		}
		g->pid = tb->pid;
		g->umin = tb->uclamp_min;
		g->umax = tb->uclamp_max;
		g->flags = tb->flags;
		g->threads = applied;
	}
	spin_unlock_irq(&zkfc_tb_lock);

unlock:
	mutex_unlock(&zkfc_tb_mutex);
	put_pid(target_pid);
	if (tgid_pid)
		put_pid(tgid_pid);
	kfree(spare);
	if (dead)
		zkfc_tb_free_group(dead);

	tb->applied = applied;
	if (ret)
		zkfc_w("task boost pid %d failed: %d", tb->pid, ret);
	else
		zkfc_d("task boost pid %d min %u max %u flags %#x (%u tasks)",
		       tb->pid, tb->uclamp_min, tb->uclamp_max, tb->flags, applied);
	return ret;
}

/* Re-apply (resume) or neutralise (suspend) every group; groups are kept. */
static void zkfc_tb_reapply_all(bool reset)
{
	struct {
		struct pid *target_pid;
		bool threads;
		u32 umin;
		u32 umax;
	} snap[ZKFC_TB_MAX_GROUPS];
	struct zkfc_tb_group *g;
	u32 n = 0, i;

	rcu_read_lock();
	spin_lock_irq(&zkfc_tb_lock);
	list_for_each_entry(g, &zkfc_tb_groups, node) {
		if (n >= ZKFC_TB_MAX_GROUPS)
			break;
		snap[n].target_pid = get_pid(g->target_pid);
		snap[n].threads = !!(g->flags & (ZKFC_TB_THREADS | ZKFC_TB_INHERIT));
		snap[n].umin = g->umin;
		snap[n].umax = g->umax;
		n++;
	}
	spin_unlock_irq(&zkfc_tb_lock);
	rcu_read_unlock();

	for (i = 0; i < n; i++) {
		zkfc_tb_apply_pid(snap[i].target_pid, snap[i].threads,
				  snap[i].umin, snap[i].umax, reset, NULL, NULL);
		put_pid(snap[i].target_pid);
	}
}

void zkfc_task_boost_suspend(bool suspend)
{
	mutex_lock(&zkfc_tb_mutex);
	zkfc_tb_prune_dead();
	if (zkfc_tb_suspended != suspend) {
		zkfc_tb_suspended = suspend;
		zkfc_tb_reapply_all(suspend);
		zkfc_i("task boosts %s", suspend ? "suspended (thermal)" : "resumed");
	}
	mutex_unlock(&zkfc_tb_mutex);
}

void zkfc_task_boost_reset_all(void)
{
	struct zkfc_tb_group *g, *tmp;
	LIST_HEAD(dead);

	mutex_lock(&zkfc_tb_mutex);
	zkfc_tb_reapply_all(true);
	spin_lock_irq(&zkfc_tb_lock);
	list_splice_init(&zkfc_tb_groups, &dead);
	spin_unlock_irq(&zkfc_tb_lock);
	mutex_unlock(&zkfc_tb_mutex);

	list_for_each_entry_safe(g, tmp, &dead, node) {
		list_del(&g->node);
		zkfc_tb_free_group(g);
	}
}

u32 zkfc_task_boost_count(u32 *inherit_groups)
{
	struct zkfc_tb_group *g;
	u32 threads = 0, inherit = 0;

	mutex_lock(&zkfc_tb_mutex);
	zkfc_tb_prune_dead();
	spin_lock_irq(&zkfc_tb_lock);
	list_for_each_entry(g, &zkfc_tb_groups, node) {
		threads += g->threads;
		if (g->flags & ZKFC_TB_INHERIT)
			inherit++;
	}
	spin_unlock_irq(&zkfc_tb_lock);
	mutex_unlock(&zkfc_tb_mutex);
	if (inherit_groups)
		*inherit_groups = inherit;
	return threads;
}

/*
 * Hook context (kprobe pre-handler or patched fork path): may be atomic, so
 * only queue a referenced struct pid for a work item; no numeric PID is used
 * as process identity anywhere in the inheritance path.
 */
void zkfc_task_boost_on_new_task(struct task_struct *p)
{
	struct zkfc_tb_group *g;
	struct pid *tgid_pid;
	struct pid *target_pid = NULL;
	unsigned long flags;
	bool match = false;

	if (!zkfc_feature_licensed(ZKFC_FEAT_BOOST_INHERIT) || !p)
		return;

	rcu_read_lock();
	tgid_pid = task_tgid(p);
	spin_lock_irqsave(&zkfc_tb_lock, flags);
	list_for_each_entry(g, &zkfc_tb_groups, node) {
		if ((g->flags & ZKFC_TB_INHERIT) && g->tgid_pid == tgid_pid) {
			match = !zkfc_tb_suspended;
			break;
		}
	}
	if (match) {
		target_pid = get_task_pid(p, PIDTYPE_PID);
		if (target_pid)
			match = kfifo_in_spinlocked(&zkfc_tb_fifo, &target_pid, 1,
						   &zkfc_tb_fifo_lock) == 1;
	}
	spin_unlock_irqrestore(&zkfc_tb_lock, flags);
	rcu_read_unlock();

	if (match)
		schedule_work(&zkfc_tb_work);
	else if (target_pid)
		put_pid(target_pid);
}

static void zkfc_tb_work_fn(struct work_struct *w)
{
	struct zkfc_tb_group *g;
	struct pid *target_pid, *tgid_pid;
	u32 umin = 0, umax = 0;
	bool found;

	while (kfifo_out_spinlocked(&zkfc_tb_fifo, &target_pid, 1,
				    &zkfc_tb_fifo_lock)) {
		struct task_struct *t;

		rcu_read_lock();
		t = pid_task(target_pid, PIDTYPE_PID);
		if (t)
			get_task_struct(t);
		rcu_read_unlock();
		if (!t) {
			put_pid(target_pid);
			continue;
		}

		rcu_read_lock();
		tgid_pid = task_tgid(t);
		found = false;
		spin_lock_irq(&zkfc_tb_lock);
		list_for_each_entry(g, &zkfc_tb_groups, node) {
			if ((g->flags & ZKFC_TB_INHERIT) && g->tgid_pid == tgid_pid) {
				umin = g->umin;
				umax = g->umax;
				found = !zkfc_tb_suspended;
				break;
			}
		}
		spin_unlock_irq(&zkfc_tb_lock);
		rcu_read_unlock();

		if (found)
			zkfc_tb_apply_one(t, umin, umax, false);
		put_task_struct(t);
		put_pid(target_pid);
	}
}

int zkfc_task_boost_init(void)
{
	INIT_KFIFO(zkfc_tb_fifo);
	return 0;
}

void zkfc_task_boost_exit(void)
{
	struct pid *target_pid;

	cancel_work_sync(&zkfc_tb_work);
	zkfc_task_boost_reset_all();
	while (kfifo_out_spinlocked(&zkfc_tb_fifo, &target_pid, 1,
				    &zkfc_tb_fifo_lock))
		put_pid(target_pid);
}
