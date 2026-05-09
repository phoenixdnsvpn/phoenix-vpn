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

    private val statsRunnable = object : Runnable {
        override fun run() {
            if (isStopping) return

            try {
                // 1. Fetch exact bytes from the Go Engine
                // (It works for both VPN and Proxy since it tracks the core tunnel!)
                val stats = mobile.Mobile.getProxyStats()
                val parts = stats.split("|")

                if (parts.size == 2) {
                    val currentRx = parts[0].toLong()
                    val currentTx = parts[1].toLong()

                    // 2. Safe Math: Prevent Kotlin background crashes
                    val diffRx = currentRx - previousRxBytes
                    val diffTx = currentTx - previousTxBytes

                    val rxSpeed = if (diffRx < 0) 0L else diffRx
                    val txSpeed = if (diffTx < 0) 0L else diffTx

                    previousRxBytes = currentRx
                    previousTxBytes = currentTx

                    // 3. Format strings
                    val speedStr = "▼ ${formatBytes(rxSpeed)}/s   ▲ ${formatBytes(txSpeed)}/s"
                    val totalStr = "Total: ${formatBytes(currentRx)} ↓   ${formatBytes(currentTx)} ↑"

                    // 4. Broadcast to MainActivity
                    sendBroadcast(Intent("VPN_STATS_UPDATE").apply {
                        putExtra("speed", speedStr)
                        putExtra("total", totalStr)
                        setPackage(packageName)
                    })

                    // 5. UPDATE THE LOCK SCREEN NOTIFICATION
                    try {
                        // 1. Recreate the PendingIntent so tapping it still opens the app
                        val intent = Intent(this@VayVpnService, MainActivity::class.java)
                        val pendingIntent = PendingIntent.getActivity(this@VayVpnService, 0, intent, PendingIntent.FLAG_IMMUTABLE)

                        // 2. Build a fresh notification right here to avoid variable clashes!
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

                        // 3. Push the update to the system
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(1, updateNotification)
                    } catch (e: Exception) {
                        android.util.Log.e("VAY_VPN", "Failed to update notification: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("VAY_VPN", "Error parsing Go stats: ${e.message}")
            }

            // 5. Always schedule the next tick
            statsHandler.postDelayed(this, 1000)
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
                    val useAuth = intent?.getBooleanExtra("USE_AUTH", false) ?: false
                    val protocol = intent?.getStringExtra("PROTOCOL") ?: "socks5"
                    val ssMethod = intent?.getStringExtra("SS_METHOD") ?: "chacha20-ietf-poly1305"
                    val user = intent?.getStringExtra("USER") ?: ""
                    val pass = intent?.getStringExtra("PASS") ?: ""

                    if (isDefaultConfig) {
                        mobile.Mobile.initVault(filesDir.absolutePath)
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
                    if (serverIp != null && isValidIp(serverIp)) builder.addRoute(serverIp, 32)
                    /*if (dnsAddress.contains(":")) {
                        val resolver = dnsAddress.substringBefore(":")
                        if (isValidIp(resolver)) builder.addRoute(resolver, 32)
                    }*/

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

                    // App Filtering
                    /*val sharedPref = getSharedPreferences("VayDNS_Settings", MODE_PRIVATE)
                    val selectedApps = sharedPref.getStringSet("allowed_apps", emptySet()) ?: emptySet()
                    if (selectedApps.isNotEmpty()) {
                        for (pkg in selectedApps) {
                            try { builder.addAllowedApplication(pkg) } catch (e: Exception) {}
                        }
                    } else {
                        try { builder.addAllowedApplication(packageName) } catch (e: Exception) {}
                    }*/

                    protector = AndroidProtector(this@VayVpnService)
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
                            isDefaultConfig,
                            configIndex,
                            udp,
                            tcp,
                            doh,
                            dot,
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
                            ssMethod,
                            user,
                            pass,
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