// SPDX-License-Identifier: GPL-2.0-only
/*
 * ZKFC leveled log: printk mirror plus a lock-protected ring buffer that the
 * manager reads through ZKFC_IOC_LOG_READ for its live log view.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#include <linux/kernel.h>
#include <linux/printk.h>
#include <linux/sched.h>
#include <linux/spinlock.h>
#include <linux/string.h>
#include <linux/timekeeping.h>
#include <linux/uidgid.h>
#include <linux/cred.h>

#include "../zkfc.h"

#define ZKFC_LOG_RING 512	/* power of two */

static struct zkfc_log_record zkfc_ring[ZKFC_LOG_RING];
static u64 zkfc_ring_next;	/* sequence of the next record */
static DEFINE_SPINLOCK(zkfc_ring_lock);
static u32 zkfc_level = ZKFC_LOG_INFO;

int zkfc_log_init(void)
{
	zkfc_ring_next = 1;
#ifdef CONFIG_ZKFC_DEBUG
	zkfc_level = ZKFC_LOG_VERBOSE;
#endif
	return 0;
}

void zkfc_log_exit(void)
{
}

void zkfc_log_set_level(u32 level)
{
	if (level > ZKFC_LOG_SILENT)
		level = ZKFC_LOG_SILENT;
	WRITE_ONCE(zkfc_level, level);
}

u32 zkfc_log_get_level(void)
{
	return READ_ONCE(zkfc_level);
}

void zkfc_log(u32 level, const char *fmt, ...)
{
	struct zkfc_log_record *r;
	unsigned long flags;
	char msg[ZKFC_LOG_MSG_SIZE];
	va_list args;

	if (level < READ_ONCE(zkfc_level) || level >= ZKFC_LOG_SILENT)
		return;

	va_start(args, fmt);
	vscnprintf(msg, sizeof(msg), fmt, args);
	va_end(args);

	switch (level) {
	case ZKFC_LOG_ERROR:
		pr_err(ZKFC_PREFIX "%s\n", msg);
		break;
	case ZKFC_LOG_WARN:
		pr_warn(ZKFC_PREFIX "%s\n", msg);
		break;
	case ZKFC_LOG_INFO:
		pr_info(ZKFC_PREFIX "%s\n", msg);
		break;
	default:
		pr_debug(ZKFC_PREFIX "%s\n", msg);
		break;
	}

	spin_lock_irqsave(&zkfc_ring_lock, flags);
	r = &zkfc_ring[zkfc_ring_next & (ZKFC_LOG_RING - 1)];
	r->seq = zkfc_ring_next++;
	r->ts_ns = zkfc_boottime_ns();
	r->level = level;
	r->pid = in_task() ? task_pid_nr(current) : 0;
	r->uid = in_task() ? from_kuid(&init_user_ns, current_uid()) : 0;
	r->reserved = 0;
	strscpy(r->msg, msg, sizeof(r->msg));
	spin_unlock_irqrestore(&zkfc_ring_lock, flags);
}

int zkfc_log_read(struct zkfc_log_read *rd)
{
	unsigned long flags;
	u64 seq, oldest;
	u32 n = 0;

	spin_lock_irqsave(&zkfc_ring_lock, flags);
	oldest = zkfc_ring_next > ZKFC_LOG_RING ? zkfc_ring_next - ZKFC_LOG_RING : 1;
	seq = rd->from_seq;
	rd->dropped = 0;
	if (seq < oldest) {
		rd->dropped = (u32)min_t(u64, oldest - seq, U32_MAX);
		seq = oldest;
	}
	while (seq < zkfc_ring_next && n < ZKFC_LOG_BATCH) {
		rd->records[n++] = zkfc_ring[seq & (ZKFC_LOG_RING - 1)];
		seq++;
	}
	spin_unlock_irqrestore(&zkfc_ring_lock, flags);

	rd->count = n;
	rd->next_seq = seq;
	return 0;
}
