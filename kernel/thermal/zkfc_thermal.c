// SPDX-License-Identifier: GPL-2.0-only
/*
 * ZKFC thermal guard (device mitigation).
 *
 * Polls up to four thermal zones chosen by the manager. When any of them
 * reaches the configured limit, every ZKFC boost is suspended: input boost
 * floors are dropped and task uclamp boosts are neutralised. Boosts resume
 * once all zones are below the release temperature (hysteresis).
 *
 * ZKFC never edits critical trip points and never disables thermal zones;
 * it only removes its own performance requests. The limit is clamped to
 * ZKFC_THERMAL_LIMIT_{MIN,MAX}_MDEG so a manager bug cannot configure an
 * unsafe value.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
#include <linux/jiffies.h>
#include <linux/kernel.h>
#include <linux/mutex.h>
#include <linux/string.h>
#include <linux/thermal.h>
#include <linux/workqueue.h>

#include "../zkfc.h"

static struct zkfc_thermal_guard zkfc_tg_cfg;
static DEFINE_MUTEX(zkfc_tg_mutex);
static bool zkfc_tg_tripped;
static int zkfc_tg_last = INT_MIN;
static u64 zkfc_tg_trips;

static void zkfc_tg_fn(struct work_struct *w);
static DECLARE_DELAYED_WORK(zkfc_tg_work, zkfc_tg_fn);

static int zkfc_zone_temp(const char *name, int *temp)
{
#ifdef CONFIG_THERMAL
	struct thermal_zone_device *tz = thermal_zone_get_zone_by_name(name);

	if (IS_ERR_OR_NULL(tz))
		return tz ? PTR_ERR(tz) : -ENODEV;
	return thermal_zone_get_temp(tz, temp);
#else
	return -EOPNOTSUPP;
#endif
}

int zkfc_thermal_read(struct zkfc_thermal_read *rd)
{
	int temp = 0;

	rd->zone[ZKFC_THERMAL_NAME - 1] = '\0';
	rd->result = zkfc_zone_temp(rd->zone, &temp);
	rd->temp_mdeg = rd->result ? 0 : temp;
	return 0;
}

static void zkfc_tg_set_tripped(bool tripped, int temp)
{
	if (zkfc_tg_tripped == tripped)
		return;
	zkfc_tg_tripped = tripped;
	if (tripped) {
		zkfc_tg_trips++;
		zkfc_w("thermal guard tripped at %d mC: suspending boosts", temp);
	} else {
		zkfc_i("thermal guard released at %d mC: resuming boosts", temp);
	}
	zkfc_input_boost_suspend(tripped);
	zkfc_task_boost_suspend(tripped);
}

static void zkfc_tg_fn(struct work_struct *w)
{
	struct zkfc_thermal_guard cfg;
	int hottest = INT_MIN, temp;
	u32 i;

	mutex_lock(&zkfc_tg_mutex);
	cfg = zkfc_tg_cfg;
	if (!cfg.enabled) {
		mutex_unlock(&zkfc_tg_mutex);
		return;
	}

	bool telemetry_ok = true;
	int first_error = 0;

	for (i = 0; i < cfg.zone_count; i++) {
		int ret = zkfc_zone_temp(cfg.zones[i], &temp);

		if (ret) {
			telemetry_ok = false;
			if (!first_error)
				first_error = ret;
			continue;
		}
		if (temp > hottest)
			hottest = temp;
	}

	zkfc_tg_last = hottest;
	if (!telemetry_ok) {
		/* Missing telemetry is unsafe: never leave a performance boost armed
		 * while the safety sensor set is incomplete. */
		if (!zkfc_tg_tripped) {
			zkfc_w("thermal telemetry unavailable (%d): suspending boosts",
			       first_error);
		}
		zkfc_tg_set_tripped(true, hottest);
	} else if (hottest != INT_MIN) {
		if (!zkfc_tg_tripped && hottest >= cfg.limit_mdeg)
			zkfc_tg_set_tripped(true, hottest);
		else if (zkfc_tg_tripped && hottest <= cfg.release_mdeg)
			zkfc_tg_set_tripped(false, hottest);
	}
	mutex_unlock(&zkfc_tg_mutex);

	schedule_delayed_work(&zkfc_tg_work, msecs_to_jiffies(cfg.interval_ms));
}

int zkfc_thermal_guard_config(const struct zkfc_thermal_guard *in)
{
	struct zkfc_thermal_guard cfg = *in;
	u32 i;

	if (cfg.enabled) {
		if (!cfg.zone_count || cfg.zone_count > ZKFC_THERMAL_ZONES)
			return -EINVAL;
		if (cfg.interval_ms < 100 || cfg.interval_ms > 10000)
			return -EINVAL;
		if (cfg.limit_mdeg < ZKFC_THERMAL_LIMIT_MIN_MDEG ||
		    cfg.limit_mdeg > ZKFC_THERMAL_LIMIT_MAX_MDEG)
			return -ERANGE;
		/* Hysteresis of at least 2 C avoids flapping. */
		if (cfg.release_mdeg > cfg.limit_mdeg - 2000)
			return -ERANGE;
		for (i = 0; i < cfg.zone_count; i++)
			cfg.zones[i][ZKFC_THERMAL_NAME - 1] = '\0';
	}

	cancel_delayed_work_sync(&zkfc_tg_work);
	mutex_lock(&zkfc_tg_mutex);
	zkfc_tg_cfg = cfg;
	if (!cfg.enabled)
		zkfc_tg_set_tripped(false, zkfc_tg_last);
	mutex_unlock(&zkfc_tg_mutex);

	if (cfg.enabled)
		schedule_delayed_work(&zkfc_tg_work, 0);
	zkfc_i("thermal guard %s (limit %d mC, release %d mC)",
	       cfg.enabled ? "enabled" : "disabled", cfg.limit_mdeg, cfg.release_mdeg);
	return 0;
}

bool zkfc_thermal_tripped(void)
{
	return READ_ONCE(zkfc_tg_tripped);
}

void zkfc_thermal_status(struct zkfc_perf_status *st)
{
	mutex_lock(&zkfc_tg_mutex);
	st->thermal_guard_enabled = zkfc_tg_cfg.enabled;
	st->thermal_tripped = zkfc_tg_tripped;
	st->thermal_last_mdeg = zkfc_tg_last == INT_MIN ? 0 : zkfc_tg_last;
	st->thermal_trip_count = zkfc_tg_trips;
	mutex_unlock(&zkfc_tg_mutex);
}

int zkfc_thermal_init(void)
{
	return 0;
}

void zkfc_thermal_exit(void)
{
	cancel_delayed_work_sync(&zkfc_tg_work);
	mutex_lock(&zkfc_tg_mutex);
	zkfc_tg_cfg.enabled = 0;
	zkfc_tg_tripped = false;
	mutex_unlock(&zkfc_tg_mutex);
}
