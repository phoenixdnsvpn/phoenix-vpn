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
        val notification = Notification.Builder(this, "VAYDNS_CHANNEL")
            .setContentTitle("VayDNS Tunnel Active")
            .setContentText("Connecting to server...")
            .setSmallIcon(R.drawable.ic_dialog_info)
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
// Now it is perfectly safe to wait for Go to stop
                    Mobile.stopVpn()
                    //tunInterface = null
                    // Give the Linux kernel 500ms to truly release the UDP ports
                    Thread.sleep(500)

                    // 4. Extract data passed from MainActivity
                    val domain = intent?.getStringExtra("DOMAIN") ?: ""
                    val pubkey = (intent?.getStringExtra("PUBKEY") ?: "").replace("\\s".toRegex(), "")
                    val dnsAddress = intent?.getStringExtra("UDP") ?: "8.8.8.8:53"
                    val mode = intent?.getStringExtra("MODE") ?: "udp"
                    val recordType = intent?.getStringExtra("RECORD_TYPE") ?: "TXT"
                    val idleTimeout = intent?.getStringExtra("IDLE_TIMEOUT") ?: "10s"
                    val keepAlive = intent?.getStringExtra("KEEP_ALIVE") ?: "2s"
//                    val clientIdSize = intent?.getIntExtra("CLIENT_ID_SIZE", 2)
                    val clientIdSize = intent?.getLongExtra("CLIENT_ID_SIZE", 2L) ?: 2L
//                    val dnsttCompatible = intent?.getBooleanExtra("DNSTT_COMPATIBLE", false)
                    val dnsttCompatible = intent?.getBooleanExtra("DNSTT_COMPATIBLE", false) ?: false
                    val useAuth = intent?.getBooleanExtra("USE_AUTH", false) ?: false
                    val protocol = intent?.getStringExtra("PROTOCOL") ?: "socks5"
                    val ssMethod = intent?.getStringExtra("SS_METHOD") ?: "chacha20-ietf-poly1305"
                    val user = intent?.getStringExtra("USER") ?: ""
                    val pass = intent?.getStringExtra("PASS") ?: ""

                    var udp = ""
                    var doh = ""
                    var dot = ""
                    when (mode.lowercase()) {
                        "udp" -> udp = dnsAddress
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
                    if (dnsAddress.contains(":")) {
                        val resolver = dnsAddress.substringBefore(":")
                        if (isValidIp(resolver)) builder.addRoute(resolver, 32)
                    }

                    // App Filtering
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
//                    val result = Mobile.startVpn(fd.toLong(), udp, doh, dot, domain, pubkey, protector)
                    if (fd != -1) {
                        val result = Mobile.startVpn(
                            fd.toLong(),
                            udp,
                            doh,
                            dot,
                            domain,
                            pubkey,
                            recordType,
                            idleTimeout,
                            keepAlive,
                            clientIdSize.toLong(), // Pass as Long for Go compatibility if needed
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

                        /*try {
                            android.os.ParcelFileDescriptor.adoptFd(fd).close()
                        } catch (e: Exception) {
                            Log.e("VAY_DEBUG", "Failed to close original FD: ${e.message}")
                        }*/
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

    /*
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. Create a Notification Channel (Required for Android 8+)
        isStopping = false
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

                // 2. Detach the FD so Go can own it exclusively
                // .detachFd() returns an Int that stays open even if tunInterface is cleared
                val fd = tunInterface!!.detachFd()
                Log.i("VayDNS", "VPN FD established: $fd")
                val result = Mobile.startVpn(fd.toLong(), udp, doh, dot, domain, pubkey, protector)
                Log.i("VayDNS", "Go Engine Result: $result")
                if (result.contains("Success")) {
                    updateNotification("Handshaking with server...")

                    Thread {
                        // 1. Give the Go engine 2 seconds to boot up the SOCKS5 listener
                        Thread.sleep(2000)

                        // 2. Call our new Go-level verification function!
                        // This will block until it succeeds, fails, or hits the 45s timeout.
                        val verifyResult = Mobile.verifyTunnel()
                        // CRITICAL FIX: If the user clicked STOP while this was verifying,
                        // do absolutely nothing and kill the thread to prevent a crash.
                        if (isStopping) return@Thread

                        if (verifyResult.contains("Success")) {
                            Log.i("VayDNS", "Go-Level verification passed! Tunnel is fully active.")
                            sendBroadcast(Intent("VPN_STATE_CHANGED").apply {
                                putExtra("status", "CONNECTED")
                                setPackage(packageName)
                            })
                            updateNotification("Status: Connected")
                        } else {
                            Log.e("VayDNS", "Go-Level verification failed: $verifyResult")

                            sendBroadcast(Intent("VPN_STATE_CHANGED").apply {
                                putExtra("status", "DISCONNECTED")
                                setPackage(packageName)
                            })
                            updateNotification("Connection Failed")

                            // Because Go reported a failure, it's safe to cleanly shut down.
                            Handler(Looper.getMainLooper()).post {
                                stopSelf()
                            }
                        }
                    }.start()

                }else {
                    // This block only runs if the Go engine IMMEDIATELY fails to start
                    // (e.g. port already in use, bad pubkey format)
                    updateNotification("Engine Failed to Start")
                    sendBroadcast(Intent("VPN_STATE_CHANGED").apply {
                        putExtra("status", "DISCONNECTED")
                        setPackage(packageName)
                    })
                }

            } catch (e: Exception) {
                Log.e("VayDNS", "VPN Start Exception: ${e.message}", e)
                updateNotification("Error starting tunnel")
            }
        }.start()

        return START_NOT_STICKY
    }
    */
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

    private fun cleanupAndStop() {
        if (isStopping) return
        isStopping = true

        Log.e("VAY_DEBUG", "PURGE: Killing network resources...")

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
    /**
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
//                    Mobile.stopVpn()
                    // 4. CLOSE FD FIRST.
                    // This is the "Emergency Brake" for the Go engine.
                    tunInterface?.close()
                    tunInterface = null
                    Thread.sleep(100)
                    Mobile.stopVpn()

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
    */
    override fun onDestroy() {
        Log.i("VayDNS", "onDestroy: Closing Go session...")

        /*Thread {
            synchronized(goLock) {
                try {
                    // 1. Tell Go to stop. Go's engine.Stop() will close the FD.
                    Mobile.stopVpn()

                    // 2. IMPORTANT: DO NOT call tunInterface?.close() here!
                    // Since we used detachFd(), Go owns the FD now.
                    // Closing it here causes the fdsan crash you saw.
                    tunInterface = null

                    // 3. Deactivate the protector link
                    protector?.deactivate()

                    Log.i("VayDNS", "Service cleanup complete.")
                } catch (e: Exception) {
                    Log.e("VayDNS", "Cleanup error: ${e.message}")
                }
            }
        }.start()*/


        super.onDestroy()
    }
        /**override fun onDestroy_x() {
            Log.i("VayDNS", "onDestroy: Ensuring Go engine is dead before service exit...")

            // Use the lock to ensure we aren't mid-start or mid-verification
            synchronized(goLock) {
                try {
                    // 1. Tell Go to stop.
                    // Your Go code has a safety timeout, so this will block briefly.
                    Mobile.stopVpn()

                    // 2. Now that Go is dead, safe to sever JNI and close FD
                    protector?.deactivate()

                    tunInterface?.close()
                    tunInterface = null

                    Log.i("VayDNS", "Go engine destroyed. Memory is clean.")
                } catch (e: Exception) {
                    Log.e("VayDNS", "Error during onDestroy cleanup: ${e.message}")
                }
            }

            // super.onDestroy() must be last
            super.onDestroy()
        }*/
    /**
    override fun onDestroy() {
        Log.i("VayDNS", "onDestroy triggered")
        // Only call cleanup if not already stopping
        if (!isStopping) {
            cleanupAndStop()
        }
        super.onDestroy()
    }
    */

}