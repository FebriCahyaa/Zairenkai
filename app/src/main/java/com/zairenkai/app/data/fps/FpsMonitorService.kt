// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Foreground service that records FPS sessions of the watched apps. It reads
 * the read-only `zperfd frames --follow --watch` stream, adds the battery
 * temperature/power at each line, and saves a session whenever a watched app
 * leaves the foreground. Nothing here changes a device setting.
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data.fps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.zairenkai.app.MainActivity
import com.zairenkai.app.R
import com.zairenkai.app.ZairenkaiApp
import com.zairenkai.app.data.power.AndroidBatteryReader
import com.zairenkai.app.data.power.BatteryMath
import com.zairenkai.app.ui.locale.AppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/** What the FPS screen shows while the recorder runs. */
data class FpsLive(
    val running: Boolean = false,
    /** True once zperfd answered; false when the stream could not start. */
    val streaming: Boolean = false,
    val foreground: String? = null,
    /** Package being recorded, and its label. */
    val recording: String? = null,
    val recordingLabel: String? = null,
    val fps: Int? = null,
    val frames: Int = 0,
)

class FpsMonitorService : Service() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }
        startForegroundCompat(getString(R.string.fps_notif_waiting))
        if (job?.isActive == true) return START_STICKY
        _live.value = FpsLive(running = true)
        val container = (application as ZairenkaiApp).container
        job = scope.launch {
            val battery = AndroidBatteryReader(this@FpsMonitorService)
            val labels = AppLabels(this@FpsMonitorService)
            container.settings.settings.map { it.fpsWatch }.distinctUntilChanged().collectLatest { watch ->
                val builder = FpsSessionBuilder()
                try {
                    while (isActive) {
                        if (watch.isEmpty()) {
                            _live.value = FpsLive(running = true)
                            delay(Long.MAX_VALUE)
                        }
                        container.zperf.frames(watch.toList(), INTERVAL_MS).collect { b ->
                            val a = battery.read()
                            val current = BatteryMath.currentMa(a.currentRaw, a.status)
                            val power = BatteryMath.powerW(a.voltageV, current)?.let { abs(it) }
                            builder.onBatch(b, a.tempC, power, labels::label)?.let { container.fpsStore.add(it) }
                            val recording = builder.current
                            val before = _live.value.recording
                            _live.value = FpsLive(
                                running = true,
                                streaming = true,
                                foreground = b.fg,
                                recording = recording,
                                recordingLabel = recording?.let(labels::label),
                                fps = if (b.watched) (b.frames.size * 1000 / INTERVAL_MS) else null,
                                frames = builder.frames,
                            )
                            if (recording != before) {
                                notify(
                                    recording?.let { getString(R.string.fps_notif_recording, labels.label(it)) }
                                        ?: getString(R.string.fps_notif_waiting),
                                )
                            }
                        }
                        // The stream ended (zperfd missing, root revoked): keep what was
                        // recorded and retry later.
                        builder.finish(labels::label)?.let { container.fpsStore.add(it) }
                        _live.value = _live.value.copy(streaming = false, recording = null, recordingLabel = null, fps = null)
                        delay(RETRY_MS)
                    }
                } finally {
                    builder.finish(labels::label)?.let { scope.launch { container.fpsStore.add(it) } }
                }
            }
        }
        return START_STICKY
    }

    private fun stopRecording() {
        job?.cancel()
        job = null
        _live.value = FpsLive()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        _live.value = FpsLive()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.fps_notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.fps_notif_channel_desc)
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, FpsMonitorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.fps_notif_title))
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.fps_notif_stop), stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundCompat(text: String) {
        val n = notification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }

    companion object {
        private const val CHANNEL = "zk_fps"
        private const val NOTIF_ID = 43
        private const val INTERVAL_MS = 1000
        private const val RETRY_MS = 10_000L
        const val ACTION_STOP = "com.zairenkai.app.FPS_STOP"

        private val _live = MutableStateFlow(FpsLive())
        val live: StateFlow<FpsLive> = _live.asStateFlow()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FpsMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FpsMonitorService::class.java).setAction(ACTION_STOP))
        }
    }
}

/** App labels from PackageManager, cached. */
class AppLabels(context: Context) {
    private val pm = context.applicationContext.packageManager
    private val cache = HashMap<String, String>()

    fun label(pkg: String): String = cache.getOrPut(pkg) {
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }
}
