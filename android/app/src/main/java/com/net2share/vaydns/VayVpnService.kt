package com.net2share.vaydns

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import mobile.Mobile
import mobile.SocketProtector
import java.net.InetAddress
import android.net.TrafficStats
import java.util.Locale

// The Protector implementation that Go will call for every UDP/TCP socket

class VayVpnService : VpnService() {
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private var isStopping = false
    private var isStarting = false
    // 1. Move protector to class level
    private var protector: AndroidProtector? = null
    private var builder: Builder = Builder()
    // The 'Lock' ensures only one thread can talk to Go at a time
    companion object {
        private val goLock = Any()
    }
    private val statsHandler = Handler(Looper.getMainLooper())
    private var initialRxBytes = 0L
    private var initialTxBytes = 0L
    private var previousRxBytes = 0L
    private var previousTxBytes = 0L
    private var pendingRxSave = 0L
    private var pendingTxSave = 0L
    // private var statsTickCount = 0
    // private var notificationTickCount = 0
    private var absoluteDailyRx = 0L
    private var absoluteDailyTx = 0L
    private var currentTrackingDate = ""
    private var previousOsRxBytes = 0L
    private var previousOsTxBytes = 0L
    private var pendingOsRxSave = 0L
    private var pendingOsTxSave = 0L
    private var absoluteDailyOsRx = 0L
    private var absoluteDailyOsTx = 0L
    private var activeConfigType = "vaydns"
    private var sessionOsRx = 0L
    private var sessionOsTx = 0L
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
                    // Calculate RAW volume
                    val diffRx = if (currentRx > previousRxBytes) currentRx - previousRxBytes else 0L
                    val diffTx = if (currentTx > previousTxBytes) currentTx - previousTxBytes else 0L

                    // Calculate TRUE speed (Volume / Time Elapsed)
                    val rxSpeed = (diffRx / elapsedSec).toLong()
                    val txSpeed = (diffTx / elapsedSec).toLong()

                    previousRxBytes = currentRx
                    previousTxBytes = currentTx

                    // Add RAW volume to totals
                    absoluteDailyRx += diffRx
                    absoluteDailyTx += diffTx
                    pendingRxSave += diffRx
                    pendingTxSave += diffTx

                    // --- NATIVE ANDROID OS STATS MATH ---
                    val osRx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
                    val osTx = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())
                    val currentOsRx = if (osRx < 0) 0L else osRx
                    val currentOsTx = if (osTx < 0) 0L else osTx

                    if (previousOsRxBytes == 0L && previousOsTxBytes == 0L) {
                        previousOsRxBytes = currentOsRx
                        previousOsTxBytes = currentOsTx
                    }

                    // Calculate RAW OS volume
                    val diffOsRx = if (currentOsRx > previousOsRxBytes) currentOsRx - previousOsRxBytes else 0L
                    val diffOsTx = if (currentOsTx > previousOsTxBytes) currentOsTx - previousOsTxBytes else 0L

                    sessionOsRx += diffOsRx
                    sessionOsTx += diffOsTx

                    // Subtract the internal Go payload from total Android traffic (RAW BYTES)
                    val pureOsDiffRx = if (diffOsRx > diffRx) diffOsRx - diffRx else 0L
                    val pureOsDiffTx = if (diffOsTx > diffTx) diffOsTx - diffTx else 0L

                    // OS Speed = Pure OS Bytes / Time Elapsed
                    val osRxSpeed = (pureOsDiffRx / elapsedSec).toLong()
                    val osTxSpeed = (pureOsDiffTx / elapsedSec).toLong()

                    previousOsRxBytes = currentOsRx
                    previousOsTxBytes = currentOsTx

                    absoluteDailyOsRx += pureOsDiffRx
                    absoluteDailyOsTx += pureOsDiffTx
                    pendingOsRxSave += pureOsDiffRx
                    pendingOsTxSave += pureOsDiffTx

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
                            val intent = Intent(this@VayVpnService, MainActivity::class.java)
                            val pendingIntent = PendingIntent.getActivity(this@VayVpnService, 0, intent, PendingIntent.FLAG_IMMUTABLE)

                            val updateNotification = androidx.core.app.NotificationCompat.Builder(this@VayVpnService, "VAY_CHANNEL_ACTIVE")
                                .setContentTitle("VayDNS VPN")
                                .setContentText(speedStr)
                                .setSmallIcon(com.net2share.vaydns.R.drawable.ic_vpn_key)
                                .setOngoing(true)
                                .setContentIntent(pendingIntent)
                                .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
                                .setOnlyAlertOnce(true)
                                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                                .build()

                            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            nm.notify(1, updateNotification)
                        } catch (e: Exception) {
                            android.util.Log.e("VAY_VPN", "Failed to update notification: ${e.message}")
                        }

                        lastUiUpdateTime = currentTime
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("VAY_VPN", "Error parsing stats: ${e.message}")
            }

            // ==========================================
            // 3. DYNAMIC DELAY (SCREEN ON vs SCREEN OFF)
            // ==========================================
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val isScreenOn = powerManager.isInteractive

            // 2 seconds if unlocked, 5 seconds if locked/asleep
            // val nextDelay = if (isScreenOn) 2000L else 5000L
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
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }
    private class AndroidProtector(private var service: VpnService?) : SocketProtector {
        @Volatile
        private var active = true

        fun deactivate() {
            active = false
            service = null
        }

        override fun protect(fd: Long): Boolean {
            val s = service
            // If we are stopping, return TRUE.
            // This stops Go from retrying the dialer and clears the log flood.
            if (!active || s == null) return true

            return try {
                s.protect(fd.toInt())
            } catch (e: Exception) {
                true // Return true on error during shutdown
            }
        }
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

    override fun onCreate() {
        super.onCreate()
        // Reset the gatekeeper so the STOP button works for this new session
        isStopping = false
        Log.i("VayDNS", "VpnService Created - isStopping reset to false")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. Reset state and handle Stop intent

        createNotificationChannel()

        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == "ACTION_STOP_VPN") {
            isStopping = true
            stopForeground(STOP_FOREGROUND_REMOVE)

            Thread {
                synchronized(goLock) {
                    try {
                        protector?.deactivate()
                        protector = null

                        // 1. Tell Go to stop (Go's engine closes the detached goFd)
                        Mobile.stopVpn()
                        Thread.sleep(500)
                        // 2. Close the original Android interface (Removes Blue Key)
                        tunInterface?.close()
                        tunInterface = null
                    } finally {
                        stopSelf()
                        isStopping = false
                    }
                }
            }.start()
            return START_NOT_STICKY
        }

        // 2. Initial Notification to satisfy Foreground Service requirements
        val notification = Notification.Builder(this, "VAY_CHANNEL_ACTIVE")
            .setContentTitle("VayDNS Tunnel Active")
            .setContentText("Connecting to server...")
//            .setSmallIcon(R.drawable.ic_dialog_info)
            .setSmallIcon(com.net2share.vaydns.R.drawable.ic_vpn_key)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        startForeground(1, notification)

        Thread {
            // CRITICAL: Use the lock to ensure onDestroy and onStart don't fight
            synchronized(goLock) {
                try {
                    isStopping = false
                    // 3. SAFE CLEANUP: If there is a "ghost" engine, kill it and wait
                    Log.i("VayDNS", "Checking for existing native instances...")

                    updateNotification("Status: Connecting...")

                    Mobile.stopVpn()
                    //tunInterface = null
                    // Give the Linux kernel 500ms to truly release the UDP ports
                    Thread.sleep(500)

                    // 4. Extract data passed from MainActivity
                    val isDefaultConfig = intent?.getBooleanExtra("IS_DEFAULT_CONFIG", false) ?: false

                    val configIndex = intent?.getLongExtra("CONFIG_INDEX", 0L) ?: 0L
                    val configType = intent?.getStringExtra("CONFIG_TYPE") ?: "vaydns"
                    val domain = intent?.getStringExtra("DOMAIN") ?: ""
                    val pubkey = (intent?.getStringExtra("PUBKEY") ?: "").replace("\\s".toRegex(), "")
                    val baseDohUrl = intent?.getStringExtra("BASE_DOH_URL") ?: ""
                    val dnsAddress = intent?.getStringExtra("UDP") ?: "8.8.8.8:53"
                    val mode = intent?.getStringExtra("MODE") ?: "udp"
                    val recordType = intent?.getStringExtra("RECORD_TYPE") ?: "TXT"
                    val idleTimeout = intent?.getStringExtra("IDLE_TIMEOUT") ?: "10s"
                    val keepAlive = intent?.getStringExtra("KEEP_ALIVE") ?: "2s"
                    val clientIdSize = intent?.getLongExtra("CLIENT_ID_SIZE", 2L) ?: 2L
                    val mtu = intent.getLongExtra("MTU", 0L)
                    val dnsttCompatible = intent?.getBooleanExtra("DNSTT_COMPATIBLE", false) ?: false
                    val useMultiDomains = intent?.getBooleanExtra("USE_MULTI_DOMAINS", false) ?: false
                    val useAuth = intent?.getBooleanExtra("USE_AUTH", false) ?: false
                    val protocol = intent?.getStringExtra("PROTOCOL") ?: "socks5"
                    val authProtocol = intent?.getStringExtra("AUTH_PROTOCOL") ?: "socks"
                    val ssMethod = intent?.getStringExtra("SS_METHOD") ?: "chacha20-ietf-poly1305"
                    val user = intent?.getStringExtra("USER") ?: ""
                    val pass = intent?.getStringExtra("PASS") ?: ""
                    val engineType = intent?.getStringExtra("ENGINE_TYPE") ?: "sing-box"
                    activeConfigType = intent?.getStringExtra("CONFIG_TYPE") ?: "vaydns"
                    val vlessWsIp = intent.getStringExtra("VLESS_WS_IP") ?: ""
                    sessionOsRx = 0L
                    sessionOsTx = 0L

                    mobile.Mobile.initVault(filesDir.absolutePath)

                    var udp = ""
                    var tcp = ""
                    var doh = ""
                    var dot = ""
                    when (mode.lowercase()) {
                        "udp" -> udp = dnsAddress
                        "tcp" -> tcp = dnsAddress
                        "doh" -> doh = dnsAddress
                        "dot" -> dot = dnsAddress
                    }

                    // Resolve NS domain
                    val serverIp = try {
                        InetAddress.getByName(domain).hostAddress
                    } catch (e: Exception) { null }

                    // 5. Establish the VPN Interface
                    builder = Builder()
                    builder.setSession("VayDNS Tunnel Active")
                        .addAddress("10.0.0.2", 24)
                        .addDnsServer("8.8.8.8")
                        .setMtu(1232)
                        .addRoute("0.0.0.0", 0)
                        .setBlocking(false)

                    // Bypass routing for the server itself and the resolver
                    //if (serverIp != null && isValidIp(serverIp)) builder.addRoute(serverIp, 32)
                    /*if (dnsAddress.contains(":")) {
                        val resolver = dnsAddress.substringBefore(":")
                        if (isValidIp(resolver)) builder.addRoute(resolver, 32)
                    }*/
                    val tunnelPrefs = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
                    val activeProtocol = tunnelPrefs.getString("active_protocol", "vaydns") ?: "vaydns"

                    // Bypass routing for the main proxy target
                    var primaryBypassIp = serverIp

                    // If using Vless-WS, fetch the exact Cloudflare IP from Go's memory to prevent infinite routing loops
                    if (activeProtocol == "vless-ws") {
                        val vlessTarget = mobile.Mobile.getActiveVlessWsIP(configIndex)
                        try {
                            // Resolve to IP address safely just in case it is a domain name
                            primaryBypassIp = InetAddress.getByName(vlessTarget).hostAddress
                        } catch (e: Exception) {
                            primaryBypassIp = vlessTarget
                        }
                    }

                    // Exclude the target IP from the VPN tunnel interface
                    if (primaryBypassIp != null && isValidIp(primaryBypassIp)) {
                        Log.i("VAY_DEBUG", "Excluding Proxy IP from VPN Routing Table: $primaryBypassIp")
                        builder.addRoute(primaryBypassIp, 32)
                    }

                    // Safely extract the IP for routing bypass (Supports UDP, DoT, and DoH)
                    var bypassIp = dnsAddress
                    if (bypassIp.startsWith("http")) {
                        try {
                            bypassIp = java.net.URL(bypassIp).host
                        } catch (e: Exception) {}
                    } else if (bypassIp.contains(":")) {
                        bypassIp = bypassIp.substringBefore(":")
                    }

                    if (isValidIp(bypassIp)) {
                        builder.addRoute(bypassIp, 32)
                    }

                    // App Filtering
                    val selectedApps = intent?.getStringArrayListExtra("ALLOWED_APPS_LIST")?.toSet() ?: emptySet()

                    if (selectedApps.isNotEmpty()) {
                        for (pkg in selectedApps) {
                            try {
                                builder.addAllowedApplication(pkg)
                            } catch (e: PackageManager.NameNotFoundException) {
                                Log.e("VayDNS", "App not found (might be uninstalled): $pkg")
                            } catch (e: Exception) {
                                Log.e("VayDNS", "Could not tunnel app: $pkg")
                            }
                        }
                    } else {
                        // Default: If nothing is selected, only tunnel VayDNS itself
                        try { builder.addAllowedApplication(packageName) } catch (e: Exception) {}
                    }

                    // 1. RUN THE PRE-SCAN BEFORE ESTABLISHING THE TUNNEL
                    // This uses raw Wi-Fi/Data because the TUN interface doesn't exist yet!
                    Log.i("VAY_DEBUG", "Starting Pre-Scan from Kotlin...")

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
                        //val retries = prefs.getInt("retries", 0).toLong()
                        //val lightE2E = prefs.getBoolean("light_e2e", false)
                        //val workers = prefs.getInt("workers", 20).toLong()

                        // --- BACKGROUND OVERRIDES FOR PRE-TUNNEL SCAN ---
                        val preScanLightE2E = false // Force True E2E
                        val preScanWorkers = 10L    // Force 10 workers for all modes
                        val originalRetries = prefs.getInt("retries", 0).toLong()
                        val preScanRetries = if (originalRetries < 1L) 1L else originalRetries // max(1, currently set)

                        finalUdp = if (udp.isNotEmpty()) Mobile.syncPreScanResolvers(isDefaultConfig, configIndex, udp, "udp", domain, pubkey, baseDohUrl, proxyType, authProtocol, user, pass, ssMethod, recordType, idleTimeout, keepAlive, clientIdSize, preScanLightE2E, preScanWorkers, tWait, pTimeout, uTimeout, preScanRetries) else ""
                        finalTcp = if (tcp.isNotEmpty()) Mobile.syncPreScanResolvers(isDefaultConfig, configIndex, tcp, "tcp", domain, pubkey, baseDohUrl, proxyType, authProtocol, user, pass, ssMethod, recordType, idleTimeout, keepAlive, clientIdSize, preScanLightE2E, preScanWorkers, tWait, pTimeout, uTimeout, preScanRetries) else ""
                        finalDoh = if (doh.isNotEmpty()) Mobile.syncPreScanResolvers(isDefaultConfig, configIndex, doh, "doh", domain, pubkey, baseDohUrl, proxyType, authProtocol, user, pass, ssMethod, recordType, idleTimeout, keepAlive, clientIdSize, preScanLightE2E, preScanWorkers, tWait, pTimeout, uTimeout, preScanRetries) else ""
                        finalDot = if (dot.isNotEmpty()) Mobile.syncPreScanResolvers(isDefaultConfig, configIndex, dot, "dot", domain, pubkey, baseDohUrl, proxyType, authProtocol, user, pass, ssMethod, recordType, idleTimeout, keepAlive, clientIdSize, preScanLightE2E, preScanWorkers, tWait, pTimeout, uTimeout, preScanRetries) else ""
                    }

                    Log.i("VAY_DEBUG", "Pre-Scan finished. Establishing TUN interface...")
                    // Extract the manually set Front IP configuration
                    //val tunnelPrefs = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
                    // val vlessWsIp = tunnelPrefs.getString("vless_ws_ip", "") ?: ""

                    protector = AndroidProtector(this@VayVpnService)

                    val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "VayDNS::VpnKeepAlive")
                    wakeLock?.acquire(12 * 60 * 60 * 1000L /*12 hours max safety limit*/)

                    tunInterface = builder.establish()
                    if (tunInterface == null) return@synchronized

                    //val fd = tunInterface!!.detachFd()
                    //tunInterface = null

                    //val dupPfd = tunInterface!!.dup()
                    //val fd = dupPfd.detachFd()
                    //val fd = tunInterface?.detachFd() ?: -1
                    val fd = tunInterface?.fd ?: -1

                    //val fd = tunInterface?.detachFd() ?: -1
                    // 2. Immediately wipe the reference so Java 'forgets' it
                    //tunInterface = null

                    // 6. Start Native Engine

                    if (fd != -1) {
                        val result = Mobile.startVpn(
                            fd.toLong(),
                            engineType,
                            isDefaultConfig,
                            configIndex,
                            configType,
                            useMultiDomains,
                            finalUdp,
                            finalTcp,
                            finalDoh,
                            finalDot,
                            baseDohUrl,
                            domain,
                            pubkey,
                            recordType,
                            idleTimeout,
                            keepAlive,
                            clientIdSize.toLong(),
                            mtu.toLong(),
                            dnsttCompatible,
                            useAuth,
                            protocol,
                            authProtocol,
                            ssMethod,
                            user,
                            pass,
                            vlessWsIp,
                            protector
                        )
                        Log.i("VayDNS", "VPN Started with FD: $fd")

                        if (result.contains("Success")) {
                            runVerificationLogic() // Move verification to a helper to keep this clean
                        } else {
                            updateNotification("Engine Failed to Start")
                            sendBroadcast(Intent("VPN_STATE_CHANGED").apply {
                                putExtra("status", "DISCONNECTED")
                                setPackage(packageName)
                            })
                        }

                    } else {
                        Log.e("VayDNS", "Failed to start: Tunnel interface was null")
                    }

                } catch (e: Exception) {
                    Log.e("VayDNS", "VPN Start Exception: ${e.message}", e)
                    updateNotification("Error starting tunnel")
                }
            } // End of synchronized block
        }.start()

        return START_NOT_STICKY
    }

    private fun runVerificationLogic() {
        updateNotification("Handshaking with server...")

        Thread {
            // 1. Give the Go engine 2 seconds to boot up the SOCKS5 listener
            Thread.sleep(2000)

            // 2. Call our Go-level verification function
            val verifyResult = Mobile.verifyTunnel()

            // 3. Safety Check: If the user clicked STOP while we were waiting, abort!
            if (isStopping) return@Thread

            if (verifyResult.contains("Success")) {
                Log.i("VayDNS", "Go-Level verification passed!")
                sendBroadcast(Intent("VPN_STATE_CHANGED").apply {
                    putExtra("status", "CONNECTED")
                    setPackage(packageName)
                })
                updateNotification("Status: Connected")
                initialRxBytes = 0L
                initialTxBytes = 0L
                statsHandler.post(statsRunnable)
            } else {
                Log.e("VayDNS", "Go-Level verification failed: $verifyResult")

                sendBroadcast(Intent("VPN_STATE_CHANGED").apply {
                    putExtra("status", "DISCONNECTED")
                    setPackage(packageName)
                })
                updateNotification("Connection Failed")

                // On failure, trigger the cleanup synchronously
                Handler(Looper.getMainLooper()).post {
                    cleanupAndStop()
                }
            }
        }.start()
    }

    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "VAY_CHANNEL_ACTIVE",
                "VayDNS Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status of VayDNS Tunnel"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, "VAY_CHANNEL_ACTIVE")
            .setContentTitle("VayDNS VPN")
            .setContentText(status) // This is where "Connected" or "Connecting..." goes
            .setSmallIcon(com.net2share.vaydns.R.drawable.ic_vpn_key)
//            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true) // Prevents the user from swiping it away
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // CRITICAL: Shows the content on the lock screen
            .setOnlyAlertOnce(true) // Updates the text silently without beeping/vibrating every second
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // Matches channel importance
            .build()

        // ID 1 matches the ID used in startForeground()
        notificationManager.notify(1, notification)
    }

    // Helper function to check if the string is an IP (add this to the class)
    private fun isValidIp(ip: String): Boolean {
        return ip.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}"""))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "VAY_CHANNEL_ACTIVE",           // ← make this the single source of truth
                "VayDNS VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN tunneling status"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun cleanupAndStop() {
        if (isStopping) return
        isStopping = true

        Log.e("VAY_DEBUG", "PURGE: Killing network resources...")
        statsHandler.removeCallbacks(statsRunnable)
        flushPendingTraffic()

        synchronized(goLock) {
            try {
                // 1. Deactivate protector first - stops log flood/SecurityException
                protector?.deactivate()

                // 2. CLOSE TUN INTERFACE - This is the most important part.
                // It forces Go's tun2socks read/write calls to crash immediately.
                tunInterface?.close()
                tunInterface = null

                // 3. Signal Go to stop
                Mobile.stopVpn()

                Log.e("VAY_DEBUG", "PURGE: Finished.")
            } catch (e: Exception) {
                Log.e("VAY_DEBUG", "Purge Error: ${e.message}")
            } finally {
                // 4. GUARANTEED WAKELOCK RELEASE
                try {
                    wakeLock?.let {
                        if (it.isHeld) it.release()
                    }
                } catch (e: Exception) {
                    Log.e("VAY_DEBUG", "WakeLock release error: ${e.message}")
                }
                isStopping = false
                stopSelf()
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i("VayDNS", "App swiped away from recent tasks. Shutting down VPN...")

        // Trigger your existing safe shutdown logic
        cleanupAndStop()

        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i("VayDNS", "onDestroy: Closing Go session...")

        // 1. Explicitly run your cleanup logic to safely shut down Go and the TUN interface
        cleanupAndStop()

        // 2. Run the standard Android lifecycle teardown
        super.onDestroy()

        //  3. Obliterate the isolated :vpn process.
        // This instantly cures any kcp-go socket leaks or memory panics,
        // leaving a perfectly clean slate for the next time the user hits START.
        System.exit(0)
    }

}