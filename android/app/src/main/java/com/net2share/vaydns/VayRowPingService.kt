package com.net2share.vaydns

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import mobile.Mobile

class VayRowPingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val configId = intent.getStringExtra("CONFIG_ID") ?: return START_NOT_STICKY
        val configType = intent.getStringExtra("CONFIG_TYPE") ?: "vaydns"
        val protocol = intent.getStringExtra("PROTOCOL") ?: ""

        // ARCHITECTURAL FORK: Check if it is a direct connection by verifying the active protocol string
        val isDirectMode = !configType.lowercase().contains("vaydns") ||
                protocol.lowercase() in listOf("hysteria", "hysteria2", "reality", "vless-ws", "vless")

        // ARCHITECTURAL FORK: Check if it is a direct connection
        //val isDirectMode = configType.lowercase() == "direct"

        if (isDirectMode) {
            // =========================================================
            // SECURE NATIVE PING (Executes entirely inside Go)
            // =========================================================
            val isDefault = intent.getBooleanExtra("IS_DEFAULT", false)
            val configIndex = intent.getLongExtra("CONFIG_INDEX", -1L)
            val serverIp = intent.getStringExtra("SERVER_IP") ?: "" // Only used for custom configs
            val protocol = intent.getStringExtra("PROTOCOL") ?: ""
            val vlessWsIp = intent.getStringExtra("VLESS_WS_IP") ?: ""

            Thread {
                mobile.Mobile.setGlobalVlessWsIP(vlessWsIp)
                val latency = Mobile.pingDirectServer(isDefault, configIndex, serverIp, configType.lowercase(),protocol.lowercase())
                broadcastResult(configId, latency)
            }.start()

        } else {
            // =========================================================
            // HEAVY GO SCANNER FOR VAYDNS TUNNELS
            // =========================================================
            val isDefault = intent.getBooleanExtra("IS_DEFAULT", false)
            val configIndex = intent.getLongExtra("CONFIG_INDEX", -1L)
            val mode = intent.getStringExtra("MODE") ?: ""
            val domain = intent.getStringExtra("DOMAIN") ?: ""
            val pubkey = intent.getStringExtra("PUBKEY") ?: ""
            val multipathDnsList = intent.getStringExtra("MULTIPATH_DNS") ?: ""
            val baseDohUrl = intent.getStringExtra("BASE_DOH_URL") ?: ""
            val proxyType = intent.getStringExtra("PROXY_TYPE") ?: "socks5h"
            val protocol = intent.getStringExtra("PROTOCOL") ?: ""
            val user = intent.getStringExtra("USER") ?: "none"
            val pass = intent.getStringExtra("PASS") ?: "none"
            val ssMethod = intent.getStringExtra("SS_METHOD") ?: "chacha20-ietf-poly1305"
            val recordType = intent.getStringExtra("RECORD_TYPE") ?: "TXT"
            val idleTimeout = intent.getStringExtra("IDLE_TIMEOUT") ?: "10s"
            val keepAlive = intent.getStringExtra("KEEP_ALIVE") ?: "2s"
            val clientIdSize = intent.getLongExtra("CLIENT_ID_SIZE", 2L)
            val mtu = intent.getLongExtra("MTU", 0L)
            val workers = intent.getLongExtra("WORKERS", 20L)
            val tunnelWait = intent.getLongExtra("TUNNEL_WAIT", 3000L)
            val udpTimeout = intent.getLongExtra("UDP_TIMEOUT", 1000L)
            val probeTimeout = intent.getLongExtra("PROBE_TIMEOUT", 15000L)
            val retries = intent.getLongExtra("RETRIES", 0L)
            val lightE2E = intent.getBooleanExtra("LIGHT_E2E", false)

            Thread {
                val bestLatency = Mobile.pingMultipleServers(
                    isDefault, configIndex, mode, domain, pubkey, multipathDnsList,
                    baseDohUrl, proxyType, protocol, user, pass, ssMethod,
                    recordType, idleTimeout, keepAlive, clientIdSize, mtu,
                    workers, tunnelWait, udpTimeout, probeTimeout, retries,
                    lightE2E, false
                )
                broadcastResult(configId, bestLatency)
            }.start()
        }

        return START_NOT_STICKY
    }

    private fun broadcastResult(configId: String, latency: Long) {
        val broadcastIntent = Intent("ROW_PING_FINISHED").setPackage(packageName).apply {
            putExtra("CONFIG_ID", configId)
            putExtra("LATENCY", latency)
        }
        sendBroadcast(broadcastIntent)

        Handler(Looper.getMainLooper()).post {
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Thread {
            Mobile.stopRowPing()
            android.os.Process.killProcess(android.os.Process.myPid())
        }.start()
    }
}