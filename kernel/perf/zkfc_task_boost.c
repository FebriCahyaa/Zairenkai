// SPDX-License-Identifier: GPL-2.0-only
/*
 * ZKFC task boost: per-process utilization clamping (uclamp) with optional
 * inheritance to threads created later (game engines spawn worker threads
 * long after start-up). Falls back to nice values on kernels without uclamp.
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
#define ZKFC_TB_FALLBACK_NICE	(-8)

struct zkfc_tb_group {
	struct list_head node;
	pid_t pid;
	pid_t tgid;
	u32 umin;
	u32 umax;
	u32 flags;
	u32 threads;
};

static LIST_HEAD(zkfc_tb_groups);
static DEFINE_SPINLOCK(zkfc_tb_lock);	/* protects the list (atomic readers) */
static DEFINE_MUTEX(zkfc_tb_mutex);	/* serialises writers */
static bool zkfc_tb_suspended;

static DEFINE_SPINLOCK(zkfc_tb_fifo_lock);
static DECLARE_KFIFO(zkfc_tb_fifo, pid_t, 128);
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
	set_user_nice(p, reset ? 0 : ZKFC_TB_FALLBACK_NICE);
	return 0;
#endif
}

/*
 * Apply to @pid (and its thread group if @threads). Tasks are pinned first
 * because sched_setattr may sleep and cannot run under rcu_read_lock().
 */
static int zkfc_tb_apply_pid(pid_t pid, bool threads, u32 umin, u32 umax,
			     bool reset, u32 *applied, pid_t *tgid_out)
{
	struct task_struct **list, *task, *t;
	u32 n = 0, i, ok = 0;
	int ret = 0;

	list = kmalloc_array(ZKFC_TB_MAX_THREADS, sizeof(*list), GFP_KERNEL);
	if (!list)
		return -ENOMEM;

	rcu_read_lock();
	task = pid_task(find_vpid(pid), PIDTYPE_PID);
	if (!task) {
		rcu_read_unlock();
		kfree(list);
		return -ESRCH;
	}
	if (tgid_out)
		*tgid_out = task_tgid_nr(task);
	if (threads) {
		for_each_thread(task, t) {
			if (n >= ZKFC_TB_MAX_THREADS)
				break;
			get_task_struct(t);
			list[n++] = t;
		}
	} else {
		get_task_struct(task);
		list[n++] = task;
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
	return ok ? 0 : ret;
}

static struct zkfc_tb_group *zkfc_tb_find(pid_t pid)
{
	struct zkfc_tb_group *g;

	list_for_each_entry(g, &zkfc_tb_groups, node)
		if (g->pid == pid)
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

int zkfc_task_boost(struct zkfc_task_boost *tb)
{
	bool reset = tb->flags & ZKFC_TB_RESET;
	bool threads = tb->flags & (ZKFC_TB_THREADS | ZKFC_TB_INHERIT);
	struct zkfc_tb_group *g, *spare = NULL, *dead = NULL;
	u32 applied = 0;
	pid_t tgid = 0;
	int ret;

	if (tb->pid <= 0 ||
	    tb->flags & ~(ZKFC_TB_THREADS | ZKFC_TB_INHERIT | ZKFC_TB_RESET))
		return -EINVAL;
	if (!reset && (tb->uclamp_min > ZKFC_UCLAMP_SCALE ||
		       tb->uclamp_max > ZKFC_UCLAMP_SCALE ||
		       tb->uclamp_min > tb->uclamp_max))
		return -EINVAL;
	if (!reset) {
		spare = kzalloc(sizeof(*spare), GFP_KERNEL);
		if (!spare)
			return -ENOMEM;
	}

	mutex_lock(&zkfc_tb_mutex);
	if (!reset && zkfc_tb_suspended) {
		ret = -EBUSY;	/* thermal guard tripped */
		goto unlock;
	}
	ret = zkfc_tb_apply_pid(tb->pid, threads, tb->uclamp_min, tb->uclamp_max,
				reset, &applied, &tgid);

	spin_lock_irq(&zkfc_tb_lock);
	g = zkfc_tb_find(tb->pid);
	if (reset) {
		if (g) {
			list_del(&g->node);
			dead = g;
		}
		/* A vanished process is not an error when resetting. */
		if (ret == -ESRCH)
			ret = 0;
	} else if (!ret) {
		if (!g) {
			if (zkfc_tb_group_count() >= ZKFC_TB_MAX_GROUPS) {
				ret = -ENOSPC;
			} else {
				g = spare;
				spare = NULL;
				g->pid = tb->pid;
				list_add_tail(&g->node, &zkfc_tb_groups);
			}
		}
		if (g) {
			g->tgid = tgid;
			g->umin = tb->uclamp_min;
			g->umax = tb->uclamp_max;
			g->flags = tb->flags;
			g->threads = applied;
		}
	}
	spin_unlock_irq(&zkfc_tb_lock);
unlock:
	mutex_unlock(&zkfc_tb_mutex);

	kfree(spare);
	kfree(dead);
	tb->applied = applied;
	if (ret)
		zkfc_w("task boost pid %d failed: %d", tb->pid, ret);
	else
		zkfc_d("task boost pid %d min %u max %u flags %#x (%u threads)",
		       tb->pid, tb->uclamp_min, tb->uclamp_max, tb->flags, applied);
	return ret;
}

/* Re-apply (resume) or neutralise (suspend) every group; groups are kept. */
static void zkfc_tb_reapply_all(bool reset)
{
	struct zkfc_tb_group snap[ZKFC_TB_MAX_GROUPS];
	struct zkfc_tb_group *g;
	u32 n = 0, i;

	spin_lock_irq(&zkfc_tb_lock);
	list_for_each_entry(g, &zkfc_tb_groups, node)
		if (n < ZKFC_TB_MAX_GROUPS)
			snap[n++] = *g;
	spin_unlock_irq(&zkfc_tb_lock);

	for (i = 0; i < n; i++)
		zkfc_tb_apply_pid(snap[i].pid,
				  snap[i].flags & (ZKFC_TB_THREADS | ZKFC_TB_INHERIT),
				  snap[i].umin, snap[i].umax, reset, NULL, NULL);
}

void zkfc_task_boost_suspend(bool suspend)
{
	mutex_lock(&zkfc_tb_mutex);
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
		kfree(g);
	}
}

u32 zkfc_task_boost_count(u32 *inherit_groups)
{
	struct zkfc_tb_group *g;
	u32 threads = 0, inherit = 0;

	spin_lock_irq(&zkfc_tb_lock);
	list_for_each_entry(g, &zkfc_tb_groups, node) {
		threads += g->threads;
		if (g->flags & ZKFC_TB_INHERIT)
			inherit++;
	}
	spin_unlock_irq(&zkfc_tb_lock);
	if (inherit_groups)
		*inherit_groups = inherit;
	return threads;
}

/*
 * Hook context (kprobe pre-handler or patched fork path): may be atomic, so
 * only queue the new task's pid; the work item applies the boost.
 */
void zkfc_task_boost_on_new_task(struct task_struct *p)
{
	struct zkfc_tb_group *g;
	unsigned long flags;
	bool match = false;
	pid_t tgid = task_tgid_nr(p);

	if (!zkfc_feature_licensed(ZKFC_FEAT_BOOST_INHERIT))
		return;

	spin_lock_irqsave(&zkfc_tb_lock, flags);
	list_for_each_entry(g, &zkfc_tb_groups, node) {
		if ((g->flags & ZKFC_TB_INHERIT) && g->tgid == tgid) {
			match = !zkfc_tb_suspended;
			break;
		}
	}
	spin_unlock_irqrestore(&zkfc_tb_lock, flags);
	if (!match)
		return;

	if (kfifo_in_spinlocked(&zkfc_tb_fifo, &p->pid, 1, &zkfc_tb_fifo_lock))
		schedule_work(&zkfc_tb_work);
}

static void zkfc_tb_work_fn(struct work_struct *w)
{
	struct zkfc_tb_group *g;
	pid_t pid, tgid;
	u32 umin = 0, umax = 0;
	bool found;

	while (kfifo_out_spinlocked(&zkfc_tb_fifo, &pid, 1, &zkfc_tb_fifo_lock)) {
		struct task_struct *t;

		rcu_read_lock();
		t = pid_task(find_vpid(pid), PIDTYPE_PID);
		if (t)
			get_task_struct(t);
		rcu_read_unlock();
		if (!t)
			continue;

		tgid = task_tgid_nr(t);
		found = false;
		spin_lock_irq(&zkfc_tb_lock);
		list_for_each_entry(g, &zkfc_tb_groups, node) {
			if ((g->flags & ZKFC_TB_INHERIT) && g->tgid == tgid) {
				umin = g->umin;
				umax = g->umax;
				g->threads++;
				found = !zkfc_tb_suspended;
				break;
			}
		}
		spin_unlock_irq(&zkfc_tb_lock);

		if (found)
			zkfc_tb_apply_one(t, umin, umax, false);
		put_task_struct(t);
	}
}

int zkfc_task_boost_init(void)
{
	INIT_KFIFO(zkfc_tb_fifo);
	return 0;
}

void zkfc_task_boost_exit(void)
{
	cancel_work_sync(&zkfc_tb_work);
	zkfc_task_boost_reset_all();
}
