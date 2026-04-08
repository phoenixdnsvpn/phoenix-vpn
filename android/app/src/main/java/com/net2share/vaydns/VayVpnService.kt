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

    // The Protector implementation that Go will call for every UDP/TCP socket
// Change 'val' to 'var' in the constructor and the property
    class VayVpnService : VpnService() {
        private var tunInterface: ParcelFileDescriptor? = null
        private var isStopping = false

        // 1. Move protector to class level
        private var protector: AndroidProtector? = null

        private var builder: Builder = Builder()
        // The 'Lock' ensures only one thread can talk to Go at a time
        companion object {
            private val goLock = Any()
        }

        class AndroidProtector(private var service: VpnService?) : SocketProtector {
            private var active = true

            // This severs the JNI link so Go can't crash the app
            fun deactivate() {
                active = false
                service = null
            }

            override fun protect(fd: Long): Boolean {
                val s = service
                if (!active || s == null) return false
                return try {
                    s.protect(fd.toInt())
                } catch (e: Exception) {
                    false
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        // Reset the gatekeeper so the STOP button works for this new session
        isStopping = false
        Log.i("VayDNS", "VpnService Created - isStopping reset to false")
    }

    /*override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        val notification = Notification.Builder(this, "VAYDNS_CHANNEL")
            .setContentTitle("VayDNS Tunnel Active")
            .setContentText("Connecting...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(1, notification)

        // === STOP COMMAND ===
        if (intent?.action == "ACTION_STOP_VPN") {
            cleanupAndStop()
            return START_NOT_STICKY   // ← This prevents Android from restarting the service
        }

        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // === NORMAL START ===
        Thread {
            try {
                synchronized(goLock) {
                    Mobile.stopVpn()
                }
                tunInterface?.close()
                tunInterface = null

                val domain = intent.getStringExtra("DOMAIN") ?: ""
                val pubkey = (intent.getStringExtra("PUBKEY") ?: "").replace("\\s".toRegex(), "")
                val dnsAddress = intent.getStringExtra("UDP") ?: "8.8.8.8:53"
                val mode = intent.getStringExtra("MODE") ?: "udp"

                Log.i("VayDNS", "Starting tunnel → Domain: $domain | Mode: $mode | DNS: $dnsAddress")

                var udp = ""
                var doh = ""
                var dot = ""
                when (mode.lowercase()) {
                    "udp" -> udp = dnsAddress
                    "doh" -> doh = dnsAddress
                    "dot" -> dot = dnsAddress
                }

                val serverIp = try { InetAddress.getByName(domain).hostAddress } catch (e: Exception) { null }

                val builder = Builder()
                    .setSession("VayDNS Tunnel")
                    .addAddress("10.0.0.2", 24)
                    .addDnsServer("8.8.8.8")
                    .setMtu(380)
                    .setBlocking(false)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("8.8.8.8", 32)

                if (dnsAddress.contains(":")) {
                    val resolver = dnsAddress.substringBefore(":")
                    if (isValidIp(resolver)) builder.addRoute(resolver, 32)
                }
                if (serverIp != null && isValidIp(serverIp)) {
                    builder.addRoute(serverIp, 32)
                }

                val sharedPref = getSharedPreferences("VayDNS_Settings", MODE_PRIVATE)
                val selectedApps = sharedPref.getStringSet("allowed_apps", emptySet()) ?: emptySet()

                if (selectedApps.isNotEmpty()) {
                    for (pkg in selectedApps) {
                        try { builder.addAllowedApplication(pkg) } catch (e: Exception) {}
                    }
                } else {
                    try { builder.addAllowedApplication(packageName) } catch (e: Exception) {}
                }

                val protector = AndroidProtector(this@VayVpnService)

                tunInterface = builder.establish()

                if (tunInterface == null) {
                    Log.e("VayDNS", "Failed to establish VPN interface")
                    updateNotification("Failed to create tunnel")
                    return@Thread
                }

                val fd = tunInterface!!.detachFd()
                Log.i("VayDNS", "VPN FD established: $fd")

                val result = Mobile.startVpn(fd.toLong(), udp, doh, dot, domain, pubkey, protector)
                Log.i("VayDNS", "Go Engine Result: $result")

                if (result.contains("Success")) {
                    sendBroadcast(Intent("VPN_STATE_CHANGED").apply {
                        putExtra("status", "CONNECTED")
                        setPackage(packageName)
                    })
                    updateNotification("Status: Connected")
                } else {
                    updateNotification("Connection Failed")
                }

            } catch (e: Exception) {
                Log.e("VayDNS", "VPN Start Exception: ${e.message}", e)
                updateNotification("Error starting tunnel")
            }
        }.start()

        return START_STICKY
    }

    private fun cleanupAndStop() {
        Log.i("VayDNS", "=== STOP COMMAND RECEIVED ===")

        if (isStopping) {
            Log.i("VayDNS", "Already stopping - ignoring duplicate")
            return
        }
        isStopping = true

        // Stop Go engine
        synchronized(goLock) {
            try {
                Mobile.stopVpn()
                Log.i("VayDNS", "Mobile.stopVpn() completed")
            } catch (e: Exception) {
                Log.e("VayDNS", "Mobile.stopVpn() error: ${e.message}")
            }
        }

        // Close tunnel
        try {
            tunInterface?.close()
            Log.i("VayDNS", "tunInterface closed")
        } catch (e: Exception) {
            Log.e("VayDNS", "Error closing tunInterface: ${e.message}")
        } finally {
            tunInterface = null
        }

        // Final cleanup on main thread
        Handler(Looper.getMainLooper()).post {
            try {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(1)
            } catch (e: Exception) {}

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    stopForeground(true)
                }
            } catch (e: Exception) {}

            stopSelf()
            Log.i("VayDNS", "stopSelf() called - service should stop now")
        }

        isStopping = false
    }*/

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. Create a Notification Channel (Required for Android 8+)

        createNotificationChannel()

        // 2. Create the Notification that keeps the service alive
        val notification = Notification.Builder(this, "VAYDNS_CHANNEL")
            .setContentTitle("VayDNS Tunnel Active")
            .setContentText("Your traffic is being protected via DNS Tunneling")
            .setSmallIcon(R.drawable.ic_dialog_info)
            .build()

        // 3. Start as Foreground Service
        startForeground(1, notification)

        if (intent?.action == "ACTION_STOP_VPN") {
            cleanupAndStop() // Call a custom cleanup function
            return START_NOT_STICKY
        }

        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        Thread {
            try {
                synchronized(goLock) {
                    Mobile.stopVpn()
                }
                tunInterface?.close()
                tunInterface = null
//            } catch (e: Exception) {
//                Log.e("VayDNS", "Start error: ${e.message}")
//            }
                // 4. Extract data passed from MainActivity
                val domain = intent?.getStringExtra("DOMAIN") ?: ""
                val pubkey = (intent?.getStringExtra("PUBKEY") ?: "").replace("\\s".toRegex(), "")
                val dnsAddress = intent?.getStringExtra("UDP") ?: "8.8.8.8:53"
                val mode = intent?.getStringExtra("MODE") ?: "udp"

                var udp = ""
                var doh = ""
                var dot = ""
                when (mode.lowercase()) {
                    "udp" -> udp = dnsAddress
                    "doh" -> doh = dnsAddress // e.g., https://dns.google/dns-query
                    "dot" -> dot = dnsAddress // e.g., dns.google:853
                }

                // 1. Resolve the NS domain to its actual IP in the background
                val serverIp = try {
                    InetAddress.getByName(domain).hostAddress
                } catch (e: Exception) {
                    null // Fallback if DNS is blocked/failed
                }
                // 5. Establish the VPN Interface
                builder = Builder()
                //        val serverIp = "46.250.246.10" // Your Rocky Linux IP
                builder.setSession("VayDNS Tunnel Active")
                    .addAddress("10.0.0.2", 24) // Virtual IP
                    .addDnsServer("8.8.8.8")      // Internal DNS
                    .setMtu(500)

                builder.addRoute("8.8.8.8", 32)
                //          builder.addRoute("1.1.1.1", 32)
                builder.addRoute("0.0.0.0", 0)
                builder.addRoute("142.250.0.0", 15) // Google IP range for connectivity checks

                // This tells the VPN: "Don't loop back any traffic generated by this app itself"
                // Prevent the app from looping its own traffic
                builder.setBlocking(false)

                if (dnsAddress.contains(":")) {
                    val resolver = dnsAddress.substringBefore(":")
                    if (isValidIp(resolver)) builder.addRoute(resolver, 32)
                }

                if (serverIp != null && isValidIp(serverIp)) {
                    builder.addRoute(serverIp, 32)
                }

                // List of package names the user selected from the UI
                // 1. Fetch the saved apps from SharedPreferences
                val sharedPref = getSharedPreferences("VayDNS_Settings", MODE_PRIVATE)
                val selectedApps = sharedPref.getStringSet("allowed_apps", emptySet()) ?: emptySet()

                if (selectedApps.isNotEmpty()) {
                    for (pkg in selectedApps) {
                        try { builder.addAllowedApplication(pkg) } catch (e: Exception) {}
                    }
                } else {
                    try { builder.addAllowedApplication(packageName) } catch (e: Exception) {}
                }

                protector = AndroidProtector(this@VayVpnService)
                // It tells Android: "I am using the current default network (WiFi/Cell) as my base."
                // This usually flips the status from "Connecting" to "Connected" instantly.

                // 1. Establish the interface ONCE
                tunInterface = builder.establish()

                if (tunInterface == null) {
                    Log.e("VayDNS", "Failed to establish VPN interface")
                    updateNotification("Failed to create tunnel")
                    return@Thread
                }

//                if (tunInterface != null) {
//                    Log.e("VayDNS", "CRITICAL: establish() returned null. Check VPN permissions!")
//                    return START_NOT_STICKY

//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
//                       setUnderlyingNetworks(null)
//                }
                    // 2. Detach the FD so Go can own it exclusively
                    // .detachFd() returns an Int that stays open even if tunInterface is cleared
                val fd = tunInterface!!.detachFd()
                Log.i("VayDNS", "VPN FD established: $fd")

                val result = Mobile.startVpn(fd.toLong(), udp, doh, dot, domain, pubkey, protector)
                Log.i("VayDNS", "Go Engine Result: $result")

                if (result.contains("Success")) {
                    sendBroadcast(Intent("VPN_STATE_CHANGED").apply {
                        putExtra("status", "CONNECTED")
                        setPackage(packageName)
                    })
                    updateNotification("Status: Connected")
                } else {
                    updateNotification("Connection Failed")
                }

            } catch (e: Exception) {
                Log.e("VayDNS", "VPN Start Exception: ${e.message}", e)
                updateNotification("Error starting tunnel")
            }
        }.start()
//        return START_STICKY
        return START_NOT_STICKY
    }

    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "VAY_CHANNEL_ID",
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

        val notification = NotificationCompat.Builder(this, "VAY_CHANNEL_ID")
            .setContentTitle("VayDNS VPN")
            .setContentText(status) // This is where "Connected" or "Connecting..." goes
            .setSmallIcon(com.net2share.vaydns.R.drawable.ic_vpn_key)
//            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true) // Prevents the user from swiping it away
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Matches channel importance
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
                "VAYDNS_CHANNEL",           // ← make this the single source of truth
                "VayDNS VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN tunneling status"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }


    /*override fun onDestroy() {
        Log.i("VayDNS", "onDestroy triggered")
        super.onDestroy()   // ← Do NOT call cleanupAndStop() here
    }*/

    private fun cleanupAndStop() {
        if (isStopping) return
        isStopping = true

        // 3. IMMEDIATELY deactivate so late JNI calls don't crash the service
        protector?.deactivate()

        sendBroadcast(Intent("VPN_STATE_CHANGED").apply {
            putExtra("status", "DISCONNECTED")
            setPackage(packageName)
        })

        Thread {
            synchronized(goLock) {
                try {
                    Mobile.stopVpn()
                    // 4. CLOSE FD FIRST.
                    // This is the "Emergency Brake" for the Go engine.
                    tunInterface?.close()
                    tunInterface = null

                    // 5. Now tell Go to stop
                    Log.i("VayDNS", "Go engine and TUN interface closed successfully.")
                } catch (e: Exception) {
                    Log.e("VayDNS", "Stop error: ${e.message}")
                }
            }

            Handler(Looper.getMainLooper()).post {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // The modern way (API 24+)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    // The old way for very old devices
                    stopForeground(true)
                }
                stopSelf()
            }
        }.start()
    }

    override fun onDestroy() {
        Log.i("VayDNS", "onDestroy triggered")
        // Only call cleanup if not already stopping
        if (!isStopping) {
            cleanupAndStop()
        }
        super.onDestroy()
    }

}