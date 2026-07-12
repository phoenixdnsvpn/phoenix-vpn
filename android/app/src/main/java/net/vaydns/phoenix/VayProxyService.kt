package net.vaydns.phoenix

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

    companion object {
        var isServiceAlive = false
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var isStopping = false

    // --- STATS TRACKING VARIABLES ---
    private val statsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var initialRxBytes = 0L
    private var initialTxBytes = 0L
    private var previousRxBytes = 0L
    private var previousTxBytes = 0L
    private var pendingRxSave = 0L
    private var pendingTxSave = 0L
    private var absoluteDailyRx = 0L
    private var absoluteDailyTx = 0L
    private var currentTrackingDate = ""
    private var previousOsRxBytes = 0L
    private var previousOsTxBytes = 0L
    private var pendingOsRxSave = 0L
    private var pendingOsTxSave = 0L
    private var absoluteDailyOsRx = 0L
    private var absoluteDailyOsTx = 0L

    // Session-specific tracking
    private var activeConfigType = "vaydns"
    private var sessionOsRx = 0L
    private var sessionOsTx = 0L

    // Time-based loop tracking (Battery Optimization)
    private var lastStatsRunTime = 0L
    private var lastDbSaveTime = 0L
    private var lastUiUpdateTime = 0L

    private val statsRunnable = object : Runnable {
        override fun run() {
            if (isStopping) return

            val currentTime = System.currentTimeMillis()
            if (lastStatsRunTime == 0L) lastStatsRunTime = currentTime

            // Calculate exactly how much time passed since the last loop
            val elapsedMs = currentTime - lastStatsRunTime
            lastStatsRunTime = currentTime

            // Prevent division by zero; default to 1 sec if called too fast
            val elapsedSec = if (elapsedMs >= 1000) elapsedMs / 1000.0 else 1.0

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
                    val diffRx = if (currentRx > previousRxBytes) currentRx - previousRxBytes else 0L
                    val diffTx = if (currentTx > previousTxBytes) currentTx - previousTxBytes else 0L

                    // Calculate TRUE speed
                    val rxSpeed = (diffRx / elapsedSec).toLong()
                    val txSpeed = (diffTx / elapsedSec).toLong()

                    previousRxBytes = currentRx
                    previousTxBytes = currentTx

                    absoluteDailyRx += diffRx
                    absoluteDailyTx += diffTx
                    pendingRxSave += diffRx
                    pendingTxSave += diffTx

                    // --- NATIVE ANDROID OS STATS MATH (STRICT PROXY ONLY) ---
                    val proxyStats = getProxyInterfaceStats()

                    val currentOsRx = proxyStats.first
                    val currentOsTx = proxyStats.second

                    if (previousOsRxBytes == 0L && previousOsTxBytes == 0L) {
                        previousOsRxBytes = currentOsRx
                        previousOsTxBytes = currentOsTx
                    }

                    // Calculate the payload size for this tick.
                    val diffOsRx = if (currentOsRx >= previousOsRxBytes) currentOsRx - previousOsRxBytes else 0L
                    val diffOsTx = if (currentOsTx >= previousOsTxBytes) currentOsTx - previousOsTxBytes else 0L

                    previousOsRxBytes = currentOsRx
                    previousOsTxBytes = currentOsTx

                    sessionOsRx += diffOsRx
                    sessionOsTx += diffOsTx

                    // OS Speed = Pure Proxy Bytes / Time Elapsed
                    val osRxSpeed = (diffOsRx / elapsedSec).toLong()
                    val osTxSpeed = (diffOsTx / elapsedSec).toLong()

                    absoluteDailyOsRx += diffOsRx
                    absoluteDailyOsTx += diffOsTx
                    pendingOsRxSave += diffOsRx
                    pendingOsTxSave += diffOsTx

                    // ==========================================
                    // 1. DATABASE FLUSH LOGIC (Every ~10 seconds)
                    // ==========================================
                    if (currentTime - lastDbSaveTime >= 10000L) {
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
                        lastDbSaveTime = currentTime
                    }

                    // ==========================================
                    // 2. UI NOTIFICATION LOGIC (Every ~4 seconds)
                    // ==========================================
                    val appPrefs = getSharedPreferences("VayDNSPrefs", Context.MODE_PRIVATE)
                    val notifUpdateMs = appPrefs.getLong("notif_update_ms", 4000L)

                    if (currentTime - lastUiUpdateTime >= notifUpdateMs) {
                        val isDirectMode = activeConfigType.lowercase() == "direct"

                        val displayRxSpeed = if (isDirectMode) osRxSpeed else rxSpeed
                        val displayTxSpeed = if (isDirectMode) osTxSpeed else txSpeed
                        val displayTotalRx = if (isDirectMode) sessionOsRx else currentRx
                        val displayTotalTx = if (isDirectMode) sessionOsTx else currentTx

                        val speedStr = "▼ ${formatBytes(displayRxSpeed)}/s   ▲ ${formatBytes(displayTxSpeed)}/s"
                        val totalStr = "Total: ${formatBytes(displayTotalRx)} ↓   ${formatBytes(displayTotalTx)} ↑"

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
                                .setContentTitle("Phoenix Proxy Active")
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

                        lastUiUpdateTime = currentTime
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("VAY_PROXY", "Error parsing stats: ${e.message}")
            }

            // ==========================================
            // 3. DYNAMIC DELAY (SCREEN ON vs SCREEN OFF)
            // ==========================================
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val isScreenOn = powerManager.isInteractive

            val appPrefs = getSharedPreferences("VayDNSPrefs", Context.MODE_PRIVATE)
            val unlockedDelayMs = appPrefs.getLong("unlocked_delay_ms", 2000L)
            val lockedDelayMs = appPrefs.getLong("locked_delay_ms", 5000L)

            val nextDelay = if (isScreenOn) unlockedDelayMs else lockedDelayMs

            statsHandler.postDelayed(this, nextDelay)
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        isServiceAlive = true

        if (intent == null || intent.action == "ACTION_STOP_VPN") {
            cleanupAndStop()
            return START_NOT_STICKY
        }

        // 1. ACQUIRE WAKELOCK TO SURVIVE SAMSUNG SCREEN LOCK
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Phoenix::ProxyKeepAlive")
        wakeLock?.acquire(12 * 60 * 60 * 1000L) // 12 hours max

        val notification = NotificationCompat.Builder(this, "VAY_PROXY_ACTIVE")
            .setContentTitle("Phoenix Proxy Active")
            .setContentText("Connecting...")
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .build()
        startForeground(2, notification)

        Thread {
            try {
                Mobile.stopVpn()
                Thread.sleep(500)

                val isDefaultConfig = intent.getBooleanExtra("IS_DEFAULT_CONFIG", false)
                val configIndex = intent.getLongExtra("CONFIG_INDEX", 0L)
                val baseDohUrl = intent.getStringExtra("BASE_DOH_URL") ?: ""
                val domain = intent.getStringExtra("DOMAIN") ?: ""
                val domainIndex = intent.getIntExtra("DOMAIN_INDEX", 0)
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
                val authProtocol = intent.getStringExtra("AUTH_PROTOCOL") ?: "socks"
                val ssMethod = intent.getStringExtra("SS_METHOD") ?: ""
                val user = intent.getStringExtra("USER") ?: ""
                val pass = intent.getStringExtra("PASS") ?: ""
                val proxyPort = intent.getLongExtra("PROXY_PORT", 1080L)

                // Initialize Session Variables
                activeConfigType = intent.getStringExtra("CONFIG_TYPE") ?: "vaydns"
                sessionOsRx = 0L
                sessionOsTx = 0L

                var udp = ""; var tcp = ""; var doh = ""; var dot = ""
                when (mode.lowercase()) {
                    "udp" -> udp = dnsAddress
                    "tcp" -> tcp = dnsAddress
                    "doh" -> doh = dnsAddress
                    "dot" -> dot = dnsAddress
                }

                Log.i("VAY_DEBUG", "Starting Pre-Scan from Kotlin...")

                val prefs = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
                val enableScan = prefs.getBoolean("enable_prescan", false)

                var globalDnsServer = prefs.getString("global_dns_server", "")?.trim() ?: ""
                if (globalDnsServer.isEmpty()) {
                    globalDnsServer = "1.1.1.1" // Fallback
                }

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

                    val preScanLightE2E = false
                    val preScanWorkers = 10L
                    val originalRetries = prefs.getInt("retries", 0).toLong()
                    val preScanRetries = if (originalRetries < 1L) 1L else originalRetries

                    finalUdp = if (udp.isNotEmpty()) Mobile.syncPreScanResolvers(isDefaultConfig, configIndex, domainIndex.toLong(), udp, "udp", domain, pubkey, baseDohUrl, proxyType, authProtocol, user, pass, ssMethod, recordType, idleTimeout, keepAlive, clientIdSize, preScanLightE2E, preScanWorkers, tWait, pTimeout, uTimeout, preScanRetries) else ""
                    finalTcp = if (tcp.isNotEmpty()) Mobile.syncPreScanResolvers(isDefaultConfig, configIndex, domainIndex.toLong(), tcp, "tcp", domain, pubkey, baseDohUrl, proxyType, authProtocol, user, pass, ssMethod, recordType, idleTimeout, keepAlive, clientIdSize, preScanLightE2E, preScanWorkers, tWait, pTimeout, uTimeout, preScanRetries) else ""
                    finalDoh = if (doh.isNotEmpty()) Mobile.syncPreScanResolvers(isDefaultConfig, configIndex, domainIndex.toLong(), doh, "doh", domain, pubkey, baseDohUrl, proxyType, authProtocol, user, pass, ssMethod, recordType, idleTimeout, keepAlive, clientIdSize, preScanLightE2E, preScanWorkers, tWait, pTimeout, uTimeout, preScanRetries) else ""
                    finalDot = if (dot.isNotEmpty()) Mobile.syncPreScanResolvers(isDefaultConfig, configIndex, domainIndex.toLong(), dot, "dot", domain, pubkey, baseDohUrl, proxyType, authProtocol, user, pass, ssMethod, recordType, idleTimeout, keepAlive, clientIdSize, preScanLightE2E, preScanWorkers, tWait, pTimeout, uTimeout, preScanRetries) else ""
                }

                Log.i("VAY_DEBUG", "Pre-Scan finished. Establishing TUN interface...")

                val engineType = intent.getStringExtra("ENGINE_TYPE") ?: "sing-box"
                val configType = intent.getStringExtra("CONFIG_TYPE") ?: "vaydns"
                val vlessWsIp = intent.getStringExtra("VLESS_WS_IP") ?: "" 

                // RESTORED: Exact, working parameter list matching your native Go layout
                val result = Mobile.startProxy(
                    engineType, isDefaultConfig, configIndex, configType, useMultiDomains, domainIndex.toLong(), finalUdp, finalTcp, finalDoh, finalDot, baseDohUrl, domain, pubkey,
                    recordType, idleTimeout, keepAlive, clientIdSize, mtu, dnsttCompatible,
                    useAuth, protocol, ssMethod, user, pass, proxyPort.toLong(), vlessWsIp, globalDnsServer
                )

                if (result.startsWith("Success")) {
                    val proxyAddress = result.substringAfter("|")

                    sendBroadcast(Intent("VPN_STATE_CHANGED").apply {
                        putExtra("status", "CONNECTED")
                        putExtra("proxy_address", proxyAddress)
                        setPackage(packageName)
                    })
                    updateNotification("Proxy running on $proxyAddress")
                    initialRxBytes = 0L
                    initialTxBytes = 0L

                    // Reset the loop timers when connection is successful
                    lastStatsRunTime = 0L
                    lastDbSaveTime = 0L
                    lastUiUpdateTime = 0L
                    statsHandler.post(statsRunnable)
                } else if (result.startsWith("Error")) {
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

        // Samsung Fix: Switched from START_NOT_STICKY to START_STICKY
        return START_STICKY
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
            .setContentTitle("Phoenix Proxy Active")
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
                "VAY_PROXY_ACTIVE", "Phoenix Proxy Service", NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Samsung Fix: Prevent system suicide on swipe out. Keep proxy running.
        android.util.Log.i("VayProxy", "App swiped away from recent tasks. Keeping Proxy alive in background...")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        android.util.Log.i("VayProxy", "onDestroy: Proxy Service closed.")

        if (!isStopping) {
            cleanupAndStop()
        }

        super.onDestroy()
    }

    private fun cleanupAndStop() {
        isServiceAlive = false

        if (isStopping) return
        isStopping = true

        Log.i("VAY_PROXY", "PURGE: Initiating Graceful Self-Destruct...")
        stopForeground(STOP_FOREGROUND_REMOVE)
        statsHandler.removeCallbacks(statsRunnable)
        flushPendingTraffic()

        Thread {
            // 1. ISOLATE GO SHUTDOWN
            Thread {
                try {
                    Mobile.stopVpn()
                } catch (e: Exception) {
                    Log.e("VAY_PROXY", "Stop error: ${e.message}")
                }
            }.start()

            // 2. GUARANTEED WAKELOCK RELEASE
            try {
                wakeLock?.let {
                    if (it.isHeld) it.release()
                }
            } catch (e: Exception) {
                Log.e("VAY_PROXY", "WakeLock release error: ${e.message}")
            }

            // 3. POLITE ANDROID SHUTDOWN
            stopSelf()

            // 4. THE OS GUILLOTINE
            Thread.sleep(1500)

            // 5. THE SANDBOX FLUSH
            System.exit(0)
        }.start()
    }

    private fun getProxyInterfaceStats(): Pair<Long, Long> {
        val uidRx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
        val uidTx = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())

        if (uidRx > 0 || uidTx > 0) {
            return Pair(uidRx / 2, uidTx / 2)
        }

        return Pair(0L, 0L)
    }
}
