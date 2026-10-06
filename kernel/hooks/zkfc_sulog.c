// SPDX-License-Identifier: GPL-2.0-only
/*
 * ZKFC sulog: audit trail of root ("su") executions and privileged ZKFC
 * operations. Records are kept in a ring buffer and read by the manager.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#include <linux/kernel.h>
#include <linux/sched.h>
#include <linux/spinlock.h>
#include <linux/string.h>
#include <linux/cred.h>
#include <linux/uidgid.h>
#include <linux/rcupdate.h>

#include "../zkfc.h"

#define ZKFC_SULOG_RING 256	/* power of two */

static struct zkfc_sulog_record zkfc_su_ring[ZKFC_SULOG_RING];
static u64 zkfc_su_next = 1;
static DEFINE_SPINLOCK(zkfc_su_lock);

int zkfc_sulog_init(void)
{
	zkfc_su_next = 1;
	return 0;
}

void zkfc_sulog_exit(void)
{
}

void zkfc_sulog_add(u32 event, int result, u32 detail, const char *path)
{
	struct zkfc_sulog_record rec = { 0 };
	struct zkfc_sulog_record *r;
	unsigned long flags;

	rec.ts_ns = zkfc_boottime_ns();
	rec.event = event;
	rec.result = result;
	rec.detail = detail;
	if (in_task()) {
		rec.uid = from_kuid(&init_user_ns, current_uid());
		rec.pid = task_tgid_nr(current);
		rcu_read_lock();
		rec.ppid = task_tgid_nr(rcu_dereference(current->real_parent));
		rcu_read_unlock();
		get_task_comm(rec.comm, current);
	}
	if (path)
		strscpy(rec.path, path, sizeof(rec.path));

	spin_lock_irqsave(&zkfc_su_lock, flags);
	r = &zkfc_su_ring[zkfc_su_next & (ZKFC_SULOG_RING - 1)];
	rec.seq = zkfc_su_next++;
	*r = rec;
	spin_unlock_irqrestore(&zkfc_su_lock, flags);
}

int zkfc_sulog_read(struct zkfc_sulog_read *rd)
{
	unsigned long flags;
	u64 seq, oldest;
	u32 n = 0;

	spin_lock_irqsave(&zkfc_su_lock, flags);
	oldest = zkfc_su_next > ZKFC_SULOG_RING ? zkfc_su_next - ZKFC_SULOG_RING : 1;
	seq = rd->from_seq;
	rd->dropped = 0;
	if (seq < oldest) {
		rd->dropped = (u32)min_t(u64, oldest - seq, U32_MAX);
		seq = oldest;
	}
	while (seq < zkfc_su_next && n < ZKFC_LOG_BATCH) {
		rd->records[n++] = zkfc_su_ring[seq & (ZKFC_SULOG_RING - 1)];
		seq++;
	}
	spin_unlock_irqrestore(&zkfc_su_lock, flags);

	rd->count = n;
	rd->next_seq = seq;
	return 0;
}
