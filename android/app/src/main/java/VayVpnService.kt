package com.net2share.vaydns

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import mobile.Mobile
import android.util.Log
import mobile.SocketProtector
import java.net.InetAddress
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

class VayVpnService : VpnService() {
    private var tunInterface: ParcelFileDescriptor? = null
    private var isStopping = false
    private var builder: Builder = Builder()
    // The 'Lock' ensures only one thread can talk to Go at a time
    companion object {
        private val goLock = Any()
    }

    // The Protector implementation that Go will call for every UDP/TCP socket
    class AndroidProtector(private val service: VpnService) : SocketProtector {
        override fun protect(fd: Long): Boolean { // Note: gomobile often uses Long for int/uintptr
            val success = service.protect(fd.toInt())
            Log.i("VayDNS", "Protecting FD $fd: $success")
            return success
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Reset the gatekeeper so the STOP button works for this new session
        isStopping = false
        Log.i("VayDNS", "VpnService Created - isStopping reset to false")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. Create a Notification Channel (Required for Android 8+)
        if (intent?.action == "ACTION_STOP_VPN") {
            cleanupAndStop() // Call a custom cleanup function
            return START_NOT_STICKY
        }

        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            synchronized(goLock) {
                Mobile.stopVpn()
            }
            tunInterface?.close()
            tunInterface = null
        } catch (e: Exception) { }
        /*
        // Do this before you even start the Go Engine or build the TUN interface.
        createNotificationChannel() // Ensure the channel exists first
        val notification = NotificationCompat.Builder(this, "VayDNS_Channel")
            .setContentTitle("VayDNS")
            .setContentText("VPN is connecting...")
            .setSmallIcon(com.net2share.vaydns.R.drawable.ic_vpn_key)
            .setOngoing(true)
            .build()

        startForeground(1, notification) // 1 is the Notification ID*/

        /*try {
            Mobile.stopVpn()
        } catch (e: Exception) {
            Log.e("VayDNS", "Pre-start cleanup: ${e.message}")
        }

        if (tunInterface != null) {
            try {
                tunInterface?.close()
                tunInterface = null
            } catch (e: Exception) {
                Log.e("VayDNS", "Failed to close old ghost tunnel")
            }
        }*/
        createNotificationChannel()

        // 2. Create the Notification that keeps the service alive
        val notification = Notification.Builder(this, "VAYDNS_CHANNEL")
            .setContentTitle("VayDNS Tunnel Active")
            .setContentText("Your traffic is being protected via DNS Tunneling")
            .setSmallIcon(R.drawable.ic_dialog_info)
            .build()

        // 3. Start as Foreground Service
        startForeground(1, notification)

        var udp = ""
        var doh = ""
        var dot = ""

        // 4. Extract data passed from MainActivity
        val domain = intent?.getStringExtra("DOMAIN") ?: ""
        val pubkey = (intent?.getStringExtra("PUBKEY") ?: "").replace("\\s".toRegex(), "")
        val dnsAddress = intent?.getStringExtra("UDP") ?: "8.8.8.8:53"
        val mode = intent?.getStringExtra("MODE") ?: "udp"

        // 1. Resolve the NS domain to its actual IP in the background
        val serverIp = try {
            InetAddress.getByName(domain).hostAddress
        } catch (e: Exception) {
            null // Fallback if DNS is blocked/failed
        }

        when (mode.lowercase()) {
            "udp" -> udp = dnsAddress
            "doh" -> doh = dnsAddress // e.g., https://dns.google/dns-query
            "dot" -> dot = dnsAddress // e.g., dns.google:853
        }

        // 5. Establish the VPN Interface
        builder = Builder()
//        val serverIp = "46.250.246.10" // Your Rocky Linux IP
        builder.setSession("VayDNS Tunnel Active")
            .addAddress("10.0.0.2",24) // Virtual IP
            .addDnsServer("8.8.8.8")      // Internal DNS
            .setMtu(500)
//            .addAllowedApplication(packageName) // Allows the app to see the real internet

//        builder.addRoute(serverIp, 32)
        builder.addRoute("8.8.8.8", 32)
//        builder.addRoute("1.1.1.1", 32)
        builder.addRoute("0.0.0.0", 0)
        builder.addRoute("142.250.0.0", 15) // Google IP range for connectivity checks
//            .addRoute(serverIp, 32)       // EXCEPTION: Direct to server
//        builder.addRoute("0.0.0.0", 0)       // Route ALL traffic
//        builder.addRoute("1.1.1.1", 32)       // Route ALL traffic
// This tells the VPN: "Don't loop back any traffic generated by this app itself"
// Prevent the app from looping its own traffic
        builder.setBlocking(false)
        val resolverIp = dnsAddress.split(":")[0]
        if (isValidIp(resolverIp)) {
            builder.addRoute(resolverIp, 32)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.e("VayVpn", "Could not disallow app: ${e.message}")
            }
        }

//        val serverIp = udp.split(":")[0] // Get "8.8.8.8"
        if (serverIp != null && isValidIp(serverIp)) {
            try {
                builder.addRoute(serverIp, 32)
                Log.i("VayDNS", "Bypassing tunnel for: $serverIp")
            } catch (e: Exception) {
                Log.e("VayDNS", "Failed to add bypass route: ${e.message}")
            }
        }

        val protector = AndroidProtector(this@VayVpnService)
        // It tells Android: "I am using the current default network (WiFi/Cell) as my base."
        // This usually flips the status from "Connecting" to "Connected" instantly.

        try {
            // 1. Establish the interface ONCE
            tunInterface = builder.establish()

            if (tunInterface == null) {
                Log.e("VayDNS", "CRITICAL: establish() returned null. Check VPN permissions!")
                return START_NOT_STICKY
            }
//            setUnderlyingNetworks(null)

//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//                setHttpProxy(null) // Clears any stale proxy settings that might hang the check
//            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                setUnderlyingNetworks(null)
            }
            // 2. Detach the FD so Go can own it exclusively
            // .detachFd() returns an Int that stays open even if tunInterface is cleared
            val fd = tunInterface!!.detachFd()

            Log.i("VayDNS", "VPN Interface established with FD: $fd")

            // 3. Start Go Tunnel in a background thread
            Thread {
                try {
                    // Match your Go signature (using fd as Int or Long as per your mobile.go)
                    val result = Mobile.startVpn(fd.toLong(), udp, doh, dot, domain, pubkey, protector)
                    Log.i("VayDNS", "Go Engine Result: $result")

                    if (result.contains("Success")) {
                        // Switch the UI from "Connecting..." to "Connected"
//                        android.os.Handler(android.os.Looper.getMainLooper()).post {
//                            updateNotification("Status: Connected to $domain")
//                        }
                        val intent = Intent("VPN_STATE_CHANGED")
                        intent.putExtra("status", "CONNECTED")
                        intent.setPackage(packageName)
                        sendBroadcast(intent)
                        updateNotification("Status: Connected")

                        // Optional: Send a broadcast to update the text inside your App Activity
//                        val intent = Intent("VPN_STATE_CHANGED")
//                        intent.putExtra("state", "CONNECTED")

                    } else {
                        updateNotification("Connection Failed")
                    }

                } catch (e: Exception) {
                    Log.e("VayDNS", "Go Engine crashed: ${e.message}")
                }
            }.start()

        } catch (e: Exception) {
            Log.e("VayDNS", "VPN Setup Exception: ${e.message}")
        }

        return START_STICKY
    }

    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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

        // Create an Intent that opens your App when the notification is clicked
        /*val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            // This flag is mandatory for Android 12 and above
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )*/

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
                "VAYDNS_CHANNEL",
                "VPN Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun cleanupAndStop() {
        if (isStopping) return // Prevent double-triggering
        isStopping = true

        try {
            tunInterface?.close()
            tunInterface = null
            Log.i("VayDNS", "TUN Interface closed. Key should be gone.")
        } catch (e: Exception) {
            Log.e("VayDNS", "Error closing TUN: ${e.message}")
        }

        // 2. Clear the notification
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(1)

        // 3. Stop Go in the background (Non-blocking)
        Thread {
            synchronized(goLock) {
                try {
                    // While this thread is in here, no other thread
                    // can start or stop the VPN.
                    Mobile.stopVpn()
                    println("VayDNS: Go Engine shutdown complete.")
                } catch (e: Exception) {
                    // Silent catch to prevent context crash
                }
            }
        }.start()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            // Fallback for very old phones
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()
//            Log.i("VayDNS", "Service cleanup finished successfully.")
    }

    override fun onDestroy() {
        Log.i("VayDNS", "onDestroy triggered")
        if (!isStopping) {
            cleanupAndStop()
        }
        super.onDestroy()
    }

}