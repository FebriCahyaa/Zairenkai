// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Background usage-recording foreground service. Keeps a UsageRecorder running
 * so the usage history ("riwayat penggunaan") fills in even when the app UI is
 * closed. Builds and persists a UsageSession when stopped.
 *
 * Copyright (C) 2026 FebriCahyaa
 */
package com.zairenkai.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.zairenkai.app.R
import com.zairenkai.app.ZairenkaiApp
import com.zairenkai.app.domain.ProfileId
import com.zairenkai.app.domain.Profiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ZkMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var recorder: UsageRecorder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }
        startForegroundSafe()
        if (job?.isActive == true) return START_STICKY

        val container = (application as ZairenkaiApp).container
        val rec = UsageRecorder(
            power = PowerSampler(this),
            fg = ForegroundApp(this),
            modeLabel = { currentModeLabel },
        )
        recorder = rec
        _running.value = true
        job = scope.launch {
            // Capture the active profile name as the scenario mode label.
            currentModeLabel = runCatching {
                val id = container.settings.settings.first().activeProfile ?: ProfileId.BALANCED
                Profiles.byId(id).name
            }.getOrDefault("Seimbang")
            rec.run(this)
        }
        return START_STICKY
    }

    private fun stopRecording() {
        job?.cancel()
        job = null
        val rec = recorder
        recorder = null
        _running.value = false
        if (rec != null && rec.hasData()) {
            val container = (application as ZairenkaiApp).container
            runBlocking { runCatching { container.usageStore.add(rec.build()) } }
        }
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        if (_running.value) stopRecording()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundSafe() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Monitor penggunaan", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Merekam riwayat daya & penggunaan aplikasi"
                    setShowBadge(false)
                },
            )
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Zairenkai")
            .setContentText("Merekam riwayat penggunaan…")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    companion object {
        private const val CHANNEL = "zk_monitor"
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "com.zairenkai.app.MONITOR_STOP"

        @Volatile private var currentModeLabel: String = "Seimbang"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(context: Context) {
            val i = Intent(context, ZkMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, ZkMonitorService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }
    }
}
