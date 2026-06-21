package com.net2share.vaydns

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import mobile.Mobile

class VayDomainService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        if (intent == null || intent.action == "ACTION_STOP_DOMAIN_SCANNER") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            Handler(Looper.getMainLooper()).post {
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val notification = NotificationCompat.Builder(this, "VAY_DOMAIN_SCANNER")
            .setContentTitle("VayDNS Security")
            .setContentText("Scanning tunnel domains via E2E...")
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(3, notification) // ID 3 to keep it separate from VPN/Proxy

        Thread {
            try {
                // Extract parameters from MainActivity
                val useMultiDomains = intent.getBooleanExtra("USE_MULTI_DOMAINS", false)
                val isDefault = intent.getBooleanExtra("IS_DEFAULT", false)
                val configIndex = intent.getLongExtra("CONFIG_INDEX", 0L)
                val domains = intent.getStringExtra("DOMAINS") ?: ""
                val domainIndex = intent.getIntExtra("DOMAIN_INDEX", 0)
                val resolverIP = intent.getStringExtra("RESOLVER_IP") ?: ""
                val dnsMode = intent.getStringExtra("DNS_MODE") ?: ""
                val pubkey = intent.getStringExtra("PUBKEY") ?: ""
                val baseDohUrl = intent.getStringExtra("BASE_DOH_URL") ?: ""
                val proxyType = intent.getStringExtra("PROXY_TYPE") ?: "socks5h"
                val tunnelProtocol = intent.getStringExtra("TUNNEL_PROTOCOL") ?: "socks5"
                val proxyUser = intent.getStringExtra("PROXY_USER") ?: "none"
                val proxyPass = intent.getStringExtra("PROXY_PASS") ?: "none"
                val ssMethod = intent.getStringExtra("SS_METHOD") ?: "chacha20-ietf-poly1305"
                val recordType = intent.getStringExtra("RECORD_TYPE") ?: "TXT"
                val idleTimeout = intent.getStringExtra("IDLE_TIMEOUT") ?: "10s"
                val keepAlive = intent.getStringExtra("KEEP_ALIVE") ?: "2s"
                val clientIdSize = intent.getLongExtra("CLIENT_ID_SIZE", 2L)
                val mtu = intent.getLongExtra("MTU", 0L)
                val workers = intent.getLongExtra("WORKERS", 10L)
                val tunnelWait = intent.getLongExtra("TUNNEL_WAIT", 3000L)
                val udpTimeout = intent.getLongExtra("UDP_TIMEOUT", 1000L)
                val probeTimeout = intent.getLongExtra("PROBE_TIMEOUT", 15000L)
                val retries = intent.getLongExtra("RETRIES", 1L)
                val lightE2E = intent.getBooleanExtra("LIGHT_E2E", false)
                val engineQuickScan = intent.getBooleanExtra("QUICK_SCAN", false)

                // Call the heavy Go Engine
                val result = Mobile.checkHealthyDomains(
                    useMultiDomains, isDefault, configIndex, domainIndex.toLong(), domains, resolverIP, dnsMode, pubkey,
                    baseDohUrl, proxyType, tunnelProtocol, proxyUser, proxyPass, ssMethod,
                    recordType, idleTimeout, keepAlive, clientIdSize, mtu, workers, tunnelWait,
                    udpTimeout, probeTimeout, retries, lightE2E, engineQuickScan
                )

                // Broadcast result back to the Main UI Process
                val broadcastIntent = Intent("ACTION_DOMAIN_SCAN_RESULT").apply {
                    putExtra("HEALTHY_DOMAINS", result)
                    setPackage(packageName)
                }
                sendBroadcast(broadcastIntent)

            } catch (e: Exception) {
                Log.e("VayDomainService", "Scanner Error: ${e.message}")
            } finally {
                // Trigger self-destruction safely on the Main Looper
                Handler(Looper.getMainLooper()).post {
                    stopSelf()
                }
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "VAY_DOMAIN_SCANNER", "VayDNS Pre-Flight Scanner", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("VayDomainService", "Destroying Scanner Sandbox and freeing memory...")

        // Run the teardown in a Background Thread so we don't freeze the OS
        Thread {
            // 1. Flush the Go network transport & send FIN packets
            Mobile.stopDomainScanner()

            // 2. Commit suicide IMMEDIATELY after the network flush is complete
            android.os.Process.killProcess(android.os.Process.myPid())
        }.start()
    }
}