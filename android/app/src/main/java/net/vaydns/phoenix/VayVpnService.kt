package net.vaydns.phoenix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import mobile.Mobile
import mobile.SocketProtector
import java.net.InetAddress
import java.util.Locale

class VayVpnService : VpnService() {
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private var isStopping = false
    private var isStarting = false
    private var protector: AndroidProtector? = null
    private var builder: Builder = Builder()

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
    private var activeEngineType = "sing-box"
    private var currentProtocol = "socks5"
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

            val elapsedMs = currentTime - lastStatsRunTime
            lastStatsRunTime = currentTime
            val elapsedSec = if (elapsedMs >= 1000) elapsedMs / 1000.0 else 1.0

            try {
                val stats = mobile.Mobile.getProxyStats()
                val parts = stats.split("|")

                if (parts.size == 2) {
                    val currentRx = parts[0].toLong()
                    val currentTx = parts[1].toLong()

                    val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                    if (currentTrackingDate != dateStr) {
                        val prefs = getSharedPreferences("Phoenix_Traffic", Context.MODE_PRIVATE)
                        absoluteDailyRx = prefs.getLong("rx_$dateStr", 0L)
                        absoluteDailyTx = prefs.getLong("tx_$dateStr", 0L)
                        absoluteDailyOsRx = prefs.getLong("os_rx_$dateStr", 0L)
                        absoluteDailyOsTx = prefs.getLong("os_tx_$dateStr", 0L)
                        currentTrackingDate = dateStr
                    }

                    val diffRx = if (currentRx > previousRxBytes) currentRx - previousRxBytes else 0L
                    val diffTx = if (currentTx > previousTxBytes) currentTx - previousTxBytes else 0L

                    val rxSpeed = (diffRx / elapsedSec).toLong()
                    val txSpeed = (diffTx / elapsedSec).toLong()

                    previousRxBytes = currentRx
                    previousTxBytes = currentTx

                    absoluteDailyRx += diffRx
                    absoluteDailyTx += diffTx
                    pendingRxSave += diffRx
                    pendingTxSave += diffTx

                    val tunStats = getTunInterfaceStats()
                    val currentOsRx = tunStats.first
                    val currentOsTx = tunStats.second

                    if (previousOsRxBytes == 0L && previousOsTxBytes == 0L) {
                        previousOsRxBytes = currentOsRx
                        previousOsTxBytes = currentOsTx
                    }

                    val diffOsRx = if (currentOsRx >= previousOsRxBytes) currentOsRx - previousOsRxBytes else 0L
                    val diffOsTx = if (currentOsTx >= previousOsTxBytes) currentOsTx - previousOsTxBytes else 0L

                    previousOsRxBytes = currentOsRx
                    previousOsTxBytes = currentOsTx

                    sessionOsRx += diffOsRx
                    sessionOsTx += diffOsTx

                    val osRxSpeed = (diffOsRx / elapsedSec).toLong()
                    val osTxSpeed = (diffOsTx / elapsedSec).toLong()

                    absoluteDailyOsRx += diffOsRx
                    absoluteDailyOsTx += diffOsTx
                    pendingOsRxSave += diffOsRx
                    pendingOsTxSave += diffOsTx

                    if (currentTime - lastDbSaveTime >= 10000L) {
                        if (pendingRxSave > 0 || pendingTxSave > 0 || pendingOsRxSave > 0 || pendingOsTxSave > 0) {
                            val prefs = getSharedPreferences("Phoenix_Traffic", Context.MODE_PRIVATE)
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

                    val appPrefs = getSharedPreferences("PhoenixVpnPrefs", Context.MODE_PRIVATE)
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

                        try {
                            val intent = Intent(this@VayVpnService, MainActivity::class.java)
                            val pendingIntent = PendingIntent.getActivity(this@VayVpnService, 0, intent, PendingIntent.FLAG_IMMUTABLE)

                            val updateNotification = androidx.core.app.NotificationCompat.Builder(this@VayVpnService, "VAY_CHANNEL_ACTIVE")
                                .setContentTitle("Phoenix VPN")
                                .setContentText(speedStr)
                                .setSmallIcon(net.vaydns.phoenix.R.drawable.ic_vpn_key)
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

            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val isScreenOn = powerManager.isInteractive

            val appPrefs = getSharedPreferences("PhoenixVpnPrefs", Context.MODE_PRIVATE)
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
            if (!active || s == null) return true
            return try {
                s.protect(fd.toInt())
            } catch (e: Exception) {
                true
            }
        }
    }

    private fun flushPendingTraffic() {
        if (pendingRxSave > 0 || pendingTxSave > 0 || pendingOsRxSave > 0 || pendingOsTxSave > 0) {
            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val prefs = getSharedPreferences("Phoenix_Traffic", Context.MODE_PRIVATE)
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
        isStopping = false
        Log.i("Phoenix", "VpnService Created - isStopping reset to false")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == "ACTION_STOP_VPN") {
            cleanupAndStop()
            return START_NOT_STICKY
        }

        val tunnelProtocol = intent.getStringExtra("TUNNEL_PROTOCOL") ?: "vaydns"
        val localProxyProtocol = intent.getStringExtra("LOCAL_PROXY_PROTOCOL") ?: "socks5"
        val authProtocol = intent.getStringExtra("AUTH_PROTOCOL") ?: "socks"

        if (tunnelProtocol.lowercase() == "amneziawg") {
            val keyFile = java.io.File(filesDir, "amneziawg_keys.json")
            if (!keyFile.exists()) {
                sendBroadcast(Intent("VPN_STATE_CHANGED").apply {
                    putExtra("status", "ERROR")
                    putExtra("message", "AmneziaWG keys missing! Please tap 'Get AmneziaWG Keys' from the menu first.")
                    setPackage(packageName)
                })
                stopSelf()
                return START_NOT_STICKY
            }
        } else if (tunnelProtocol.lowercase() == "wireguard" || tunnelProtocol.lowercase() == "warp") {
            val keyFile = java.io.File(filesDir, "warp_keys.json")
            if (!keyFile.exists()) {
                sendBroadcast(Intent("VPN_STATE_CHANGED").apply {
                    putExtra("status", "ERROR")
                    putExtra("message", "WARP keys missing! Please tap 'WARP Settings' and provision keys.")
                    setPackage(packageName)
                })
                stopSelf()
                return START_NOT_STICKY
            }
        } else if (tunnelProtocol.lowercase() == "masque") {
            val keyFile = java.io.File(filesDir, "usque_config.json")
            if (!keyFile.exists()) {
                sendBroadcast(Intent("VPN_STATE_CHANGED").apply {
                    putExtra("status", "ERROR")
                    putExtra("message", "QUIC (MASQUE) keys missing! Please tap 'WARP Settings' and provision keys.")
                    setPackage(packageName)
                })
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val notification = Notification.Builder(this, "VAY_CHANNEL_ACTIVE")
            .setContentTitle("Phoenix Tunnel Active")
            .setContentText("Connecting to server...")
            .setSmallIcon(net.vaydns.phoenix.R.drawable.ic_vpn_key)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        startForeground(1, notification)

        Thread {
            synchronized(goLock) {
                try {
                    isStopping = false
                    Log.i("Phoenix", "Checking for existing native instances...")
                    updateNotification("Status: Connecting...")

                    Mobile.stopVpn()
                    Thread.sleep(500)

                    val isDefaultConfig = intent.getBooleanExtra("IS_DEFAULT_CONFIG", false)
                    val configIndex = intent.getLongExtra("CONFIG_INDEX", 0L)
                    val configType = intent.getStringExtra("CONFIG_TYPE") ?: "vaydns"
                    val domain = intent.getStringExtra("DOMAIN") ?: ""
                    val domainIndex = intent.getIntExtra("DOMAIN_INDEX", 0)
                    val pubkey = (intent.getStringExtra("PUBKEY") ?: "").replace("\\s".toRegex(), "")
                    val baseDohUrl = intent.getStringExtra("BASE_DOH_URL") ?: ""
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
                    currentProtocol = tunnelProtocol
                    val authProtocol = intent.getStringExtra("AUTH_PROTOCOL") ?: "socks"
                    val ssMethod = intent.getStringExtra("SS_METHOD") ?: "chacha20-ietf-poly1305"
                    val user = intent.getStringExtra("USER") ?: ""
                    val pass = intent.getStringExtra("PASS") ?: ""
                    val engineType = intent.getStringExtra("ENGINE_TYPE") ?: "sing-box"
                    activeEngineType = intent.getStringExtra("ENGINE_TYPE") ?: "sing-box"
                    activeConfigType = intent.getStringExtra("CONFIG_TYPE") ?: "vaydns"

                    val vlessWsIp = intent.getStringExtra("VLESS_WS_IP") ?: ""
                    val targetCdn = intent.getStringExtra("TARGET_CDN") ?: "CloudX"
                    val fragment = intent.getBooleanExtra("USE_FRAGMENTATION", false)
                    val blockQuic = intent.getBooleanExtra("BLOCK_QUIC", true)
                    val getServerIpFromDomain = intent.getBooleanExtra("GET_SERVER_IP_FROM_DOMAIN", false)
                    val sniIndex = intent.getLongExtra("SNI_INDEX", -1L)
                    val useHysteriaCore = intent.getBooleanExtra("USE_HYSTERIA_CORE", false)
                    sessionOsRx = 0L
                    sessionOsTx = 0L

                    val lowerConfig = configType.lowercase()
                    val lowerProto = tunnelProtocol.lowercase()
                    var finalMtu = if (lowerConfig == "direct" ||
                        lowerProto == "hysteria2" || lowerProto == "reality-tcp" || lowerProto == "reality-xhttp" || lowerProto == "dns" ||
                        lowerProto == "vless-httpupgrade" || lowerProto == "vless-ws" || lowerProto == "vless-grpc" || lowerProto == "vless-xhttp" ||
                        lowerProto == "amneziawg" || lowerProto == "wireguard" || lowerProto == "masque" || lowerProto == "warp") {
                        if (lowerProto == "amneziawg" || lowerProto == "wireguard" || lowerProto == "masque" || lowerProto == "warp") 1280 else 1420
                    } else {
                        1232
                    }

                    if (lowerProto == "warp") {
                        val wPrefs = getSharedPreferences("WarpProfilePrefs", Context.MODE_PRIVATE)
                        val isNested = wPrefs.getBoolean("warp_plus", false)
                        if (isNested) {
                            finalMtu = 1200
                        }
                    }

                    mobile.Mobile.initVault(filesDir.absolutePath)
                    // Set the binary path inside the isolated :vpn process!
                    if (tunnelProtocol.lowercase() == "masque") {
                        val usquePath = applicationInfo.nativeLibraryDir + "/libusque.so"
                        mobile.Mobile.setUsqueBinaryPath(usquePath)
                    }

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

                    val serverIp = try {
                        InetAddress.getByName(domain).hostAddress
                    } catch (e: Exception) { null }

                    // =========================================================
                    // 1. DYNAMIC NATIVE TUN PARAMETER EXTRACTION
                    // =========================================================
                    var dynamicServerIp = ""
                    var localIpv4 = "10.0.0.2"
                    var prefixV4 = 24

                    if (tunnelProtocol.lowercase() == "amneziawg") {
                        val prefs = getSharedPreferences("AmneziaKeysPrefs", Context.MODE_PRIVATE)
                        dynamicServerIp = prefs.getString("server_ip", "") ?: ""

                        val fullLocal = prefs.getString("internal_ip", "10.0.0.2/32") ?: "10.0.0.2/32"
                        if (fullLocal.contains("/")) {
                            localIpv4 = fullLocal.substringBefore("/")
                            prefixV4 = fullLocal.substringAfter("/").toIntOrNull() ?: 32
                        } else {
                            localIpv4 = fullLocal
                            prefixV4 = 32
                        }
                    } else if (tunnelProtocol.lowercase() == "wireguard" || tunnelProtocol.lowercase() == "masque" || tunnelProtocol.lowercase() == "warp") {
                        val prefName = if (tunnelProtocol.lowercase() == "masque") "UsqueProfilePrefs" else "WarpProfilePrefs"
                        val prefs = getSharedPreferences(prefName, Context.MODE_PRIVATE)

                        val useIp = prefs.getString("connection_mode", "endpoint") == "ip"
                        val customIp = prefs.getString("custom_ip", "")
                        val defaultDomain = prefs.getString("endpoint", "engage.cloudflareclient.com") ?: "engage.cloudflareclient.com"
                        // 1. Determine the active target (Custom IP vs Domain)
                        var activeTarget = if (useIp && customIp != null && customIp.isNotEmpty()) {
                            customIp
                        } else {
                            defaultDomain
                        }

                        if (!isValidIp(activeTarget)) {
                            try {
                                val inetAddresses = InetAddress.getAllByName(activeTarget)
                                if (inetAddresses.isNotEmpty()) {
                                    activeTarget = inetAddresses[0].hostAddress
                                }
                            } catch (e: Exception) {
                                // Fallback to a guaranteed Cloudflare Anycast IP if DNS fails
                                activeTarget = "162.159.192.1"
                            }
                        }

                        // 2. Set it so Android's VPN builder excludes it from the tunnel
                        dynamicServerIp = activeTarget

                    }

                    builder = Builder()
                    builder.setSession("Phoenix Tunnel Active")
                        .addAddress(localIpv4, prefixV4)
                        .addDnsServer("1.1.1.1") // Primary public DNS
                        .addDnsServer("8.8.8.8") // Secondary public DNS
                        .setMtu(finalMtu)
                        .addRoute("0.0.0.0", 0)

                    builder.setBlocking(false)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        builder.setUnderlyingNetworks(null)
                    }

                    val tunnelPrefs = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
                    val activeProtocol = tunnelPrefs.getString("active_protocol", "vaydns") ?: "vaydns"
                    Log.i("VAY_DEBUG", "EVALUATING BYPASS:")
                    Log.i("VAY_DEBUG", "1. Intent protocol: $tunnelProtocol")
                    Log.i("VAY_DEBUG", "2. Prefs activeProtocol: $activeProtocol")
                    Log.i("VAY_DEBUG", "3. Intent activeConfigType: $activeConfigType")
                    Log.i("VAY_DEBUG", "4. dynamicServerIp: $dynamicServerIp")

                    var globalDnsServer = tunnelPrefs.getString("global_dns_server", "")?.trim() ?: ""
                    if (globalDnsServer.isEmpty()) {
                        globalDnsServer = "1.1.1.1"
                    }

                    // =========================================================
                    // 3. EXCLUDE THE CORRECT DYNAMIC SERVER IP FROM VPN ROUTING
                    // =========================================================
                    // CRITICAL FIX: If the Intent protocol is a direct protocol, override the UI preference!
                    //val directProtocols = listOf("amneziawg", "wireguard", "masque", "warp", "hysteria2", "reality-tcp", "reality-xhttp", "vless-ws", "vless-xhttp", "vless-grpc", "vless-httpupgrade")
                    val directProtocols = Mobile.getDirectProtocols().split(",").map { it.trim().lowercase() }
                    val isDirectMode = activeProtocol.lowercase() != "vaydns" || tunnelProtocol.lowercase() in directProtocols

                    var primaryBypassIp = serverIp

                    // =========================================================
                    // 3. EXCLUDE THE CORRECT DYNAMIC SERVER IP FROM VPN ROUTING
                    // =========================================================
                    if (isDirectMode) {
                        if ((tunnelProtocol.lowercase() == "amneziawg" || tunnelProtocol.lowercase() == "wireguard" || tunnelProtocol.lowercase() == "masque" || tunnelProtocol.lowercase() == "warp") && dynamicServerIp.isNotEmpty()) {
                            primaryBypassIp = dynamicServerIp

                            if (primaryBypassIp != null && !isValidIp(primaryBypassIp!!)) {
                                try {
                                    val inetAddresses = InetAddress.getAllByName(primaryBypassIp)
                                    if (inetAddresses.isNotEmpty()) {
                                        primaryBypassIp = inetAddresses[0].hostAddress
                                    }
                                } catch (e: Exception) {
                                    Log.e("VAY_DEBUG", "Failed to resolve endpoint for bypass: ${e.message}")
                                }
                            }
                        } else if (tunnelProtocol.lowercase() !in listOf("amneziawg", "wireguard", "masque", "warp")) {
                            val targetIp = mobile.Mobile.getTargetIP(configIndex, activeProtocol, globalDnsServer, getServerIpFromDomain, targetCdn, vlessWsIp)
                            try {
                                primaryBypassIp = InetAddress.getByName(targetIp).hostAddress
                            } catch (e: Exception) {
                                primaryBypassIp = targetIp
                            }
                        }
                    }

                    if (primaryBypassIp != null && isValidIp(primaryBypassIp!!)) {
                        Log.i("VAY_DEBUG", "Excluding Proxy IP from VPN Routing Table: $primaryBypassIp")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            try {
                                val inetAddress = InetAddress.getByName(primaryBypassIp)
                                val ipPrefix = android.net.IpPrefix(inetAddress, 32)
                                builder.excludeRoute(ipPrefix)
                            } catch (e: Exception) {}
                        }
                    }

                    var bypassIp = dnsAddress
                    if (bypassIp.startsWith("http")) {
                        try { bypassIp = java.net.URL(bypassIp).host } catch (e: Exception) {}
                    } else if (bypassIp.contains(":")) {
                        bypassIp = bypassIp.substringBefore(":")
                    }

                    // CRITICAL FIX: Usque requires DNS bypass to resolve Watchdog domains and Cloudflare endpoints!
                    // If it is NOT direct mode, or if it IS Usque, bypass the DNS IP.
                    if ((!isDirectMode || tunnelProtocol.lowercase() == "masque") && isValidIp(bypassIp)) {
                        Log.i("VAY_DEBUG", "Excluding DNS IP from VPN Routing Table: $bypassIp")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            try {
                                val inetAddress = InetAddress.getByName(bypassIp)
                                val ipPrefix = android.net.IpPrefix(inetAddress, 32)
                                builder.excludeRoute(ipPrefix)
                            } catch (e: Exception) {}
                        }
                    }

                    val sharedPrefs = getSharedPreferences("PhoenixVpnPrefs", Context.MODE_PRIVATE)
                    val isDebugEnabled = sharedPrefs.getBoolean("debug_logs_enabled", false)

                    val selectedApps = intent.getStringArrayListExtra("ALLOWED_APPS_LIST")?.toSet() ?: emptySet()
                    val tunnelAllApps = intent.getBooleanExtra("TUNNEL_ALL_APPS", false)
                    val tunnelAndroidServices = intent.getBooleanExtra("TUNNEL_ANDROID_SERVICES", false)

                    if (!tunnelAllApps) {
                        val coreGoogleApps = listOf("com.android.vending", "com.google.android.gms", "com.google.android.gsf")
                        if (selectedApps.isNotEmpty() || tunnelAndroidServices) {
                            for (pkg in selectedApps) {
                                try { builder.addAllowedApplication(pkg) } catch (e: Exception) {}
                            }
                            if (tunnelAndroidServices) {
                                for (pkg in coreGoogleApps) {
                                    try { builder.addAllowedApplication(pkg) } catch (e: Exception) {}
                                }
                            }
                        } else {
                            try { builder.addAllowedApplication(packageName) } catch (e: Exception) {}
                        }
                    } else {
                        try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}
                    }

                    Log.i("VAY_DEBUG", "Starting Pre-Scan from Kotlin...")

                    val prefs = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
                    val enableScan = prefs.getBoolean("enable_prescan", false)

                    var finalUdp = udp
                    var finalTcp = tcp
                    var finalDoh = doh
                    var finalDot = dot

                    if (enableScan) {
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

                    protector = AndroidProtector(this@VayVpnService)

                    val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Phoenix::VpnKeepAlive")
                    wakeLock?.acquire(12 * 60 * 60 * 1000L)

                    if (tunnelProtocol.lowercase() == "masque") {
                        val uPrefs = getSharedPreferences("UsqueProfilePrefs", Context.MODE_PRIVATE)
                        val sni = uPrefs.getString("sni", "speed.cloudflare.com") ?: "speed.cloudflare.com"
                        val useHttp2 = uPrefs.getBoolean("use_http2", false)
                        mobile.Mobile.setMasqueAdvancedParams(sni, useHttp2)
                    } else if (tunnelProtocol.lowercase() == "warp") {
                        val wPrefs = getSharedPreferences("WarpProfilePrefs", Context.MODE_PRIVATE)
                        val isNested = wPrefs.getBoolean("warp_plus", false)
                        mobile.Mobile.setWarpAdvancedParams(isNested)
                    }

                    if (tunnelProtocol.lowercase() == "masque" || tunnelProtocol.lowercase() == "warp" || tunnelProtocol.lowercase() == "wireguard"){
                        val wPrefs = getSharedPreferences("WarpProfilePrefs", Context.MODE_PRIVATE)
                        val isRandom = wPrefs.getBoolean("random_endpoint", false)
                        mobile.Mobile.setWarpRandomEndpoint(isRandom)
                    }

                    tunInterface = builder.establish()
                    if (tunInterface == null) return@synchronized

                    val fd = tunInterface?.fd ?: -1

                    if (fd != -1) {
                        PhoenixVpnVerify.bind(this)

                        val result = Mobile.startVpn(
                            fd.toLong(),
                            engineType,
                            isDefaultConfig,
                            configIndex,
                            configType,
                            useMultiDomains,
                            domainIndex.toLong(),
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
                            tunnelProtocol,
                            localProxyProtocol,
                            authProtocol,
                            ssMethod,
                            user,
                            pass,
                            vlessWsIp,
                            targetCdn,
                            globalDnsServer,
                            isDebugEnabled,
                            fragment,
                            blockQuic,
                            getServerIpFromDomain,
                            sniIndex,
                            useHysteriaCore,
                            protector
                        )
                        Log.i("Phoenix", "VPN Base Engine Started with Result: $result")

                        if (result.contains("Success")) {
                            // CHANGED: Only AmneziaWG uses the native TUN. WARP requires the C-Tunnel proxy!
                            val isNativeTun = tunnelProtocol.lowercase() == "amneziawg"

                            if (!isNativeTun) {
                                // ===============================================
                                // C-TUNNEL INTERCEPTOR HANDOFF (Proxy Protocols)
                                // ===============================================
                                val portStr = if (result.contains("|")) {
                                    result.split("|").getOrNull(1)?.trim()
                                } else {
                                    result.substringAfterLast(":").trim()
                                }

                                val socksPort = portStr?.toIntOrNull() ?: 35795
                                Log.i("Phoenix", "Handoff to C-Tunnel using verified SOCKS Port $socksPort...")

                                try {
                                    val hevConfig = """
                                        tunnel:
                                          name: tun0
                                          mtu: $finalMtu
                                          ipv4: '10.0.0.2'
                                          ipv6: ''
                                        socks5:
                                          address: '127.0.0.1'
                                          port: $socksPort
                                          udp: 'udp'
                                        misc:
                                          task-stack-size: 8192
                                          connect-timeout: 5000
                                          read-write-timeout: 60000
                                    """.trimIndent()

                                    val configFile = java.io.File(cacheDir, "hev_config.yml")
                                    configFile.writeText(hevConfig)

                                    hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, fd)
                                    Log.i("Phoenix", "HEV C-Tunnel Started successfully on port $socksPort.")
                                } catch (e: Exception) {
                                    Log.e("Phoenix", "Failed to start HEV C-Tunnel: ${e.message}")
                                }
                            } else {
                                Log.i("Phoenix", "Native TUN interface bound directly to Go Core ($tunnelProtocol). Skipping C-Tunnel.")
                            }

                            runVerificationLogic()
                        } else {
                            updateNotification("Engine Failed to Start")

                            sendBroadcast(Intent("VPN_STATE_CHANGED").apply {
                                putExtra("status", "ERROR")
                                putExtra("message", result.replace("Error: ", ""))
                                setPackage(packageName)
                            })

                            Handler(Looper.getMainLooper()).post {
                                cleanupAndStop()
                            }
                        }

                    } else {
                        Log.e("Phoenix", "Failed to start: Tunnel interface was null")
                    }

                } catch (e: Exception) {
                    Log.e("Phoenix", "VPN Start Exception: ${e.message}", e)
                    updateNotification("Error starting tunnel")
                }
            }
        }.start()

        return START_STICKY
    }

    private fun runVerificationLogic() {
        updateNotification("Handshaking with server...")

        Thread {
            // val directProtocols = listOf("amneziawg", "wireguard", "masque", "warp", "hysteria2", "reality-tcp", "reality-xhttp", "vless-ws", "vless-xhttp", "vless-grpc", "vless-httpupgrade")
            val directProtocols = Mobile.getDirectProtocols().split(",").map { it.trim().lowercase() }
            val isDirectMode = activeConfigType.lowercase() == "direct" || currentProtocol.lowercase() in directProtocols

            // Preserve the 2000ms stabilization delay ONLY for VayDNS
            if (!isDirectMode) {
                Thread.sleep(2000)
            }
            // val verifyResult = Mobile.verifyTunnel()
            val verifyResult = Mobile.verifyTunnel(currentProtocol)

            if (isStopping) return@Thread

            if (verifyResult.contains("Success")) {
                Log.i("Phoenix", "Go-Level verification passed!")
                sendBroadcast(Intent("VPN_STATE_CHANGED").apply {
                    putExtra("status", "CONNECTED")
                    setPackage(packageName)
                })
                updateNotification("Status: Connected")
                initialRxBytes = 0L
                initialTxBytes = 0L
                statsHandler.post(statsRunnable)
            } else {
                Log.e("Phoenix", "Go-Level verification failed: $verifyResult")
                sendBroadcast(Intent("VPN_STATE_CHANGED").apply {
                    putExtra("status", "ERROR")
                    putExtra("message", "Verification Failed: Server Unreachable")
                    setPackage(packageName)
                })
                updateNotification("Connection Failed")

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
                "Phoenix Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Status of Phoenix Tunnel" }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, "VAY_CHANNEL_ACTIVE")
            .setContentTitle("Phoenix VPN")
            .setContentText(status)
            .setSmallIcon(net.vaydns.phoenix.R.drawable.ic_vpn_key)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(1, notification)
    }

    private fun isValidIp(ip: String): Boolean {
        return ip.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}"""))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "VAY_CHANNEL_ACTIVE",
                "Phoenix VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "VPN tunneling status" }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun cleanupAndStop() {
        if (isStopping) return
        isStopping = true

        Log.e("VAY_DEBUG", "PURGE: Initiating Graceful Self-Destruct...")
        statsHandler.removeCallbacks(statsRunnable)
        flushPendingTraffic()
        stopForeground(STOP_FOREGROUND_REMOVE)

        Thread {
            try { hev.htproxy.TProxyService.TProxyStopService() } catch (e: Exception) {}

            try {
                tunInterface?.close()
                tunInterface = null
            } catch (e: Exception) {}

            try {
                wakeLock?.let {
                    if (it.isHeld) it.release()
                }
            } catch (e: Exception) {}

            Thread {
                try { Mobile.stopVpn() } catch (e: Exception) {}
            }.start()

            stopSelf()
            Thread.sleep(1500)
            System.exit(0)
        }.start()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i("Phoenix", "App swiped away from recent tasks. Keeping VPN alive in background...")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i("Phoenix", "onDestroy: Closing Service...")
        super.onDestroy()
    }

    private fun getTunInterfaceStats(): Pair<Long, Long> {
        val isNativeTun = currentProtocol.lowercase() == "amneziawg"

        // 1. PURE NATIVE STATS: Pull directly from Go's injected atomic counters
        if (isNativeTun) {
            try {
                val stats = mobile.Mobile.getProxyStats().split("|")
                if (stats.size == 2) {
                    return Pair(stats[0].toLong(), stats[1].toLong())
                }
            } catch (e: Exception) {}
        }

        // 2. C-TUNNEL STATS: For proxy protocols (Hysteria2, VLESS, WARP, etc.)
        if (activeConfigType.lowercase() != "vaydns") {
            try {
                val cStats = hev.htproxy.TProxyService.TProxyGetStats()
                if (cStats != null && cStats.size >= 4) {
                    val downloadBytes = cStats[3]
                    val uploadBytes = cStats[1]
                    return Pair(downloadBytes, uploadBytes)
                }
            } catch (e: Exception) {
            }
        }

        // 3. FALLBACK: Legacy Android UID TrafficStats
        val uidRx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
        val uidTx = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())

        if (uidRx > 0 || uidTx > 0) {
            return Pair(uidRx, uidTx)
        }

        return Pair(0L, 0L)
    }
}
