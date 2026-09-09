package net.vaydns.phoenix

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import mobile.Mobile


class VayAutoConnectService : Service() {
    private var isRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == "ACTION_STOP_VPN") {
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "VAY_CHANNEL_AUTO_CONNECT")
            .setContentTitle("Phoenix Auto-Connect")
            .setContentText("Finding the fastest available server...")
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setOngoing(true)
            .build()

        // Android 14+ Crash Prevention
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(2, notification)
        }

        isRunning = true

        Thread {
            // Extract the original parameters passed from MainActivity
            val originalIndex = intent.getLongExtra("CONFIG_INDEX", -1L)
            val isDefaultConfig = intent.getBooleanExtra("IS_DEFAULT_CONFIG", false)
            val configType = intent.getStringExtra("CONFIG_TYPE") ?: "vaydns"
            val useMultiDomains = intent.getBooleanExtra("USE_MULTI_DOMAINS", false)
            val domainIndex = intent.getIntExtra("DOMAIN_INDEX", 0)
            val domain = intent.getStringExtra("DOMAIN") ?: ""
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
            val useAuth = intent.getBooleanExtra("USE_AUTH", false)
            val tunnelProtocol = intent.getStringExtra("TUNNEL_PROTOCOL") ?: "vaydns"
            val localProxyProtocol = intent.getStringExtra("LOCAL_PROXY_PROTOCOL") ?: "socks5"
            val authProtocol = intent.getStringExtra("AUTH_PROTOCOL") ?: "socks"
            val ssMethod = intent.getStringExtra("SS_METHOD") ?: "chacha20-ietf-poly1305"
            val user = intent.getStringExtra("USER") ?: ""
            val pass = intent.getStringExtra("PASS") ?: ""
            val engineType = intent.getStringExtra("ENGINE_TYPE") ?: "sing-box"
            val vlessWsIp = intent.getStringExtra("VLESS_WS_IP") ?: ""
            val targetCdn = intent.getStringExtra("TARGET_CDN") ?: "CloudX"
            val fragment = intent.getBooleanExtra("USE_FRAGMENTATION", false)
            val blockQuic = intent.getBooleanExtra("BLOCK_QUIC", true)
            val getServerIpFromDomain = intent.getBooleanExtra("GET_SERVER_IP_FROM_DOMAIN", false)
            val sniIndex = intent.getLongExtra("SNI_INDEX", -1L)
            val useHysteriaCore = intent.getBooleanExtra("USE_HYSTERIA_CORE", false)
            val isProxyMode = intent.getBooleanExtra("IS_PROXY_MODE", false)

            val tunnelPrefs = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
            val globalDnsServer = tunnelPrefs.getString("global_dns_server", "1.1.1.1") ?: "1.1.1.1"
            val isDebugEnabled = getSharedPreferences("PhoenixVpnPrefs", Context.MODE_PRIVATE).getBoolean("debug_logs_enabled", false)

            var udp = ""; var tcp = ""; var doh = ""; var dot = ""
            when (mode.lowercase()) {
                "udp" -> udp = dnsAddress
                "tcp" -> tcp = dnsAddress
                "doh" -> doh = dnsAddress
                "dot" -> dot = dnsAddress
            }

            val dns_mode = intent.getStringExtra("DNS_MODE") ?: when ((intent.getStringExtra("MODE") ?: "udp").lowercase()) {
                "tcp" -> "TCP"
                "dot" -> "DoT"
                "doh" -> "DoH"
                else -> "UDP"
            }

            // =========================================================================
            // 1. FETCH SHUFFLED ELIGIBLE CONFIGS
            // =========================================================================
            // Get the fully shuffled list of all eligible auto-connect indices from Go
            val indicesCsv = Mobile.getRandomizedConfigIndices()
            val candidateIndices = indicesCsv.split(",").mapNotNull { it.trim().toLongOrNull() }

            // Failsafe: If no configs were marked "randomize" in the JSON, fallback to the original tapped index
            val fallbackList = if (candidateIndices.isNotEmpty()) candidateIndices else listOf(originalIndex)

            val maxAttempts = fallbackList.size
            var connected = false

            sendBroadcast(Intent("VPN_STATE_CHANGED").apply {
                putExtra("status", "CONNECTING")
                setPackage(packageName)
            })

            // =========================================================================
            // 2. ITERATE DETERMINISTICALLY
            // =========================================================================
            for ((attemptIndex, candidateIndex) in fallbackList.withIndex()) {
                if (!isRunning) break
                val currentAttempt = attemptIndex + 1

                Log.i("PhoenixAuto", "Attempt $currentAttempt/$maxAttempts: Testing Config Index $candidateIndex with REAL Proxy Engine")

// Start the lightweight Proxy engine using the current candidate index
                val proxyResult = Mobile.startProxy(
                    engineType, isDefaultConfig, candidateIndex, configType, useMultiDomains, domainIndex.toLong(),
                    udp, tcp, doh, dot, baseDohUrl, domain, pubkey, recordType, idleTimeout, keepAlive,
                    clientIdSize, mtu, dnsttCompatible, useAuth, tunnelProtocol, localProxyProtocol,
                    authProtocol, ssMethod, user, pass, 35000L, vlessWsIp, targetCdn, globalDnsServer,
                    isDebugEnabled, fragment, blockQuic, getServerIpFromDomain, sniIndex, useHysteriaCore, dns_mode
                )

                if (proxyResult.contains("Success")) {
                    Log.i("PhoenixAuto", "Proxy Engine mounted locally. Firing E2E HTTP payload to test DPI...")

                    // Wait exactly 1.5s for the native core to stabilize its routing
                    Thread.sleep(1500)

                    // Send REAL HTTP traffic through the tunnel to prove DPI isn't dropping the connection
                    val verifyResult = Mobile.verifyTunnel(tunnelProtocol)

                    if (verifyResult.contains("Success")) {
                        Log.i("PhoenixAuto", "SUCCESS! Config $candidateIndex defeated DPI. Handing off to Main VPN...")

                        // Stop the temporary testing proxy
                        Mobile.stopVpn()
                        Thread.sleep(500) // Ensure sockets are freed

                        val targetClass = if (isProxyMode) VayProxyService::class.java else VayVpnService::class.java

                        // 1. Create a fresh intent, copy the extras, and explicitly set the ACTION
                        val nextIntent = Intent(this@VayAutoConnectService, targetClass).apply {
                            action = "ACTION_START_VPN"
                            if (intent?.extras != null) {
                                putExtras(intent.extras!!)
                            }
                            putExtra("CONFIG_INDEX", candidateIndex)
                            putExtra("DISABLE_AUTO_ROLL", true)
                        }

                        Log.i("PhoenixAuto", "Dispatching intent to ${targetClass.simpleName}...")

                        try {
                            // 2. CRITICAL FIX: We MUST use startForegroundService for VpnService too!
                            // ContextCompat handles the Android version bridging automatically and safely.
                            androidx.core.content.ContextCompat.startForegroundService(this@VayAutoConnectService, nextIntent)
                        } catch (e: Exception) {
                            Log.e("PhoenixAuto", "Failed to dispatch handoff intent: ${e.message}")
                        }

                        connected = true
                        break
                    } else {
                        Log.w("PhoenixAuto", "Config $candidateIndex blocked by DPI during payload transfer. Trying next...")
                    }
                } else {
                    Log.w("PhoenixAuto", "Config $candidateIndex failed to mount proxy: $proxyResult")
                }

                // Clean up the dead engine before trying the next one in the list
                Mobile.stopVpn()
                Thread.sleep(500)
            }

            if (!connected && isRunning) {
                Log.e("PhoenixAuto", "Exhausted all $maxAttempts attempts. No working servers found.")
                sendBroadcast(Intent("VPN_STATE_CHANGED").apply {
                    putExtra("status", "ERROR")
                    putExtra("message", "Auto-Connect Failed: All eligible servers are blocked by DPI.")
                    setPackage(packageName)
                })
            } else if (connected) {
                // 3. Give the OS Binder time to cross processes and launch the VPN before destroying this process!
                Thread.sleep(2500)
            }

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.start()

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("VAY_CHANNEL_AUTO_CONNECT", "Auto Connect Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}