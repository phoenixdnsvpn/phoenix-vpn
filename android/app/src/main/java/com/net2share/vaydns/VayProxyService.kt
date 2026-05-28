package com.net2share.vaydns

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Context
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
    private var pendingRxSave = 0L
    private var pendingTxSave = 0L
    private var statsTickCount = 0
    private var absoluteDailyRx = 0L
    private var absoluteDailyTx = 0L
    private var currentTrackingDate = ""
    private var previousOsRxBytes = 0L
    private var previousOsTxBytes = 0L
    private var pendingOsRxSave = 0L
    private var pendingOsTxSave = 0L
    private var absoluteDailyOsRx = 0L
    private var absoluteDailyOsTx = 0L

    private val statsRunnable = object : Runnable {
        override fun run() {
            if (isStopping) return

            try {
                // 1. Fetch exact bytes from the Go Engine
                val stats = mobile.Mobile.getProxyStats()
                val parts = stats.split("|")

                if (parts.size == 2) {
                    val currentRx = parts[0].toLong()
                    val currentTx = parts[1].toLong()

                    val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                    if (currentTrackingDate != dateStr) {
                        val prefs = getSharedPreferences("VayDNS_Traffic", Context.MODE_PRIVATE)
                        absoluteDailyRx = prefs.getLong("rx_$dateStr", 0L)
                        absoluteDailyTx = prefs.getLong("tx_$dateStr", 0L)
                        absoluteDailyOsRx = prefs.getLong("os_rx_$dateStr", 0L)
                        absoluteDailyOsTx = prefs.getLong("os_tx_$dateStr", 0L)
                        currentTrackingDate = dateStr
                    }

                    // --- GO ENGINE STATS MATH ---
                    val diffRx = currentRx - previousRxBytes
                    val diffTx = currentTx - previousTxBytes
                    val rxSpeed = if (diffRx < 0) 0L else diffRx
                    val txSpeed = if (diffTx < 0) 0L else diffTx
                    previousRxBytes = currentRx
                    previousTxBytes = currentTx
                    absoluteDailyRx += rxSpeed
                    absoluteDailyTx += txSpeed
                    pendingRxSave += rxSpeed
                    pendingTxSave += txSpeed

                    // --- NATIVE ANDROID OS STATS MATH ---
                    val osRx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
                    val osTx = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())
                    val currentOsRx = if (osRx < 0) 0L else osRx
                    val currentOsTx = if (osTx < 0) 0L else osTx

                    // Init on first tick to prevent spiking from old app usage before connection
                    if (previousOsRxBytes == 0L && previousOsTxBytes == 0L) {
                        previousOsRxBytes = currentOsRx
                        previousOsTxBytes = currentOsTx
                    }

                    val diffOsRx = currentOsRx - previousOsRxBytes
                    val diffOsTx = currentOsTx - previousOsTxBytes

                    // Subtract the internal Go payload from the total Android OS traffic
                    val rawOsRxSpeed = diffOsRx - rxSpeed
                    val rawOsTxSpeed = diffOsTx - txSpeed

                    // Clamp to 0 to prevent negative values
                    val osRxSpeed = if (rawOsRxSpeed < 0) 0L else rawOsRxSpeed
                    val osTxSpeed = if (rawOsTxSpeed < 0) 0L else rawOsTxSpeed

                    previousOsRxBytes = currentOsRx
                    previousOsTxBytes = currentOsTx

                    absoluteDailyOsRx += osRxSpeed
                    absoluteDailyOsTx += osTxSpeed
                    pendingOsRxSave += osRxSpeed
                    pendingOsTxSave += osTxSpeed

                    statsTickCount++

                    if (statsTickCount >= 10) {
                        if (pendingRxSave > 0 || pendingTxSave > 0 || pendingOsRxSave > 0 || pendingOsTxSave > 0) {
                            val prefs = getSharedPreferences("VayDNS_Traffic", Context.MODE_PRIVATE)
                            val dailyRx = prefs.getLong("rx_$dateStr", 0L) + pendingRxSave
                            val dailyTx = prefs.getLong("tx_$dateStr", 0L) + pendingTxSave
                            val dailyOsRx = prefs.getLong("os_rx_$dateStr", 0L) + pendingOsRxSave
                            val dailyOsTx = prefs.getLong("os_tx_$dateStr", 0L) + pendingOsTxSave

                            prefs.edit()
                                .putLong("rx_$dateStr", dailyRx)
                                .putLong("tx_$dateStr", dailyTx)
                                .putLong("os_rx_$dateStr", dailyOsRx)
                                .putLong("os_tx_$dateStr", dailyOsTx)
                                .apply()

                            pendingRxSave = 0L
                            pendingTxSave = 0L
                            pendingOsRxSave = 0L
                            pendingOsTxSave = 0L
                        }
                        statsTickCount = 0
                    }

                    val speedStr = "▼ ${formatBytes(rxSpeed)}/s   ▲ ${formatBytes(txSpeed)}/s"
                    val totalStr = "Total: ${formatBytes(currentRx)} ↓   ${formatBytes(currentTx)} ↑"

                    sendBroadcast(Intent("VPN_STATS_UPDATE").apply {
                        putExtra("speed", speedStr)
                        putExtra("total", totalStr)
                        putExtra("liveDailyRx", absoluteDailyRx)
                        putExtra("liveDailyTx", absoluteDailyTx)
                        putExtra("liveDailyOsRx", absoluteDailyOsRx)
                        putExtra("liveDailyOsTx", absoluteDailyOsTx)
                        setPackage(packageName)
                    })

                    // UPDATE THE LOCK SCREEN NOTIFICATION
                    try {
                        val intent = Intent(this@VayProxyService, MainActivity::class.java)
                        val pendingIntent = PendingIntent.getActivity(this@VayProxyService, 0, intent, PendingIntent.FLAG_IMMUTABLE)

                        val updateNotification = androidx.core.app.NotificationCompat.Builder(this@VayProxyService, "VAY_PROXY_ACTIVE")
                            .setContentTitle("VayDNS Proxy Active")
                            .setContentText(speedStr)
                            .setSmallIcon(R.drawable.ic_vpn_key)
                            .setOngoing(true)
                            .setContentIntent(pendingIntent)
                            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
                            .setOnlyAlertOnce(true)
                            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                            .build()

                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(2, updateNotification)
                    } catch (e: Exception) {
                        android.util.Log.e("VAY_PROXY", "Failed to update notification: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("VAY_PROXY", "Error parsing stats: ${e.message}")
            }

            // Always schedule the next tick
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
            flushPendingTraffic()

            Thread {
                Mobile.stopVpn()
                stopSelf()
            }.start()
            return START_NOT_STICKY
        }

        val notification = NotificationCompat.Builder(this, "VAY_PROXY_ACTIVE")
            .setContentTitle("VayDNS Proxy Active")
            .setContentText("Connecting...")
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .build()
        startForeground(2, notification) // Use ID 2 to separate from VPN
        startForeground(2, notification) // Use ID 2 to separate from VPN

        Thread {
            try {
                Mobile.stopVpn()
                Thread.sleep(500)

                val isDefaultConfig = intent.getBooleanExtra("IS_DEFAULT_CONFIG", false)
                val configIndex = intent.getLongExtra("CONFIG_INDEX", 0L)
                val baseDohUrl = intent?.getStringExtra("BASE_DOH_URL") ?: ""
                val domain = intent.getStringExtra("DOMAIN") ?: ""
                val pubkey = (intent.getStringExtra("PUBKEY") ?: "").replace("\\s".toRegex(), "")
                val dnsAddress = intent.getStringExtra("UDP") ?: "8.8.8.8:53"
                val mode = intent.getStringExtra("MODE") ?: "udp"
                val recordType = intent.getStringExtra("RECORD_TYPE") ?: "TXT"
                val idleTimeout = intent.getStringExtra("IDLE_TIMEOUT") ?: "10s"
                val keepAlive = intent.getStringExtra("KEEP_ALIVE") ?: "2s"
                val clientIdSize = intent.getLongExtra("CLIENT_ID_SIZE", 2L)
                val mtu = intent.getLongExtra("MTU", 0L)
                val dnsttCompatible = intent.getBooleanExtra("DNSTT_COMPATIBLE", false)
                val useMultiDomains = intent.getBooleanExtra("USE_MULTI_DOMAINS", false)
                val useAuth = intent.getBooleanExtra("USE_AUTH", false)
                val protocol = intent.getStringExtra("PROTOCOL") ?: "socks5"
                val authProtocol = intent?.getStringExtra("AUTH_PROTOCOL") ?: "socks"
                val ssMethod = intent.getStringExtra("SS_METHOD") ?: ""
                val user = intent.getStringExtra("USER") ?: ""
                val pass = intent.getStringExtra("PASS") ?: ""
                val proxyPort = intent.getLongExtra("PROXY_PORT", 1080L)

                var udp = ""; var tcp = ""; var doh = ""; var dot = ""
                when (mode.lowercase()) {
                    "udp" -> udp = dnsAddress
                    "tcp" -> tcp = dnsAddress
                    "doh" -> doh = dnsAddress
                    "dot" -> dot = dnsAddress
                }

                Log.i("VAY_DEBUG", "Starting Pre-Scan from Kotlin...")

                // Note: Go's 'int' for clientIdSize is converted to 'Long' in Kotlin by Gomobile
                // --- LOAD PRE-SCANNER SETTINGS ---
                val prefs = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
                val enableScan = prefs.getBoolean("enable_prescan", false)

                var finalUdp = udp
                var finalTcp = tcp
                var finalDoh = doh
                var finalDot = dot

                if (enableScan) {
                    Log.i("VAY_DEBUG", "Running Custom Pre-Tunnel Scan...")
                    val proxyType = prefs.getString("proxy_type", "socks5h") ?: "socks5h"
                    val tWait = prefs.getInt("tunnel_wait", 3000).toLong()
                    val pTimeout = prefs.getInt("probe_timeout", 15000).toLong()
                    val uTimeout = prefs.getInt("udp_timeout", 1000).toLong()

                    // --- BACKGROUND OVERRIDES FOR PRE-TUNNEL SCAN ---
                    val preScanLightE2E = false // Force True E2E
                    val preScanWorkers = 10L    // Force 10 workers for all modes
                    val originalRetries = prefs.getInt("retries", 0).toLong()
                    val preScanRetries = if (originalRetries < 1L) 1L else originalRetries // max(1, currently set)
                    //val lightE2E = prefs.getBoolean("light_e2e", false)
                    //val workers = prefs.getInt("workers", 20).toLong()
                    //val retries = prefs.getInt("retries", 0).toLong()

                    finalUdp = if (udp.isNotEmpty()) Mobile.syncPreScanResolvers(isDefaultConfig, configIndex, udp, "udp", domain, pubkey, baseDohUrl, proxyType, authProtocol, user, pass, ssMethod, recordType, idleTimeout, keepAlive, clientIdSize, preScanLightE2E, preScanWorkers, tWait, pTimeout, uTimeout, preScanRetries) else ""
                    finalTcp = if (tcp.isNotEmpty()) Mobile.syncPreScanResolvers(isDefaultConfig, configIndex, tcp, "tcp", domain, pubkey, baseDohUrl, proxyType, authProtocol, user, pass, ssMethod, recordType, idleTimeout, keepAlive, clientIdSize, preScanLightE2E, preScanWorkers, tWait, pTimeout, uTimeout, preScanRetries) else ""
                    finalDoh = if (doh.isNotEmpty()) Mobile.syncPreScanResolvers(isDefaultConfig, configIndex, doh, "doh", domain, pubkey, baseDohUrl, proxyType, authProtocol, user, pass, ssMethod, recordType, idleTimeout, keepAlive, clientIdSize, preScanLightE2E, preScanWorkers, tWait, pTimeout, uTimeout, preScanRetries) else ""
                    finalDot = if (dot.isNotEmpty()) Mobile.syncPreScanResolvers(isDefaultConfig, configIndex, dot, "dot", domain, pubkey, baseDohUrl, proxyType, authProtocol, user, pass, ssMethod, recordType, idleTimeout, keepAlive, clientIdSize, preScanLightE2E, preScanWorkers, tWait, pTimeout, uTimeout, preScanRetries) else ""

                    //finalUdp = if (udp.isNotEmpty()) Mobile.syncPreScanResolvers(isDefaultConfig, configIndex, udp, "udp", domain, pubkey, baseDohUrl, proxyType, authProtocol, user, pass, ssMethod, recordType, idleTimeout, keepAlive, clientIdSize, lightE2E, workers, tWait, pTimeout, uTimeout, retries) else ""
                    //finalTcp = if (tcp.isNotEmpty()) Mobile.syncPreScanResolvers(isDefaultConfig, configIndex, tcp, "tcp", domain, pubkey, baseDohUrl, proxyType, authProtocol, user, pass, ssMethod, recordType, idleTimeout, keepAlive, clientIdSize, lightE2E, workers, tWait, pTimeout, uTimeout, retries) else ""
                    //finalDoh = if (doh.isNotEmpty()) Mobile.syncPreScanResolvers(isDefaultConfig, configIndex, doh, "doh", domain, pubkey, baseDohUrl, proxyType, authProtocol, user, pass, ssMethod, recordType, idleTimeout, keepAlive, clientIdSize, lightE2E, workers, tWait, pTimeout, uTimeout, retries) else ""
                    //finalDot = if (dot.isNotEmpty()) Mobile.syncPreScanResolvers(isDefaultConfig, configIndex, dot, "dot", domain, pubkey, baseDohUrl, proxyType, authProtocol, user, pass, ssMethod, recordType, idleTimeout, keepAlive, clientIdSize, lightE2E, workers, tWait, pTimeout, uTimeout, retries) else ""
                }

                Log.i("VAY_DEBUG", "Pre-Scan finished. Establishing TUN interface...")

                // Call the new Proxy function!
                val result = Mobile.startProxy(
                    isDefaultConfig, configIndex, useMultiDomains, finalUdp, finalTcp,finalDoh, finalDot, baseDohUrl, domain, pubkey,
                    recordType, idleTimeout, keepAlive, clientIdSize, mtu, dnsttCompatible,
                    useAuth, protocol, ssMethod, user, pass, proxyPort
                )

                if (result.startsWith("Success")) {
                    val proxyAddress = result.substringAfter("|")

                    sendBroadcast(Intent("VPN_STATE_CHANGED").apply {
                        putExtra("status", "CONNECTED")
                        putExtra("proxy_address", proxyAddress)
                        setPackage(packageName)
                    })
                    //updateNotification("Proxy running on 127.0.0.1:$proxyPort")
                    updateNotification("Proxy running on $proxyAddress")
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

    private fun flushPendingTraffic() {
        if (pendingRxSave > 0 || pendingTxSave > 0 || pendingOsRxSave > 0 || pendingOsTxSave > 0) {
            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val prefs = getSharedPreferences("VayDNS_Traffic", Context.MODE_PRIVATE)
            prefs.edit()
                .putLong("rx_$dateStr", absoluteDailyRx)
                .putLong("tx_$dateStr", absoluteDailyTx)
                .putLong("os_rx_$dateStr", absoluteDailyOsRx)
                .putLong("os_tx_$dateStr", absoluteDailyOsTx)
                .apply()
            pendingRxSave = 0L
            pendingTxSave = 0L
            pendingOsRxSave = 0L
            pendingOsTxSave = 0L
        }
    }

    private fun updateNotification(status: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, "VAY_PROXY_ACTIVE")
            .setContentTitle("VayDNS Proxy Active")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(2, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "VAY_PROXY_ACTIVE", "VayDNS Proxy Service", NotificationManager.IMPORTANCE_DEFAULT
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
        flushPendingTraffic()
        super.onDestroy()

        // Kill this isolated process to guarantee the SOCKS listener is destroyed
        // This instantly frees Port 1080 for the next connection.
        System.exit(0)
    }
}