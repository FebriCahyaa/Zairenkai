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
 * ZKFC preserves the task's pre-existing requested uclamp state. In particular,
 * a task which entered ZKFC with a user-defined clamp is restored to that exact
 * request, while a task which inherited its clamp from scheduler defaults/group
 * state has its task-specific request cleared again rather than being forced to
 * the numeric defaults 0/1024.
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

struct zkfc_tb_saved_task {
	struct list_head node;
	struct pid *pid;
	u32 umin;
	u32 umax;
	bool umin_user_defined;
	bool umax_user_defined;
};

struct zkfc_tb_group {
	struct list_head node;
	pid_t pid;
	struct pid *target_pid;
	struct pid *tgid_pid;
	u32 umin;
	u32 umax;
	u32 flags;
	u32 threads;
	struct list_head saved;
};

static LIST_HEAD(zkfc_tb_groups);
static DEFINE_SPINLOCK(zkfc_tb_lock);
static DEFINE_MUTEX(zkfc_tb_mutex);
static bool zkfc_tb_suspended;
static atomic_t zkfc_tb_thermal_scale = ATOMIC_INIT(1000);

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

static struct zkfc_tb_saved_task *zkfc_tb_saved_find(
	struct zkfc_tb_group *g, struct pid *pid)
{
	struct zkfc_tb_saved_task *saved;

	if (!g || !pid)
		return NULL;

	list_for_each_entry(saved, &g->saved, node) {
		if (saved->pid == pid)
			return saved;
	}
	return NULL;
}

static void zkfc_tb_snapshot_uclamp(struct task_struct *p,
					struct zkfc_tb_saved_task *saved)
{
#ifdef ZKFC_HAVE_UCLAMP
	unsigned long irq_flags;

	/*
	 * Scheduler uclamp requests are protected by scheduler task/rq locking.
	 * Current Android common kernels assert p->pi_lock while updating the
	 * requested clamp. Hold the same lock for this small snapshot and never
	 * call into the scheduler while it is held.
	 */
	spin_lock_irqsave(&p->pi_lock, irq_flags);
	saved->umin = p->uclamp_req[UCLAMP_MIN].value;
	saved->umax = p->uclamp_req[UCLAMP_MAX].value;
	saved->umin_user_defined = p->uclamp_req[UCLAMP_MIN].user_defined;
	saved->umax_user_defined = p->uclamp_req[UCLAMP_MAX].user_defined;
	spin_unlock_irqrestore(&p->pi_lock, irq_flags);
#else
	(void)p;
	saved->umin = 0;
	saved->umax = ZKFC_UCLAMP_SCALE;
	saved->umin_user_defined = false;
	saved->umax_user_defined = false;
#endif
}

static int zkfc_tb_save_task(struct zkfc_tb_group *g,
				     struct task_struct *p,
				     struct zkfc_tb_saved_task *parent_saved,
				     struct list_head *pending)
{
	struct zkfc_tb_saved_task *saved;

	{
		struct pid *pid = get_task_pid(p, PIDTYPE_PID);
		bool already = pid && zkfc_tb_saved_find(g, pid);
		if (pid)
			put_pid(pid);
		if (already)
			return 0;
	}

	saved = kzalloc(sizeof(*saved), GFP_KERNEL);
	if (!saved)
		return -ENOMEM;

	saved->pid = get_task_pid(p, PIDTYPE_PID);
	if (!saved->pid) {
		kfree(saved);
		return -ESRCH;
	}

	if (parent_saved) {
		/*
		 * A freshly cloned thread may already have inherited the boosted
		 * parent's uclamp request. Use the parent's saved baseline rather
		 * than accidentally treating the inherited boost as the child's
		 * original state.
		 */
		saved->umin = parent_saved->umin;
		saved->umax = parent_saved->umax;
		saved->umin_user_defined = parent_saved->umin_user_defined;
		saved->umax_user_defined = parent_saved->umax_user_defined;
	} else {
		zkfc_tb_snapshot_uclamp(p, saved);
	}

	list_add_tail(&saved->node, pending);
	return 0;
}

static struct zkfc_tb_saved_task *zkfc_tb_parent_saved(
	struct zkfc_tb_group *g, struct task_struct *p)
{
	struct task_struct *parent;
	struct pid *parent_pid;
	struct zkfc_tb_saved_task *saved;

	if (!g || !p)
		return NULL;

	parent = rcu_access_pointer(p->real_parent);
	if (!parent)
		return NULL;
	parent_pid = get_task_pid(parent, PIDTYPE_PID);
	if (!parent_pid)
		return NULL;
	saved = zkfc_tb_saved_find(g, parent_pid);
	put_pid(parent_pid);
	return saved;
}

static int zkfc_tb_apply_one(struct task_struct *p, u32 umin, u32 umax,
				 bool reset)
{
#ifdef ZKFC_HAVE_UCLAMP
	u32 scale = (u32)atomic_read(&zkfc_tb_thermal_scale);
	u32 effective_min = (umin * scale) / 1000U;
	struct sched_attr attr = {
		.size = sizeof(attr),
		.sched_policy = p->policy,
		.sched_flags = SCHED_FLAG_KEEP_ALL | SCHED_FLAG_UTIL_CLAMP,
		.sched_util_min = reset ? (u32)-1 : effective_min,
		.sched_util_max = reset ? (u32)-1 : umax,
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

static int zkfc_tb_restore_one(struct task_struct *p,
				       const struct zkfc_tb_saved_task *saved)
{
#ifdef ZKFC_HAVE_UCLAMP
	struct sched_attr attr = {
		.size = sizeof(attr),
		.sched_policy = p->policy,
		.sched_flags = SCHED_FLAG_KEEP_ALL | SCHED_FLAG_UTIL_CLAMP,
		.sched_util_min = saved->umin_user_defined ? saved->umin : (u32)-1,
		.sched_util_max = saved->umax_user_defined ? saved->umax : (u32)-1,
	};

	return sched_setattr_nocheck(p, &attr);
#else
	(void)p;
	(void)saved;
	return -EOPNOTSUPP;
#endif
}

static u32 zkfc_tb_restore_saved(struct zkfc_tb_group *g)
{
	struct zkfc_tb_saved_task *saved;
	u32 restored = 0;

	if (!g)
		return 0;

	list_for_each_entry(saved, &g->saved, node) {
		struct task_struct *task;
		int ret;

		rcu_read_lock();
		task = pid_task(saved->pid, PIDTYPE_PID);
		if (task)
			get_task_struct(task);
		rcu_read_unlock();
		if (!task)
			continue;

		ret = zkfc_tb_restore_one(task, saved);
		if (!ret)
			restored++;
		else
			zkfc_w("task uclamp restore failed: %d", ret);
		put_task_struct(task);
	}
	return restored;
}

static void zkfc_tb_free_saved(struct zkfc_tb_group *g)
{
	struct zkfc_tb_saved_task *saved, *tmp;

	if (!g)
		return;
	list_for_each_entry_safe(saved, tmp, &g->saved, node) {
		list_del(&saved->node);
		put_pid(saved->pid);
		kfree(saved);
	}
}

/*
 * Apply to @target_pid (and its thread group if @threads). The caller owns a
 * reference to @target_pid. Every task is pinned before scheduler calls run.
 * For a new group, @group is a temporary group whose saved baseline is attached
 * only after the complete operation succeeds. For an existing group, its saved
 * list is retained across updates, thermal suspend and resume.
 */
static int zkfc_tb_apply_pid(struct pid *target_pid,
				 struct zkfc_tb_group *group,
				 bool threads,
				 u32 umin,
				 u32 umax,
				 bool reset,
				 u32 *applied,
				 struct pid **tgid_pid_out)
{
	struct task_struct **list, *task, *t;
	struct list_head pending;
	struct pid *tgid_pid = NULL;
	u32 n = 0, i, ok = 0;
	int ret = 0;

	INIT_LIST_HEAD(&pending);
	list = kmalloc_array(ZKFC_TB_MAX_THREADS, sizeof(*list), GFP_KERNEL);
	if (!list)
		return -ENOMEM;

	if (reset) {
		u32 restored = zkfc_tb_restore_saved(group);
		if (applied)
			*applied = restored;
		kfree(list);
		if (tgid_pid_out)
			*tgid_pid_out = NULL;
		return restored ? 0 : -ESRCH;
	}

	if (!group) {
		kfree(list);
		return -EINVAL;
	}

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

	/* Snapshot every task before the first mutation. */
	for (i = 0; i < n; i++) {
		struct pid *pid = get_task_pid(list[i], PIDTYPE_PID);
		struct zkfc_tb_saved_task *existing = pid ? zkfc_tb_saved_find(group, pid) : NULL;
		struct zkfc_tb_saved_task *parent_saved = existing ? NULL : zkfc_tb_parent_saved(group, list[i]);

		if (pid && !existing) {
			int r = zkfc_tb_save_task(group, list[i], parent_saved, &pending);
			if (r) {
				ret = r;
				if (pid)
					put_pid(pid);
				break;
			}
		}
		if (pid)
			put_pid(pid);
	}

	if (ret) {
		struct zkfc_tb_saved_task *saved, *tmp;
		list_for_each_entry_safe(saved, tmp, &pending, node) {
			list_del(&saved->node);
			put_pid(saved->pid);
			kfree(saved);
		}
		for (i = 0; i < n; i++)
			put_task_struct(list[i]);
		put_pid(tgid_pid);
		kfree(list);
		return ret;
	}

	list_splice_tail_init(&pending, &group->saved);

	for (i = 0; i < n; i++) {
		int r = zkfc_tb_apply_one(list[i], umin, umax, false);

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
	return ok ? ret : ret;
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
	if (!g)
		return;
	zkfc_tb_free_saved(g);
	if (g->target_pid)
		put_pid(g->target_pid);
	if (g->tgid_pid)
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
		zkfc_tb_restore_saved(g);
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
	pid_t old_pid = -1;
	u32 old_umin = 0, old_umax = ZKFC_UCLAMP_SCALE, old_flags = 0;
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

	target_pid = find_get_pid(tb->pid);
	if (!target_pid)
		return reset ? 0 : -ESRCH;

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
		if (!g) {
			spare = kzalloc(sizeof(*spare), GFP_KERNEL);
			if (!spare) {
				ret = -ENOMEM;
				goto unlock;
			}
			INIT_LIST_HEAD(&spare->saved);
		}
	}

	if (g) {
		old_pid = g->pid;
		old_umin = g->umin;
		old_umax = g->umax;
		old_flags = g->flags;
	}

	ret = zkfc_tb_apply_pid(target_pid, g ? g : spare, threads,
				tb->uclamp_min, tb->uclamp_max, reset,
				&applied, &tgid_pid);

	if (!reset && ret) {
		/* New groups must never leak a partially applied boost. */
		if (!g && spare) {
			zkfc_tb_restore_saved(spare);
			zkfc_tb_free_group(spare);
			spare = NULL;
		} else if (g && old_pid > 0) {
			/* Preserve the previously committed policy after a failed update. */
			(void)zkfc_tb_apply_pid(target_pid, g,
						!!(old_flags & (ZKFC_TB_THREADS | ZKFC_TB_INHERIT)),
						old_umin, old_umax, false, NULL, NULL);
		}
	}

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
	if (spare)
		zkfc_tb_free_group(spare);
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

/* Re-apply (resume/scale) or restore saved state (thermal suspend/reset). */
static void zkfc_tb_reapply_all(bool reset)
{
	struct zkfc_tb_group *g, *tmp;
	LIST_HEAD(dead);

	list_for_each_entry_safe(g, tmp, &zkfc_tb_groups, node) {
		int ret;

		ret = zkfc_tb_apply_pid(g->target_pid, g,
					!!(g->flags & (ZKFC_TB_THREADS | ZKFC_TB_INHERIT)),
					g->umin, g->umax, reset, NULL, NULL);
		if (ret && ret != -ESRCH)
			zkfc_w("task boost reapply pid %d failed: %d", g->pid, ret);
		if (reset)
			continue;
		if (ret == -ESRCH)
			list_move_tail(&g->node, &dead);
	}

	list_for_each_entry_safe(g, tmp, &dead, node) {
		list_del(&g->node);
		zkfc_tb_free_group(g);
	}
}

void zkfc_task_boost_thermal_scale(u32 permille)
{
	if (permille > 1000)
		permille = 1000;
	atomic_set(&zkfc_tb_thermal_scale, (int)permille);
	mutex_lock(&zkfc_tb_mutex);
	zkfc_tb_prune_dead();
	if (!zkfc_tb_suspended)
		zkfc_tb_reapply_all(false);
	mutex_unlock(&zkfc_tb_mutex);
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
	struct pid *target_pid;

	while (kfifo_out_spinlocked(&zkfc_tb_fifo, &target_pid, 1,
					&zkfc_tb_fifo_lock)) {
		struct task_struct *t;
		struct zkfc_tb_group *g;
		u32 umin = 0, umax = 0;
		bool found = false;

		rcu_read_lock();
		t = pid_task(target_pid, PIDTYPE_PID);
		if (t)
			get_task_struct(t);
		rcu_read_unlock();
		if (!t) {
			put_pid(target_pid);
			continue;
		}

		mutex_lock(&zkfc_tb_mutex);
		zkfc_tb_prune_dead();
		rcu_read_lock();
		{
			struct pid *tgid_pid = task_tgid(t);
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
		}
		rcu_read_unlock();

		if (found && g)
			(void)zkfc_tb_apply_pid(target_pid, g, false, umin, umax,
						false, NULL, NULL);
		mutex_unlock(&zkfc_tb_mutex);

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
