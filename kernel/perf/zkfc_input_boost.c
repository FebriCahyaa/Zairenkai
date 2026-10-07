// SPDX-License-Identifier: GPL-2.0-only
/*
 * ZKFC input boost.
 *
 * Registers a standard input handler (no kernel patch needed, GKI-safe).
 * A touch or key event raises a per-cluster cpufreq floor for a short time
 * so the first frames after user input render without ramp-up latency.
 * Events that arrive while a boost is active only extend it.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#include <linux/input.h>
#include <linux/jiffies.h>
#include <linux/kernel.h>
#include <linux/mutex.h>
#include <linux/slab.h>
#include <linux/spinlock.h>
#include <linux/workqueue.h>

#include "../zkfc.h"

#ifdef CONFIG_INPUT
#define ZKFC_IB_MIN_MS		10
#define ZKFC_IB_MAX_MS		5000
/* Ignore events closer than this to the previous boost trigger. */
#define ZKFC_IB_RATELIMIT_MS	20

static struct zkfc_input_boost zkfc_ib_cfg;
static DEFINE_MUTEX(zkfc_ib_mutex);
static DEFINE_SPINLOCK(zkfc_ib_lock);
static bool zkfc_ib_enabled;
static bool zkfc_ib_suspended;
static bool zkfc_ib_active;
static unsigned long zkfc_ib_last;
static u64 zkfc_ib_count;
static u32 zkfc_ib_thermal_scale = 1000;
static bool zkfc_ib_registered;

static void zkfc_ib_on_fn(struct work_struct *w);
static void zkfc_ib_off_fn(struct work_struct *w);
static DECLARE_WORK(zkfc_ib_on_work, zkfc_ib_on_fn);
static DECLARE_DELAYED_WORK(zkfc_ib_off_work, zkfc_ib_off_fn);

static void zkfc_ib_on_fn(struct work_struct *w)
{
	struct zkfc_input_boost cfg;
	u32 i, scale;

	mutex_lock(&zkfc_ib_mutex);
	cfg = zkfc_ib_cfg;
	scale = zkfc_ib_thermal_scale;
	mutex_unlock(&zkfc_ib_mutex);

	spin_lock_irq(&zkfc_ib_lock);
	if (!zkfc_ib_enabled || zkfc_ib_suspended) {
		spin_unlock_irq(&zkfc_ib_lock);
		return;
	}
	spin_unlock_irq(&zkfc_ib_lock);

	for (i = 0; i < cfg.cluster_count && i < ZKFC_MAX_CLUSTERS; i++)
		if (cfg.min_khz[i])
			zkfc_cpufreq_set_boost_floor(cfg.cluster_cpu[i],
					(cfg.min_khz[i] * scale) / 1000U);

	mod_delayed_work(system_wq, &zkfc_ib_off_work,
			 msecs_to_jiffies(cfg.duration_ms));
}

static void zkfc_ib_off_fn(struct work_struct *w)
{
	zkfc_cpufreq_clear_boost_floors();
	spin_lock_irq(&zkfc_ib_lock);
	zkfc_ib_active = false;
	spin_unlock_irq(&zkfc_ib_lock);
}

static bool zkfc_ib_event_allowed(const struct input_handle *handle,
				  unsigned int type, unsigned int code, int value)
{
	const struct input_dev *dev = handle->dev;

	if (!dev || value == 0)
		return false;
	if (type == EV_ABS) {
		bool touch = test_bit(BTN_TOUCH, dev->keybit) ||
			(test_bit(ABS_MT_POSITION_X, dev->absbit) &&
			 test_bit(ABS_MT_POSITION_Y, dev->absbit));
		if (!touch)
			return false;
		return code == ABS_X || code == ABS_Y ||
			code == ABS_MT_POSITION_X || code == ABS_MT_POSITION_Y ||
			(code == ABS_MT_TRACKING_ID && value >= 0);
	}
	if (type == EV_KEY) {
		if (code == BTN_TOUCH)
			return true;
		/* Gamepad events are allowed only from devices which advertise the
		 * gamepad class bit; this avoids treating volume/media keys as input
		 * boost triggers. */
		return test_bit(BTN_GAMEPAD, dev->keybit);
	}
	return false;
}

static void zkfc_ib_event(struct input_handle *handle, unsigned int type,
			  unsigned int code, int value)
{
	unsigned long flags;
	(void)handle;
	(void)code;
	(void)value;
	bool kick = false;

	if (!zkfc_ib_event_allowed(handle, type, code, value))
		return;

	spin_lock_irqsave(&zkfc_ib_lock, flags);
	if (zkfc_ib_enabled && !zkfc_ib_suspended &&
	    time_after(jiffies, zkfc_ib_last + msecs_to_jiffies(ZKFC_IB_RATELIMIT_MS))) {
		zkfc_ib_last = jiffies;
		zkfc_ib_active = true;
		zkfc_ib_count++;
		kick = true;
	}
	spin_unlock_irqrestore(&zkfc_ib_lock, flags);

	if (kick)
		queue_work(system_highpri_wq, &zkfc_ib_on_work);
}

static int zkfc_ib_connect(struct input_handler *handler, struct input_dev *dev,
			   const struct input_device_id *id)
{
	struct input_handle *handle;
	int ret;

	handle = kzalloc(sizeof(*handle), GFP_KERNEL);
	if (!handle)
		return -ENOMEM;
	handle->dev = dev;
	handle->handler = handler;
	handle->name = "zkfc_input_boost";

	ret = input_register_handle(handle);
	if (ret)
		goto err_free;
	ret = input_open_device(handle);
	if (ret)
		goto err_unregister;
	return 0;

err_unregister:
	input_unregister_handle(handle);
err_free:
	kfree(handle);
	return ret;
}

static void zkfc_ib_disconnect(struct input_handle *handle)
{
	input_close_device(handle);
	input_unregister_handle(handle);
	kfree(handle);
}

static const struct input_device_id zkfc_ib_ids[] = {
	/* multi-touch touchscreens */
	{
		.flags = INPUT_DEVICE_ID_MATCH_EVBIT | INPUT_DEVICE_ID_MATCH_ABSBIT,
		.evbit = { BIT_MASK(EV_ABS) },
		.absbit = { [BIT_WORD(ABS_MT_POSITION_X)] =
			    BIT_MASK(ABS_MT_POSITION_X) | BIT_MASK(ABS_MT_POSITION_Y) },
	},
	/* touchpads / single touch */
	{
		.flags = INPUT_DEVICE_ID_MATCH_KEYBIT | INPUT_DEVICE_ID_MATCH_ABSBIT,
		.keybit = { [BIT_WORD(BTN_TOUCH)] = BIT_MASK(BTN_TOUCH) },
		.absbit = { [BIT_WORD(ABS_X)] = BIT_MASK(ABS_X) | BIT_MASK(ABS_Y) },
	},
	/* gamepads */
	{
		.flags = INPUT_DEVICE_ID_MATCH_KEYBIT,
		.keybit = { [BIT_WORD(BTN_GAMEPAD)] = BIT_MASK(BTN_GAMEPAD) },
	},
	{ },
};

static struct input_handler zkfc_ib_handler = {
	.event = zkfc_ib_event,
	.connect = zkfc_ib_connect,
	.disconnect = zkfc_ib_disconnect,
	.name = "zkfc_input_boost",
	.id_table = zkfc_ib_ids,
};

#endif /* CONFIG_INPUT */

int zkfc_input_boost_config(const struct zkfc_input_boost *cfg)
{
	u32 i;

#ifndef CONFIG_INPUT
	if (cfg->enabled)
		return -EOPNOTSUPP;
	return 0;
#else

	if (cfg->enabled) {
		if (cfg->duration_ms < ZKFC_IB_MIN_MS || cfg->duration_ms > ZKFC_IB_MAX_MS)
			return -EINVAL;
		if (!cfg->cluster_count || cfg->cluster_count > ZKFC_MAX_CLUSTERS)
			return -EINVAL;
		for (i = 0; i < cfg->cluster_count; i++)
			if (cfg->cluster_cpu[i] >= nr_cpu_ids ||
			    !cpu_possible(cfg->cluster_cpu[i]))
				return -EINVAL;
	}

	mutex_lock(&zkfc_ib_mutex);
	zkfc_ib_cfg = *cfg;
	mutex_unlock(&zkfc_ib_mutex);

	spin_lock_irq(&zkfc_ib_lock);
	zkfc_ib_enabled = !!cfg->enabled;
	spin_unlock_irq(&zkfc_ib_lock);

	if (!cfg->enabled) {
		cancel_work_sync(&zkfc_ib_on_work);
		mod_delayed_work(system_wq, &zkfc_ib_off_work, 0);
	}
	zkfc_i("input boost %s (%u ms, %u clusters)",
	       cfg->enabled ? "enabled" : "disabled", cfg->duration_ms,
	       cfg->cluster_count);
	return 0;
#endif /* CONFIG_INPUT */
}

void zkfc_input_boost_thermal_scale(u32 permille)
{
	struct zkfc_input_boost cfg;
	u32 i;

#ifdef CONFIG_INPUT
	if (permille > 1000)
		permille = 1000;
	mutex_lock(&zkfc_ib_mutex);
	zkfc_ib_thermal_scale = permille;
	cfg = zkfc_ib_cfg;
	mutex_unlock(&zkfc_ib_mutex);

	spin_lock_irq(&zkfc_ib_lock);
	if (!zkfc_ib_enabled || zkfc_ib_suspended || !zkfc_ib_active) {
		spin_unlock_irq(&zkfc_ib_lock);
		return;
	}
	spin_unlock_irq(&zkfc_ib_lock);

	if (!permille) {
		zkfc_cpufreq_clear_boost_floors();
		return;
	}
	for (i = 0; i < cfg.cluster_count && i < ZKFC_MAX_CLUSTERS; i++)
		if (cfg.min_khz[i])
			zkfc_cpufreq_set_boost_floor(cfg.cluster_cpu[i],
					(cfg.min_khz[i] * permille) / 1000U);
#else
	(void)permille;
#endif
}

void zkfc_input_boost_suspend(bool suspend)
{
#ifdef CONFIG_INPUT
	spin_lock_irq(&zkfc_ib_lock);
	zkfc_ib_suspended = suspend;
	spin_unlock_irq(&zkfc_ib_lock);
	if (suspend)
		mod_delayed_work(system_wq, &zkfc_ib_off_work, 0);
#else
	(void)suspend;
#endif
}

void zkfc_input_boost_status(struct zkfc_perf_status *st)
{
#ifdef CONFIG_INPUT
	spin_lock_irq(&zkfc_ib_lock);
	st->input_boost_enabled = zkfc_ib_enabled;
	st->input_boost_active = zkfc_ib_active;
	st->input_boost_count = zkfc_ib_count;
	spin_unlock_irq(&zkfc_ib_lock);
#else
	st->input_boost_enabled = false;
	st->input_boost_active = false;
	st->input_boost_count = 0;
#endif
}

int zkfc_input_boost_init(void)
{
#ifdef CONFIG_INPUT
	int ret = input_register_handler(&zkfc_ib_handler);

	if (ret) {
		zkfc_w("input handler registration failed: %d", ret);
		return ret;
	}
	zkfc_ib_registered = true;
	return 0;
#else
	return -EOPNOTSUPP;
#endif
}

void zkfc_input_boost_exit(void)
{
#ifdef CONFIG_INPUT
	spin_lock_irq(&zkfc_ib_lock);
	zkfc_ib_enabled = false;
	spin_unlock_irq(&zkfc_ib_lock);
	if (zkfc_ib_registered)
		input_unregister_handler(&zkfc_ib_handler);
	cancel_work_sync(&zkfc_ib_on_work);
	cancel_delayed_work_sync(&zkfc_ib_off_work);
	zkfc_cpufreq_clear_boost_floors();
#else
	return;
#endif
}
