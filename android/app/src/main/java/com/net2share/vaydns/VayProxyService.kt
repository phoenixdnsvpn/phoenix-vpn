package com.net2share.vaydns

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import mobile.Mobile

class VayProxyService : Service() {

    // --- STATS TRACKING VARIABLES ---
    private val statsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var initialRxBytes = 0L
    private var initialTxBytes = 0L
    private var previousRxBytes = 0L
    private var previousTxBytes = 0L

    /**private val statsRunnable = object : Runnable {
        override fun run() {
            if (isStopping) return

            // 1. Ask Android OS for Total Device Traffic
            val currentRx = android.net.TrafficStats.getTotalRxBytes()
            val currentTx = android.net.TrafficStats.getTotalTxBytes()

            // 2. RESILIENCE: If OS temporarily blocks the read, wait 1s and try again.
            if (currentRx == android.net.TrafficStats.UNSUPPORTED.toLong() || currentRx == 0L) {
                statsHandler.postDelayed(this, 1000)
                return
            }

            // 3. Initialize baseline
            if (initialRxBytes == 0L && initialTxBytes == 0L) {
                initialRxBytes = currentRx
                initialTxBytes = currentTx
                previousRxBytes = currentRx
                previousTxBytes = currentTx
            }

            // 4. Calculate raw speed
            var rxSpeed = currentRx - previousRxBytes
            var txSpeed = currentTx - previousTxBytes
            var rxTotal = currentRx - initialRxBytes
            var txTotal = currentTx - initialTxBytes

            previousRxBytes = currentRx
            previousTxBytes = currentTx

            // 5. Sanitize negative spikes (Safe math, no Math.max casting issues)
            if (rxSpeed < 0) rxSpeed = 0L
            if (txSpeed < 0) txSpeed = 0L
            if (rxTotal < 0) rxTotal = 0L
            if (txTotal < 0) txTotal = 0L

            // --- 🔴 PROVE WHAT ANDROID IS SEEING ---
            android.util.Log.d("VAY_PROXY_DEBUG", "TrafficStats RX Speed: $rxSpeed | TX Speed: $txSpeed")

            val speedStr = "▼ ${formatBytes(rxSpeed)}/s   ▲ ${formatBytes(txSpeed)}/s"
            val totalStr = "Total: ${formatBytes(rxTotal)} ↓   ${formatBytes(txTotal)} ↑"

            // 6. Broadcast to MainActivity
            sendBroadcast(Intent("VPN_STATS_UPDATE").apply {
                putExtra("speed", speedStr)
                putExtra("total", totalStr)
                setPackage(packageName)
            })

            // 7. Schedule next tick
            statsHandler.postDelayed(this, 1000)
        }
    }*/

    private val statsRunnable = object : Runnable {
        override fun run() {
            if (isStopping) return

            try {
                // 1. Ask Go for the exact byte count (Format: "RX|TX")
                val stats = mobile.Mobile.getProxyStats()
                val parts = stats.split("|")

                if (parts.size == 2) {
                    val currentRx = parts[0].toLong()
                    val currentTx = parts[1].toLong()

                    // --- SAFE MATH (No Math.max casting issues) ---
                    val diffRx = currentRx - previousRxBytes
                    val diffTx = currentTx - previousTxBytes

                    val rxSpeed = if (diffRx < 0) 0L else diffRx
                    val txSpeed = if (diffTx < 0) 0L else diffTx

                    previousRxBytes = currentRx
                    previousTxBytes = currentTx

                    val speedStr = "▼ ${formatBytes(rxSpeed)}/s   ▲ ${formatBytes(txSpeed)}/s"
                    // currentRx IS the total since the session started, so we use it directly!
                    val totalStr = "Total: ${formatBytes(currentRx)} ↓   ${formatBytes(currentTx)} ↑"

                    // --- BROADCAST ---
                    sendBroadcast(Intent("VPN_STATS_UPDATE").apply {
                        putExtra("speed", speedStr)
                        putExtra("total", totalStr)
                        setPackage(packageName)
                    })
                }
            } catch (e: Exception) {
                android.util.Log.e("VayProxy", "Failed to parse Go stats: ${e.message}")
            }

            // 4. Schedule next tick
            statsHandler.postDelayed(this, 1000)
        }
    }
    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.US, "%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format(java.util.Locale.US, "%.2f GB", gb)
    }
    private var isStopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        if (intent == null || intent.action == "ACTION_STOP_VPN") {
            isStopping = true
            stopForeground(STOP_FOREGROUND_REMOVE)
            statsHandler.removeCallbacks(statsRunnable)

            Thread {
                Mobile.stopVpn()
                stopSelf()
            }.start()
            return START_NOT_STICKY
        }

        val notification = NotificationCompat.Builder(this, "VAYDNS_CHANNEL")
            .setContentTitle("VayDNS Proxy Active")
            .setContentText("Connecting...")
            .setSmallIcon(R.drawable.ic_vpn_key)
            .build()
        startForeground(2, notification) // Use ID 2 to separate from VPN

        Thread {
            try {
                Mobile.stopVpn()
                Thread.sleep(500)

                val isDefaultConfig = intent.getBooleanExtra("IS_DEFAULT_CONFIG", false)
                val configIndex = intent.getLongExtra("CONFIG_INDEX", 0L)
                val domain = intent.getStringExtra("DOMAIN") ?: ""
                val pubkey = (intent.getStringExtra("PUBKEY") ?: "").replace("\\s".toRegex(), "")
                val dnsAddress = intent.getStringExtra("UDP") ?: "8.8.8.8:53"
                val mode = intent.getStringExtra("MODE") ?: "udp"
                val recordType = intent.getStringExtra("RECORD_TYPE") ?: "TXT"
                val idleTimeout = intent.getStringExtra("IDLE_TIMEOUT") ?: "10s"
                val keepAlive = intent.getStringExtra("KEEP_ALIVE") ?: "2s"
                val clientIdSize = intent.getLongExtra("CLIENT_ID_SIZE", 2L)
                val dnsttCompatible = intent.getBooleanExtra("DNSTT_COMPATIBLE", false)
                val useAuth = intent.getBooleanExtra("USE_AUTH", false)
                val protocol = intent.getStringExtra("PROTOCOL") ?: "socks5"
                val ssMethod = intent.getStringExtra("SS_METHOD") ?: ""
                val user = intent.getStringExtra("USER") ?: ""
                val pass = intent.getStringExtra("PASS") ?: ""
                val proxyPort = intent.getLongExtra("PROXY_PORT", 1080L)

                var udp = ""; var doh = ""; var dot = ""
                when (mode.lowercase()) {
                    "udp" -> udp = dnsAddress
                    "doh" -> doh = dnsAddress
                    "dot" -> dot = dnsAddress
                }

                // Call the new Proxy function!
                val result = Mobile.startProxy(
                    isDefaultConfig, configIndex, udp, doh, dot, domain, pubkey,
                    recordType, idleTimeout, keepAlive, clientIdSize, dnsttCompatible,
                    useAuth, protocol, ssMethod, user, pass, proxyPort
                )

                if (result.startsWith("Success")) {
                    val proxyAddress = result.substringAfter("|")

                    sendBroadcast(Intent("VPN_STATE_CHANGED").apply {
                        putExtra("status", "CONNECTED")
                        setPackage(packageName)
                    })
                    updateNotification("Proxy running on 127.0.0.1:$proxyPort")
                    initialRxBytes = 0L
                    initialTxBytes = 0L
                    statsHandler.post(statsRunnable)
                } else if (result.startsWith("Error")) {
                    // Tell the UI that the port was in use
                    val errorMsg = result.substringAfter("|")
                    sendBroadcast(Intent("VPN_STATE_CHANGED").apply {
                        putExtra("status", "ERROR")
                        putExtra("message", errorMsg)
                        setPackage(packageName)
                    })
                    stopSelf()
                } else {
                    sendBroadcast(Intent("VPN_STATE_CHANGED").apply {
                        putExtra("status", "DISCONNECTED")
                        setPackage(packageName)
                    })
                    stopSelf()
                }

            } catch (e: Exception) {
                Log.e("VayProxy", "Error: ${e.message}")
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun updateNotification(status: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, "VAYDNS_CHANNEL")
            .setContentTitle("VayDNS Proxy Active")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(2, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "VAYDNS_CHANNEL", "VayDNS Proxy Service", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        android.util.Log.i("VayProxy", "App swiped away. Shutting down proxy...")
        Thread {
            mobile.Mobile.stopVpn()
            stopSelf()
        }.start()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        android.util.Log.i("VayProxy", "Destroying Proxy Service and freeing ports...")
        statsHandler.removeCallbacks(statsRunnable)
        super.onDestroy()

        // Kill this isolated process to guarantee the SOCKS listener is destroyed
        // This instantly frees Port 1080 for the next connection.
        System.exit(0)
    }
}