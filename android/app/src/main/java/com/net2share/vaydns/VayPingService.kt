package com.net2share.vaydns

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import mobile.Mobile

class VayPingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val tasksJson = intent.getStringExtra("TASKS_JSON") ?: "[]"
        val workers = intent.getLongExtra("WORKERS", 20L)
        val tunnelWait = intent.getLongExtra("TUNNEL_WAIT", 3000L)
        val udpTimeout = intent.getLongExtra("UDP_TIMEOUT", 1000L)
        val probeTimeout = intent.getLongExtra("PROBE_TIMEOUT", 15L)
        val retries = intent.getLongExtra("RETRIES", 0L)
        val lightE2EEnabled = intent.getBooleanExtra("LIGHT_E2E", false)
        val engineQuickScan = intent.getBooleanExtra("QUICK_SCAN", false)

        Thread {
            // Run the heavy Go process
            val resultsJson = Mobile.pingAllConfigs(
                tasksJson, workers, tunnelWait, udpTimeout,
                probeTimeout, retries, lightE2EEnabled, engineQuickScan
            )

            // Broadcast the payload back to the MainActivity UI
            val broadcastIntent = Intent("PING_ALL_FINISHED").setPackage(packageName)
            broadcastIntent.putExtra("RESULTS_JSON", resultsJson)
            sendBroadcast(broadcastIntent)

            // Trigger self-destruction
            Handler(Looper.getMainLooper()).post {
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Run the teardown in a Background Thread so we don't freeze the OS
        Thread {
            // 1. Flush the Go network transport & send FIN packets (Takes ~1000ms)
            Mobile.stopPingAllConfigs()

            // 2. Commit suicide IMMEDIATELY after the network flush is complete
            android.os.Process.killProcess(android.os.Process.myPid())
        }.start()
    }
}