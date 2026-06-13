package com.net2share.vaydns

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import mobile.Mobile
import org.json.JSONObject

class VayScannerService : Service() {

    private val pollHandler = Handler(Looper.getMainLooper())
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null

    private var isRunning = false

    // Tracking state for the Foreground Notification
    private var totalResolvers = 0
    private var scannedCountSoFar = 0
    private var passedCountSoFar = 0
    private var scannedThisBatch = 0
    private var passedThisBatch = 0

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return

            try {
                val resultsStr = Mobile.getScanResults()

                if (resultsStr.isNotEmpty()) {
                    val lines = resultsStr.split("\n")

                    // Buffers to send IPC payload to Main Process
                    val ips = ArrayList<String>()
                    val lats = ArrayList<Int>()
                    var validLinesCount = 0

                    for (line in lines) {
                        if (line.isBlank()) continue
                        validLinesCount++

                        try {
                            val json = JSONObject(line)
                            val latency = json.optInt("latency_ms", 99999)

                            if (latency != 99999) {
                                val ip = json.optString("resolver", "")
                                if (ip.isNotEmpty()) {
                                    ips.add(ip)
                                    lats.add(latency)
                                    passedThisBatch++
                                }
                            }
                        } catch (e: Exception) {}
                    }

                    scannedThisBatch += validLinesCount

                    // Send IPC Payload to UI
                    val uiIntent = Intent("SCANNER_UI_REFRESH").setPackage(packageName)
                    uiIntent.putStringArrayListExtra("IPS", ips)
                    uiIntent.putIntegerArrayListExtra("LATENCIES", lats)
                    uiIntent.putExtra("SCANNED_ADD", validLinesCount)
                    sendBroadcast(uiIntent)
                }

                // Update Status Bar
                val currentScanned = scannedCountSoFar + scannedThisBatch
                val currentPassed = passedCountSoFar + passedThisBatch
                updateNotification("Scanned: $currentScanned / $totalResolvers  |  Passed: $currentPassed")

                // Handle Batch Exit Status
                val status = Mobile.getScanStatus()
                if (status == "finished" || status.startsWith("error|")) {
                    isRunning = false

                    // 1. Tell UI the batch is done
                    sendBroadcast(Intent("SCANNER_BATCH_FINISHED").setPackage(packageName))

                    // 2. Shut down this service cleanly
                    pollHandler.postDelayed({
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }, 300) // Small delay ensures Broadcast escapes process before death
                    return
                }
            } catch (e: Exception) {}

            val isScreenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
            pollHandler.postDelayed(this, if (isScreenOn) 3000 else 10000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VayDNS::ScannerServiceWakelock")
            wakeLock?.acquire()
        } catch (e: Exception) {
            android.util.Log.e("VAY_SCANNER", "Failed to acquire wakelock", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Retrieve Notification Trackers
        totalResolvers = intent.getIntExtra("totalResolvers", 0)
        scannedCountSoFar = intent.getIntExtra("scannedSoFar", 0)
        passedCountSoFar = intent.getIntExtra("passedSoFar", 0)
        scannedThisBatch = 0
        passedThisBatch = 0

        // Retrieve Go Configuration
        val isDefaultConfig = intent.getBooleanExtra("isDefaultConfig", false)
        val configIndex = intent.getLongExtra("configIndex", 0L)
        val selectedMode = intent.getStringExtra("selectedMode") ?: "udp"
        val domain = intent.getStringExtra("domain") ?: ""
        val pubkey = intent.getStringExtra("pubkey") ?: ""
        val resolvers = intent.getStringExtra("resolvers") ?: ""
        val baseDohUrl = intent.getStringExtra("baseDohUrl") ?: ""
        val proxyType = intent.getStringExtra("proxyType") ?: "socks5h"
        val tunnelProtocol = intent.getStringExtra("tunnelProtocol") ?: "socks"
        val user = intent.getStringExtra("user") ?: "none"
        val pass = intent.getStringExtra("pass") ?: "none"
        val ssMethod = intent.getStringExtra("ssMethod") ?: "chacha20-ietf-poly1305"
        val recordType = intent.getStringExtra("recordType") ?: "TXT"
        val idleTimeout = intent.getStringExtra("idleTimeout") ?: "10s"
        val keepAlive = intent.getStringExtra("keepAlive") ?: "2s"

        val clientIdSize = intent.getLongExtra("clientIdSize", 2L)
        val mtu = intent.getLongExtra("mtu", 0L)
        val workers = intent.getLongExtra("workers", 20L)
        val tunnelWait = intent.getLongExtra("tunnelWait", 2000L)
        val udpTimeout = intent.getLongExtra("udpTimeout", 1000L)
        val probeTimeout = intent.getLongExtra("probeTimeout", 15L)
        val retries = intent.getLongExtra("retries", 0L)

        val lightE2EEnabled = intent.getBooleanExtra("lightE2EEnabled", true)
        val engineQuickScan = intent.getBooleanExtra("engineQuickScan", false)
        val isQuickScanner = intent.getBooleanExtra("isQuickScanner", false)

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, DnsScannerResultActivity::class.java).apply {
                // Use addFlags instead of direct assignment
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Route the tap to the existing activity in memory, don't spawn a new one

        val notification = NotificationCompat.Builder(this, "VAY_SCANNER_CHANNEL")
            .setContentTitle("VayDNS Scanner Running")
            .setContentText("Initializing Batch...")
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .build()

        //startForeground(3, notification)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                3,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(3, notification)
        }

        // START GO ENGINE
        val result = Mobile.startF35Scan(
            isDefaultConfig, configIndex, selectedMode, domain, pubkey, resolvers, baseDohUrl, proxyType, tunnelProtocol,
            user, pass, ssMethod, recordType, idleTimeout, keepAlive, clientIdSize, mtu, workers, tunnelWait, udpTimeout,
            probeTimeout, retries, lightE2EEnabled, engineQuickScan
        )

        if (result.startsWith("error")) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.postDelayed(pollRunnable, 1000)

        return START_NOT_STICKY
    }

    private fun updateNotification(text: String) {
        // Route the tap to the existing activity in memory, don't spawn a new one

        val intent = Intent(this, DnsScannerResultActivity::class.java).apply {
            // Correct way to set flags on an existing Intent
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, "VAY_SCANNER_CHANNEL")
            .setContentTitle("VayDNS Scanner Running")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .build()
        notificationManager.notify(3, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "VAY_SCANNER_CHANNEL", "VayDNS Scanner", NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        if (wakeLock?.isHeld == true) wakeLock?.release()

        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()

        // 🚨 Run the teardown in a Background Thread so we don't freeze the Android UI.
        Thread {
            // 1. Flush the Go network transport & send FIN packets (Takes ~1000ms)
            Mobile.stopF35Scan()

            // 2. Commit suicide IMMEDIATELY after the network flush is complete
            // This instantly frees all 2GB of native KCP/SMUX memory.
            android.os.Process.killProcess(android.os.Process.myPid())
        }.start()
    }

}